package com.orqix.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Plain text for assistants (S136 section 3): request explanations built only from the request detail and timeline the
 * person may read (no model call, no extra data), confirmation summaries of form values, and results.
 */
final class McpExplanations {

    static final int RECENT_EVENTS = 10;

    private McpExplanations() {
    }

    /** explain_request: where the request is, who it waits for, recent steps and attention, from visible data only. */
    static ObjectNode explain(Json json, JsonNode run, JsonNode events) {
        ObjectNode out = json.object();
        ObjectNode request = out.putObject("request");
        request.put("id", run.path("id").asString());
        request.put("reference", run.path("reference").asString(""));
        if (run.path("requestTitle").isString()) {
            request.put("title", run.path("requestTitle").asString());
        }
        request.put("workflow", run.path("workflow").path("name").asString(""));
        request.put("lifecycle", run.path("lifecycle").asString(""));
        if (run.path("outcome").isString()) {
            request.put("outcome", run.path("outcome").asString());
        }
        request.put("requester", run.path("initiator").path("displayName").asString(""));
        request.put("createdAt", run.path("createdAt").asString(""));

        List<String> lines = new ArrayList<>();
        String name = "Request " + run.path("reference").asString("")
                + (run.path("requestTitle").isString() ? " \"" + run.path("requestTitle").asString() + "\"" : "")
                + " (" + run.path("workflow").path("name").asString("") + ", requested by "
                + run.path("initiator").path("displayName").asString("someone") + " on "
                + run.path("createdAt").asString("") + ")";
        String lifecycle = run.path("lifecycle").asString("");
        switch (lifecycle) {
            case "completed" -> lines.add(name + " is completed" + (run.path("outcome").isString()
                    ? " with the outcome \"" + run.path("outcome").asString() + "\"" : "") + " ("
                    + run.path("endedAt").asString("") + ").");
            case "cancelled" -> lines.add(name + " was cancelled (" + run.path("endedAt").asString("") + ").");
            case "needsAttention" -> lines.add(name + " is stopped and needs attention from an operator.");
            default -> lines.add(name + " is " + ("waiting".equals(lifecycle) ? "waiting" : "in progress") + ".");
        }
        ArrayNode now = out.putArray("currentSteps");
        for (JsonNode step : run.path("currentSteps")) {
            ObjectNode s = now.addObject();
            s.put("label", step.path("label").asString(""));
            s.put("kind", step.path("kind").asString(""));
            s.put("since", step.path("since").asString(""));
            StringBuilder line = new StringBuilder("Now at \"" + step.path("label").asString("") + "\"");
            if (step.path("assignee").isObject()) {
                s.put("assignee", step.path("assignee").path("displayName").asString(""));
                line.append(", with ").append(step.path("assignee").path("displayName").asString(""));
            } else if (step.path("candidateGroup").isString()) {
                s.put("waitingFor", step.path("candidateGroup").asString());
                line.append(", waiting for someone in ").append(step.path("candidateGroup").asString());
            } else if (step.path("messageName").isString()) {
                s.put("waitingForMessage", step.path("messageName").asString());
                line.append(", waiting for the message ").append(step.path("messageName").asString());
            }
            line.append(" since ").append(step.path("since").asString(""));
            if (step.path("dueAt").isString()) {
                s.put("dueAt", step.path("dueAt").asString());
                line.append(", due ").append(step.path("dueAt").asString());
            }
            lines.add(line.append('.').toString());
        }
        JsonNode attention = run.path("attention");
        if (attention.isObject()) {
            ObjectNode a = out.putObject("attention");
            a.put("reason", attention.path("reason").asString(""));
            if (attention.path("message").isString()) {
                a.put("message", attention.path("message").asString());
            }
            a.put("since", attention.path("since").asString(""));
            lines.add("It needs attention (" + attention.path("reason").asString("") + ")"
                    + (attention.path("message").isString() ? ": " + attention.path("message").asString() : "") + ".");
        }
        List<JsonNode> all = new ArrayList<>();
        events.path("items").forEach(all::add);
        ArrayNode recent = out.putArray("recent");
        for (JsonNode e : all.subList(Math.max(0, all.size() - RECENT_EVENTS), all.size())) {
            ObjectNode r = recent.addObject();
            r.put("type", e.path("type").asString(""));
            if (e.path("nodeLabel").isString()) {
                r.put("step", e.path("nodeLabel").asString());
            }
            if (e.path("actor").isObject()) {
                r.put("by", e.path("actor").path("displayName").asString(""));
            }
            r.put("at", e.path("occurredAt").asString(""));
        }
        if (!recent.isEmpty()) {
            List<String> steps = new ArrayList<>();
            recent.forEach(r -> steps.add(humanize(r.path("type").asString()) + (r.has("step")
                    ? " \"" + r.path("step").asString() + "\"" : "") + (r.has("by") ? " by " + r.path("by").asString() : "")));
            lines.add("Recently: " + String.join("; ", steps) + ".");
        }
        out.put("explanation", String.join(" ", lines));
        return out;
    }

    /** Confirmation summary of form values: field titles and values, in form order. */
    static String values(JsonNode inputSchema, JsonNode input) {
        if (input == null || !input.isObject() || input.isEmpty()) {
            return " No values.";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : input.properties()) {
            JsonNode field = inputSchema.path("properties").path(e.getKey());
            String title = field.path("title").asString(e.getKey());
            JsonNode v = e.getValue();
            parts.add(title + ": " + (v.isArray() ? v.size() + " row" + (v.size() == 1 ? "" : "s")
                    : v.isObject() ? "(" + v.size() + " values)" : scalar(v)));
        }
        return " Values: " + String.join("; ", parts) + ".";
    }

    static String scalar(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return "(empty)";
        }
        String text = v.isString() ? v.asString() : v.toString();
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }

    /** Text of a carried-out action. */
    static String done(ObjectNode body) {
        return switch (body.path("action").asString("")) {
            case "requestStarted" -> "Started request " + body.path("request").path("reference").asString("")
                    + " (ID " + body.path("request").path("id").asString("") + ").";
            case "taskDecided" -> "Recorded the decision (" + body.path("decision").path("action").asString("") + ").";
            case "messageSent" -> "The message was " + body.path("message").path("status").asString("accepted")
                    + " (" + body.path("message").path("matched").asInt() + " waiting request(s) took it).";
            default -> "Done.";
        };
    }

    private static String humanize(String type) {
        String lower = type.toLowerCase(Locale.ROOT).replace('_', ' ');
        return lower.isEmpty() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
