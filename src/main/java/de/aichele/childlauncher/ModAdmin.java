package de.aichele.childlauncher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lists, toggles and installs mods/plugins on demand. Does not change a running world. */
final class ModAdmin {
    record Entry(String path, String name, String folder, boolean directory, boolean toggleable, boolean enabled, long size, long mtime) {}

    private ModAdmin() {}

    static List<Entry> list(Path work) throws IOException {
        List<Entry> out = new ArrayList<>();
        for (String folder : List.of("mods", "plugins")) {
            Path dir = work.resolve(folder);
            if (!Files.isDirectory(dir)) continue;
            try (var ds = Files.newDirectoryStream(dir)) {
                for (Path c : ds) {
                    String name = c.getFileName().toString();
                    if (name.startsWith(".fl-")) continue;
                    BasicFileAttributes a = Files.readAttributes(c, BasicFileAttributes.class);
                    boolean jar = !a.isDirectory() && (name.endsWith(".jar") || name.endsWith(".jar.disabled"));
                    out.add(new Entry(folder + "/" + name, name, folder, a.isDirectory(), jar, name.endsWith(".jar") && !name.endsWith(".jar.disabled"), a.size(), a.lastModifiedTime().toMillis()));
                }
            }
        }
        out.sort(Comparator.comparing(Entry::folder).thenComparing(Entry::directory).thenComparing(e -> e.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    /** {@code .jar} &lt;-&gt; {@code .jar.disabled}. Effective after the child restarts. */
    static String toggle(PathGuard guard, String rel, String confirm) throws IOException {
        Path p = guard.resolve(rel);
        if (!Files.isRegularFile(p)) throw new IOException("Keine Datei");
        String name = p.getFileName().toString();
        if (confirm == null || !confirm.equals(name)) throw new IllegalArgumentException("Bestätigung fehlt");
        if (!name.endsWith(".jar") && !name.endsWith(".jar.disabled")) throw new IllegalArgumentException("Nur .jar");
        Path parent = p.getParent() == null ? null : p.getParent().getFileName();
        if (parent == null || !(parent.toString().equals("mods") || parent.toString().equals("plugins"))) throw new SecurityException("Nur mods/ oder plugins/");
        String next = name.endsWith(".disabled") ? name.substring(0, name.length() - ".disabled".length()) : name + ".disabled";
        Path dest = p.resolveSibling(next);
        if (Files.exists(dest)) throw new IOException("Ziel existiert bereits");
        Files.move(p, dest);
        return guard.relativize(dest);
    }

    static List<Map<String, Object>> search(Upstream up, String type, String query) throws Exception {
        if (query == null || query.isBlank() || query.length() > 80) throw new IllegalArgumentException("query");
        String facets = facets(type);
        JsonObject o = up.getJson("https://api.modrinth.com/v2/search?limit=15&query=" + enc(query) + "&facets=" + enc(facets)).getAsJsonObject();
        List<Map<String, Object>> hits = new ArrayList<>();
        JsonArray arr = o.getAsJsonArray("hits");
        if (arr == null) return hits;
        for (JsonElement e : arr) {
            JsonObject h = e.getAsJsonObject();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slug", str(h, "slug"));
            row.put("title", str(h, "title"));
            row.put("project_type", str(h, "project_type"));
            row.put("downloads", h.has("downloads") && !h.get("downloads").isJsonNull() ? h.get("downloads").getAsLong() : 0L);
            hits.add(row);
        }
        return hits;
    }

    static Map<String, Object> install(Upstream up, Path work, String type, String mc, String slug, String version) throws Exception {
        if (slug == null || !slug.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("slug");
        if (version != null && !version.isBlank() && !version.matches("[A-Za-z0-9._+-]{1,80}")) throw new IllegalArgumentException("version");
        Target t = target(type);
        JsonObject ver;
        if (version != null && !version.isBlank()) {
            ver = up.getJson("https://api.modrinth.com/v2/project/" + enc(slug) + "/version/" + enc(version)).getAsJsonObject();
        } else {
            String q = "?loaders=" + enc("[\"" + t.loader + "\"]") + "&game_versions=" + enc("[\"" + mc + "\"]");
            JsonArray vers = up.getJson("https://api.modrinth.com/v2/project/" + enc(slug) + "/version" + q).getAsJsonArray();
            if (vers.isEmpty()) throw new IOException("Keine Version für " + slug + " / " + mc);
            ver = pickRelease(vers);
        }
        JsonObject file = primaryFile(ver);
        String name = str(file, "filename");
        if (name == null || !name.matches("[A-Za-z0-9._+-]{1,128}\\.jar")) throw new IOException("Unerwarteter Dateiname");
        requireModrinthCdn(str(file, "url"));
        String sha1 = null;
        if (file.has("hashes") && file.get("hashes").isJsonObject() && file.getAsJsonObject("hashes").has("sha1"))
            sha1 = file.getAsJsonObject("hashes").get("sha1").getAsString();
        Path dir = work.resolve(t.folder);
        Files.createDirectories(dir);
        up.download(str(file, "url"), dir.resolve(name), "SHA-1", sha1);
        String verNum = str(ver, "version_number");
        Files.writeString(dir.resolve(".fl-" + slug + "-" + name + ".marker"), verNum == null ? "" : verNum);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", t.folder + "/" + name);
        out.put("version", verNum);
        out.put("folder", t.folder);
        return out;
    }

    static void requireModrinthCdn(String url) {
        URI u = URI.create(url);
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(u.getScheme()) || !(host.equals("cdn.modrinth.com") || host.endsWith(".modrinth.com")))
            throw new IllegalArgumentException("Unerwartete Download-URL");
    }

    private static JsonObject pickRelease(JsonArray vers) {
        JsonObject v = vers.get(0).getAsJsonObject();
        for (JsonElement e : vers) if ("release".equals(str(e.getAsJsonObject(), "version_type"))) return e.getAsJsonObject();
        return v;
    }

    private static JsonObject primaryFile(JsonObject ver) {
        JsonObject file = null;
        for (JsonElement f : ver.getAsJsonArray("files")) if (f.getAsJsonObject().has("primary") && f.getAsJsonObject().get("primary").getAsBoolean()) file = f.getAsJsonObject();
        if (file == null) file = ver.getAsJsonArray("files").get(0).getAsJsonObject();
        return file;
    }

    private static String facets(String type) {
        return switch (norm(type)) {
            case "paper" -> "[[\"project_type:plugin\"],[\"categories:paper\"]]";
            case "spigot" -> "[[\"project_type:plugin\"],[\"categories:spigot\"]]";
            case "vanilla" -> throw new IllegalArgumentException("type vanilla hat keinen Mod-Loader");
            default -> "[[\"project_type:mod\"],[\"categories:fabric\"]]";
        };
    }

    private record Target(String folder, String loader) {}

    private static Target target(String type) {
        return switch (norm(type)) {
            case "paper" -> new Target("plugins", "paper");
            case "spigot" -> new Target("plugins", "spigot");
            case "vanilla" -> throw new IllegalArgumentException("type vanilla hat keinen Mod-Loader");
            default -> new Target("mods", "fabric");
        };
    }

    private static String norm(String type) { return type == null ? "fabric" : type.toLowerCase(Locale.ROOT); }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static String str(JsonObject o, String k) { JsonElement e = o.get(k); return e == null || e.isJsonNull() ? null : e.getAsString(); }
}
