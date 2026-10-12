package de.aichele.childlauncher;

import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.file.*;
import java.util.*;

/** Makes the outer Spigot as cheap as possible. Only touches the outer server's root files and worlds, never child-server/. */
final class OuterTuner {
    private OuterTuner() {}

    static void patchFiles(FabricLauncherPlugin pl) {
        Path root = Paths.get("").toAbsolutePath();
        List<String> changed = new ArrayList<>();
        try {
            Map<String, String> props = new LinkedHashMap<>();
            props.put("view-distance", "2"); props.put("simulation-distance", "2");
            props.put("spawn-monsters", "false"); props.put("spawn-animals", "false"); props.put("spawn-npcs", "false");
            props.put("allow-nether", "false"); props.put("generate-structures", "false");
            props.put("max-players", "2"); props.put("white-list", "true"); props.put("enforce-whitelist", "true");
            props.put("spawn-protection", "0"); props.put("sync-chunk-writes", "false");
            props.put("entity-broadcast-range-percentage", "10");
            Path sp = root.resolve("server.properties");
            if (!sp.toAbsolutePath().startsWith(pl.workDir())) {
                String before = Files.exists(sp) ? Files.readString(sp) : "";
                FabricLauncherPlugin.updateProperties(sp, props, Map.of());
                if (!before.equals(Files.readString(sp))) changed.add("server.properties");
            }
        } catch (Exception e) { pl.getLogger().warning("Outer server.properties patch failed: " + e); }

        Map<String, Object> bukkit = new LinkedHashMap<>();
        bukkit.put("settings.allow-end", false); bukkit.put("settings.allow-nether", false);
        for (String k : List.of("monsters", "animals", "water-animals", "water-ambient", "water-underground-creature", "axolotls", "ambient"))
            bukkit.put("spawn-limits." + k, 0);
        for (String k : List.of("animal-spawns", "monster-spawns", "water-spawns", "water-ambient-spawns", "water-underground-creature-spawns", "axolotl-spawns", "ambient-spawns"))
            bukkit.put("ticks-per." + k, 6000);
        bukkit.put("ticks-per.autosave", 72000);
        bukkit.put("chunk-gc.period-in-ticks", 200);
        if (patchYaml(pl, root.resolve("bukkit.yml"), bukkit)) changed.add("bukkit.yml");

        Map<String, Object> spigot = new LinkedHashMap<>();
        String d = "world-settings.default.";
        spigot.put(d + "view-distance", 2); spigot.put(d + "simulation-distance", 2);
        spigot.put(d + "mob-spawn-range", 0);
        for (String k : List.of("animals", "monsters", "raiders", "misc"))
            spigot.put(d + "entity-activation-range." + k, 2);
        for (String k : List.of("players", "animals", "monsters", "misc", "display", "other"))
            spigot.put(d + "entity-tracking-range." + k, 8);
        spigot.put(d + "entity-activation-range.tick-inactive-villagers", false);
        spigot.put(d + "nerf-spawner-mobs", true);
        spigot.put("settings.save-user-cache-on-stop-only", true);
        if (patchYaml(pl, root.resolve("spigot.yml"), spigot)) changed.add("spigot.yml");

        // Empty leftover dirs in the root (FTP cannot remove them: root not writable for the FTP user). Only EMPTY dirs are removed.
        for (String n : List.of("world_nether", "world_the_end", "timings")) {
            Path ed = root.resolve(n);
            try { if (Files.isDirectory(ed) && !n.equals(pl.getServer().getWorlds().isEmpty() ? "" : pl.getServer().getWorlds().get(0).getName())) {
                try (var st = Files.list(ed)) { if (st.findAny().isEmpty()) { Files.delete(ed); changed.add("removed empty " + n + "/"); } } } }
            catch (Exception ignored) {}
        }
        if (!changed.isEmpty()) pl.getLogger().info("Outer server neutered: patched " + changed + " (effective on next Spigot start)");
    }

    private static boolean patchYaml(FabricLauncherPlugin pl, Path f, Map<String, Object> vals) {
        try {
            if (!Files.exists(f)) return false;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f.toFile());
            boolean ch = false;
            for (var e : vals.entrySet()) if (!Objects.equals(String.valueOf(y.get(e.getKey())), String.valueOf(e.getValue()))) { y.set(e.getKey(), e.getValue()); ch = true; }
            if (ch) { Files.copy(f, f.resolveSibling(f.getFileName() + ".fl-bak"), StandardCopyOption.REPLACE_EXISTING); y.save(f.toFile()); }
            return ch;
        } catch (Exception e) { pl.getLogger().warning("Patch of " + f.getFileName() + " failed: " + e); return false; }
    }

    /** Runtime tweaks for the (already loaded) outer worlds. Main thread. */
    @SuppressWarnings({"deprecation", "unchecked"})
    static void applyRuntime(FabricLauncherPlugin pl) {
        for (World w : Bukkit.getWorlds()) {
            try { w.setSpawnFlags(false, false); } catch (Throwable ignored) {}
            for (String g : List.of("doMobSpawning", "spawn_mobs", "doPatrolSpawning", "doTraderSpawning", "doWardenSpawning", "doWeatherCycle", "doFireTick"))
                setRule(w, g, false);
            setRule(w, "spawnChunkRadius", 0); setRule(w, "spawn_chunk_radius", 0);
            setRule(w, "randomTickSpeed", 0); setRule(w, "random_tick_speed", 0);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setRule(World w, String name, Object v) {
        try {
            GameRule r = GameRule.getByName(name);
            if (r != null && r.getType().isInstance(v)) w.setGameRule(r, v);
        } catch (Throwable ignored) {}
    }
}
