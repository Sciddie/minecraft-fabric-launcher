package de.aichele.childlauncher;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Zip on demand, then optional metadata in an external JDBC database. No local database is started. */
final class BackupBridge {
    record Spec(String directory, List<String> paths, int keep, boolean dbEnabled, String jdbcUrl, String user,
                String password, String driverJar, String driverClass, String table) {}

    record Result(String id, String path, long bytes, String sha256, String db) {}
    record Entry(String name, long bytes, long mtime) {}

    private static final long MAX_RESTORE_BYTES = 32L * 1024 * 1024 * 1024;
    private static final int MAX_RESTORE_FILES = 100_000;

    private BackupBridge() {}

    static List<Entry> list(Spec spec, String serverId) throws IOException {
        Path dir = directory(spec);
        if (!Files.isDirectory(dir)) return List.of();
        String safe = safeId(serverId);
        List<Entry> out = new ArrayList<>();
        try (var ds = Files.newDirectoryStream(dir, "backup-" + safe + "-*.zip")) {
            for (Path p : ds) {
                if (!named(safe, p.getFileName().toString()) || !Files.isRegularFile(p) || Files.isSymbolicLink(p)) continue;
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                out.add(new Entry(p.getFileName().toString(), a.size(), a.lastModifiedTime().toMillis()));
            }
        }
        out.sort(Comparator.comparing(Entry::name).reversed());
        return out;
    }

    static Path file(Spec spec, String serverId, String name) {
        String safe = safeId(serverId);
        if (!named(safe, name)) throw new IllegalArgumentException("backup name");
        Path dir = directory(spec);
        Path f = dir.resolve(name).normalize();
        if (f.getParent() == null || !f.getParent().equals(dir)) throw new IllegalArgumentException("backup name");
        if (!Files.isRegularFile(f) || Files.isSymbolicLink(f)) throw new IllegalArgumentException("backup name");
        return f;
    }

    static void delete(Spec spec, String serverId, String name) throws IOException {
        Files.deleteIfExists(file(spec, serverId, name));
    }

    /** Extracts a backup this bridge wrote. Rejects traversal. Caller must stop the child first. */
    static int restore(Path work, Spec spec, String serverId, String name) throws IOException {
        PathGuard guard = PathGuard.open(work);
        Path zip = file(spec, serverId, name);
        int n = 0;
        long bytes = 0;
        try (ZipFile z = new ZipFile(zip.toFile())) {
            if (z.size() > MAX_RESTORE_FILES) throw new IOException("zip too large");
            var en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String raw = e.getName().replace('\\', '/');
                if (raw.isBlank() || raw.contains("\0")) throw new SecurityException("zip entry");
                if (e.getSize() > MAX_RESTORE_BYTES) throw new IOException("zip entry too large");
                Path target = guard.resolve(raw);
                if (Files.isSymbolicLink(target)) throw new SecurityException("symlink");
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                try (InputStream in = z.getInputStream(e); var out = Files.newOutputStream(target)) {
                    byte[] buf = new byte[65536];
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        bytes += r;
                        if (bytes > MAX_RESTORE_BYTES) throw new IOException("zip too large");
                        out.write(buf, 0, r);
                    }
                }
                n++;
            }
        }
        if (n == 0) throw new IOException("empty zip");
        return n;
    }

    private static Path directory(Spec spec) {
        if (spec.directory == null || spec.directory.isBlank()) throw new IllegalArgumentException("backup directory");
        return Path.of(spec.directory).toAbsolutePath().normalize();
    }

    static String safeId(String serverId) {
        String s = serverId == null ? "" : serverId.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.isBlank() ? "server" : s;
    }

    private static boolean named(String safe, String name) {
        return name != null && name.matches("backup-" + Pattern.quote(safe) + "-[0-9]{8}-[0-9]{6}-[0-9a-z]+\\.zip");
    }

    static Result archive(Path work, Spec spec, String serverId) throws Exception {
        if (spec.directory == null || spec.directory.isBlank()) throw new IOException("backup directory");
        PathGuard guard = PathGuard.open(work);
        Path dir = Path.of(spec.directory);
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + Long.toString(System.nanoTime(), 36);
        String safe = safeId(serverId);
        Path zip = dir.resolve("backup-" + safe + "-" + stamp + ".zip");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        int files = 0;
        try (ZipOutputStream out = new ZipOutputStream(new DigestOutputStream(Files.newOutputStream(zip), md))) {
            List<String> paths = spec.paths == null || spec.paths.isEmpty() ? List.of("world") : spec.paths;
            for (String rel : paths) {
                Path start;
                try { start = guard.resolve(rel); } catch (SecurityException e) { continue; }
                if (!Files.exists(start) || Files.isSymbolicLink(start)) continue;
                if (Files.isDirectory(start)) {
                    try (var walk = Files.walk(start)) {
                        for (Path p : (Iterable<Path>) walk::iterator) {
                            if (Files.isDirectory(p) || Files.isSymbolicLink(p)) continue;
                            if (!p.toRealPath().startsWith(guard.root())) continue;
                            files += write(out, guard.root(), p);
                        }
                    }
                } else if (pRealInside(guard, start)) files += write(out, guard.root(), start);
            }
        }
        if (files == 0) { Files.deleteIfExists(zip); throw new IOException("nothing to archive"); }
        String sha = HexFormat.of().formatHex(md.digest());
        long bytes = Files.size(zip);
        retain(dir, safe, spec.keep);
        String db = "";
        if (spec.dbEnabled) {
            try { insert(spec, "b-" + safe + "-" + stamp, serverId, zip.toString(), bytes, sha); db = "ok"; }
            catch (Exception e) { db = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
        }
        return new Result("b-" + safe + "-" + stamp, zip.toString(), bytes, sha, db);
    }

    static void test(Spec spec) throws Exception {
        if (!spec.dbEnabled) throw new IllegalArgumentException("backup.database.enabled");
        try (Connection c = connect(spec)) { ensureTable(c, table(spec)); }
    }

    private static int write(ZipOutputStream out, Path root, Path file) throws IOException {
        String name = root.relativize(file).toString().replace('\\', '/');
        out.putNextEntry(new ZipEntry(name));
        try (InputStream in = Files.newInputStream(file)) { in.transferTo(out); }
        out.closeEntry();
        return 1;
    }

    private static boolean pRealInside(PathGuard guard, Path p) throws IOException {
        return p.toRealPath().startsWith(guard.root());
    }

    private static void retain(Path dir, String server, int keep) throws IOException {
        if (keep <= 0) return;
        List<Path> zips = new ArrayList<>();
        try (var ds = Files.newDirectoryStream(dir, "backup-" + server + "-*.zip")) { for (Path p : ds) zips.add(p); }
        zips.sort(Comparator.comparing(Path::getFileName).reversed());
        for (int i = keep; i < zips.size(); i++) Files.deleteIfExists(zips.get(i));
    }

    private static void insert(Spec spec, String id, String server, String path, long bytes, String sha) throws Exception {
        try (Connection c = connect(spec)) {
            String table = table(spec);
            ensureTable(c, table);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table + " (id, server_id, created_at, path, bytes, sha256, note) VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, server);
                ps.setString(3, java.time.OffsetDateTime.now().toString());
                ps.setString(4, path);
                ps.setLong(5, bytes);
                ps.setString(6, sha);
                ps.setString(7, "");
                ps.executeUpdate();
            }
        }
    }

    private static void ensureTable(Connection c, String table) throws Exception {
        try (var st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " (id VARCHAR(64) PRIMARY KEY, server_id VARCHAR(128), created_at VARCHAR(40), path VARCHAR(1024), bytes BIGINT, sha256 VARCHAR(64), note VARCHAR(512))");
        }
    }

    static String table(Spec spec) {
        String t = spec.table == null || spec.table.isBlank() ? "fabriclauncher_backups" : spec.table;
        if (!t.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) throw new IllegalArgumentException("backup.database.table");
        return t;
    }

    private static Connection connect(Spec spec) throws Exception {
        if (spec.driverClass == null || spec.driverClass.isBlank()) throw new IllegalArgumentException("driverClass");
        if (spec.jdbcUrl == null || spec.jdbcUrl.isBlank()) throw new IllegalArgumentException("jdbcUrl");
        Properties props = new Properties();
        if (spec.user != null) props.setProperty("user", spec.user);
        if (spec.password != null) props.setProperty("password", spec.password);
        if (spec.driverJar != null && !spec.driverJar.isBlank()) {
            URLClassLoader cl = new URLClassLoader(new URL[]{Path.of(spec.driverJar).toUri().toURL()}, ClassLoader.getPlatformClassLoader());
            Driver driver = (Driver) Class.forName(spec.driverClass, true, cl).getDeclaredConstructor().newInstance();
            Connection c = driver.connect(spec.jdbcUrl, props);
            if (c == null) throw new java.sql.SQLException("driver rejected url");
            return c;
        }
        Class.forName(spec.driverClass);
        return DriverManager.getConnection(spec.jdbcUrl, props);
    }

    static int count(Spec spec) throws Exception {
        try (Connection c = connect(spec); ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM " + table(spec))) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
