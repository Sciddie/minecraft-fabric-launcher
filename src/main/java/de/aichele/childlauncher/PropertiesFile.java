package de.aichele.childlauncher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Line-based server.properties edit: keeps comments, order and unknown keys. */
final class PropertiesFile {
    private PropertiesFile() {}

    static void update(Path f, Map<String, String> force, Map<String, String> defaults) throws IOException {
        List<String> lines = Files.exists(f) ? new ArrayList<>(Files.readAllLines(f, StandardCharsets.UTF_8)) : new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) continue;
            int eq = t.indexOf('=');
            String k = (eq < 0 ? t : t.substring(0, eq)).trim();
            seen.add(k);
            if (force.containsKey(k)) lines.set(i, k + "=" + force.get(k));
        }
        for (var e : force.entrySet()) if (!seen.contains(e.getKey())) { lines.add(e.getKey() + "=" + e.getValue()); seen.add(e.getKey()); }
        for (var e : defaults.entrySet()) if (!seen.contains(e.getKey())) lines.add(e.getKey() + "=" + e.getValue());
        Files.write(f, lines, StandardCharsets.UTF_8);
    }
}
