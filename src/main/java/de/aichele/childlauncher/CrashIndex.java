package de.aichele.childlauncher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Reads existing crash-reports. No writes, no downloads. */
final class CrashIndex {
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,180}\\.txt");
    static final int MAX_LINES = 4000;
    static final int MAX_BYTES = 1024 * 1024;

    private CrashIndex() {}

    static List<String> list(Path workDir) throws IOException {
        PathGuard guard = PathGuard.open(workDir);
        Path dir = guard.resolve("crash-reports");
        if (Files.isSymbolicLink(dir)) throw new SecurityException("Pfad außerhalb des Server-Ordners");
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<String> names = new ArrayList<>();
        try (var ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (!NAME.matcher(name).matches()) continue;
                if (Files.isSymbolicLink(p) || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue;
                names.add(name);
            }
        }
        names.sort(Comparator.reverseOrder());
        return List.copyOf(names);
    }

    static String read(Path workDir, String file) throws IOException {
        if (file == null || !NAME.matcher(file).matches()) throw new SecurityException("Pfad außerhalb des Server-Ordners");
        PathGuard guard = PathGuard.open(workDir);
        Path dir = guard.resolve("crash-reports");
        if (Files.isSymbolicLink(dir)) throw new SecurityException("Pfad außerhalb des Server-Ordners");
        Path p = guard.resolve("crash-reports/" + file);
        if (Files.isSymbolicLink(p)) throw new SecurityException("Pfad außerhalb des Server-Ordners");
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) throw new IOException(file);
        LogQuery.Result result = LogQuery.query(p, "all", "", MAX_LINES, MAX_BYTES);
        return String.join("\n", result.lines());
    }
}
