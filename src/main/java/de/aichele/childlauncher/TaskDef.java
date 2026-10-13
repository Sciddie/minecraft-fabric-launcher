package de.aichele.childlauncher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class TaskDef {
    final String id;
    final boolean enabled;
    final String cron;
    final String timezone;
    final String action;
    final String command;
    final String server;

    TaskDef(String id, boolean enabled, String cron, String timezone, String action, String command, String server) {
        this.id = id;
        this.enabled = enabled;
        this.cron = cron;
        this.timezone = CronExpr.normZone(timezone);
        this.action = CronExpr.normAction(action);
        this.command = command == null ? "" : command;
        this.server = server == null || server.isBlank() ? "" : server;
    }

    static List<TaskDef> load(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<TaskDef> out = new ArrayList<>();
        int n = 0;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            n++;
            String id = str(map, "id", "task-" + n);
            boolean enabled = map.get("enabled") instanceof Boolean b ? b : Boolean.parseBoolean(str(map, "enabled", "false"));
            out.add(new TaskDef(id, enabled, str(map, "cron", ""), str(map, "timezone", "UTC"), str(map, "action", "command"), str(map, "command", ""), str(map, "server", "")));
        }
        return out;
    }

    private static String str(Map<?, ?> map, String key, String fallback) {
        Object v = map.get(key);
        return v == null ? fallback : String.valueOf(v);
    }
}
