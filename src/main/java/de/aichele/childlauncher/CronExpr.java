package de.aichele.childlauncher;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.util.Locale;

/** Five-field cron: minute hour day-of-month month day-of-week. DOM and DOW both set means either may match. */
final class CronExpr {
    private CronExpr() {}

    static boolean matches(String expr, ZonedDateTime time) {
        if (expr == null) throw new IllegalArgumentException("cron");
        String[] f = expr.trim().split("\\s+");
        if (f.length != 5) throw new IllegalArgumentException("cron needs 5 fields");
        boolean domSet = !f[2].equals("*");
        boolean dowSet = !f[4].equals("*");
        boolean day = domSet && dowSet
                ? (field(f[2], time.getDayOfMonth(), 1, 31) || dow(f[4], time.getDayOfWeek()))
                : (field(f[2], time.getDayOfMonth(), 1, 31) && dow(f[4], time.getDayOfWeek()));
        return field(f[0], time.getMinute(), 0, 59)
                && field(f[1], time.getHour(), 0, 23)
                && day
                && field(f[3], time.getMonthValue(), 1, 12);
    }

    private static boolean dow(String expr, DayOfWeek day) {
        int cron = day.getValue() % 7; // Sunday = 0
        return field(expr, cron, 0, 7) || (cron == 0 && field(expr, 7, 0, 7));
    }

    static boolean field(String expr, int value, int min, int max) {
        for (String part : expr.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            String stepPart = p;
            int step = 1;
            int slash = p.indexOf('/');
            if (slash >= 0) { stepPart = p.substring(0, slash); step = Integer.parseInt(p.substring(slash + 1)); }
            if (step <= 0) throw new IllegalArgumentException("cron step");
            int from = min, to = max;
            if (!stepPart.equals("*")) {
                int dash = stepPart.indexOf('-');
                if (dash >= 0) {
                    from = Integer.parseInt(stepPart.substring(0, dash));
                    to = Integer.parseInt(stepPart.substring(dash + 1));
                } else {
                    from = to = Integer.parseInt(stepPart);
                }
            }
            if (value >= from && value <= to && (value - from) % step == 0) return true;
        }
        return false;
    }

    static String normZone(String zone) {
        return zone == null || zone.isBlank() ? "UTC" : zone.trim();
    }

    static String normAction(String action) {
        return action == null ? "" : action.toLowerCase(Locale.ROOT).trim();
    }
}
