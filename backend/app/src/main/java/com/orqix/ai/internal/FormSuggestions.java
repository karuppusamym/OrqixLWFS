package com.orqix.ai.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.orqix.compiler.Severity;
import com.orqix.compiler.ValidationIssue;
import com.orqix.compiler.ValidationReport;
import com.orqix.compiler.WorkflowCompiler;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Validation of AI form suggestions (S90, G4). The model's reply is untrusted: every change is checked on its own
 * (shape, key, operation against the current form) and then by the real compiler on a minimal workflow that holds the
 * candidate request form, so types, keys, choices, bounds and pattern rules are exactly the publishing rules. Changes
 * are applied in order to a running candidate; a change that adds a blocker under {@code /inputSchema} is rejected with
 * the compiler's messages and not applied. Nothing here is stored or applied to a draft: the designer reviews the
 * accepted changes and applies them in the studio with an explicit click.
 */
public final class FormSuggestions {

    public static final int MAX_CHANGES = 10;
    public static final int MAX_NOTES = 5;
    public static final int MAX_FIELDS = 30;
    private static final int MAX_TEXT = 300;
    private static final Pattern KEY = Pattern.compile("^[a-z][A-Za-z0-9]{0,63}$");
    private static final Set<String> OPS = Set.of("add", "update", "remove");

    /** One accepted change. {@code before} and {@code wasRequired} describe the question as it is now (update/remove). */
    public record Change(String op, String key, JsonNode field, boolean required, String reason, JsonNode before,
            Boolean wasRequired) {
    }

    public record Rejected(String op, String key, List<String> reasons) {
    }

    /** {@code proposedSchema} is the current form with every accepted change applied (what applying all would give). */
    public record Result(List<Change> changes, List<Rejected> rejected, List<String> notes, String notAForm,
            JsonNode proposedSchema) {
    }

    private final Json json;
    private final WorkflowCompiler compiler;

    public FormSuggestions(Json json, WorkflowCompiler compiler) {
        this.json = json;
        this.compiler = compiler;
    }

    /** Validates the model reply against the current request form. */
    public Result evaluate(JsonNode reply, JsonNode currentSchema) {
        List<String> notes = new ArrayList<>();
        for (JsonNode note : reply.path("notes")) {
            String text = plain(note.asString(""));
            if (!text.isEmpty() && notes.size() < MAX_NOTES) {
                notes.add(text);
            }
        }
        String notAForm = reply.path("notAForm").isString() ? plain(reply.path("notAForm").asString()) : null;
        if (notAForm != null && notAForm.isEmpty()) {
            notAForm = null;
        }
        ObjectNode candidate = normalized(currentSchema);
        Set<String> baseline = blockers(candidate);
        List<Change> changes = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        Set<String> touched = new HashSet<>();
        int index = 0;
        for (JsonNode raw : reply.path("changes")) {
            if (index++ >= MAX_CHANGES) {
                rejected.add(new Rejected(raw.path("op").asString("?"), key(raw), List.of(
                        "Only " + MAX_CHANGES + " changes are reviewed at a time.")));
                continue;
            }
            String op = raw.path("op").asString("");
            String key = key(raw);
            String reason = plain(raw.path("reason").asString(""));
            List<String> problems = new ArrayList<>();
            ObjectNode properties = (ObjectNode) candidate.get("properties");
            if (!OPS.contains(op)) {
                problems.add("Unknown change \"" + clip(op) + "\"; use add, update or remove.");
            }
            if (!KEY.matcher(key).matches()) {
                problems.add("The key must start with a lowercase letter and use letters and digits only.");
            } else if (!touched.add(key)) {
                problems.add("Only one change per question is reviewed at a time.");
            } else if ("add".equals(op) && properties.has(key)) {
                problems.add("A question with the key \"" + key + "\" already exists.");
            } else if (!"add".equals(op) && OPS.contains(op) && !properties.has(key)) {
                problems.add("There is no question with the key \"" + key + "\".");
            }
            JsonNode field = raw.get("field");
            if (!"remove".equals(op) && (field == null || !field.isObject())) {
                problems.add("The change does not describe the question.");
            }
            if ("add".equals(op) && properties.size() >= MAX_FIELDS) {
                problems.add("A request form has at most " + MAX_FIELDS + " questions.");
            }
            JsonNode requiredNode = raw.path("required");
            if (!problems.isEmpty()) {
                rejected.add(new Rejected(op, key, List.copyOf(problems)));
                continue;
            }
            JsonNode before = properties.has(key) ? properties.get(key).deepCopy() : null;
            Boolean wasRequired = before == null ? null : isRequired(candidate, key);
            ObjectNode next = candidate.deepCopy();
            ObjectNode nextProperties = (ObjectNode) next.get("properties");
            boolean required;
            if ("remove".equals(op)) {
                nextProperties.remove(key);
                setRequired(next, key, false);
                required = false;
            } else {
                nextProperties.set(key, field.deepCopy());
                required = requiredNode.isBoolean() ? requiredNode.booleanValue()
                        : wasRequired != null && wasRequired;
                setRequired(next, key, required);
            }
            Set<String> after = blockers(next);
            List<String> added = new ArrayList<>();
            for (String issue : after) {
                if (!baseline.contains(issue)) {
                    added.add(issue.substring(issue.indexOf('\u0000') + 1));
                }
            }
            if (!added.isEmpty()) {
                rejected.add(new Rejected(op, key, List.copyOf(added.subList(0, Math.min(5, added.size())))));
                continue;
            }
            candidate = next;
            baseline = after;
            changes.add(new Change(op, key, "remove".equals(op) ? null : field.deepCopy(), required,
                    reason.isEmpty() ? "Suggested from your description." : reason, before, wasRequired));
        }
        return new Result(List.copyOf(changes), List.copyOf(rejected), List.copyOf(notes), notAForm, candidate);
    }

    /** Blockers of the minimal workflow under /inputSchema, as "code path\0message". */
    private Set<String> blockers(ObjectNode schema) {
        ValidationReport report = compiler.validate(json.write(wrap(schema)), null);
        Set<String> found = new LinkedHashSet<>();
        for (ValidationIssue issue : report.issues()) {
            String path = issue.path() == null ? "" : issue.path();
            if (issue.severity() == Severity.BLOCKER && (path.startsWith("/inputSchema") || path.isEmpty())) {
                found.add(issue.code() + " " + path + '\u0000' + issue.message());
            }
        }
        return found;
    }

    /** The smallest publishable workflow around a request form: start, finish, one route. */
    private ObjectNode wrap(ObjectNode schema) {
        ObjectNode doc = json.object();
        doc.put("schemaVersion", "0.1");
        doc.put("workflowKey", "form-suggestion-check");
        doc.put("name", "Form suggestion check");
        doc.putObject("metadata").put("description", "");
        doc.set("inputSchema", schema);
        ObjectNode output = doc.putObject("outputSchema");
        output.put("type", "object").put("additionalProperties", false);
        output.putObject("properties");
        doc.putArray("triggers").add("manual");
        ArrayNode nodes = doc.putArray("nodes");
        nodes.addObject().put("id", "start").put("type", "start").put("label", "Request submitted").putObject("config");
        ObjectNode end = nodes.addObject().put("id", "done").put("type", "end").put("label", "Request completed");
        end.putObject("config").put("businessOutcome", "Completed").put("outcomeKind", "success");
        doc.putArray("transitions").addObject().put("id", "t1").put("from", "start").put("to", "done").put("on", "next");
        doc.putArray("decisions");
        doc.putObject("execution").put("maxConcurrentRuns", 500).put("maxAttempts", 3).put("retryDelaySeconds", 30);
        doc.putObject("security").put("selfApproval", "deny");
        doc.putObject("dependencies").putArray("connections");
        doc.putObject("outputMapping");
        return doc;
    }

    /** The current form as the compiler expects it (object, closed, required list), keeping the designer's fields. */
    public ObjectNode normalized(JsonNode current) {
        ObjectNode schema = json.object();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ArrayNode required = schema.putArray("required");
        current.path("required").forEach(r -> {
            if (r.isString() && current.path("properties").has(r.asString())) {
                required.add(r.asString());
            }
        });
        ObjectNode properties = schema.putObject("properties");
        current.path("properties").properties().forEach(e -> properties.set(e.getKey(), e.getValue().deepCopy()));
        return schema;
    }

    private static boolean isRequired(ObjectNode schema, String key) {
        for (JsonNode r : schema.path("required")) {
            if (key.equals(r.asString())) {
                return true;
            }
        }
        return false;
    }

    private static void setRequired(ObjectNode schema, String key, boolean required) {
        ArrayNode list = (ArrayNode) schema.get("required");
        for (int i = list.size() - 1; i >= 0; i--) {
            if (key.equals(list.get(i).asString())) {
                list.remove(i);
            }
        }
        if (required) {
            list.add(key);
        }
    }

    private static String key(JsonNode raw) {
        return clip(raw.path("key").asString(""));
    }

    /** One line of plain text, at most 300 characters; control characters and expression markers removed. */
    static String plain(String text) {
        String line = text.replaceAll("[\\p{Cntrl}\\s]+", " ").replace("${", "$ {").replace("#{", "# {").strip();
        return line.length() > MAX_TEXT ? line.substring(0, MAX_TEXT - 1).strip() + "…" : line;
    }

    private static String clip(String text) {
        return text.length() > 64 ? text.substring(0, 64) : text;
    }
}
