package de.aichele.childlauncher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Read-only process and disk facts. jcmd is limited to inspection commands. */
final class ProcessProbe {
    static final Set<String> JCMD = Set.of("Thread.print", "GC.heap_info", "VM.flags");

    record Disk(long bytes, long files, boolean partial) {}

    private ProcessProbe() {}

    static Map<String, Object> snapshot(long pid) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rssMb", -1L); m.put("hwmMb", -1L); m.put("swapMb", -1L); m.put("vmMb", -1L);
        m.put("threads", -1L); m.put("fds", -1); m.put("readBytes", -1L); m.put("writeBytes", -1L);
        m.put("state", ""); m.put("cmdline", List.of());
        if (pid <= 0) return m;
        try {
            for (String l : Files.readAllLines(Path.of("/proc/" + pid + "/status"))) {
                if (l.startsWith("VmRSS:")) m.put("rssMb", kb(l) / 1024);
                else if (l.startsWith("VmHWM:")) m.put("hwmMb", kb(l) / 1024);
                else if (l.startsWith("VmSwap:")) m.put("swapMb", kb(l) / 1024);
                else if (l.startsWith("VmSize:")) m.put("vmMb", kb(l) / 1024);
                else if (l.startsWith("Threads:")) m.put("threads", kb(l));
                else if (l.startsWith("State:")) m.put("state", l.substring(6).trim());
            }
        } catch (Exception ignored) {}
        try {
            for (String l : Files.readAllLines(Path.of("/proc/" + pid + "/io"))) {
                if (l.startsWith("read_bytes:")) m.put("readBytes", Long.parseLong(l.substring(11).trim()));
                else if (l.startsWith("write_bytes:")) m.put("writeBytes", Long.parseLong(l.substring(12).trim()));
            }
        } catch (Exception ignored) {}
        try (var st = Files.list(Path.of("/proc/" + pid + "/fd"))) { m.put("fds", (int) st.count()); } catch (Exception ignored) {}
        try {
            String raw = Files.readString(Path.of("/proc/" + pid + "/cmdline"));
            m.put("cmdline", List.of(raw.split("\0")));
        } catch (Exception ignored) {}
        return m;
    }

    static Disk disk(Path root, int maxEntries) {
        if (root == null || !Files.isDirectory(root)) return new Disk(0, 0, false);
        long bytes = 0, files = 0;
        boolean partial = false;
        try (var walk = Files.walk(root)) {
            var it = walk.iterator();
            while (it.hasNext()) {
                Path p = it.next();
                if (files > maxEntries) { partial = true; break; }
                if (Files.isSymbolicLink(p) || Files.isDirectory(p)) continue;
                files++;
                try { bytes += Files.size(p); } catch (IOException ignored) {}
            }
        } catch (IOException ignored) { partial = true; }
        return new Disk(bytes, files, partial);
    }

    static String jcmd(long pid, String command) throws IOException {
        if (!JCMD.contains(command)) throw new IllegalArgumentException("jcmd");
        if (pid <= 0) throw new IllegalStateException("Child läuft nicht");
        String exe = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "jcmd.exe" : "jcmd";
        Path bin = Path.of(System.getProperty("java.home"), "bin", exe);
        if (!Files.isExecutable(bin)) throw new IOException("jcmd fehlt: " + bin);
        Process p = new ProcessBuilder(bin.toString(), Long.toString(pid), command).redirectErrorStream(true).start();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Thread pump = new Thread(() -> {
            try { p.getInputStream().transferTo(buf); } catch (IOException ignored) {}
        }, "FabricLauncher-jcmd");
        pump.setDaemon(true);
        pump.start();
        try {
            if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IOException("jcmd timeout"); }
            pump.join(2000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("jcmd interrupted");
        }
        String text = buf.toString(StandardCharsets.UTF_8);
        if (text.length() > 1_500_000) text = text.substring(0, 1_500_000) + "\n...truncated";
        if (p.exitValue() != 0) throw new IOException("jcmd exit " + p.exitValue() + ": " + abbreviate(text));
        return text;
    }

    private static long kb(String line) {
        String digits = line.replaceAll("\\D", "");
        return digits.isEmpty() ? -1 : Long.parseLong(digits);
    }

    private static String abbreviate(String s) { return s.length() > 500 ? s.substring(0, 500) : s; }
}
