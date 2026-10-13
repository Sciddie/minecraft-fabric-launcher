package de.aichele.childlauncher;

import java.io.BufferedWriter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime of one child process. Downloads and the JVM start only when this slot is launched. */
final class ChildSlot {
    final String id;
    final ConsoleBuffer console;
    final Set<String> onlinePlayers = ConcurrentHashMap.newKeySet();
    final TickQueryFilter ticks = new TickQueryFilter();
    final Object lock = new Object();
    final Object stopLock = new Object();
    volatile Process process;
    volatile BufferedWriter stdin;
    volatile boolean wantRunning;
    volatile long startedAt;
    volatile int attempts;
    volatile String lastStatus = "idle";
    volatile double mspt = -1;
    volatile double tps = -1;
    volatile long tickAt;
    volatile long queryAt;
    final double[] msptRing = new double[60];
    int msptN;
    volatile ServerProfile profile;

    ChildSlot(String id, int consoleCap) { this.id = id; this.console = new ConsoleBuffer(consoleCap); }

    boolean running() { Process p = process; return p != null && p.isAlive(); }
    long pid() { Process p = process; return p != null && p.isAlive() ? p.pid() : -1; }
    long uptimeMillis() { return running() ? System.currentTimeMillis() - startedAt : 0; }

    void pushMspt(double v) { msptRing[msptN++ % msptRing.length] = v; }

    java.util.List<Double> msptHistory() {
        int n = Math.min(msptN, msptRing.length);
        int start = msptN - n;
        java.util.List<Double> out = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(msptRing[(start + i) % msptRing.length]);
        return out;
    }
}
