package de.aichele.childlauncher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Official BuildTools, only when a Spigot profile is started and the jar is missing. */
final class SpigotBuilder {
    static final String BUILD_TOOLS_URL = "https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar";

    private SpigotBuilder() {}

    static void assertRev(String mc) {
        if (mc == null || !mc.matches("[0-9A-Za-z][0-9A-Za-z._-]{0,31}")) throw new IllegalArgumentException("mcVersion");
    }

    static List<String> command(String javaBin, Path buildTools, String mc, Path outputDir) {
        assertRev(mc);
        return List.of(javaBin, "-jar", buildTools.toString(), "--rev", mc, "--output-dir", outputDir.toString(), "--final-name", "spigot-" + mc + ".jar");
    }

    static Path build(Upstream up, Path dataDir, String mc, Path outputDir, String javaBin, Consumer<String> log) throws Exception {
        assertRev(mc);
        Path jar = outputDir.resolve("spigot-" + mc + ".jar");
        if (Files.isRegularFile(jar)) return jar;
        Path tools = dataDir.resolve("buildtools");
        Files.createDirectories(tools);
        Files.createDirectories(outputDir);
        Path bt = tools.resolve("BuildTools.jar");
        if (!Files.isRegularFile(bt)) up.download(BUILD_TOOLS_URL, bt);
        Path work = tools.resolve("work-" + mc.replaceAll("[^A-Za-z0-9._-]", "_"));
        Files.createDirectories(work);
        if (!commandExists("git")) throw new IOException("git missing; BuildTools needs git");
        List<String> cmd = new ArrayList<>(command(javaBin, bt, mc, outputDir));
        log.accept("BuildTools " + String.join(" ", cmd));
        Process p = new ProcessBuilder(cmd).directory(work.toFile()).redirectErrorStream(true).start();
        Thread pump = new Thread(() -> {
            try (var in = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line; while ((line = in.readLine()) != null) log.accept("[BuildTools] " + line);
            } catch (IOException ignored) {}
        }, "FabricLauncher-BuildTools");
        pump.setDaemon(true);
        pump.start();
        if (!p.waitFor(45, TimeUnit.MINUTES)) { p.destroyForcibly(); throw new IOException("BuildTools timeout"); }
        pump.join(2000);
        if (p.exitValue() != 0 || !Files.isRegularFile(jar)) throw new IOException("BuildTools exit " + p.exitValue());
        return jar;
    }

    private static boolean commandExists(String name) {
        try {
            Process p = new ProcessBuilder(name, "--version").redirectErrorStream(true).start();
            return p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }
}
