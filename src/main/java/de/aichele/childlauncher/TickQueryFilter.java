package de.aichele.childlauncher;

import java.util.regex.Pattern;

/**
 * Hides vanilla {@code tick query} replies from the console mirror.
 * Only lines that belong to that reply are swallowed, and only for a few seconds after the query.
 */
final class TickQueryFilter {
    private static final Pattern AVG = Pattern.compile("Average time per tick: ([\\d.,]+) ?ms");
    private static final Pattern RATE = Pattern.compile("Target tick rate: ([\\d.,]+)");

    double targetRate = 20;
    double mspt = -1;
    double tps = -1;
    long tickAt;

    boolean consume(String line, long queryAt, long now) {
        if (now - queryAt > 3000) return false;
        var rate = RATE.matcher(line);
        if (rate.find()) {
            try { targetRate = Double.parseDouble(rate.group(1).replace(',', '.')); } catch (NumberFormatException ignored) {}
            return true;
        }
        var avg = AVG.matcher(line);
        if (avg.find()) {
            try {
                double ms = Double.parseDouble(avg.group(1).replace(',', '.'));
                mspt = ms;
                tps = Math.round(Math.min(targetRate, 1000 / Math.max(ms, 0.001)) * 100) / 100.0;
                tickAt = now;
            } catch (NumberFormatException ignored) {}
            return true;
        }
        return line.contains("The game is running normally")
                || line.contains("The game is frozen")
                || line.contains("The game is sprinting")
                || line.contains("game is frozen")
                || line.contains("Percentiles")
                || line.matches(".*\\]: ?P\\d+: .*")
                || line.contains("Sprinting");
    }
}
