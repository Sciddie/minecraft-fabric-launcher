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
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
    private Upstream upstream;
    private final ExecutorService exec = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "FabricLauncher"); t.setDaemon(true); return t; });
    private final Map<String, ChildSlot> slots = new ConcurrentHashMap<>();
    private int consoleCap = 4000;
    private volatile List<ServerProfile> profiles = List.of();
    private volatile String focus = "default";
    private volatile boolean allowParallel;
    private final Map<String, String> taskFired = new ConcurrentHashMap<>();
    volatile String lastBackup = "";
    private final Map<String, ProcessProbe.Disk> diskCaches = new ConcurrentHashMap<>();
    private final Map<String, Long> diskCacheAt = new ConcurrentHashMap<>();
    private final Map<String, List<String>> versionCache = new ConcurrentHashMap<>();
    private final Map<String, Long> versionCacheAt = new ConcurrentHashMap<>();
    private volatile boolean shuttingDown = false;
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
    @Override public void onEnable() {
        saveDefaultConfig();
        consoleCap = Math.max(200, Math.min(getConfig().getInt("consoleBufferLines", 4000), 20_000));
        upstream = new Upstream(http, "aichele/FabricLauncher/" + getDescription().getVersion() + " (spigot child launcher)", m -> getLogger().info(m));
        reloadProfiles();
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
            shuttingDown = true;
            for (ChildSlot s : slots.values()) s.wantRunning = false;
            for (ChildSlot s : List.copyOf(slots.values())) if (s.running()) {
                System.out.println("[FabricLauncher] JVM shutdown hook: stopping " + s.id);
                stopProcess(s, getConfig().getLong("shutdownHookTimeoutSeconds", 25), true);
            }
        }, "FabricLauncher-ShutdownHook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        long every = getConfig().getLong("saveAllIntervalMinutes", 5);
        if (every > 0) sched.scheduleAtFixedRate(() -> { for (ChildSlot s : slots.values()) if (s.running()) send(s, "save-all"); }, every, every, TimeUnit.MINUTES);
        sched.scheduleAtFixedRate(this::fireDueTasks, 20, 20, TimeUnit.SECONDS);
        for (ServerProfile p : profiles) if (p.autoStart && (allowParallel || p.id.equals(focus))) startAsync(null, p.id);
    }

    @Override public void onDisable() {
        shuttingDown = true;
        for (ChildSlot s : slots.values()) s.wantRunning = false;
        for (ChildSlot s : List.copyOf(slots.values())) stopBlocking(s);
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
    void reloadProfiles() {
        Map<String, Object> flat = getConfig().getValues(true);
        profiles = Profiles.load(flat);
        focus = Profiles.focus(flat, profiles);
        Object ap = flat.get("allowParallel");
        allowParallel = ap instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(ap));
        for (ServerProfile p : profiles) slot(p.id).profile = p;
    }

    String focusId() { return focus; }
    List<ServerProfile> profiles() { return profiles; }
    boolean allowParallel() { return allowParallel; }

    ServerProfile profile(String id) {
        String key = id == null || id.isBlank() ? focus : id;
        for (ServerProfile p : profiles) if (p.id.equals(key)) return p;
        throw new IllegalArgumentException("unknown server " + key);
    }

    ChildSlot slot(String id) {
        String key = id == null || id.isBlank() ? focus : id;
        profile(key);
        return slots.computeIfAbsent(key, k -> new ChildSlot(k, consoleCap));
    }

    private ChildSlot focusSlot() { return slot(focus); }
    boolean running() { return focusSlot().running(); }
    boolean running(String id) { return slot(id).running(); }
    long pid() { return focusSlot().pid(); }
    long pid(String id) { return slot(id).pid(); }
    long uptimeMillis() { return focusSlot().uptimeMillis(); }
    long uptimeMillis(String id) { return slot(id).uptimeMillis(); }
    String lastStatus() { return focusSlot().lastStatus; }
    String lastStatus(String id) { return slot(id).lastStatus; }
    ConsoleBuffer console() { return focusSlot().console; }
    ConsoleBuffer console(String id) { return slot(id).console; }
    Set<String> players() { return focusSlot().onlinePlayers; }
    Set<String> players(String id) { return slot(id).onlinePlayers; }
    boolean sendCommand(String c) { return sendCommand(focus, c); }
    boolean sendCommand(String id, String c) { if (c.startsWith("/")) c = c.substring(1); return send(slot(id), c); }
    void requestStart() { requestStart(focus); }
    void requestStop() { requestStop(focus); }
    void requestRestart() { requestRestart(focus); }
    void requestStart(String id) { startAsync(null, id); }
    void requestStop(String id) { ChildSlot s = slot(id); s.wantRunning = false; exec.submit(() -> stopBlocking(s)); }
    void requestRestart(String id) { exec.submit(() -> { ChildSlot s = slot(id); s.wantRunning = false; stopBlocking(s); s.attempts = 0; reloadConfig(); reloadProfiles(); startAsync(null, id); }); }
    void focus(String id) {
        profile(id);
        focus = id;
        getConfig().set("focusServer", id);
        saveConfigWritable();
    }
    void info(String m) { info(focus, m); }
    void info(String id, String m) { getLogger().info(m); slot(id).console.add("[FabricLauncher] " + m); }

    /** Writes managed server.properties keys back into config so the next start keeps the edit. */
    boolean syncPropertiesEdit(String id, java.util.Properties p) {
        ServerProfile pr = profile(id);
        String prefix = configPrefix(pr);
        boolean changed = false;
        for (String k : p.stringPropertyNames()) {
            String cur = pr.serverProperties.get(k);
            if (cur != null && !p.getProperty(k).equals(cur)) {
                getConfig().set(prefix + "serverProperties." + k, parseValue(p.getProperty(k)));
                changed = true;
            }
        }
        String port = p.getProperty("server-port");
        if (port != null && !port.trim().equals(String.valueOf(pr.port))) {
            try { getConfig().set(prefix + "port", Integer.parseInt(port.trim())); changed = true; } catch (NumberFormatException ignored) {}
        }
        String ip = p.getProperty("server-ip");
        if (ip != null && !ip.equals(pr.serverIp == null ? "" : pr.serverIp)) {
            getConfig().set(prefix + "serverIp", ip);
            changed = true;
        }
        if (changed) { saveConfigWritable(); reloadProfiles(); }
        return changed;
    }

    private String configPrefix(ServerProfile pr) {
        return profiles.isEmpty() || profiles.get(0).id.equals(pr.id) ? "" : "servers." + pr.id + ".";
    }

    Path workDir() { return profile(focus).directory(); }
    Path workDir(String id) { return profile(id).directory(); }
    List<Path> workDirs() { List<Path> out = new ArrayList<>(); for (ServerProfile p : profiles) out.add(p.directory()); return out; }

    Upstream upstream() { return upstream; }
    boolean dashboardTls() { return dashboard != null && dashboard.tls(); }

    List<Double> msptHistory() { return focusSlot().msptHistory(); }
    List<Double> msptHistory(String id) { return slot(id).msptHistory(); }

    ProcessProbe.Disk diskCached(String id) {
        long now = System.currentTimeMillis();
        Long at = diskCacheAt.get(id);
        ProcessProbe.Disk cached = diskCaches.get(id);
        if (cached != null && at != null && now - at < 60_000) return cached;
        ProcessProbe.Disk fresh = ProcessProbe.disk(workDir(id), 100_000);
        diskCaches.put(id, fresh); diskCacheAt.put(id, now);
        return fresh;
    }

    Map<String, Object> diagMap() { return diagMap(focus); }
    Map<String, Object> diagMap(String id) {
        ChildSlot s = slot(id);
        Map<String, Object> m = new LinkedHashMap<>();
        long pid = s.pid();
        m.put("server", s.id);
        m.put("running", s.running());
        m.put("pid", pid);
        m.put("tls", dashboardTls());
        m.put("status", s.lastStatus);
        m.put("mspt", s.mspt);
        m.put("tps", s.tps);
        if (pid > 0) m.putAll(ProcessProbe.snapshot(pid));
        ProcessProbe.Disk d = diskCached(s.id);
        m.put("diskBytes", d.bytes());
        m.put("diskFiles", d.files());
        m.put("diskPartial", d.partial());
        return m;
    }

    String jcmd(String command) throws IOException { return jcmd(focus, command); }
    String jcmd(String id, String command) throws IOException { return ProcessProbe.jcmd(slot(id).pid(), command); }

    List<String> versions(String type) throws Exception {
        long now = System.currentTimeMillis();
        Long at = versionCacheAt.get(type);
        if (at != null && now - at < 600_000 && versionCache.containsKey(type)) return versionCache.get(type);
        List<String> list = VersionCatalog.fetch(upstream, type, 40);
        versionCache.put(type, list); versionCacheAt.put(type, now);
        return list;
    }

    List<TaskDef> tasks() {
        List<?> raw = getConfig().getList("tasks");
        if (raw == null) return List.of();
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object o : raw) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> c = new LinkedHashMap<>();
                m.forEach((k, v) -> c.put(String.valueOf(k), v));
                maps.add(c);
            } else if (o instanceof ConfigurationSection s) maps.add(s.getValues(false));
        }
        return TaskDef.load(maps);
    }

    void runTask(TaskDef task) {
        String id = task.server.isBlank() ? focus : task.server;
        try {
            switch (task.action) {
                case "start" -> { startAsync(null, id); }
                case "stop" -> { ChildSlot s = slot(id); s.wantRunning = false; stopBlocking(s); }
                case "restart" -> requestRestart(id);
                case "backup" -> runBackup(id);
                case "command", "save-all" -> {
                    String cmd = task.action.equals("save-all") || task.command.isBlank() ? "save-all" : task.command;
                    boolean ok = send(slot(id), cmd.startsWith("/") ? cmd.substring(1) : cmd);
                    slot(id).console.add("[FabricLauncher] task " + task.id + (ok ? " sent " : " skipped ") + cmd);
                }
                default -> getLogger().warning("Unknown task action " + task.action);
            }
        } catch (Exception e) { getLogger().log(Level.WARNING, "Task " + task.id + " failed", e); }
    }

    String runBackup(String id) throws Exception {
        ChildSlot s = slot(id);
        if (s.running()) {
            send(s, "save-all");
            Thread.sleep(Math.max(0, getConfig().getInt("backup.saveWaitSeconds", 3)) * 1000L);
        }
        BackupBridge.Result r = BackupBridge.archive(profile(id).directory(), backupSpec(), id);
        lastBackup = r.path() + " sha256=" + r.sha256() + (r.db().isBlank() ? "" : " db=" + r.db());
        s.console.add("[FabricLauncher] backup " + lastBackup);
        getLogger().info("Backup " + lastBackup);
        return lastBackup;
    }

    void testBackupDb() throws Exception { BackupBridge.test(backupSpec()); }

    private BackupBridge.Spec backupSpec() {
        FileConfiguration c = getConfig();
        String dir = c.getString("backup.directory", "");
        Path path = dir == null || dir.isBlank() ? getDataFolder().toPath().resolve("backups") : Paths.get(dir);
        if (!path.isAbsolute()) path = Paths.get("").toAbsolutePath().resolve(path);
        return new BackupBridge.Spec(path.toString(), c.getStringList("backup.paths"), c.getInt("backup.keep", 5),
                c.getBoolean("backup.database.enabled"), c.getString("backup.database.jdbcUrl", ""),
                c.getString("backup.database.user", ""), c.getString("backup.database.password", ""),
                c.getString("backup.database.driverJar", ""), c.getString("backup.database.driverClass", ""),
                c.getString("backup.database.table", "fabriclauncher_backups"));
    }

    private void fireDueTasks() {
        if (shuttingDown) return;
        for (TaskDef task : tasks()) {
            if (!task.enabled || task.cron.isBlank()) continue;
            java.time.ZoneId zone;
            try { zone = java.time.ZoneId.of(task.timezone); }
            catch (Exception e) { getLogger().warning("Task " + task.id + " timezone " + task.timezone); continue; }
            java.time.ZonedDateTime local = java.time.ZonedDateTime.now(zone);
            boolean due;
            try { due = CronExpr.matches(task.cron, local); }
            catch (RuntimeException e) { getLogger().warning("Task " + task.id + " cron: " + e.getMessage()); continue; }
            if (!due) continue;
            String mark = task.id + " " + local.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
            if (taskFired.putIfAbsent(mark, "1") != null) continue;
            if (taskFired.size() > 500) taskFired.keySet().removeIf(k -> !k.endsWith(mark.substring(mark.length() - 12)));
            exec.submit(() -> runTask(task));
        }
    }

    // ---------------- lifecycle ----------------
    private void startAsync(CommandSender s, String id) {
        exec.submit(() -> {
            try {
                if (!allowParallel) for (ChildSlot other : List.copyOf(slots.values())) if (!other.id.equals(id) && other.running()) {
                    other.wantRunning = false;
                    stopBlocking(other);
                }
                ChildSlot slot = slot(id);
                if (slot.running()) { msg(s, "Already running."); return; }
                slot.wantRunning = true;
                launch(profile(id), slot);
                msg(s, "Child server started.");
            } catch (Exception e) {
                ChildSlot slot = slot(id);
                slot.lastStatus = "start failed: " + e.getMessage();
                getLogger().log(Level.SEVERE, "Failed to start child " + id, e);
                msg(s, "Start failed: " + e.getMessage());
                scheduleRestart(slot);
            }
        });
    }

    private void launch(ServerProfile profile, ChildSlot slot) throws Exception {
        synchronized (slot.lock) {
            if (slot.running()) throw new IllegalStateException("already running");
            slot.profile = profile;
            Path wd = profile.directory();
            boolean fresh = !Files.exists(wd);
            Files.createDirectories(wd);
            if (fresh) copyPlayerFiles(wd);
            slot.lastStatus = "preparing";
            Path jar = prepareFiles(profile, wd);
            Files.writeString(wd.resolve("eula.txt"), "eula=true\n");
            writeProperties(profile, wd);
            String javaBin = Paths.get(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
            List<String> cmd = new ArrayList<>();
            cmd.add(javaBin);
            cmd.add("-XX:+IgnoreUnrecognizedVMOptions");
            cmd.add("-Xms" + profile.xms);
            cmd.add("-Xmx" + profile.xmx);
            for (String fl : profile.jvmFlags) { if (!fl.contains("G1RSetUpdatingPauseIntervalMillis")) cmd.add(fl); }
            cmd.add("-jar"); cmd.add(jar.toString()); cmd.add("nogui");
            cmd.addAll(profile.serverArgs);
            getLogger().info("Launching " + profile.id + ": " + String.join(" ", cmd));
            Process p = new ProcessBuilder(cmd).directory(wd.toFile()).redirectErrorStream(true).start();
            slot.process = p; slot.startedAt = System.currentTimeMillis(); slot.onlinePlayers.clear();
            slot.console.add("[FabricLauncher] Child started, pid " + p.pid());
            slot.stdin = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
            slot.lastStatus = "running";
            String basePrefix = getConfig().getString("logPrefix", "[Child] ");
            final String prefix = !"default".equals(profile.id) && profiles.size() > 1 ? "[" + profile.id + "] " : basePrefix;
            exec.submit(() -> pipe(slot, p, prefix));
            exec.submit(() -> watch(slot, p));
        }
    }

    private void pipe(ChildSlot slot, Process p, String prefix) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (parseTick(slot, line)) continue;
                getLogger().info(prefix + line);
                slot.console.add(line);
                trackPlayers(slot, line);
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
    void maybeQueryChildTick(String id) {
        ChildSlot slot = slot(id);
        long now = System.currentTimeMillis();
        if (!slot.running() || now - slot.queryAt < 10_000 || now - slot.startedAt < 30_000) return;
        slot.queryAt = now;
        send(slot, "tick query");
    }

    /** Returns true if the line belongs to a tick-query answer (hidden from console/log). */
    private boolean parseTick(ChildSlot slot, String line) {
        long before = slot.ticks.tickAt;
        boolean hide = slot.ticks.consume(line, slot.queryAt, System.currentTimeMillis());
        if (hide && slot.ticks.tickAt != before) {
            slot.mspt = slot.ticks.mspt;
            slot.tps = slot.ticks.tps;
            slot.tickAt = slot.ticks.tickAt;
            slot.pushMspt(slot.mspt);
        }
        return hide;
    }

    private void trackPlayers(ChildSlot slot, String line) {
        var m = JOIN.matcher(line); if (m.find()) { slot.onlinePlayers.add(m.group(1)); return; }
        m = LEFT.matcher(line); if (m.find()) { slot.onlinePlayers.remove(m.group(1)); return; }
        m = LIST.matcher(line);
        if (m.find()) {
            slot.onlinePlayers.clear();
            for (String n : m.group(1).split(",")) { n = n.trim(); if (!n.isEmpty()) slot.onlinePlayers.add(n); }
        }
    }

    private void watch(ChildSlot slot, Process p) {
        try {
            int code = p.waitFor();
            long up = System.currentTimeMillis() - slot.startedAt;
            synchronized (slot.lock) { if (slot.process == p) { slot.process = null; slot.stdin = null; } }
            slot.lastStatus = "exited (code " + code + ")";
            slot.onlinePlayers.clear();
            slot.console.add("[FabricLauncher] Child exited with code " + code);
            getLogger().warning("Child " + slot.id + " exited with code " + code + " after " + up / 1000 + "s");
            if (up >= getConfig().getLong("autoRestart.resetAfterSeconds", 600) * 1000L) slot.attempts = 0;
            if (slot.wantRunning && !shuttingDown) scheduleRestart(slot);
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private void scheduleRestart(ChildSlot slot) {
        if (!slot.wantRunning || shuttingDown || !getConfig().getBoolean("autoRestart.enabled", true)) return;
        int max = getConfig().getInt("autoRestart.maxAttempts", 0);
        if (max > 0 && slot.attempts >= max) { getLogger().severe("Max restart attempts reached for " + slot.id + "; giving up."); slot.wantRunning = false; slot.lastStatus += ", gave up"; return; }
        long base = getConfig().getLong("autoRestart.initialDelaySeconds", 5);
        long cap = getConfig().getLong("autoRestart.maxDelaySeconds", 300);
        long delay = RestartBackoff.delaySeconds(base, cap, slot.attempts);
        slot.attempts++;
        slot.lastStatus += ", restarting in " + delay + "s (attempt " + slot.attempts + ")";
        getLogger().warning("Restarting " + slot.id + " in " + delay + "s (attempt " + slot.attempts + ")");
        exec.submit(() -> {
            try { Thread.sleep(delay * 1000L); } catch (InterruptedException e) { return; }
            if (!slot.wantRunning || shuttingDown || slot.running()) return;
            try { launch(profile(slot.id), slot); } catch (Exception e) { getLogger().log(Level.SEVERE, "Restart failed", e); scheduleRestart(slot); }
        });
    }

    private boolean send(ChildSlot slot, String command) {
        BufferedWriter w = slot.stdin;
        if (w == null || !slot.running()) return false;
        try { synchronized (w) { w.write(command); w.newLine(); w.flush(); } return true; }
        catch (IOException e) { return false; }
    }

    /** Sends "stop", waits stopTimeoutSeconds, then kills. Blocks. */
    private void stopBlocking(ChildSlot slot) {
        if (!slot.running()) return;
        stopProcess(slot, getConfig().getLong("stopTimeoutSeconds", 60), false);
    }

    /** Thread-safe (onDisable + shutdown hook may race). useStdout: logger may be gone during JVM shutdown. */
    private void stopProcess(ChildSlot slot, long timeout, boolean useStdout) {
        synchronized (slot.stopLock) {
            Process p = slot.process;
            if (p == null || !p.isAlive()) return;
            java.util.function.Consumer<String> log = m -> { if (useStdout) System.out.println("[FabricLauncher] " + m); else { getLogger().info(m); slot.console.add("[FabricLauncher] " + m); } };
            log.accept("Stopping " + slot.id + " (pid " + p.pid() + ", waiting up to " + timeout + "s for clean save)...");
            BufferedWriter w = slot.stdin;
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
            case "start" -> { s.sendMessage("Starting " + focus + " (downloads may take a while)..."); startAsync(s, focus); }
            case "stop" -> { focusSlot().wantRunning = false; s.sendMessage("Stopping " + focus + "..."); ChildSlot cur = focusSlot(); exec.submit(() -> { stopBlocking(cur); msg(s, "Child stopped."); }); }
            case "restart" -> { s.sendMessage("Restarting " + focus + "..."); requestRestart(focus); }
            case "status" -> {
                ChildSlot cur = focusSlot();
                ServerProfile pr = profile(focus);
                s.sendMessage(cur.id + ": " + (cur.running() ? "RUNNING pid " + cur.pid() + ", up " + cur.uptimeMillis() / 1000 + "s" : "NOT RUNNING") + " | " + cur.lastStatus);
                s.sendMessage("Type " + pr.type + " " + pr.mcVersion + " on port " + pr.port + ", dir " + pr.directory());
            }
            case "servers" -> { for (ServerProfile pr : profiles) s.sendMessage((pr.id.equals(focus) ? "* " : "  ") + pr.id + " " + pr.type + " " + pr.mcVersion + " :" + pr.port + (slot(pr.id).running() ? " RUNNING" : "")); }
            case "focus" -> { if (a.length < 2) { s.sendMessage("focus " + focus); return true; } focus(a[1]); s.sendMessage("focus " + focus); }
            case "tasks" -> { for (TaskDef t : tasks()) s.sendMessage((t.enabled ? "on  " : "off ") + t.id + " " + t.cron + " " + t.timezone + " " + t.action); }
            case "task" -> {
                if (a.length < 3 || !a[1].equalsIgnoreCase("run")) { s.sendMessage("/child task run <id>"); return true; }
                TaskDef found = null; for (TaskDef t : tasks()) if (t.id.equals(a[2])) found = t;
                if (found == null) s.sendMessage("Unknown task"); else { TaskDef run = found; exec.submit(() -> runTask(run)); s.sendMessage("Task " + a[2]); }
            }
            case "backup" -> { s.sendMessage("Backup " + focus + "..."); exec.submit(() -> { try { msg(s, runBackup(focus)); } catch (Exception e) { msg(s, "Backup failed: " + e.getMessage()); } }); }
            case "versions" -> {
                if (a.length < 2) { s.sendMessage("/child versions <fabric|paper|vanilla|spigot>"); return true; }
                exec.submit(() -> { try { for (String v : versions(a[1])) msg(s, v); } catch (Exception e) { msg(s, "Versions failed: " + e.getMessage()); } });
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
            case "mods" -> {
                try {
                    var list = ModAdmin.list(workDir());
                    if (list.isEmpty()) s.sendMessage("No mods/ or plugins/ entries.");
                    for (var e : list) s.sendMessage((e.enabled() ? "on  " : "off ") + e.path() + (e.directory() ? "/" : "  " + e.size()));
                } catch (IOException e) { s.sendMessage("Failed: " + e.getMessage()); }
            }
            case "mod" -> {
                if (a.length < 3) { s.sendMessage("/child mod <install|toggle|search> ..."); return true; }
                try { modCommand(s, a); }
                catch (Exception e) { s.sendMessage("Failed: " + e.getMessage()); }
            }
            case "diag" -> {
                Map<String, Object> d = diagMap();
                s.sendMessage("running=" + d.get("running") + " pid=" + d.get("pid") + " tls=" + d.get("tls") + " status=" + d.get("status"));
                s.sendMessage("rssMb=" + d.get("rssMb") + " threads=" + d.get("threads") + " fds=" + d.get("fds") + " mspt=" + d.get("mspt") + " tps=" + d.get("tps"));
                s.sendMessage("diskBytes=" + d.get("diskBytes") + " diskFiles=" + d.get("diskFiles") + " partial=" + d.get("diskPartial"));
            }
            case "threaddump", "heap", "flags" -> {
                String cmd = switch (a[0].toLowerCase(Locale.ROOT)) { case "heap" -> "GC.heap_info"; case "flags" -> "VM.flags"; default -> "Thread.print"; };
                try {
                    String text = jcmd(cmd);
                    String[] lines = text.split("\n");
                    int n = Math.min(lines.length, 40);
                    for (int i = 0; i < n; i++) s.sendMessage(lines[i]);
                    if (lines.length > n) s.sendMessage("... " + (lines.length - n) + " more lines in the dashboard");
                } catch (IOException e) { s.sendMessage("Failed: " + e.getMessage()); }
            }
            default -> { return false; }
        }
        return true;
    }

    private void modCommand(CommandSender s, String[] a) throws Exception {
        switch (a[1].toLowerCase(Locale.ROOT)) {
            case "search" -> {
                if (a.length < 3) { s.sendMessage("/child mod search <text>"); return; }
                String q = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
                ServerProfile pr = profile(focus);
                for (var hit : ModAdmin.search(upstream, pr.type, q))
                    s.sendMessage(hit.get("slug") + "  " + hit.get("title") + "  " + hit.get("downloads"));
            }
            case "install" -> {
                if (a.length < 3) { s.sendMessage("/child mod install <slug> [version]"); return; }
                String ver = a.length > 3 ? a[3] : "";
                ServerProfile pr = profile(focus);
                var r = ModAdmin.install(upstream, pr.directory(), pr.type, pr.mcVersion, a[2], ver);
                s.sendMessage("Installed " + r.get("path") + " " + r.get("version") + " (active after child restart)");
            }
            case "toggle" -> {
                if (a.length < 3) { s.sendMessage("/child mod toggle <mods/file.jar>"); return; }
                PathGuard g = PathGuard.open(workDir());
                String name = Path.of(a[2]).getFileName().toString();
                s.sendMessage("Toggled -> " + ModAdmin.toggle(g, a[2], name) + " (active after child restart)");
            }
            default -> s.sendMessage("/child mod <install|toggle|search> ...");
        }
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 2 && (a[0].equalsIgnoreCase("config") || a[0].equalsIgnoreCase("prop"))) return List.of("set");
        if (a.length == 2 && a[0].equalsIgnoreCase("mod")) return List.of("install", "toggle", "search").stream().filter(x -> x.startsWith(a[1].toLowerCase(Locale.ROOT))).toList();
        if (a.length == 1) return List.of("start", "stop", "restart", "status", "cmd", "config", "prop", "mods", "mod", "diag", "threaddump", "heap", "flags", "servers", "focus", "tasks", "task", "backup", "versions").stream().filter(x -> x.startsWith(a[0].toLowerCase(Locale.ROOT))).toList();
        if (a.length == 2 && a[0].equalsIgnoreCase("focus")) return profiles.stream().map(p -> p.id).filter(x -> x.startsWith(a[1])).toList();
        if (a.length == 2 && a[0].equalsIgnoreCase("versions")) return List.of("fabric", "paper", "vanilla", "spigot").stream().filter(x -> x.startsWith(a[1].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }

    private void msg(CommandSender s, String m) {
        if (s == null || shuttingDown) return;
        getServer().getScheduler().runTask(this, () -> s.sendMessage(m));
    }

    // ---------------- files ----------------
    private Path prepareFiles(ServerProfile c, Path wd) throws Exception {
        String type = c.type.toLowerCase(Locale.ROOT);
        String mc = c.mcVersion;
        switch (type) {
            case "fabric": {
                Path jar = wd.resolve("fabric-server-launch-" + mc + ".jar");
                if (!Files.exists(jar)) {
                    String loader = c.fabricLoaderVersion;
                    String inst = c.fabricInstallerVersion;
                    if ("latest".equalsIgnoreCase(loader)) loader = firstStable(upstream.getJson("https://meta.fabricmc.net/v2/versions/loader/" + mc).getAsJsonArray(), true);
                    if ("latest".equalsIgnoreCase(inst)) inst = firstStable(upstream.getJson("https://meta.fabricmc.net/v2/versions/installer").getAsJsonArray(), false);
                    upstream.download("https://meta.fabricmc.net/v2/versions/loader/" + mc + "/" + loader + "/" + inst + "/server/jar", jar);
                }
                downloadMods(c.mods, wd.resolve("mods"), mc);
                return jar;
            }
            case "spigot": {
                String javaBin = Paths.get(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
                return SpigotBuilder.build(upstream, getDataFolder().toPath(), mc, wd, javaBin, m -> { getLogger().info(m); });
            }
            case "paper": {
                Path jar = wd.resolve("paper-" + mc + ".jar");
                if (!Files.exists(jar)) {
                    JsonArray builds = upstream.getJson("https://fill.papermc.io/v3/projects/paper/versions/" + mc + "/builds").getAsJsonArray();
                    if (builds.isEmpty()) throw new IOException("No Paper builds for " + mc);
                    JsonObject pick = null;
                    for (JsonElement e : builds) { JsonObject b = e.getAsJsonObject(); if ("STABLE".equalsIgnoreCase(str(b, "channel"))) { pick = b; break; } }
                    if (pick == null) { pick = builds.get(0).getAsJsonObject(); getLogger().warning("No STABLE Paper build for " + mc + ", using build " + pick.get("id")); }
                    JsonObject dl = pick.getAsJsonObject("downloads").getAsJsonObject("server:default");
                    String sha = dl.has("checksums") && dl.getAsJsonObject("checksums").has("sha256") ? dl.getAsJsonObject("checksums").get("sha256").getAsString() : null;
                    upstream.download(dl.get("url").getAsString(), jar, "SHA-256", sha);
                }
                return jar;
            }
            case "vanilla": {
                Path jar = wd.resolve("minecraft_server-" + mc + ".jar");
                if (!Files.exists(jar)) {
                    JsonObject man = upstream.getJson("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json").getAsJsonObject();
                    String url = null;
                    for (JsonElement e : man.getAsJsonArray("versions")) if (mc.equals(str(e.getAsJsonObject(), "id"))) url = str(e.getAsJsonObject(), "url");
                    if (url == null) throw new IOException("Version " + mc + " not in Mojang manifest");
                    JsonObject v = upstream.getJson(url).getAsJsonObject();
                    JsonObject server = v.getAsJsonObject("downloads").getAsJsonObject("server");
                    upstream.download(server.get("url").getAsString(), jar, "SHA-1", server.has("sha1") ? server.get("sha1").getAsString() : null);
                }
                return jar;
            }
            case "custom": {
                Path jar = Paths.get(c.customJar);
                if (!Files.exists(jar)) throw new FileNotFoundException("Custom jar not found: " + jar);
                return jar;
            }
            default: throw new IllegalArgumentException("Unknown type: " + type);
        }
    }

    private void downloadMods(Map<String, Boolean> mods, Path modsDir, String mc) throws Exception {
        Files.createDirectories(modsDir);
        if (mods == null || mods.isEmpty()) return;
        for (var en : mods.entrySet()) {
            if (!Boolean.TRUE.equals(en.getValue())) continue;
            String slug = en.getKey();
            try (var st = Files.list(modsDir)) {
                if (st.anyMatch(f -> f.getFileName().toString().startsWith(".fl-" + slug + "-"))) continue; // marker exists
            }
            String q = "?loaders=" + enc("[\"fabric\"]") + "&game_versions=" + enc("[\"" + mc + "\"]");
            JsonArray vers = upstream.getJson("https://api.modrinth.com/v2/project/" + slug + "/version" + q).getAsJsonArray();
            if (vers.isEmpty()) { getLogger().warning("Modrinth: no " + slug + " build for fabric " + mc + " - skipped"); continue; }
            JsonObject v = vers.get(0).getAsJsonObject();
            for (JsonElement e : vers) { if ("release".equals(str(e.getAsJsonObject(), "version_type"))) { v = e.getAsJsonObject(); break; } }
            JsonObject file = null;
            for (JsonElement f : v.getAsJsonArray("files")) { if (f.getAsJsonObject().get("primary").getAsBoolean()) file = f.getAsJsonObject(); }
            if (file == null) file = v.getAsJsonArray("files").get(0).getAsJsonObject();
            String name = str(file, "filename");
            if (name == null || name.contains("/") || name.contains("\\") || name.contains("..") || !name.endsWith(".jar")) {
                getLogger().warning("Modrinth: unexpected filename for " + slug + " - skipped"); continue;
            }
            String sha1 = file.has("hashes") && file.get("hashes").isJsonObject() && file.getAsJsonObject("hashes").has("sha1") ? file.getAsJsonObject("hashes").get("sha1").getAsString() : null;
            try { ModAdmin.requireModrinthCdn(str(file, "url")); }
            catch (IllegalArgumentException bad) { getLogger().warning("Modrinth: rejected URL for " + slug); continue; }
            upstream.download(str(file, "url"), modsDir.resolve(name), "SHA-1", sha1);
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

    /** Sets one key in the focused child's server.properties and, if the key is managed, in config too. */
    void setChildProperty(String key, String value) throws IOException { setChildProperty(focus, key, value); }

    void setChildProperty(String id, String key, String value) throws IOException {
        Path wd = profile(id).directory();
        Files.createDirectories(wd);
        updateProperties(wd.resolve("server.properties"), Map.of(key, value), Map.of());
        java.util.Properties p = new java.util.Properties();
        p.setProperty(key, value);
        syncPropertiesEdit(id, p);
    }

    /** Line-based edit: keeps comments, order and all other keys. force = always set, defaults = only if absent. */
    static void updateProperties(Path f, Map<String, String> force, Map<String, String> defaults) throws IOException {
        PropertiesFile.update(f, force, defaults);
    }

    private void writeProperties(ServerProfile c, Path wd) throws IOException {
        Map<String, String> force = new LinkedHashMap<>();
        if (c.serverProperties != null) force.putAll(c.serverProperties);
        force.put("server-port", String.valueOf(c.port));
        force.put("server-ip", c.serverIp == null ? "" : c.serverIp);
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("level-name", "world");
        defaults.put("white-list", "false");
        defaults.put("enforce-whitelist", "false");
        defaults.put("enable-rcon", "false");
        defaults.put("enable-query", "false");
        updateProperties(wd.resolve("server.properties"), force, defaults);
    }

    private static String str(JsonObject o, String k) { JsonElement e = o.get(k); return e == null || e.isJsonNull() ? null : e.getAsString(); }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static boolean isWindows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"); }
}
