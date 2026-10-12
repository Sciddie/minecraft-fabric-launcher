package de.aichele.childlauncher;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;

/** Web dashboard using only com.sun.net.httpserver. */
final class Dashboard {
    private static final long SESSION_TTL = 12 * 3600_000L;
    private static final long MAX_TEXT = 2 * 1024 * 1024;
    private static final long MAX_UPLOAD = 1024L * 1024 * 1024;
    private final FabricLauncherPlugin pl;
    private final Gson gson = new Gson();
    private final SecureRandom rnd = new SecureRandom();
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final Map<String, int[]> fails = new ConcurrentHashMap<>();      // ip -> [count]
    private final Map<String, Long> lockedUntil = new ConcurrentHashMap<>();
    private HttpServer server;
    private ExecutorService pool;
    private String password;
    private volatile long lastCpuTicks = -1, lastCpuWall;
    private volatile double lastCpuPct;

    Dashboard(FabricLauncherPlugin pl) { this.pl = pl; }

    void start() throws IOException {
        password = loadOrCreatePassword();
        String bind = pl.getConfig().getString("dashboard.bind", "");
        if (bind == null || bind.isBlank()) bind = pl.getConfig().getString("serverIp", "");
        if (bind == null || bind.isBlank()) bind = "0.0.0.0";
        int port = pl.getConfig().getInt("dashboard.port", 2064);
        server = HttpServer.create(new InetSocketAddress(bind, port), 32);
        pool = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "FabricLauncher-Web"); t.setDaemon(true); return t; });
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
        pl.getLogger().info("Dashboard listening on http://" + bind + ":" + port + "/");
    }

    void stop() {
        if (server != null) server.stop(1);
        if (pool != null) pool.shutdownNow();
    }

    private String loadOrCreatePassword() throws IOException {
        Path f = pl.getDataFolder().toPath().resolve("dashboard-password.txt");
        Files.createDirectories(f.getParent());
        if (Files.exists(f)) {
            String p = Files.readString(f).trim();
            if (!p.isEmpty()) return p;
        }
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
        Files.writeString(f, sb + "\n");
        try { Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw----")); } catch (Exception ignored) {}
        pl.getLogger().warning("==================================================");
        pl.getLogger().warning(" Dashboard password generated (shown once): " + sb);
        pl.getLogger().warning(" Stored in " + f + " (edit file + restart to change)");
        pl.getLogger().warning("==================================================");
        return sb.toString();
    }

    // ---------------- routing ----------------
    private void handle(HttpExchange ex) throws IOException {
        try {
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().set("X-Frame-Options", "DENY");
            ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/") || path.equals("/index.html")) { serveIndex(ex); return; }
            if (path.equals("/api/login") && method.equals("POST")) { login(ex); return; }
            if (!path.startsWith("/api/")) { send(ex, 404, "text/plain", "Not found"); return; }
            if (!authed(ex)) { json(ex, 401, Map.of("error", "Nicht angemeldet")); return; }
            if (method.equals("POST") && !"1".equals(ex.getRequestHeaders().getFirst("X-FL"))) { json(ex, 403, Map.of("error", "CSRF")); return; }
            Map<String, String> q = query(ex);
            switch (method + " " + path) {
                case "POST /api/logout" -> { sessions.remove(cookie(ex)); ex.getResponseHeaders().add("Set-Cookie", "FLS=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict"); json(ex, 200, Map.of("ok", true)); }
                case "GET /api/status" -> json(ex, 200, status());
                case "GET /api/console" -> json(ex, 200, pl.console.snapshot());
                case "GET /api/console/stream" -> sse(ex);
                case "POST /api/cmd" -> {
                    String c = body(ex, obj -> obj.get("command").getAsString()).trim();
                    if (c.isEmpty()) { json(ex, 400, Map.of("error", "leer")); return; }
                    pl.console.add("> " + c);
                    json(ex, 200, Map.of("ok", pl.sendCommand(c)));
                }
                case "POST /api/action" -> {
                    String a = q.getOrDefault("do", "");
                    switch (a) {
                        case "start" -> pl.requestStart();
                        case "stop" -> pl.requestStop();
                        case "restart" -> pl.requestRestart();
                        default -> { json(ex, 400, Map.of("error", "unknown action")); return; }
                    }
                    pl.info("Dashboard action: " + a + " from " + ip(ex));
                    json(ex, 200, Map.of("ok", true));
                }
                case "GET /api/props" -> text(ex, readOr(pl.workDir().resolve("server.properties")));
                case "POST /api/props" -> saveProps(ex);
                case "GET /api/config" -> text(ex, readOr(pl.getDataFolder().toPath().resolve("config.yml")));
                case "POST /api/config" -> saveConfig(ex);
                case "GET /api/players" -> players(ex);
                case "GET /api/fs/list" -> fsList(ex, q);
                case "GET /api/fs/read" -> fsRead(ex, q);
                case "GET /api/fs/download" -> fsDownload(ex, q);
                case "POST /api/fs/write" -> fsWrite(ex, q);
                case "POST /api/fs/upload" -> fsUpload(ex, q);
                case "POST /api/fs/rename" -> fsRename(ex);
                case "POST /api/fs/mkdir" -> fsMkdir(ex, q);
                case "POST /api/fs/delete" -> fsDelete(ex);
                default -> json(ex, 404, Map.of("error", "Not found"));
            }
        } catch (SecurityException e) {
            json(ex, 403, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            pl.getLogger().log(Level.FINE, "Dashboard error", e);
            try { json(ex, 500, Map.of("error", String.valueOf(e.getMessage()))); } catch (Exception ignored) {}
        } finally {
            ex.close();
        }
    }

    // ---------------- auth ----------------
    private String ip(HttpExchange ex) { return ex.getRemoteAddress().getAddress().getHostAddress(); }

    private void login(HttpExchange ex) throws IOException {
        String ip = ip(ex);
        long now = System.currentTimeMillis();
        Long until = lockedUntil.get(ip);
        if (until != null && until > now) { json(ex, 429, Map.of("error", "Zu viele Fehlversuche. Bitte " + ((until - now) / 1000 + 1) + " s warten.")); return; }
        String pw = body(ex, o -> o.has("password") ? o.get("password").getAsString() : "");
        if (MessageDigest.isEqual(pw.getBytes(StandardCharsets.UTF_8), password.getBytes(StandardCharsets.UTF_8))) {
            fails.remove(ip); lockedUntil.remove(ip);
            byte[] b = new byte[32]; rnd.nextBytes(b);
            String tok = HexFormat.of().formatHex(b);
            sessions.put(tok, now + SESSION_TTL);
            sessions.entrySet().removeIf(e -> e.getValue() < now);
            ex.getResponseHeaders().add("Set-Cookie", "FLS=" + tok + "; Path=/; Max-Age=" + SESSION_TTL / 1000 + "; HttpOnly; SameSite=Strict");
            pl.getLogger().info("Dashboard login from " + ip);
            json(ex, 200, Map.of("ok", true));
        } else {
            int n = fails.computeIfAbsent(ip, k -> new int[1])[0] += 1;
            try { Thread.sleep(800); } catch (InterruptedException ignored) {}
            if (n >= 5) {
                long lock = Math.min(3600_000L, 30_000L << Math.min(n - 5, 7));
                lockedUntil.put(ip, now + lock);
                pl.getLogger().warning("Dashboard: " + n + " failed logins from " + ip + ", locked for " + lock / 1000 + "s");
            }
            json(ex, 403, Map.of("error", "Falsches Passwort"));
        }
    }

    private String cookie(HttpExchange ex) {
        for (String h : ex.getRequestHeaders().getOrDefault("Cookie", List.of()))
            for (String part : h.split(";")) { String p = part.trim(); if (p.startsWith("FLS=")) return p.substring(4); }
        return "";
    }

    private boolean authed(HttpExchange ex) {
        String c = cookie(ex);
        Long exp = c.isEmpty() ? null : sessions.get(c);
        if (exp == null) return false;
        if (exp < System.currentTimeMillis()) { sessions.remove(c); return false; }
        return true;
    }

    // ---------------- status ----------------
    private Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        long pid = pl.pid();
        m.put("running", pl.running());
        m.put("pid", pid);
        m.put("uptime", pl.uptimeMillis() / 1000);
        m.put("status", pl.lastStatus());
        m.put("type", pl.getConfig().getString("type"));
        m.put("mcVersion", pl.getConfig().getString("mcVersion"));
        m.put("address", pl.getConfig().getString("serverIp", "") + ":" + pl.getConfig().getInt("port"));
        m.put("xmx", pl.getConfig().getString("memory.xmx"));
        m.put("players", new TreeSet<>(pl.onlinePlayers));
        if (pid > 0) {
            m.put("cpu", cpu(pid)); m.put("rssMb", rssMb(pid)); m.put("threads", procField(pid, "Threads:"));
            pl.maybeQueryChildTick();
            if (System.currentTimeMillis() - pl.childTickAt < 60_000) { m.put("tps", pl.childTps); m.put("mspt", pl.childMspt); }
        }
        m.put("spigot", spigotStats());
        return m;
    }

    private Map<String, Object> spigotStats() {
        Map<String, Object> o = new LinkedHashMap<>();
        Runtime rt = Runtime.getRuntime();
        o.put("heapUsedMb", (rt.totalMemory() - rt.freeMemory()) / 1048576);
        o.put("heapCommittedMb", rt.totalMemory() / 1048576);
        o.put("heapMaxMb", rt.maxMemory() / 1048576);
        try {
            var os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sos) {
                double l = sos.getProcessCpuLoad();
                o.put("cpu", l < 0 ? -1 : Math.round(l * Runtime.getRuntime().availableProcessors() * 1000) / 10.0); // % of one core
            }
        } catch (Throwable ignored) {}
        o.put("threads", java.lang.management.ManagementFactory.getThreadMXBean().getThreadCount());
        long self = ProcessHandle.current().pid();
        o.put("pid", self);
        o.put("rssMb", rssMb(self));
        o.put("tps", pl.outerTps); o.put("mspt", pl.outerMspt);
        o.put("chunks", pl.outerChunks); o.put("entities", pl.outerEntities); o.put("worlds", pl.outerWorlds);
        o.put("cores", Runtime.getRuntime().availableProcessors());
        return o;
    }

    private long procField(long pid, String key) {
        try { for (String l : Files.readAllLines(Paths.get("/proc/" + pid + "/status"))) if (l.startsWith(key)) return Long.parseLong(l.replaceAll("\\D", "")); }
        catch (Exception ignored) {}
        return -1;
    }

    private double cpu(long pid) {
        try {
            String st = Files.readString(Paths.get("/proc/" + pid + "/stat"));
            String[] f = st.substring(st.lastIndexOf(')') + 2).split(" ");
            long ticks = Long.parseLong(f[11]) + Long.parseLong(f[12]); // utime, stime
            long now = System.nanoTime();
            synchronized (this) {
                if (lastCpuTicks >= 0 && ticks >= lastCpuTicks && now - lastCpuWall > 500_000_000L) {
                    double secs = (now - lastCpuWall) / 1e9;
                    lastCpuPct = Math.round((ticks - lastCpuTicks) / 100.0 / secs * 1000) / 10.0; // % of one core, CLK_TCK=100
                }
                if (now - lastCpuWall > 500_000_000L || lastCpuTicks < 0 || ticks < lastCpuTicks) { lastCpuTicks = ticks; lastCpuWall = now; }
            }
            return lastCpuPct;
        } catch (Exception e) { return -1; }
    }

    private long rssMb(long pid) {
        try {
            for (String l : Files.readAllLines(Paths.get("/proc/" + pid + "/status")))
                if (l.startsWith("VmRSS:")) return Long.parseLong(l.replaceAll("\\D", "")) / 1024;
        } catch (Exception ignored) {}
        return -1;
    }

    private void players(HttpExchange ex) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String n : List.of("whitelist", "ops", "banned-players")) {
            Path f = pl.workDir().resolve(n + ".json");
            List<String> names = new ArrayList<>();
            try {
                if (Files.exists(f)) for (JsonElement e : JsonParser.parseString(Files.readString(f)).getAsJsonArray())
                    if (e.getAsJsonObject().has("name")) names.add(e.getAsJsonObject().get("name").getAsString());
            } catch (Exception ignored) {}
            m.put(n, names);
        }
        m.put("online", new TreeSet<>(pl.onlinePlayers));
        json(ex, 200, m);
    }

    // ---------------- console SSE ----------------
    private void sse(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        BlockingQueue<String> q = new LinkedBlockingQueue<>(5000);
        Consumer<String> l = q::offer;
        List<String> hist = pl.console.subscribe(l);
        try (OutputStream os = ex.getResponseBody()) {
            StringBuilder sb = new StringBuilder();
            for (String s : hist) sb.append("data: ").append(gson.toJson(s)).append("\n\n");
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8)); os.flush();
            while (!Thread.currentThread().isInterrupted()) {
                String s = q.poll(15, TimeUnit.SECONDS);
                if (s == null) { os.write(": ping\n\n".getBytes(StandardCharsets.UTF_8)); os.flush(); continue; }
                sb.setLength(0);
                sb.append("data: ").append(gson.toJson(s)).append("\n\n");
                String more; int n = 0;
                while (n++ < 200 && (more = q.poll()) != null) sb.append("data: ").append(gson.toJson(more)).append("\n\n");
                os.write(sb.toString().getBytes(StandardCharsets.UTF_8)); os.flush();
            }
        } catch (IOException | InterruptedException ignored) {
        } finally {
            pl.console.unsubscribe(l);
        }
    }

    // ---------------- editors ----------------
    private void saveProps(HttpExchange ex) throws IOException {
        String txt = readBodyText(ex);
        Properties p = new Properties();
        p.load(new StringReader(txt)); // validate
        Path f = pl.workDir().resolve("server.properties");
        Files.createDirectories(f.getParent());
        Files.writeString(f, txt, StandardCharsets.UTF_8);
        // keep launcher-managed keys in sync so the next start does not revert the edit
        boolean changed = false;
        for (String k : p.stringPropertyNames()) {
            if (pl.getConfig().isSet("serverProperties." + k) && !p.getProperty(k).equals(String.valueOf(pl.getConfig().get("serverProperties." + k)))) {
                pl.getConfig().set("serverProperties." + k, FabricLauncherPlugin.parseValue(p.getProperty(k))); changed = true;
            }
        }
        if (p.getProperty("server-port") != null && !p.getProperty("server-port").equals(String.valueOf(pl.getConfig().getInt("port")))) {
            try { pl.getConfig().set("port", Integer.parseInt(p.getProperty("server-port").trim())); changed = true; } catch (NumberFormatException ignored) {}
        }
        if (p.getProperty("server-ip") != null && !p.getProperty("server-ip").equals(pl.getConfig().getString("serverIp", ""))) { pl.getConfig().set("serverIp", p.getProperty("server-ip")); changed = true; }
        if (changed) pl.saveConfigWritable();
        pl.info("server.properties saved via dashboard" + (changed ? " (config.yml synced)" : ""));
        json(ex, 200, Map.of("ok", true, "hint", "Wird beim nächsten Neustart des Servers aktiv."));
    }

    private void saveConfig(HttpExchange ex) throws IOException {
        String txt = readBodyText(ex);
        YamlConfiguration y = new YamlConfiguration();
        try { y.loadFromString(txt); } catch (Exception e) { json(ex, 400, Map.of("error", "YAML-Fehler: " + e.getMessage())); return; }
        Path f = pl.getDataFolder().toPath().resolve("config.yml");
        Files.writeString(f, txt, StandardCharsets.UTF_8);
        FabricLauncherPlugin.makeWritable(f, false);
        pl.reloadConfig();
        pl.info("config.yml saved via dashboard");
        json(ex, 200, Map.of("ok", true, "hint", "Gespeichert. Wirkt beim nächsten Neustart des Child-Servers (Dashboard-Port/Passwort erst nach Spigot-Neustart)."));
    }

    // ---------------- file browser ----------------
    private Path root() throws IOException { Path r = pl.workDir(); Files.createDirectories(r); return r.toRealPath(); }

    /** Resolves a user path inside the child dir; rejects traversal and symlink escapes. */
    private Path safe(String rel) throws IOException {
        Path root = root();
        if (rel == null) rel = "";
        rel = rel.replace('\\', '/');
        while (rel.startsWith("/")) rel = rel.substring(1);
        Path p = root.resolve(rel).normalize();
        if (!p.startsWith(root)) throw new SecurityException("Pfad außerhalb des Server-Ordners");
        Path check = p;
        while (check != null && !Files.exists(check, LinkOption.NOFOLLOW_LINKS)) check = check.getParent();
        if (check == null || !check.toRealPath().startsWith(root)) throw new SecurityException("Pfad außerhalb des Server-Ordners");
        return p;
    }

    private String rel(Path p) throws IOException { return root().relativize(p).toString().replace('\\', '/'); }

    private void fsList(HttpExchange ex, Map<String, String> q) throws IOException {
        Path d = safe(q.get("path"));
        if (!Files.isDirectory(d)) { json(ex, 404, Map.of("error", "Kein Ordner")); return; }
        List<Map<String, Object>> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
            for (Path c : ds) {
                BasicFileAttributes a = Files.readAttributes(c, BasicFileAttributes.class);
                out.add(Map.of("name", c.getFileName().toString(), "dir", a.isDirectory(), "size", a.size(), "mtime", a.lastModifiedTime().toMillis()));
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> !(Boolean) m.get("dir")).thenComparing(m -> ((String) m.get("name")).toLowerCase(Locale.ROOT)));
        json(ex, 200, Map.of("path", rel(d), "entries", out));
    }

    private void fsRead(HttpExchange ex, Map<String, String> q) throws IOException {
        Path f = safe(q.get("path"));
        if (!Files.isRegularFile(f)) { json(ex, 404, Map.of("error", "Keine Datei")); return; }
        if (f.toString().endsWith(".gz")) {
            try (InputStream in = new GZIPInputStream(Files.newInputStream(f))) { text(ex, new String(in.readNBytes((int) MAX_TEXT * 4), StandardCharsets.UTF_8)); }
            return;
        }
        if (Files.size(f) > MAX_TEXT) {
            // tail for big logs
            long size = Files.size(f);
            try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
                raf.seek(size - MAX_TEXT); byte[] b = new byte[(int) MAX_TEXT]; raf.readFully(b);
                text(ex, "[... Datei gekürzt, letzte 2 MB ...]\n" + new String(b, StandardCharsets.UTF_8));
            }
            return;
        }
        text(ex, Files.readString(f, StandardCharsets.UTF_8));
    }

    private void fsDownload(HttpExchange ex, Map<String, String> q) throws IOException {
        Path f = safe(q.get("path"));
        if (!Files.isRegularFile(f)) { json(ex, 404, Map.of("error", "Keine Datei")); return; }
        ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + f.getFileName().toString().replaceAll("[\"\\\\\\r\\n]", "_") + "\"");
        ex.sendResponseHeaders(200, Files.size(f));
        try (OutputStream os = ex.getResponseBody()) { Files.copy(f, os); }
    }

    private void fsWrite(HttpExchange ex, Map<String, String> q) throws IOException {
        Path f = safe(q.get("path"));
        if (Files.isDirectory(f)) { json(ex, 400, Map.of("error", "Ist ein Ordner")); return; }
        String txt = readBodyText(ex);
        Files.writeString(f, txt, StandardCharsets.UTF_8);
        pl.info("File saved via dashboard: " + rel(f));
        json(ex, 200, Map.of("ok", true));
    }

    private void fsUpload(HttpExchange ex, Map<String, String> q) throws IOException {
        Path dir = safe(q.get("path"));
        String name = q.getOrDefault("name", "");
        if (name.isBlank() || name.contains("/") || name.contains("\\") || name.equals("..") || name.equals(".")) { json(ex, 400, Map.of("error", "Ungültiger Name")); return; }
        Path f = safe(rel(dir) + "/" + name);
        if (Files.exists(f) && !"1".equals(q.get("overwrite"))) { json(ex, 409, Map.of("error", "Datei existiert bereits")); return; }
        Path tmp = f.resolveSibling(name + ".upload-part");
        long n = 0;
        try (InputStream in = ex.getRequestBody(); OutputStream os = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[65536]; int r;
            while ((r = in.read(buf)) > 0) { n += r; if (n > MAX_UPLOAD) throw new IOException("Upload zu groß (max 1 GB)"); os.write(buf, 0, r); }
        } catch (IOException e) { Files.deleteIfExists(tmp); throw e; }
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        pl.info("File uploaded via dashboard: " + rel(f) + " (" + n + " bytes)");
        json(ex, 200, Map.of("ok", true));
    }

    private void fsRename(HttpExchange ex) throws IOException {
        JsonObject o = JsonParser.parseString(readBodyText(ex)).getAsJsonObject();
        Path from = safe(o.get("from").getAsString()), to = safe(o.get("to").getAsString());
        if (from.equals(root())) throw new SecurityException("Wurzelordner kann nicht umbenannt werden");
        if (Files.exists(to)) { json(ex, 409, Map.of("error", "Ziel existiert bereits")); return; }
        Files.move(from, to);
        pl.info("Renamed via dashboard: " + rel(from) + " -> " + rel(to));
        json(ex, 200, Map.of("ok", true));
    }

    private void fsMkdir(HttpExchange ex, Map<String, String> q) throws IOException {
        Path d = safe(q.get("path"));
        Files.createDirectories(d);
        json(ex, 200, Map.of("ok", true));
    }

    /** Deletes a single file or EMPTY folder; requires confirm == file name (UI asks the user). */
    private void fsDelete(HttpExchange ex) throws IOException {
        JsonObject o = JsonParser.parseString(readBodyText(ex)).getAsJsonObject();
        Path p = safe(o.get("path").getAsString());
        if (p.equals(root())) throw new SecurityException("Wurzelordner kann nicht gelöscht werden");
        if (!o.has("confirm") || !o.get("confirm").getAsString().equals(p.getFileName().toString())) { json(ex, 400, Map.of("error", "Bestätigung fehlt")); return; }
        Files.delete(p); // throws if non-empty directory
        pl.info("Deleted via dashboard: " + rel(p));
        json(ex, 200, Map.of("ok", true));
    }

    // ---------------- helpers ----------------
    private void serveIndex(HttpExchange ex) throws IOException {
        try (InputStream in = pl.getResource("web/index.html")) {
            byte[] b = in == null ? "index.html missing".getBytes() : in.readAllBytes();
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:");
            sendBytes(ex, 200, "text/html; charset=utf-8", b);
        }
    }

    private String readOr(Path f) throws IOException { return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : ""; }

    private String readBodyText(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = in.readNBytes((int) MAX_TEXT + 1);
            if (b.length > MAX_TEXT) throw new IOException("Zu groß (max 2 MB)");
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    private interface Extract { String get(JsonObject o); }
    private String body(HttpExchange ex, Extract e) throws IOException { return e.get(JsonParser.parseString(readBodyText(ex)).getAsJsonObject()); }

    private Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private void json(HttpExchange ex, int code, Object o) throws IOException { sendBytes(ex, code, "application/json; charset=utf-8", gson.toJson(o).getBytes(StandardCharsets.UTF_8)); }
    private void text(HttpExchange ex, String s) throws IOException { sendBytes(ex, 200, "text/plain; charset=utf-8", s.getBytes(StandardCharsets.UTF_8)); }
    private void send(HttpExchange ex, int code, String type, String s) throws IOException { sendBytes(ex, code, type, s.getBytes(StandardCharsets.UTF_8)); }
    private void sendBytes(HttpExchange ex, int code, String type, byte[] b) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
