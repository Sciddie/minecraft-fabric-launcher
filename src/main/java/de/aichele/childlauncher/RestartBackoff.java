package de.aichele.childlauncher;

/** Crash-restart delay: base * 2^attempt, capped, without long overflow. */
final class RestartBackoff {
    private RestartBackoff() {}

    static long delaySeconds(long base, long cap, int attempts) {
        if (base < 0) base = 0;
        if (cap < 0) cap = 0;
        int shift = Math.min(Math.max(attempts, 0), 20);
        if (shift >= 1 && base > (Long.MAX_VALUE >> shift)) return cap;
        long raw = base << shift;
        return Math.min(cap, raw);
    }
}
