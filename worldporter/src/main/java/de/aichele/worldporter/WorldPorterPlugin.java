package de.aichele.worldporter;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class WorldPorterPlugin extends JavaPlugin {
    private record Move(Path from, Path to) {}

    @Override public void onEnable() {
        saveDefaultConfig();
        if (!getConfig().getBoolean("runOnEnable", true)) { getLogger().info("runOnEnable=false, idle. Use /worldporter run"); return; }
        if (Files.exists(marker())) { getLogger().info("Already ran successfully (marker " + marker() + "). Delete it to run again on enable."); return; }
        run(getConfig().getBoolean("dryRun", false), null);
    }

    private Path root() { return Paths.get("").toAbsolutePath().normalize(); }
    private Path marker() { return getDataFolder().toPath().resolve("done.marker"); }

    private List<Move> plan(FileConfiguration c) {
        Path src = root().resolve(c.getString("sourceDir", ".")).normalize();
        Path dst = root().resolve(c.getString("targetDir", "./child-server")).normalize();
        String ow = c.getString("worlds.overworld", "world"), ne = c.getString("worlds.nether", "world_nether"), en = c.getString("worlds.end", "world_the_end");
        String tl = c.getString("targetLevelName", "world");
        List<Move> m = new ArrayList<>();
        switch (c.getString("direction", "spigot-to-vanilla").toLowerCase(Locale.ROOT)) {
            case "spigot-to-vanilla" -> {
                m.add(new Move(src.resolve(ow), dst.resolve(tl)));
                m.add(dim(src.resolve(ne), dst.resolve(tl), "the_nether", "DIM-1"));
                m.add(dim(src.resolve(en), dst.resolve(tl), "the_end", "DIM1"));
            }
            case "vanilla-to-spigot" -> {
                // dimensions first (they live inside the overworld folder), then the overworld itself
                m.add(dim(src.resolve(tl), dst.resolve(ne), "the_nether", "DIM-1"));
                m.add(dim(src.resolve(tl), dst.resolve(en), "the_end", "DIM1"));
                m.add(new Move(src.resolve(tl), dst.resolve(ow)));
            }
            case "move-only" -> {
                for (String w : List.of(ow, ne, en)) m.add(new Move(src.resolve(w), dst.resolve(w)));
            }
            default -> throw new IllegalArgumentException("Unknown direction " + c.getString("direction"));
        }
        return m;
    }

    /** 26.1+ layout: dimensions/minecraft/<id>; falls back to legacy DIM-1/DIM1 (pre-26.1) if only that exists. */
    private Move dim(Path fromWorld, Path toWorld, String id, String legacy) {
        Path modern = Paths.get("dimensions", "minecraft", id);
        if (!Files.isDirectory(fromWorld.resolve(modern)) && Files.isDirectory(fromWorld.resolve(legacy))) {
            getLogger().info("Using legacy layout for " + id + ": " + fromWorld.resolve(legacy));
            return new Move(fromWorld.resolve(legacy), toWorld.resolve(legacy));
        }
        return new Move(fromWorld.resolve(modern), toWorld.resolve(modern));
    }

    /** Returns true if every applicable move succeeded. */
    private boolean run(boolean dry, CommandSender s) {
        FileConfiguration c = getConfig();
        List<Move> moves;
        try { moves = plan(c); } catch (Exception e) { log(s, "ERROR: " + e.getMessage()); return false; }
        log(s, (dry ? "[DRY-RUN] " : "") + "WorldPorter direction=" + c.getString("direction") + " root=" + root());
        Set<Path> loaded = new HashSet<>();
        for (World w : Bukkit.getWorlds()) loaded.add(w.getWorldFolder().toPath().toAbsolutePath().normalize());
        log(s, "Worlds currently loaded by Spigot (will never be touched): " + loaded);
        int ok = 0, skipped = 0, failed = 0;
        for (Move mv : moves) {
            Path from = mv.from(), to = mv.to();
            log(s, "Step: " + from + "  ->  " + to);
            boolean inUse = loaded.stream().anyMatch(l -> from.startsWith(l) || l.startsWith(from));
            if (inUse) { log(s, "  REFUSED: source is (part of) a world Spigot has loaded. Set level-name to e.g. lobby_empty and restart."); failed++; continue; }
            if (!Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) { log(s, "  SKIP: source does not exist."); skipped++; continue; }
            if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) { log(s, "  REFUSED: target already exists, nothing changed."); failed++; continue; }
            if (to.startsWith(from)) { log(s, "  REFUSED: target is inside source."); failed++; continue; }
            if (dry) { log(s, "  [DRY-RUN] would rename (atomic, same filesystem)."); ok++; continue; }
            try {
                Files.createDirectories(to.getParent());
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
                log(s, "  DONE: renamed."); ok++;
            } catch (AtomicMoveNotSupportedException e) {
                log(s, "  FAILED: atomic rename impossible (different filesystem?). Not copying by design: " + e.getMessage()); failed++;
            } catch (IOException e) {
                log(s, "  FAILED: " + e); failed++;
            }
        }
        log(s, (dry ? "[DRY-RUN] " : "") + "Summary: ok=" + ok + " skipped=" + skipped + " failed/refused=" + failed + ". Nothing was deleted; leftover folders (e.g. world_nether/level.dat) remain as backup.");
        if (!dry && failed == 0 && ok > 0) {
            try { Files.createDirectories(getDataFolder().toPath()); Files.writeString(marker(), new Date() + "\n"); } catch (IOException ignored) {}
        }
        return failed == 0;
    }

    private void log(CommandSender s, String m) {
        getLogger().info(m);
        if (s != null && !(s instanceof ConsoleCommandSender)) s.sendMessage(m);
    }

    @Override public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("worldporter.admin")) { s.sendMessage("No permission."); return true; }
        if (a.length == 0) return false;
        reloadConfig();
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "run" -> run(getConfig().getBoolean("dryRun", false), s);
            case "dryrun", "plan" -> run(true, s);
            default -> { return false; }
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        return a.length == 1 ? List.of("run", "dryrun") : List.of();
    }
}
