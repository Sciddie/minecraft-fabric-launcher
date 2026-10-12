package de.aichele.childlauncher;

import com.google.gson.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

public final class FabricLauncherPlugin extends JavaPlugin {
    private static final String UA = "aichele/FabricLauncher/1.0 (spigot child launcher)";
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
    private final ExecutorService exec = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "FabricLauncher"); t.setDaemon(true); return t; });
    private final Object lock = new Object();
    private volatile Process process;
    private volatile BufferedWriter stdin;
    private volatile boolean wantRunning = false;
    private volatile boolean shuttingDown = false;
    private volatile long startedAt;
    private volatile int attempts = 0;
    private volatile String lastStatus = "idle";
    final ConsoleBuffer console = new ConsoleBuffer(1000);
    final Set<String> onlinePlayers = ConcurrentHashMap.newKeySet();
    private Dashboard dashboard;
    private Thread shutdownHook;
    private final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "FabricLauncher-Save"); t.setDaemon(true); return t; });
    private final Object stopLock = new Object();
    private static final java.util.regex.Pattern JOIN = java.util.regex.Pattern.compile("\\]: (\\w{1,16}) joined the game");
    private static final java.util.regex.Pattern LEFT = java.util.regex.Pattern.compile("\\]: (\\w{1,16}) left the game");
    private static final java.util.regex.Pattern LIST = java.util.regex.Pattern.compile("There are \\d+ of a max of \\d+ players online:(.*)$");

    // ---- outer (Spigot) stats, sampled on the main thread once per second ----
    volatile double outerTps = 20, outerMspt = 0; volatile int outerChunks, outerEntities, outerWorlds;
    private long lastSampleNs; private final long[] tickNs = new long[20]; private int tickIdx; private long tickStart;
    // ---- child tick stats (from 'tick query') ----
    volatile double childMspt = -1, childTps = -1; volatile long childTickAt; private volatile long tickQueryAt;
    private static final java.util.regex.Pattern AVG_TICK = java.util.regex.Pattern.compile("Average time per tick: ([\\d.,]+) ?ms");
    private static final java.util.regex.Pattern TARGET_RATE = java.util.regex.Pattern.compile("Target tick rate: ([\\d.,]+)");
    private volatile double childTargetRate = 20;

    @Override public void onEnable() {
        saveDefaultConfig();
        if (getConfig().getBoolean("neuterOuter", true)) {
            OuterTuner.patchFiles(this);
            getServer().getScheduler().runTask(this, () -> OuterTuner.applyRuntime(this));
        }
        startSampler();
        makeWritable(getDataFolder().toPath(), true);
        makeWritable(getDataFolder().toPath().resolve("config.yml"), false);
        if (getConfig().getBoolean("dashboard.enabled", true)) {
            try { dashboard = new Dashboard(this); dashboard.start(); }
            catch (Exception e) { getLogger().log(Level.SEVERE, "Dashboard failed to start", e); }
        }
        // JVM shutdown hook: covers SIGTERM / Zap restart where onDisable might not run
        shutdownHook = new Thread(() -> {
            shuttingDown = true; wantRunning = false;
            Process p = process;
            if (p != null && p.isAlive()) {
                System.out.println("[FabricLauncher] JVM shutdown hook: stopping child server...");
                stopProcess(p, getConfig().getLong("shutdownHookTimeoutSeconds", 25), true);
            }
        }, "FabricLauncher-ShutdownHook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        long every = getConfig().getLong("saveAllIntervalMinutes", 5);
        if (every > 0) sched.scheduleAtFixedRate(() -> { if (isRunning()) send("save-all"); }, every, every, TimeUnit.MINUTES);
        if (getConfig().getBoolean("autoStart", true)) startAsync(null);
    }

    @Override public void onDisable() {
        shuttingDown = true; wantRunning = false;
        stopBlocking();
        sched.shutdownNow();
        try { if (shutdownHook != null) Runtime.getRuntime().removeShutdownHook(shutdownHook); } catch (IllegalStateException ignored) { /* JVM already shutting down; hook runs/ran */ }
        if (dashboard != null) dashboard.stop();
        exec.shutdownNow();
    }

    /** chmod rw for group/other (best effort; FTP user usually differs from the server process user). */
    static void makeWritable(Path p, boolean dir) {
        try { Files.setPosixFilePermissions(p, java.nio.file.attribute.PosixFilePermissions.fromString(dir ? "rwxrwxrwx" : "rw-rw-rw-")); } catch (Exception ignored) {}
    }

    void saveConfigWritable() {
        saveConfig();
        makeWritable(getDataFolder().toPath().resolve("config.yml"), false);
    }

    // ---------------- public control API (used by commands + dashboard) ----------------
    boolean running() { return isRunning(); }
    long pid() { Process p = process; return p != null && p.isAlive() ? p.pid() : -1; }
    long uptimeMillis() { return isRunning() ? System.currentTimeMillis() - startedAt : 0; }
    String lastStatus() { return lastStatus; }
    boolean sendCommand(String c) { if (c.startsWith("/")) c = c.substring(1); return send(c); }
    void requestStart() { if (!isRunning()) { attempts = 0; startAsync(null); } }
    void requestStop() { wantRunning = false; exec.submit(this::stopBlocking); }
    void requestRestart() { exec.submit(() -> { wantRunning = false; stopBlocking(); attempts = 0; reloadConfig(); startAsync(null); }); }
    void info(String m) { getLogger().info(m); console.add("[FabricLauncher] " + m); }

    Path workDir() {
        String d = getConfig().getString("workingDir", "./child-server");
        return Paths.get(d).toAbsolutePath().normalize();
    }

    // ---------------- lifecycle ----------------
    private void startAsync(CommandSender s) {
        wantRunning = true;
        exec.submit(() -> {
            try { launch(); msg(s, "Child server started."); }
            catch (Exception e) { lastStatus = "start failed: " + e.getMessage(); getLogger().log(Level.SEVERE, "Failed to start child", e); msg(s, "Start failed: " + e.getMessage()); scheduleRestart(); }
        });
    }

    private void launch() throws Exception {
        synchronized (lock) {
            if (isRunning()) throw new IllegalStateException("already running");
            FileConfiguration c = getConfig();
            Path wd = workDir();
            boolean fresh = !Files.exists(wd);
            Files.createDirectories(wd);
            if (fresh) copyPlayerFiles(wd);
            lastStatus = "preparing";
            Path jar = prepareFiles(c, wd);
            Files.writeString(wd.resolve("eula.txt"), "eula=true\n");
            writeProperties(c, wd);
            String javaBin = Paths.get(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
            List<String> cmd = new ArrayList<>();
            cmd.add(javaBin);
            cmd.add("-XX:+IgnoreUnrecognizedVMOptions");
            cmd.add("-Xms" + c.getString("memory.xms", "9G"));
            cmd.add("-Xmx" + c.getString("memory.xmx", "9G"));
            for (String fl : c.getStringList("jvmFlags")) { if (!fl.contains("G1RSetUpdatingPauseIntervalMillis")) cmd.add(fl); }
            cmd.add("-jar"); cmd.add(jar.toString()); cmd.add("nogui");
            cmd.addAll(c.getStringList("serverArgs"));
            getLogger().info("Launching child: " + String.join(" ", cmd));
            Process p = new ProcessBuilder(cmd).directory(wd.toFile()).redirectErrorStream(true).start();
            process = p; startedAt = System.currentTimeMillis(); onlinePlayers.clear();
            console.add("[FabricLauncher] Child started, pid " + p.pid());
            stdin = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
            lastStatus = "running";
            String prefix = c.getString("logPrefix", "[Child] ");
            exec.submit(() -> pipe(p, prefix));
            exec.submit(() -> watch(p));
        }
    }

    private void pipe(Process p, String prefix) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (parseTick(line)) continue;
                getLogger().info(prefix + line);
                console.add(line);
                trackPlayers(line);
            }
        } catch (IOException ignored) {}
    }

    private void startSampler() {
        lastSampleNs = System.nanoTime();
        // 1-tick task: measures tick-to-tick interval (MSPT approx. = busy part is not observable via API; we report interval)
        getServer().getScheduler().runTaskTimer(this, () -> {
            long now = System.nanoTime();
            if (tickStart != 0) { tickNs[tickIdx++ % tickNs.length] = now - tickStart; }
            tickStart = now;
            if (tickIdx % 20 == 0 && tickIdx > 0) {
                long sum = 0; for (long t : tickNs) sum += t;
                double avgMs = sum / (double) tickNs.length / 1e6;
                outerMspt = Math.round(avgMs * 100) / 100.0;
                outerTps = Math.round(Math.min(20, 1000 / Math.max(avgMs, 0.001)) * 100) / 100.0;
                int ch = 0, en = 0, ws = 0;
                for (org.bukkit.World w : getServer().getWorlds()) { ws++; ch += w.getLoadedChunks().length; en += w.getEntities().size(); }
                outerChunks = ch; outerEntities = en; outerWorlds = ws;
            }
        }, 1L, 1L);
    }

    /** Sends 'tick query' to the child at most every 10 s (called from dashboard status polling). */
    void maybeQueryChildTick() {
        long now = System.currentTimeMillis();
        if (!isRunning() || now - tickQueryAt < 10_000 || now - startedAt < 30_000) return;
        tickQueryAt = now;
        send("tick query");
    }

    /** Returns true if the line belongs to a tick-query answer (hidden from console/log). */
    private boolean parseTick(String line) {
        if (System.currentTimeMillis() - tickQueryAt > 3000) return false;
        var m = TARGET_RATE.matcher(line);
        if (m.find()) { try { childTargetRate = Double.parseDouble(m.group(1).replace(',', '.')); } catch (Exception ignored) {} return true; }
        m = AVG_TICK.matcher(line);
        if (m.find()) {
            try {
                double ms = Double.parseDouble(m.group(1).replace(',', '.'));
                childMspt = ms; childTps = Math.round(Math.min(childTargetRate, 1000 / Math.max(ms, 0.001)) * 100) / 100.0; childTickAt = System.currentTimeMillis();
            } catch (Exception ignored) {}
            return true;
        }
        return line.contains("The game is running normally") || line.contains("Percentiles") || line.matches(".*\\]: ?P\\d+: .*") || line.contains("Sprinting") || line.contains("game is frozen") || line.contains("tick rate");
    }

    private void trackPlayers(String line) {
        var m = JOIN.matcher(line); if (m.find()) { onlinePlayers.add(m.group(1)); return; }
        m = LEFT.matcher(line); if (m.find()) { onlinePlayers.remove(m.group(1)); return; }
        m = LIST.matcher(line);
        if (m.find()) {
            onlinePlayers.clear();
            for (String n : m.group(1).split(",")) { n = n.trim(); if (!n.isEmpty()) onlinePlayers.add(n); }
        }
    }

    private void watch(Process p) {
        try {
            int code = p.waitFor();
            long up = System.currentTimeMillis() - startedAt;
            synchronized (lock) { if (process == p) { process = null; stdin = null; } }
            lastStatus = "exited (code " + code + ")";
            onlinePlayers.clear();
            console.add("[FabricLauncher] Child exited with code " + code);
            getLogger().warning("Child server exited with code " + code + " after " + up / 1000 + "s");
            if (up >= getConfig().getLong("autoRestart.resetAfterSeconds", 600) * 1000L) attempts = 0;
            if (wantRunning && !shuttingDown) scheduleRestart();
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private void scheduleRestart() {
        if (!wantRunning || shuttingDown || !getConfig().getBoolean("autoRestart.enabled", true)) return;
        int max = getConfig().getInt("autoRestart.maxAttempts", 0);
        if (max > 0 && attempts >= max) { getLogger().severe("Max restart attempts reached; giving up. Use /child start."); wantRunning = false; lastStatus += ", gave up"; return; }
        long base = getConfig().getLong("autoRestart.initialDelaySeconds", 5);
        long cap = getConfig().getLong("autoRestart.maxDelaySeconds", 300);
        long delay = Math.min(cap, base * (1L << Math.min(attempts, 20)));
        attempts++;
        lastStatus += ", restarting in " + delay + "s (attempt " + attempts + ")";
        getLogger().warning("Restarting child in " + delay + "s (attempt " + attempts + ")");
        exec.submit(() -> {
            try { Thread.sleep(delay * 1000L); } catch (InterruptedException e) { return; }
            if (!wantRunning || shuttingDown || isRunning()) return;
            try { launch(); } catch (Exception e) { getLogger().log(Level.SEVERE, "Restart failed", e); scheduleRestart(); }
        });
    }

    private boolean isRunning() { Process p = process; return p != null && p.isAlive(); }

    private boolean send(String command) {
        BufferedWriter w = stdin;
        if (w == null || !isRunning()) return false;
        try { synchronized (w) { w.write(command); w.newLine(); w.flush(); } return true; }
        catch (IOException e) { return false; }
    }

    /** Sends "stop", waits stopTimeoutSeconds, then kills. Blocks. */
    private void stopBlocking() {
        Process p = process;
        if (p == null || !p.isAlive()) return;
        stopProcess(p, getConfig().getLong("stopTimeoutSeconds", 60), false);
    }

    /** Thread-safe (onDisable + shutdown hook may race). useStdout: logger may be gone during JVM shutdown. */
    private void stopProcess(Process p, long timeout, boolean useStdout) {
        synchronized (stopLock) {
            if (!p.isAlive()) return;
            java.util.function.Consumer<String> log = m -> { if (useStdout) System.out.println("[FabricLauncher] " + m); else info(m); };
            log.accept("Stopping child server (pid " + p.pid() + ", waiting up to " + timeout + "s for clean save)...");
            BufferedWriter w = stdin;
            try { if (w != null) synchronized (w) { w.write("stop"); w.newLine(); w.flush(); } } catch (IOException e) { log.accept("Could not send 'stop': " + e.getMessage()); }
            try {
                if (p.waitFor(timeout, TimeUnit.SECONDS)) {
                    log.accept("Child stopped cleanly (exit code " + p.exitValue() + ").");
                } else {
                    log.accept("Child did not stop within " + timeout + "s - KILLING (unsaved changes since last save-all may be lost).");
                    p.descendants().forEach(ProcessHandle::destroy);
                    p.destroy();
                    if (!p.waitFor(10, TimeUnit.SECONDS)) { p.descendants().forEach(ProcessHandle::destroyForcibly); p.destroyForcibly(); p.waitFor(10, TimeUnit.SECONDS); }
                    log.accept("Child killed.");
                }
            } catch (InterruptedException e) {
                p.destroyForcibly(); log.accept("Child killed (interrupted)."); Thread.currentThread().interrupt();
            }
        }
    }

    // ---------------- commands ----------------
    @Override public boolean onCommand(CommandSender s, Command command, String label, String[] a) {
        if (!s.hasPermission("fabriclauncher.admin")) { s.sendMessage("No permission."); return true; }
        if (a.length == 0) return false;
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "start" -> { if (isRunning()) s.sendMessage("Already running."); else { attempts = 0; s.sendMessage("Starting child (downloads may take a while)..."); startAsync(s); } }
            case "stop" -> { wantRunning = false; s.sendMessage("Stopping child..."); exec.submit(() -> { stopBlocking(); msg(s, "Child stopped."); }); }
            case "restart" -> { s.sendMessage("Restarting child..."); exec.submit(() -> { wantRunning = false; stopBlocking(); attempts = 0; reloadConfig(); startAsync(s); }); }
            case "status" -> {
                Process p = process;
                s.sendMessage("Child: " + (isRunning() ? "RUNNING pid " + p.pid() + ", up " + (System.currentTimeMillis() - startedAt) / 1000 + "s" : "NOT RUNNING") + " | " + lastStatus);
                s.sendMessage("Type " + getConfig().getString("type") + " " + getConfig().getString("mcVersion") + " on port " + getConfig().getInt("port") + ", dir " + workDir());
            }
            case "cmd" -> {
                if (a.length < 2) { s.sendMessage("/child cmd <command>"); return true; }
                String c = String.join(" ", Arrays.copyOfRange(a, 1, a.length));
                s.sendMessage(sendCommand(c) ? "Sent: " + c : "Child not running.");
            }
            case "config" -> {
                if (a.length < 4 || !a[1].equalsIgnoreCase("set")) { s.sendMessage("/child config set <key.path> <value>  (true/false/number/text; lists: a,b,c with [list])"); return true; }
                String val = String.join(" ", Arrays.copyOfRange(a, 3, a.length));
                getConfig().set(a[2], parseValue(val));
                saveConfigWritable();
                s.sendMessage("config.yml: " + a[2] + " = " + getConfig().get(a[2]) + "  (applies on /child restart)");
            }
            case "prop" -> {
                if (a.length < 3 || !a[1].equalsIgnoreCase("set")) { s.sendMessage("/child prop set <key> <value>"); return true; }
                String val = a.length > 3 ? String.join(" ", Arrays.copyOfRange(a, 3, a.length)) : "";
                try {
                    setChildProperty(a[2], val);
                    s.sendMessage("child server.properties: " + a[2] + "=" + val + "  (applies on /child restart)");
                } catch (IOException e) { s.sendMessage("Failed: " + e.getMessage()); }
            }
            default -> { return false; }
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 2 && (a[0].equalsIgnoreCase("config") || a[0].equalsIgnoreCase("prop"))) return List.of("set");
        if (a.length == 1) return List.of("start", "stop", "restart", "status", "cmd", "config", "prop").stream().filter(x -> x.startsWith(a[0].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }

    private void msg(CommandSender s, String m) {
        if (s == null || shuttingDown) return;
        getServer().getScheduler().runTask(this, () -> s.sendMessage(m));
    }

    // ---------------- files ----------------
    private Path prepareFiles(FileConfiguration c, Path wd) throws Exception {
        String type = c.getString("type", "fabric").toLowerCase(Locale.ROOT);
        String mc = c.getString("mcVersion", "26.3");
        switch (type) {
            case "fabric": {
                Path jar = wd.resolve("fabric-server-launch-" + mc + ".jar");
                if (!Files.exists(jar)) {
                    String loader = c.getString("fabricLoaderVersion", "latest");
                    String inst = c.getString("fabricInstallerVersion", "latest");
                    if ("latest".equalsIgnoreCase(loader)) loader = firstStable(getJson("https://meta.fabricmc.net/v2/versions/loader/" + mc).getAsJsonArray(), true);
                    if ("latest".equalsIgnoreCase(inst)) inst = firstStable(getJson("https://meta.fabricmc.net/v2/versions/installer").getAsJsonArray(), false);
                    download("https://meta.fabricmc.net/v2/versions/loader/" + mc + "/" + loader + "/" + inst + "/server/jar", jar);
                }
                downloadMods(c, wd.resolve("mods"), mc);
                return jar;
            }
            case "paper": {
                Path jar = wd.resolve("paper-" + mc + ".jar");
                if (!Files.exists(jar)) {
                    JsonArray builds = getJson("https://fill.papermc.io/v3/projects/paper/versions/" + mc + "/builds").getAsJsonArray();
                    if (builds.isEmpty()) throw new IOException("No Paper builds for " + mc);
                    JsonObject pick = null;
                    for (JsonElement e : builds) { JsonObject b = e.getAsJsonObject(); if ("STABLE".equalsIgnoreCase(str(b, "channel"))) { pick = b; break; } }
                    if (pick == null) { pick = builds.get(0).getAsJsonObject(); getLogger().warning("No STABLE Paper build for " + mc + ", using build " + pick.get("id")); }
                    download(pick.getAsJsonObject("downloads").getAsJsonObject("server:default").get("url").getAsString(), jar);
                }
                return jar;
            }
            case "vanilla": {
                Path jar = wd.resolve("minecraft_server-" + mc + ".jar");
                if (!Files.exists(jar)) {
                    JsonObject man = getJson("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json").getAsJsonObject();
                    String url = null;
                    for (JsonElement e : man.getAsJsonArray("versions")) if (mc.equals(str(e.getAsJsonObject(), "id"))) url = str(e.getAsJsonObject(), "url");
                    if (url == null) throw new IOException("Version " + mc + " not in Mojang manifest");
                    JsonObject v = getJson(url).getAsJsonObject();
                    download(v.getAsJsonObject("downloads").getAsJsonObject("server").get("url").getAsString(), jar);
                }
                return jar;
            }
            case "custom": {
                Path jar = Paths.get(c.getString("customJar", "server.jar"));
                if (!jar.isAbsolute()) jar = wd.resolve(jar);
                if (!Files.exists(jar)) throw new FileNotFoundException("Custom jar not found: " + jar);
                return jar;
            }
            default: throw new IllegalArgumentException("Unknown type: " + type);
        }
    }

    private void downloadMods(FileConfiguration c, Path modsDir, String mc) throws Exception {
        Files.createDirectories(modsDir);
        ConfigurationSection sec = c.getConfigurationSection("mods");
        if (sec == null) return;
        for (String slug : sec.getKeys(false)) {
            if (!sec.getBoolean(slug)) continue;
            try (var st = Files.list(modsDir)) {
                if (st.anyMatch(f -> f.getFileName().toString().startsWith(".fl-" + slug + "-"))) continue; // marker exists
            }
            String q = "?loaders=" + enc("[\"fabric\"]") + "&game_versions=" + enc("[\"" + mc + "\"]");
            JsonArray vers = getJson("https://api.modrinth.com/v2/project/" + slug + "/version" + q).getAsJsonArray();
            if (vers.isEmpty()) { getLogger().warning("Modrinth: no " + slug + " build for fabric " + mc + " - skipped"); continue; }
            JsonObject v = vers.get(0).getAsJsonObject();
            for (JsonElement e : vers) { if ("release".equals(str(e.getAsJsonObject(), "version_type"))) { v = e.getAsJsonObject(); break; } }
            JsonObject file = null;
            for (JsonElement f : v.getAsJsonArray("files")) { if (f.getAsJsonObject().get("primary").getAsBoolean()) file = f.getAsJsonObject(); }
            if (file == null) file = v.getAsJsonArray("files").get(0).getAsJsonObject();
            String name = str(file, "filename");
            download(str(file, "url"), modsDir.resolve(name));
            Files.writeString(modsDir.resolve(".fl-" + slug + "-" + name + ".marker"), str(v, "version_number"));
            getLogger().info("Installed mod " + slug + " -> " + name);
        }
    }

    private static String firstStable(JsonArray arr, boolean loaderWrapped) {
        for (JsonElement e : arr) {
            JsonObject o = e.getAsJsonObject();
            if (loaderWrapped) o = o.getAsJsonObject("loader");
            if (o.get("stable").getAsBoolean()) return o.get("version").getAsString();
        }
        JsonObject o = arr.get(0).getAsJsonObject();
        return (loaderWrapped ? o.getAsJsonObject("loader") : o).get("version").getAsString();
    }

    private void copyPlayerFiles(Path wd) {
        Path root = Paths.get("").toAbsolutePath();
        for (String n : List.of("ops.json", "whitelist.json", "banned-players.json", "banned-ips.json", "usercache.json")) {
            Path src = root.resolve(n), dst = wd.resolve(n);
            try {
                if (Files.exists(src) && !Files.exists(dst)) { Files.copy(src, dst); getLogger().info("Copied " + n + " into child dir"); }
            } catch (IOException e) { getLogger().warning("Could not copy " + n + ": " + e); }
        }
    }

    static Object parseValue(String v) {
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) return Boolean.parseBoolean(v);
        if (v.matches("-?\\d{1,9}")) return Integer.parseInt(v);
        if (v.matches("-?\\d+\\.\\d+")) return Double.parseDouble(v);
        if (v.startsWith("[list]")) { List<String> l = new ArrayList<>(); for (String x : v.substring(6).split(",")) if (!x.isBlank()) l.add(x.trim()); return l; }
        return v;
    }

    /** Sets one key in the child server.properties and, if the key is managed via config serverProperties, there too. */
    void setChildProperty(String key, String value) throws IOException {
        Path wd = workDir(); Files.createDirectories(wd);
        updateProperties(wd.resolve("server.properties"), Map.of(key, value), Map.of());
        if (getConfig().isSet("serverProperties." + key)) { getConfig().set("serverProperties." + key, value); saveConfigWritable(); }
        if (key.equals("server-port")) { getConfig().set("port", Integer.parseInt(value)); saveConfigWritable(); }
        if (key.equals("server-ip")) { getConfig().set("serverIp", value); saveConfigWritable(); }
    }

    /** Line-based edit: keeps comments, order and all other keys. force = always set, defaults = only if absent. */
    static void updateProperties(Path f, Map<String, String> force, Map<String, String> defaults) throws IOException {
        List<String> lines = Files.exists(f) ? new ArrayList<>(Files.readAllLines(f, StandardCharsets.UTF_8)) : new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i), t = l.trim();
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

    private void writeProperties(FileConfiguration c, Path wd) throws IOException {
        Map<String, String> force = new LinkedHashMap<>();
        ConfigurationSection extra = c.getConfigurationSection("serverProperties");
        if (extra != null) for (String k : extra.getKeys(false)) force.put(k, String.valueOf(extra.get(k)));
        force.put("server-port", String.valueOf(c.getInt("port", 2063)));
        force.put("server-ip", c.getString("serverIp", ""));
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("level-name", "world");
        defaults.put("white-list", "false");
        defaults.put("enforce-whitelist", "false");
        defaults.put("enable-rcon", "false");
        defaults.put("enable-query", "false");
        updateProperties(wd.resolve("server.properties"), force, defaults);
    }

    private JsonElement getJson(String url) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", UA).timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode() + " for " + url);
        return JsonParser.parseString(r.body());
    }

    private void download(String url, Path target) throws Exception {
        getLogger().info("Downloading " + url);
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        HttpResponse<Path> r = http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", UA).timeout(Duration.ofMinutes(10)).GET().build(), HttpResponse.BodyHandlers.ofFile(tmp));
        if (r.statusCode() / 100 != 2) { Files.deleteIfExists(tmp); throw new IOException("HTTP " + r.statusCode() + " for " + url); }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String str(JsonObject o, String k) { JsonElement e = o.get(k); return e == null || e.isJsonNull() ? null : e.getAsString(); }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static boolean isWindows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"); }
}
