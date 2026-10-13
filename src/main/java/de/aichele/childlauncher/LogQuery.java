package de.aichele.childlauncher;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/** Bounded log tail with a level and substring filter. Reads from the end; does not load the whole file. */
final class LogQuery {
    record Result(boolean truncated, List<String> lines) {}

    private LogQuery() {}

    static Result query(Path file, String level, String text, int limit, int maxBytes) throws IOException {
        if (!Files.isRegularFile(file)) throw new NoSuchFileException(file.getFileName().toString());
        if (limit <= 0) limit = 2000;
        limit = Math.min(limit, 20_000);
        if (maxBytes < 4096) maxBytes = 4096;
        maxBytes = Math.min(maxBytes, 8 * 1024 * 1024);
        boolean gz = file.getFileName().toString().endsWith(".gz");
        String data;
        boolean truncated;
        if (gz) {
            try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
                byte[] b = in.readNBytes(maxBytes + 1);
                truncated = b.length > maxBytes;
                data = new String(b, 0, Math.min(b.length, maxBytes), StandardCharsets.UTF_8);
            }
        } else {
            long size = Files.size(file);
            truncated = size > maxBytes;
            if (!truncated) data = Files.readString(file, StandardCharsets.UTF_8);
            else {
                try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                    raf.seek(size - maxBytes);
                    byte[] b = new byte[maxBytes];
                    raf.readFully(b);
                    data = new String(b, StandardCharsets.UTF_8);
                    int nl = data.indexOf('\n');
                    if (nl >= 0 && nl + 1 < data.length()) data = data.substring(nl + 1);
                }
            }
        }
        String needle = text == null ? "" : text.toLowerCase(Locale.ROOT);
        String lv = level == null || level.isBlank() ? "all" : level.toLowerCase(Locale.ROOT);
        ArrayDeque<String> keep = new ArrayDeque<>();
        boolean dropped = false;
        for (String line : data.split("\n", -1)) {
            if (!levelOk(line, lv) || (!needle.isEmpty() && !line.toLowerCase(Locale.ROOT).contains(needle))) continue;
            keep.addLast(line);
            if (keep.size() > limit) { keep.removeFirst(); dropped = true; }
        }
        return new Result(truncated || dropped, new ArrayList<>(keep));
    }

    static boolean levelOk(String line, String level) {
        return switch (level) {
            case "error" -> isError(line);
            case "warn" -> isWarn(line) || isError(line);
            case "info" -> !isWarn(line) && !isError(line);
            default -> true;
        };
    }

    static boolean isError(String line) { return line.contains("ERROR") || line.contains("FATAL") || line.contains("Exception"); }
    static boolean isWarn(String line) { return line.contains("WARN"); }
}
