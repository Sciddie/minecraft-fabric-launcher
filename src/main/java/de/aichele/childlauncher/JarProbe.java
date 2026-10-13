package de.aichele.childlauncher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Local stat of the jar LaunchPlan would use. Does not download, hash, or follow symlinks. */
final class JarProbe {
    record Info(String name, String path, boolean exists, boolean regular, boolean symlink, long bytes, long mtime) {}

    private JarProbe() {}

    static Info inspect(ServerProfile profile) throws IOException {
        Path jar = LaunchPlan.jarPath(profile);
        String name = jar.getFileName() == null ? LaunchPlan.jarName(profile.type, profile.mcVersion, profile.customJar) : jar.getFileName().toString();
        if (Files.isSymbolicLink(jar)) return new Info(name, jar.toString(), true, false, true, -1, -1);
        if (!Files.exists(jar, LinkOption.NOFOLLOW_LINKS)) return new Info(name, jar.toString(), false, false, false, -1, -1);
        if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) return new Info(name, jar.toString(), true, false, false, -1, -1);
        return new Info(name, jar.toString(), true, true, false, Files.size(jar), Files.getLastModifiedTime(jar).toMillis());
    }

    static Map<String, Object> view(Info info) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", info.name());
        m.put("path", info.path());
        m.put("exists", info.exists());
        m.put("regular", info.regular());
        m.put("symlink", info.symlink());
        m.put("bytes", info.bytes());
        m.put("mtime", info.mtime());
        return m;
    }

    static String text(Info info) {
        if (!info.exists()) return info.name() + " fehlt " + info.path();
        if (info.symlink()) return info.name() + " symlink " + info.path();
        if (!info.regular()) return info.name() + " kein File " + info.path();
        return info.name() + " " + info.bytes() + " " + info.path();
    }
}
