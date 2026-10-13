package de.aichele.childlauncher;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** One child definition. Loaded from config only; jars are fetched when that profile starts. */
final class ServerProfile {
    final String id;
    final String type;
    final String mcVersion;
    final int port;
    final String serverIp;
    final String customJar;
    final String fabricLoaderVersion;
    final String fabricInstallerVersion;
    final String workingDir;
    final String xms;
    final String xmx;
    final List<String> jvmFlags;
    final List<String> serverArgs;
    final boolean autoStart;
    final Map<String, String> serverProperties;
    final Map<String, Boolean> mods;

    ServerProfile(String id, String type, String mcVersion, int port, String serverIp, String customJar,
                  String fabricLoaderVersion, String fabricInstallerVersion, String workingDir,
                  String xms, String xmx, List<String> jvmFlags, List<String> serverArgs, boolean autoStart,
                  Map<String, String> serverProperties, Map<String, Boolean> mods) {
        this.id = id;
        this.type = type;
        this.mcVersion = mcVersion;
        this.port = port;
        this.serverIp = serverIp;
        this.customJar = customJar;
        this.fabricLoaderVersion = fabricLoaderVersion;
        this.fabricInstallerVersion = fabricInstallerVersion;
        this.workingDir = workingDir;
        this.xms = xms;
        this.xmx = xmx;
        this.jvmFlags = jvmFlags;
        this.serverArgs = serverArgs;
        this.autoStart = autoStart;
        this.serverProperties = serverProperties;
        this.mods = mods;
    }

    Path directory() { return Paths.get(workingDir).toAbsolutePath().normalize(); }

    Map<String, Object> view() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("type", type); m.put("mcVersion", mcVersion); m.put("port", port);
        m.put("serverIp", serverIp); m.put("workingDir", directory().toString()); m.put("xmx", xmx); m.put("autoStart", autoStart);
        return m;
    }
}

/** Reads the flat dotted map produced by Bukkit {@code getValues(true)}. */
final class Profiles {
    private Profiles() {}

    static List<ServerProfile> load(Map<String, Object> flat) {
        ServerProfile root = one(flat, "default", "", true);
        String rootId = str(flat, "serverId", "default");
        if (!"default".equals(rootId)) root = copyId(root, rootId);
        Map<String, ServerProfile> out = new LinkedHashMap<>();
        out.put(root.id, root);
        for (String id : serverIds(flat)) {
            if (id.isBlank()) continue;
            out.put(id, ProfileMerge.inherit(one(flat, id, "servers." + id + ".", false), root));
        }
        return List.copyOf(out.values());
    }

    static boolean portClash(List<ServerProfile> profiles, int port) {
        int n = 0;
        for (ServerProfile p : profiles) if (p.port == port) n++;
        return n > 1;
    }

    static String focus(Map<String, Object> flat, List<ServerProfile> profiles) {
        String id = str(flat, "focusServer", profiles.get(0).id);
        for (ServerProfile p : profiles) if (p.id.equals(id)) return id;
        return profiles.get(0).id;
    }

    private static Set<String> serverIds(Map<String, Object> flat) {
        Set<String> ids = new LinkedHashSet<>();
        for (String k : flat.keySet()) {
            if (!k.startsWith("servers.")) continue;
            String rest = k.substring("servers.".length());
            int dot = rest.indexOf('.');
            ids.add(dot < 0 ? rest : rest.substring(0, dot));
        }
        return ids;
    }

    private static ServerProfile one(Map<String, Object> flat, String id, String prefix, boolean rootDefaults) {
        String type = str(flat, prefix + "type", rootDefaults ? "fabric" : null);
        String mc = str(flat, prefix + "mcVersion", rootDefaults ? "26.3" : null);
        int port = num(flat, prefix + "port", rootDefaults ? 2063 : -1);
        String ip = str(flat, prefix + "serverIp", rootDefaults ? "0.0.0.0" : null);
        String jar = str(flat, prefix + "customJar", rootDefaults ? "server.jar" : null);
        String loader = str(flat, prefix + "fabricLoaderVersion", rootDefaults ? "latest" : null);
        String inst = str(flat, prefix + "fabricInstallerVersion", rootDefaults ? "latest" : null);
        String dir = str(flat, prefix + "workingDir", rootDefaults ? "./child-server" : null);
        String xms = str(flat, prefix + "memory.xms", rootDefaults ? "8G" : null);
        String xmx = str(flat, prefix + "memory.xmx", rootDefaults ? "8G" : null);
        List<String> flags = list(flat.get(prefix + "jvmFlags"));
        List<String> args = list(flat.get(prefix + "serverArgs"));
        if (rootDefaults) {
            if (flags == null) flags = List.of();
            if (args == null) args = List.of();
        }
        boolean auto = bool(flat, prefix + "autoStart", rootDefaults);
        return new ServerProfile(id, type, mc, port, ip, jar, loader, inst, dir, xms, xmx,
                flags, args, auto, props(flat, prefix), mods(flat, prefix));
    }

    private static ServerProfile copyId(ServerProfile p, String id) {
        return new ServerProfile(id, p.type, p.mcVersion, p.port, p.serverIp, p.customJar, p.fabricLoaderVersion,
                p.fabricInstallerVersion, p.workingDir, p.xms, p.xmx, p.jvmFlags, p.serverArgs, p.autoStart, p.serverProperties, p.mods);
    }

    private static Map<String, String> props(Map<String, Object> flat, String prefix) {
        String p = prefix + "serverProperties.";
        Map<String, String> m = new LinkedHashMap<>();
        for (var e : flat.entrySet()) if (e.getKey().startsWith(p)) m.put(e.getKey().substring(p.length()), String.valueOf(e.getValue()));
        return m;
    }

    private static Map<String, Boolean> mods(Map<String, Object> flat, String prefix) {
        String p = prefix + "mods.";
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (var e : flat.entrySet()) if (e.getKey().startsWith(p) && e.getValue() instanceof Boolean b) m.put(e.getKey().substring(p.length()), b);
        return m;
    }

    private static String str(Map<String, Object> flat, String key, String fallback) {
        Object v = flat.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    private static int num(Map<String, Object> flat, String key, int fallback) {
        Object v = flat.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v == null) return fallback;
        try { return Integer.parseInt(String.valueOf(v).trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private static boolean bool(Map<String, Object> flat, String key, boolean fallback) {
        Object v = flat.get(key);
        if (v instanceof Boolean b) return b;
        if (v == null) return fallback;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Object raw) {
        if (!(raw instanceof List<?> l)) return null;
        List<String> out = new ArrayList<>();
        for (Object o : l) if (o != null) out.add(String.valueOf(o));
        return out;
    }
}

final class ProfileMerge {
    private ProfileMerge() {}

    static ServerProfile inherit(ServerProfile child, ServerProfile root) {
        return new ServerProfile(child.id,
                nvl(child.type, root.type),
                nvl(child.mcVersion, root.mcVersion),
                child.port < 0 ? root.port : child.port,
                nvl(child.serverIp, root.serverIp),
                nvl(child.customJar, root.customJar),
                nvl(child.fabricLoaderVersion, root.fabricLoaderVersion),
                nvl(child.fabricInstallerVersion, root.fabricInstallerVersion),
                nvl(child.workingDir, "./child-" + safeId(child.id)),
                nvl(child.xms, root.xms),
                nvl(child.xmx, root.xmx),
                child.jvmFlags == null ? root.jvmFlags : child.jvmFlags,
                child.serverArgs == null ? root.serverArgs : child.serverArgs,
                child.autoStart,
                mergeProps(root.serverProperties, child.serverProperties),
                child.mods.isEmpty() ? Map.of() : child.mods);
    }

    private static Map<String, String> mergeProps(Map<String, String> base, Map<String, String> over) {
        Map<String, String> m = new LinkedHashMap<>();
        if (base != null) m.putAll(base);
        if (over != null) m.putAll(over);
        return m;
    }

    private static String nvl(String v, String fallback) { return v == null || v.isBlank() ? fallback : v; }
    private static String safeId(String id) { return id.replaceAll("[^A-Za-z0-9._-]", "_"); }
}
