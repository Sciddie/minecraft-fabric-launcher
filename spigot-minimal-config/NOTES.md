# Minimal outer Spigot (host for FabricLauncher)

The outer Spigot only exists so Zap-Hosting starts a JVM; the real game runs as the child
(Fabric on port 2063). Goal: outer server as small as possible.

1. Memory: package has 12G total. Outer Spigot needs ~1-1.5G (set Zap's RAM/Xmx for Spigot low if
   the panel allows). Child default Xms/Xmx = 8G. Keep ~1-1.5G headroom for JVM overhead/native
   memory (metaspace, threads, direct buffers) of BOTH processes, else the container OOM-kills.
   If Zap forces Spigot Xmx to ~12G, lower the child to ~7G. (AlwaysPreTouch is not used.)
2. Merge server.properties / bukkit.yml / spigot.yml keys from this folder. Do NOT change the
   outer server-port. The void flat world + generate-structures=false keeps the world tiny;
   delete existing world folders before first start to regenerate as flat void.
3. white-list=true + max-players=1: nobody should play on the outer server; players connect to
   <host>:2063. Port 2064 stays free (second instance/query).
4. The child's console appears in the Spigot console prefixed "[Child] "; use
   `child cmd <command>` from the Zap console (e.g. `child cmd op Name`, `child cmd save-all`).
5. Always stop the server via the panel/`stop` (not "kill"), so the plugin can stop the child
   cleanly (up to 60s). Check Zap's stop timeout is >60s if configurable.
6. Optional: remove unneeded plugins; set `settings.timings`/bstats off in plugins/bStats/config.yml.
7. World move plan: set level-name=lobby_empty in the outer server.properties FIRST, then install
   WorldPorter + FabricLauncher. WorldPorter (enabled before FabricLauncher via softdepend) moves
   ./world -> ./child-server/world, world_nether/dimensions/minecraft/the_nether and
   world_the_end/dimensions/minecraft/the_end -> child-server/world/dimensions/minecraft/ (26.1+ layout;
   legacy DIM-1/DIM1 only as fallback)
   by atomic rename, never deletes, refuses if a target exists. Leftover world_nether/world_the_end
   folders (level.dat, data) stay in place as backup. Back up the worlds before the first run.
