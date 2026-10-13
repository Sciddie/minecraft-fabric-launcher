package de.aichele.childlauncher;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HosterTest {
    @TempDir Path tmp;

    @Test void profilesKeepLegacyRootAndAddServers() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "fabric");
        cfg.put("mcVersion", "26.3");
        cfg.put("port", 2063);
        cfg.put("workingDir", "./child-server");
        cfg.put("memory.xmx", "8G");
        cfg.put("memory.xms", "8G");
        cfg.put("autoStart", true);
        cfg.put("jvmFlags", List.of("-XX:+UseG1GC"));
        cfg.put("serverProperties.motd", "hi");
        cfg.put("mods.lithium", true);
        cfg.put("servers.paper-test.type", "paper");
        cfg.put("servers.paper-test.mcVersion", "26.3");
        cfg.put("servers.paper-test.port", 2065);
        cfg.put("servers.paper-test.autoStart", false);
        cfg.put("focusServer", "paper-test");
        List<ServerProfile> all = Profiles.load(cfg);
        assertEquals(2, all.size());
        ServerProfile fabric = all.get(0);
        assertEquals("default", fabric.id);
        assertEquals("fabric", fabric.type);
        assertTrue(fabric.mods.get("lithium"));
        ServerProfile paper = all.get(1);
        assertEquals("paper", paper.type);
        assertEquals(2065, paper.port);
        assertEquals("8G", paper.xmx);
        assertEquals("-XX:+UseG1GC", paper.jvmFlags.get(0));
        assertTrue(paper.mods.isEmpty());
        assertTrue(paper.directory().endsWith("child-paper-test"));
        assertEquals("hi", paper.serverProperties.get("motd"));
        assertEquals("paper-test", Profiles.focus(cfg, all));
        assertFalse(Profiles.portClash(all, 2063));
        cfg.put("servers.paper-test.port", 2063);
        assertTrue(Profiles.portClash(Profiles.load(cfg), 2063));
    }

    @Test void cronRespectsZoneAndSunday() {
        ZonedDateTime berlin = ZonedDateTime.of(2026, 10, 13, 4, 0, 0, 0, ZoneId.of("Europe/Berlin"));
        ZonedDateTime utc = berlin.withZoneSameInstant(ZoneId.of("UTC"));
        assertEquals(2, utc.getHour());
        assertTrue(CronExpr.matches("0 4 * * *", berlin));
        assertFalse(CronExpr.matches("0 4 * * *", utc));
        assertTrue(CronExpr.matches("*/15 * * * *", berlin.withMinute(15)));
        assertFalse(CronExpr.matches("*/15 * * * *", berlin.withMinute(16)));
        ZonedDateTime sunday = ZonedDateTime.of(2026, 10, 18, 5, 0, 0, 0, ZoneId.of("UTC"));
        assertEquals(java.time.DayOfWeek.SUNDAY, sunday.getDayOfWeek());
        assertTrue(CronExpr.matches("0 5 * * 0", sunday));
        assertTrue(CronExpr.matches("0 5 * * 7", sunday));
        assertFalse(CronExpr.matches("0 5 * * 1", sunday));
        assertThrows(IllegalArgumentException.class, () -> CronExpr.matches("0 5 * *", sunday));
        ZonedDateTime before = berlin.minusMinutes(1);
        assertEquals(berlin.withSecond(0).withNano(0), CronExpr.next("0 4 * * *", before));
        assertEquals(berlin.plusDays(1).withSecond(0).withNano(0), CronExpr.next("0 4 * * *", berlin));
    }

    @Test void tasksParse() {
        List<TaskDef> tasks = TaskDef.load(List.of(Map.of("id", "save", "enabled", true, "cron", "*/15 * * * *", "timezone", "Europe/Berlin", "action", "command", "command", "save-all", "server", "default")));
        assertEquals(1, tasks.size());
        assertEquals("save", tasks.get(0).id);
        assertEquals("Europe/Berlin", tasks.get(0).timezone);
        assertTrue(tasks.get(0).enabled);
        assertTrue(TaskDef.load(null).isEmpty());
    }

    @Test void versionParsers() {
        var vanilla = VersionCatalog.vanilla(JsonParser.parseString("{\"versions\":[{\"id\":\"26.3\",\"type\":\"release\"},{\"id\":\"26.4-pre\",\"type\":\"snapshot\"},{\"id\":\"26.2\",\"type\":\"release\"}]}").getAsJsonObject(), 10);
        assertEquals(List.of("26.3", "26.2"), vanilla);
        var fabric = VersionCatalog.fabric(JsonParser.parseString("[{\"version\":\"26.4-snapshot\",\"stable\":false},{\"version\":\"26.3\",\"stable\":true}]").getAsJsonArray(), 10);
        assertEquals(List.of("26.3"), fabric);
        var paper = VersionCatalog.paper(JsonParser.parseString("{\"versions\":{\"26.3\":[\"26.3\",\"26.3-rc-3\"],\"1.21\":[\"1.21.8\"]}}").getAsJsonObject(), 10);
        assertEquals(List.of("26.3", "26.3-rc-3", "1.21.8"), paper);
        var spigot = VersionCatalog.spigot("<a href=\"latest.json\">latest.json</a><a href=\"1.21.4.json\">1.21.4.json</a><a href=\"26.3.json\">26.3.json</a><a href=\"1.8.json\">", 10);
        assertEquals(List.of("26.3", "1.21.4", "1.8"), spigot);
        assertTrue(VersionCatalog.compare("26.3", "26.3-rc-1") < 0);
    }

    @Test void spigotCommandIsArgv() {
        var cmd = SpigotBuilder.command("/usr/bin/java", Path.of("/opt/BuildTools.jar"), "26.3", Path.of("/srv/child"));
        assertEquals("26.3", cmd.get(cmd.indexOf("--rev") + 1));
        assertEquals("spigot-26.3.jar", cmd.get(cmd.size() - 1));
        assertFalse(String.join(" ", cmd).contains("&&"));
        assertThrows(IllegalArgumentException.class, () -> SpigotBuilder.assertRev("26.3;rm"));
    }

    @Test void backupZipAndExternalJdbc() throws Exception {
        Path world = Files.createDirectories(tmp.resolve("child/world"));
        Files.writeString(world.resolve("level.dat"), "world");
        Files.createSymbolicLink(tmp.resolve("child/escape"), tmp.resolve("outside"));
        Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(tmp.resolve("outside/secret.txt"), "nope");
        BackupBridge.Spec spec = new BackupBridge.Spec(tmp.resolve("backups").toString(), List.of("world", "escape"), 2, true,
                "jdbc:h2:mem:fl" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "", "", "org.h2.Driver", "fabriclauncher_backups");
        BackupBridge.Result r = BackupBridge.archive(tmp.resolve("child"), spec, "default");
        assertTrue(Files.size(Path.of(r.path())) > 0);
        assertEquals(64, r.sha256().length());
        assertEquals("ok", r.db());
        assertEquals(1, BackupBridge.count(spec));
        try (var z = new java.util.zip.ZipFile(r.path())) {
            assertNotNull(z.getEntry("world/level.dat"));
            assertNull(z.getEntry("escape/secret.txt"));
        }
        BackupBridge.archive(tmp.resolve("child"), spec, "default");
        BackupBridge.archive(tmp.resolve("child"), spec, "default");
        try (var ds = Files.newDirectoryStream(tmp.resolve("backups"), "backup-default-*.zip")) {
            int n = 0; for (var ignored : ds) n++;
            assertEquals(2, n);
        }
        assertEquals(3, BackupBridge.count(spec));
        assertThrows(IllegalArgumentException.class, () -> BackupBridge.table(new BackupBridge.Spec("", List.of(), 1, false, "", "", "", "", "", "bad-name;drop")));
        List<BackupBridge.Entry> listed = BackupBridge.list(spec, "default");
        assertEquals(2, listed.size());
        String name = listed.get(0).name();
        Path fresh = Files.createDirectories(tmp.resolve("restored"));
        Files.writeString(fresh.resolve("old.txt"), "x");
        assertEquals(1, BackupBridge.restore(fresh, spec, "default", name));
        assertEquals("world", Files.readString(fresh.resolve("world/level.dat")));
        Path slip = tmp.resolve("backups").resolve("backup-default-20000101-000000-zz.zip");
        try (var z = new java.util.zip.ZipOutputStream(Files.newOutputStream(slip))) {
            z.putNextEntry(new java.util.zip.ZipEntry("../secret.txt"));
            z.write("nope".getBytes());
            z.closeEntry();
        }
        assertThrows(SecurityException.class, () -> BackupBridge.restore(fresh, spec, "default", slip.getFileName().toString()));
        assertFalse(Files.exists(tmp.resolve("secret.txt")));
        BackupBridge.delete(spec, "default", slip.getFileName().toString());
        BackupBridge.delete(spec, "default", name);
        assertEquals(1, BackupBridge.list(spec, "default").size());
        assertThrows(IllegalArgumentException.class, () -> BackupBridge.file(spec, "default", "../" + name));
    }

    @Test void launchPlanDropsDeadFlagAndNamesJars() {
        Path dir = tmp.resolve("plan");
        ServerProfile fabric = profile("fabric", "26.3", "server.jar", List.of("-XX:+UseG1GC", "-XX:G1RSetUpdatingPauseIntervalMillis=0", "-Xlog:gc"), List.of("--bonus"), dir.toString());
        List<String> argv = LaunchPlan.argv("java", fabric);
        String joined = String.join("\n", argv);
        assertFalse(joined.contains("G1RSetUpdatingPauseIntervalMillis"));
        assertEquals(List.of("java", "-XX:+IgnoreUnrecognizedVMOptions", "-Xms8G", "-Xmx8G", "-XX:+UseG1GC", "-Xlog:gc", "-jar", dir.resolve("fabric-server-launch-26.3.jar").toString(), "nogui", "--bonus"), argv);
        assertFalse(Files.exists(dir.resolve("fabric-server-launch-26.3.jar")));
        assertEquals("paper-26.3.jar", LaunchPlan.jarName("paper", "26.3", null));
        assertEquals("minecraft_server-1.21.8.jar", LaunchPlan.jarName("vanilla", "1.21.8", null));
        assertEquals("spigot-26.3.jar", LaunchPlan.jarName("spigot", "26.3", null));
        assertEquals("custom-pack.jar", LaunchPlan.jarName("custom", "26.3", "/opt/jars/custom-pack.jar"));
        assertEquals("server.jar", LaunchPlan.jarName("other", "26.3", null));
    }

    @Test void crashIndexReadsAndRejectsEscape() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("child"));
        Path crashes = Files.createDirectories(root.resolve("crash-reports"));
        Files.writeString(crashes.resolve("crash-2026-10-13_12.00.00.txt"), "hello crash\nline2\n");
        Files.writeString(crashes.resolve("notes.md"), "nope");
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(crashes.resolve("link.txt"), outside.resolve("secret.txt"));
        assertEquals(List.of("crash-2026-10-13_12.00.00.txt"), CrashIndex.list(root));
        String body = CrashIndex.read(root, "crash-2026-10-13_12.00.00.txt");
        assertTrue(body.startsWith("hello crash\nline2"));
        assertThrows(SecurityException.class, () -> CrashIndex.read(root, "../secret.txt"));
        assertThrows(SecurityException.class, () -> CrashIndex.read(root, "link.txt"));
        assertFalse(body.contains("secret"));
    }

    private static ServerProfile profile(String type, String mc, String custom, List<String> flags, List<String> args, String dir) {
        return new ServerProfile("t", type, mc, 2063, "0.0.0.0", custom, "latest", "latest", dir, "8G", "8G", flags, args, false, Map.of(), Map.of());
    }
}
