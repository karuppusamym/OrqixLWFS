package com.orqix.compiler.internal.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import tools.jackson.databind.JsonNode;

/** Reads the S139 wait settings ({@link WaitExtras}) of a schema-valid definition. */
final class WaitsReader {

    private WaitsReader() {
    }

    static WaitExtras read(JsonNode nodes, JsonNode dependencies) {
        Map<String, WaitExtras.Working> working = new TreeMap<>();
        Map<String, String> timeoutCalendars = new TreeMap<>();
        Map<String, WaitExtras.Remind> reminders = new TreeMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            JsonNode n = nodes.get(i);
            String id = n.get("id").stringValue();
            String type = n.get("type").stringValue();
            JsonNode c = n.get("config");
            if ("wait".equals(type) && c.has("workingDuration")) {
                working.put(id, new WaitExtras.Working(c.get("workingDuration").stringValue(),
                        c.has("calendarRef") ? c.get("calendarRef").stringValue() : null));
            } else if ("wait".equals(type) && c.has("calendarRef")) {
                working.put(id, new WaitExtras.Working(null, c.get("calendarRef").stringValue()));
            }
            if ("waitForMessage".equals(type) && c.has("timeoutCalendarRef")) {
                timeoutCalendars.put(id, c.get("timeoutCalendarRef").stringValue());
            }
            if ("http".equals(type) && c.path("poll").has("timeoutCalendarRef")) {
                timeoutCalendars.put(id, c.get("poll").get("timeoutCalendarRef").stringValue());
            }
            JsonNode remind = c.path("whileWaiting").path("remind");
            if (remind.isObject()) {
                reminders.put(id, remind(remind, "/nodes/" + i + "/config/whileWaiting/remind"));
            }
        }
        Map<String, WaitExtras.CalendarPin> pins = new TreeMap<>();
        JsonNode assets = dependencies == null ? null : dependencies.get("assets");
        if (assets != null) {
            for (int i = 0; i < assets.size(); i++) {
                JsonNode a = assets.get(i);
                if ("calendar".equals(a.path("kind").asString(""))) {
                    pins.putIfAbsent(a.get("key").stringValue(),
                            new WaitExtras.CalendarPin(i, a.get("key").stringValue(), a.get("version").intValue()));
                }
            }
        }
        return new WaitExtras(working, timeoutCalendars, reminders, pins);
    }

    private static WaitExtras.Remind remind(JsonNode r, String path) {
        List<String> to = new ArrayList<>();
        r.path("to").forEach(v -> to.add(v.stringValue()));
        Map<String, String> mapping = new TreeMap<>();
        r.path("inputMapping").properties().forEach(e -> mapping.put(e.getKey(), e.getValue().stringValue()));
        return new WaitExtras.Remind(r.get("every").stringValue(), r.path("maxTimes").asInt(3),
                r.get("adapter").stringValue(), to, text(r, "toField"), text(r.path("template"), "subject"),
                text(r.path("template"), "body"), text(r.path("template"), "text"), mapping, path);
    }

    private static String text(JsonNode object, String member) {
        JsonNode v = object == null ? null : object.get(member);
        return v == null || v.isNull() || v.isMissingNode() ? null : v.stringValue();
    }
}
