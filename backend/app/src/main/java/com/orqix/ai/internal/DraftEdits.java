package com.orqix.ai.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.orqix.catalog.DraftAuthoring;
import com.orqix.compiler.CompileContext;
import com.orqix.compiler.Severity;
import com.orqix.compiler.ValidationIssue;
import com.orqix.compiler.ValidationReport;
import com.orqix.compiler.WorkflowCompiler;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Conversational authoring (STU02, docs/build/S95): the redacted draft view sent to the model and the validation of its
 * reply. The reply is untrusted. It proposes <em>changes</em>, each a short list of structured operations (add, change,
 * rename or remove a step, connect, disconnect, change a request-form question). Every operation is shape-checked and
 * applied in order to a candidate copy of the draft; after each change the real compiler validates the candidate, and a
 * change that adds a blocker is rejected with the compiler's messages and not offered. Accepted operations carry the
 * state they expect ({@code before}), so the studio can skip a change whose target changed since. Nothing here is
 * stored or applied to a draft.
 */
public final class DraftEdits {

    public static final int MAX_CHANGES = 8;
    public static final int MAX_OPERATIONS_PER_CHANGE = 12;
    public static final int MAX_REGENERATE_OPERATIONS = 60;
    public static final int MAX_NOTES = 5;
    public static final int MAX_SUMMARY_STEPS = 120;
    public static final int MAX_SUMMARY_CHARS = 48_000;
    static final int MAX_TEXT = 300;
    static final int MAX_LABEL = 100;
    private static final int MAX_STRING_IN_SUMMARY = 200;
    private static final int MAX_OPERATION_CHARS = 8_000;

    /** Step kinds the assistant may add; other kinds need configuration the redacted view does not show. */
    public static final Set<String> ADDABLE_KINDS = Set.of("approval", "branch", "notification", "wait", "end");
    public static final Set<String> OPERATIONS = Set.of("addStep", "updateStep", "renameStep", "removeStep", "connect",
            "disconnect", "changeFormField", "setFieldRule", "setStepFieldAccess", "setFormGroups", "addDecision",
            "addCall");
    /** Clarifying questions the assistant may ask before it drafts (S124). */
    public static final int MAX_QUESTIONS = 3;
    static final int MAX_QUESTION = 200;
    static final Set<String> FIELD_ACCESS = Set.of("hidden", "readOnly", "editable", "required");
    static final Pattern TABLE_KEY = Pattern.compile("^[a-z][a-z0-9-]{0,62}[a-z0-9]$");
    static final Set<String> ROUTES = Set.of("next", "approved", "rejected", "timedOut", "failed", "condition",
            "default");
    static final Pattern NODE_ID = Pattern.compile("^(?!.*__)[A-Za-z][A-Za-z0-9_-]{0,63}$");
    static final Pattern CONFIG_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9]{0,63}$");
    static final Pattern FIELD_KEY = Pattern.compile("^[a-z][A-Za-z0-9]{0,63}$");
    /** Setting names whose values are never shown to the model and never written by it. */
    static final Pattern SENSITIVE = Pattern.compile(
            "(?i).*(secret|password|passwd|token|apikey|api_key|authorization|credential|cookie|header|privatekey).*");
    /** Values that look like credentials (bearer tokens, long opaque strings) are redacted wherever they appear. */
    private static final Pattern SECRET_VALUE = Pattern.compile(
            "(?i)^(bearer|basic)\\s+\\S+.*|^[A-Za-z0-9+/_=.-]{32,}$|.*(sk|pk|ghp|xox[abp])[-_][A-Za-z0-9]{12,}.*");
    private static final Pattern INDEXED = Pattern.compile("^/(nodes|transitions|decisions)/(\\d+)");

    public enum Mode {
        EDIT, REGENERATE;

        public String value() {
            return this == EDIT ? "edit" : "regenerate";
        }
    }

    /**
     * One reviewable change: its operations as the server applied them (normalized, never the raw reply), and the
     * earlier changes it needs ({@code dependsOn}: they create or modify something it relies on).
     */
    public record Change(String id, String summary, List<ObjectNode> operations, List<String> dependsOn) {
    }

    public record Rejected(String summary, List<String> reasons) {
    }

    /**
     * @param definition regenerate only: the complete proposed draft (laid out), else null
     */
    public record Result(String message, List<Change> changes, List<Rejected> rejected, List<String> notes,
            String notAnEdit, ObjectNode definition, List<String> questions) {

        public Result(String message, List<Change> changes, List<Rejected> rejected, List<String> notes,
                String notAnEdit, ObjectNode definition) {
            this(message, changes, rejected, notes, notAnEdit, definition, List.of());
        }

        public int operationCount() {
            return changes.stream().mapToInt(c -> c.operations().size()).sum();
        }
    }

    private final Json json;
    private final WorkflowCompiler compiler;

    public DraftEdits(Json json, WorkflowCompiler compiler) {
        this.json = json;
        this.compiler = compiler;
    }

    // ---------------------------------------------------------------- the redacted view sent to the model

    /**
     * A bounded, redacted summary of the draft: name, description, request form (types, titles, choices), steps with
     * their settings, routes, rule tables (keys and sizes only) and the workspace's groups. Settings whose names look
     * sensitive are dropped, values that look like credentials are replaced with "[redacted]", long texts are clipped,
     * layout, notes, provenance and assessment data are left out. At most {@value #MAX_SUMMARY_STEPS} steps; when the
     * result is still over {@value #MAX_SUMMARY_CHARS} characters, step settings are left out.
     */
    public String summary(JsonNode definition, List<DraftRules.GroupFact> groups) {
        return summary(definition, groups, List.of());
    }

    /**
     * The summary with the form's rules and groups and, for {@code addCall} (S124), the workspace's connections: the
     * latest registered version of each, with its operations' request fields and response properties (names only).
     */
    public String summary(JsonNode definition, List<DraftRules.GroupFact> groups, List<DraftRules.ConnectionFact> connections) {
        ObjectNode view = summaryNode(definition, groups, connections, true);
        String text = json.write(view);
        if (text.length() > MAX_SUMMARY_CHARS) {
            text = json.write(summaryNode(definition, groups, connections, false));
        }
        return text.length() > MAX_SUMMARY_CHARS ? text.substring(0, MAX_SUMMARY_CHARS) : text;
    }

    private ObjectNode summaryNode(JsonNode definition, List<DraftRules.GroupFact> groups,
            List<DraftRules.ConnectionFact> connections, boolean settings) {
        ObjectNode view = json.object();
        view.put("name", clipped(definition.path("name").asString("")));
        view.put("description", clipped(definition.path("metadata").path("description").asString("")));
        ObjectNode form = view.putObject("form");
        Set<String> required = new HashSet<>();
        definition.path("inputSchema").path("required").forEach(r -> required.add(r.asString("")));
        for (Map.Entry<String, JsonNode> field : definition.path("inputSchema").path("properties").properties()) {
            ObjectNode out = form.putObject(field.getKey());
            JsonNode in = field.getValue();
            for (String name : List.of("type", "title", "format", "minimum", "maximum", "minLength", "maxLength")) {
                if (in.has(name) && in.get(name).isValueNode()) {
                    out.set(name, redactValue(in.get(name)));
                }
            }
            if (in.path("enum").isArray()) {
                ArrayNode choices = out.putArray("enum");
                int count = 0;
                for (JsonNode choice : in.path("enum")) {
                    if (count++ >= 20) {
                        break;
                    }
                    choices.add(redactValue(choice));
                }
            }
            out.put("required", required.contains(field.getKey()));
        }
        ArrayNode steps = view.putArray("steps");
        int index = 0;
        for (JsonNode node : definition.path("nodes")) {
            if (index++ >= MAX_SUMMARY_STEPS) {
                view.put("stepsOmitted", definition.path("nodes").size() - MAX_SUMMARY_STEPS);
                break;
            }
            ObjectNode step = steps.addObject();
            step.put("id", node.path("id").asString(""));
            step.put("kind", node.path("type").asString(""));
            step.put("label", clipped(node.path("label").asString("")));
            if (settings && node.path("config").isObject()) {
                step.set("settings", redact(node.path("config"), 0));
            }
        }
        ArrayNode routes = view.putArray("routes");
        for (JsonNode transition : definition.path("transitions")) {
            ObjectNode route = routes.addObject();
            for (String name : List.of("id", "from", "to", "on")) {
                route.put(name, transition.path(name).asString(""));
            }
            if (transition.path("label").isString()) {
                route.put("label", clipped(transition.path("label").asString()));
            }
            if (transition.has("condition")) {
                route.set("condition", redact(transition.get("condition"), 0));
            }
        }
        ArrayNode decisions = view.putArray("ruleTables");
        for (JsonNode table : definition.path("decisions")) {
            ObjectNode out = decisions.addObject();
            out.put("key", table.path("key").asString(""));
            out.put("rows", table.path("rows").size());
            ArrayNode outputs = out.putArray("outputs");
            table.path("default").propertyNames().forEach(outputs::add);
        }
        ArrayNode groupList = view.putArray("groups");
        for (DraftRules.GroupFact group : groups) {
            groupList.addObject().put("key", group.key()).put("name", clipped(group.name()));
        }
        JsonNode ui = definition.path("inputUi");
        ObjectNode rules = view.putObject("formRules");
        for (String name : List.of("visibleWhen", "requiredWhen")) {
            if (ui.path(name).isObject() && !ui.path(name).isEmpty()) {
                rules.set(name, redact(ui.path(name), 0));
            }
        }
        if (ui.path("layout").path("sections").isArray()) {
            ArrayNode formGroups = rules.putArray("groups");
            for (JsonNode section : ui.path("layout").path("sections")) {
                ObjectNode out = formGroups.addObject();
                out.put("id", section.path("id").asString(""));
                out.put("title", clipped(section.path("title").asString("")));
                out.set("fields", section.path("fields").deepCopy());
            }
        }
        ArrayNode connectionList = view.putArray("connections");
        for (DraftRules.ConnectionFact connection : latest(connections)) {
            ObjectNode out = connectionList.addObject();
            out.put("key", connection.key()).put("version", connection.version()).put("name", clipped(connection.name()));
            ArrayNode operations = out.putArray("operations");
            for (Map.Entry<String, JsonNode> op : connection.document().path("operations").properties()) {
                ObjectNode operation = operations.addObject();
                operation.put("name", op.getKey());
                operation.put("label", clipped(op.getValue().path("label").asString(op.getKey())));
                ArrayNode request = operation.putArray("request");
                op.getValue().path("requestSchema").path("properties").propertyNames().forEach(request::add);
                ArrayNode response = operation.putArray("response");
                op.getValue().path("responseSchema").path("properties").propertyNames().forEach(response::add);
                operation.put("maxTimeoutSeconds", op.getValue().path("maxTimeoutSeconds").asInt(30));
            }
        }
        return view;
    }

    /** The latest registered version of each connection, by key. */
    private static List<DraftRules.ConnectionFact> latest(List<DraftRules.ConnectionFact> connections) {
        Map<String, DraftRules.ConnectionFact> latest = new java.util.TreeMap<>();
        for (DraftRules.ConnectionFact connection : connections) {
            DraftRules.ConnectionFact current = latest.get(connection.key());
            if (current == null || current.version() < connection.version()) {
                latest.put(connection.key(), connection);
            }
        }
        return List.copyOf(latest.values());
    }

    private JsonNode redact(JsonNode value, int depth) {
        if (value.isObject()) {
            ObjectNode out = json.object();
            if (depth >= 3) {
                out.put("…", value.size() + " settings");
                return out;
            }
            int count = 0;
            for (Map.Entry<String, JsonNode> entry : value.properties()) {
                if (SENSITIVE.matcher(entry.getKey()).matches()) {
                    out.put(entry.getKey(), "[redacted]");
                    continue;
                }
                if (count++ >= 30) {
                    break;
                }
                out.set(entry.getKey(), redact(entry.getValue(), depth + 1));
            }
            return out;
        }
        if (value.isArray()) {
            ArrayNode out = json.object().putArray("x");
            if (depth >= 3) {
                return out;
            }
            int count = 0;
            for (JsonNode item : value) {
                if (count++ >= 20) {
                    break;
                }
                out.add(redact(item, depth + 1));
            }
            return out;
        }
        return redactValue(value);
    }

    private JsonNode redactValue(JsonNode value) {
        if (!value.isString()) {
            return value.deepCopy();
        }
        String text = value.asString();
        if (SECRET_VALUE.matcher(text.strip()).matches()) {
            return json.toTree("[redacted]");
        }
        return json.toTree(clipped(scrubEmbeddedSecrets(text)));
    }

    /** Credentials in URLs and query strings: "user:pass@host", "?api_key=...", and long opaque path segments. */
    private static final Pattern URL_USERINFO = Pattern.compile("(?i)(://)[^/@\s]+@");
    private static final Pattern SENSITIVE_PARAM = Pattern.compile(
            "(?i)([?&;])([^=&#\s]*(secret|password|passwd|token|apikey|api_key|key|authorization|credential|signature|sig|auth)[^=&#\s]*)=[^&#\s]*");
    private static final Pattern OPAQUE_SEGMENT = Pattern.compile("[A-Za-z0-9_-]{24,}");

    static String scrubEmbeddedSecrets(String text) {
        String out = URL_USERINFO.matcher(text).replaceAll("$1[redacted]@");
        out = SENSITIVE_PARAM.matcher(out).replaceAll("$1$2=[redacted]");
        // A long run of letters and digits mixed together is a token (for example the secret part of a webhook path).
        java.util.regex.Matcher m = OPAQUE_SEGMENT.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String run = m.group();
            boolean mixed = run.chars().anyMatch(Character::isDigit) && run.chars().anyMatch(Character::isLetter);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(mixed ? "[redacted]" : run));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String clipped(String text) {
        String line = text.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        return line.length() > MAX_STRING_IN_SUMMARY ? line.substring(0, MAX_STRING_IN_SUMMARY - 1) + "…" : line;
    }

    // ---------------------------------------------------------------- validation of the reply

    /**
     * Validates the model reply against the current draft.
     *
     * @param current the stored draft revision the request was made for
     * @param context the workspace compile context (null in pure tests: binding rules are then skipped)
     */
    public Result evaluate(JsonNode reply, JsonNode current, Mode mode, CompileContext context) {
        return evaluate(reply, current, mode, context, List.of());
    }

    /** @param connections the workspace's registered connections, for {@code addCall} (S124) */
    public Result evaluate(JsonNode reply, JsonNode current, Mode mode, CompileContext context,
            List<DraftRules.ConnectionFact> connections) {
        this.connectionFacts.set(latest(connections));
        try {
            return evaluateWith(reply, current, mode, context);
        } finally {
            this.connectionFacts.remove();
        }
    }

    private final ThreadLocal<List<DraftRules.ConnectionFact>> connectionFacts = ThreadLocal.withInitial(List::of);

    private Result evaluateWith(JsonNode reply, JsonNode current, Mode mode, CompileContext context) {
        String message = optionalText(reply.path("message"));
        List<String> questions = new ArrayList<>();
        for (JsonNode question : reply.path("questions")) {
            String text = plain(question.asString(""));
            if (text.length() > MAX_QUESTION) {
                text = text.substring(0, MAX_QUESTION - 1) + "…";
            }
            if (!text.isEmpty() && questions.size() < MAX_QUESTIONS) {
                questions.add(text);
            }
        }
        String notAnEdit = optionalText(reply.path("notAnEdit"));
        List<String> notes = new ArrayList<>();
        for (JsonNode note : reply.path("notes")) {
            String text = plain(note.asString(""));
            if (!text.isEmpty() && notes.size() < MAX_NOTES) {
                notes.add(text);
            }
        }
        List<JsonNode> raws = new ArrayList<>();
        reply.path("changes").forEach(raws::add);
        if (mode == Mode.REGENERATE && !raws.isEmpty()) {
            // A regeneration is one change: the whole new draft is reviewed and applied together.
            ObjectNode merged = json.object();
            merged.put("summary", message != null ? message : "Regenerate the draft");
            ArrayNode operations = merged.putArray("operations");
            raws.forEach(raw -> raw.path("operations").forEach(op -> operations.add(op.deepCopy())));
            raws = List.of(merged);
        }
        ObjectNode candidate = mode == Mode.REGENERATE ? emptied(current) : (ObjectNode) current.deepCopy();
        Map<String, String> baseline = blockers(candidate, context);
        List<Change> changes = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        Map<String, String> creators = new HashMap<>();
        Map<String, String> modifiers = new HashMap<>();
        int limit = mode == Mode.REGENERATE ? MAX_REGENERATE_OPERATIONS : MAX_OPERATIONS_PER_CHANGE;
        int index = 0;
        for (JsonNode raw : raws) {
            String summary = plain(raw.path("summary").asString(""));
            if (summary.isEmpty()) {
                summary = "Change " + (index + 1);
            }
            if (index++ >= MAX_CHANGES) {
                rejected.add(new Rejected(summary, List.of("Only " + MAX_CHANGES + " changes are reviewed at a time.")));
                continue;
            }
            JsonNode operations = raw.path("operations");
            if (!operations.isArray() || operations.isEmpty()) {
                rejected.add(new Rejected(summary, List.of("The change has no operations.")));
                continue;
            }
            if (operations.size() > limit) {
                rejected.add(new Rejected(summary, List.of("A change has at most " + limit + " operations.")));
                continue;
            }
            ObjectNode next = candidate.deepCopy();
            List<ObjectNode> accepted = new ArrayList<>();
            Set<String> needsCreated = new LinkedHashSet<>();
            Set<String> needsUnchanged = new LinkedHashSet<>();
            Set<String> touched = new LinkedHashSet<>();
            Set<String> created = new LinkedHashSet<>();
            String problem = null;
            int position = 0;
            for (JsonNode op : operations) {
                position++;
                Applied applied = apply(next, op);
                if (applied.problem() != null) {
                    problem = "Operation " + position + " (" + clip(op.path("op").asString("?")) + "): "
                            + applied.problem();
                    break;
                }
                accepted.add(applied.operation());
                needsCreated.addAll(applied.needsCreated());
                needsUnchanged.addAll(applied.needsUnchanged());
                touched.addAll(applied.touched());
                created.addAll(applied.created());
            }
            if (problem != null) {
                rejected.add(new Rejected(summary, List.of(problem)));
                continue;
            }
            Map<String, String> after = blockers(next, context);
            List<String> added = new ArrayList<>();
            for (Map.Entry<String, String> issue : after.entrySet()) {
                if (!baseline.containsKey(issue.getKey()) && added.size() < 5) {
                    added.add(issue.getValue());
                }
            }
            if (!added.isEmpty()) {
                rejected.add(new Rejected(summary, List.copyOf(added)));
                continue;
            }
            String id = "c" + (changes.size() + 1);
            Set<String> depends = new LinkedHashSet<>();
            needsCreated.forEach(ref -> {
                if (creators.containsKey(ref) && !created.contains(ref)) {
                    depends.add(creators.get(ref));
                }
            });
            needsUnchanged.forEach(ref -> {
                if (modifiers.containsKey(ref) && !created.contains(ref)) {
                    depends.add(modifiers.get(ref));
                }
            });
            created.forEach(ref -> creators.put(ref, id));
            touched.forEach(ref -> modifiers.put(ref, id));
            candidate = next;
            baseline = after;
            changes.add(new Change(id, summary, List.copyOf(accepted), List.copyOf(depends)));
        }
        ObjectNode definition = null;
        if (mode == Mode.REGENERATE && !changes.isEmpty()) {
            definition = candidate;
            if (definition.path("uiLayout").isObject()) {
                ((ObjectNode) definition.get("uiLayout")).putObject("positions");
            }
            DraftAuthoring.ensureLayout(definition);
        }
        return new Result(message, List.copyOf(changes), List.copyOf(rejected), List.copyOf(notes), notAnEdit,
                definition, List.copyOf(questions));
    }

    /**
     * Outcome of one operation. {@code needsCreated}: references that must exist (a change that created them comes
     * first); {@code needsUnchanged}: elements whose current state the operation expects; {@code touched}: elements it
     * changes; {@code created}: elements it adds. References are "node:id", "route:id" and "field:key".
     */
    private record Applied(ObjectNode operation, String problem, Set<String> needsCreated, Set<String> needsUnchanged,
            Set<String> touched, Set<String> created) {

        static Applied fail(String problem) {
            return new Applied(null, problem, Set.of(), Set.of(), Set.of(), Set.of());
        }
    }

    private Applied apply(ObjectNode doc, JsonNode op) {
        if (!op.isObject()) {
            return Applied.fail("Every operation must be an object.");
        }
        String name = op.path("op").asString("");
        if (!OPERATIONS.contains(name)) {
            return Applied.fail("Unknown operation; use one of " + String.join(", ", OPERATIONS.stream().sorted()
                    .toList()) + ".");
        }
        if (json.write(op).length() > MAX_OPERATION_CHARS) {
            return Applied.fail("The operation is too large.");
        }
        if (hasExpressionMarker(op)) {
            return Applied.fail("Texts must not contain \"${\" or \"#{\".");
        }
        return switch (name) {
            case "addStep" -> addStep(doc, op);
            case "updateStep" -> updateStep(doc, op);
            case "renameStep" -> renameStep(doc, op);
            case "removeStep" -> removeStep(doc, op);
            case "connect" -> connect(doc, op);
            case "disconnect" -> disconnect(doc, op);
            case "setFieldRule" -> setFieldRule(doc, op);
            case "setStepFieldAccess" -> setStepFieldAccess(doc, op);
            case "setFormGroups" -> setFormGroups(doc, op);
            case "addDecision" -> addDecision(doc, op);
            case "addCall" -> addCall(doc, op);
            default -> changeFormField(doc, op);
        };
    }

    // ---------------------------------------------------------------- S124 operations

    /** Shows a question only when, or makes it required when, a condition holds ({@code inputUi.visibleWhen/requiredWhen}). */
    private Applied setFieldRule(ObjectNode doc, JsonNode op) {
        String key = op.path("key").asString("");
        String rule = op.path("rule").asString("");
        if (!Set.of("visibleWhen", "requiredWhen").contains(rule)) {
            return Applied.fail("The rule must be visibleWhen or requiredWhen.");
        }
        if (!doc.path("inputSchema").path("properties").has(key)) {
            return Applied.fail("There is no question with the key \"" + clip(key) + "\".");
        }
        JsonNode condition = op.path("condition");
        if (!condition.isNull() && !condition.isObject()) {
            return Applied.fail("The condition must be an object, or null to remove the rule.");
        }
        ObjectNode ui = doc.path("inputUi").isObject() ? (ObjectNode) doc.get("inputUi") : doc.putObject("inputUi");
        ObjectNode rules = ui.path(rule).isObject() ? (ObjectNode) ui.get(rule) : ui.putObject(rule);
        JsonNode before = rules.has(key) ? rules.get(key).deepCopy() : json.object().nullNode();
        if (condition.isNull() && before.isNull()) {
            return Applied.fail("The question has no such rule to remove.");
        }
        if (condition.equals(before)) {
            return Applied.fail("The question already has this rule.");
        }
        if (condition.isNull()) {
            rules.remove(key);
        } else {
            rules.set(key, condition.deepCopy());
        }
        if (rules.isEmpty()) {
            ui.remove(rule);
        }
        ObjectNode normalized = json.object();
        normalized.put("op", "setFieldRule").put("key", key).put("rule", rule);
        normalized.set("condition", condition.deepCopy());
        normalized.set("before", before);
        String ref = "rule:" + rule + ":" + key;
        return new Applied(normalized, null, Set.of("field:" + key), Set.of(ref), Set.of(ref), Set.of());
    }

    /**
     * Sets a step form (S123): field access per request field (null back to read only) and, when given, the fields the
     * step captures (null removes them). Normalized as the resulting settings patch, like {@code updateStep}.
     */
    private Applied setStepFieldAccess(ObjectNode doc, JsonNode op) {
        String id = op.path("id").asString("");
        ObjectNode node = node(doc, id);
        if (node == null || !"approval".equals(node.path("type").asString())) {
            return Applied.fail("Step forms are set on approval steps; \"" + clip(id) + "\" is not one.");
        }
        JsonNode access = op.path("fieldAccess");
        boolean hasCapture = op.has("capture");
        if (!access.isMissingNode() && !access.isObject()) {
            return Applied.fail("fieldAccess must be an object of question keys to hidden, readOnly, editable or required.");
        }
        if (access.isMissingNode() && !hasCapture) {
            return Applied.fail("Give fieldAccess, capture, or both.");
        }
        ObjectNode before = node.deepCopy();
        ObjectNode config = node.path("config").isObject() ? (ObjectNode) node.get("config") : node.putObject("config");
        ObjectNode patch = json.object();
        if (access.isObject()) {
            ObjectNode merged = config.path("fieldAccess").isObject() ? (ObjectNode) config.get("fieldAccess").deepCopy() : json.object();
            for (Map.Entry<String, JsonNode> e : access.properties()) {
                String value = e.getValue().isNull() ? "readOnly" : e.getValue().asString("");
                if (!FIELD_ACCESS.contains(value)) {
                    return Applied.fail("Field access is hidden, readOnly, editable or required.");
                }
                if (value.equals("readOnly")) {
                    merged.remove(e.getKey());
                } else {
                    merged.put(e.getKey(), value);
                }
            }
            if (merged.isEmpty()) {
                config.remove("fieldAccess");
                patch.putNull("fieldAccess");
            } else {
                config.set("fieldAccess", merged);
                patch.set("fieldAccess", merged.deepCopy());
            }
        }
        if (hasCapture) {
            JsonNode capture = op.get("capture");
            if (capture.isNull()) {
                config.remove("capture");
                patch.putNull("capture");
            } else if (capture.isObject()) {
                ObjectNode schema = (ObjectNode) capture.deepCopy();
                schema.put("type", "object").put("additionalProperties", false);
                if (!schema.path("properties").isObject()) {
                    return Applied.fail("capture needs properties: the fields the step fills in.");
                }
                config.set("capture", schema);
                patch.set("capture", schema.deepCopy());
            } else {
                return Applied.fail("capture must be a form (an object) or null.");
            }
        }
        if (before.equals(node)) {
            return Applied.fail("The step already has this step form.");
        }
        ObjectNode normalized = json.object();
        normalized.put("op", "setStepFieldAccess").put("id", id);
        normalized.set("config", patch);
        normalized.set("before", before);
        return new Applied(normalized, null, Set.of(), Set.of("node:" + id), Set.of("node:" + id), Set.of());
    }

    /** Replaces the form's groups ({@code inputUi.layout.sections}); an empty list removes them. */
    private Applied setFormGroups(ObjectNode doc, JsonNode op) {
        JsonNode groups = op.path("groups");
        if (!groups.isArray()) {
            return Applied.fail("groups must be a list of {id, title, fields}.");
        }
        ObjectNode ui = doc.path("inputUi").isObject() ? (ObjectNode) doc.get("inputUi") : doc.putObject("inputUi");
        ObjectNode layout = ui.path("layout").isObject() ? (ObjectNode) ui.get("layout") : ui.putObject("layout");
        JsonNode before = layout.has("sections") ? layout.get("sections").deepCopy() : json.object().nullNode();
        if (groups.isEmpty()) {
            if (before.isNull()) {
                return Applied.fail("The form has no groups to remove.");
            }
            layout.remove("sections");
            if (layout.isEmpty()) {
                ui.remove("layout");
            }
        } else {
            if (groups.equals(before)) {
                return Applied.fail("The form already has these groups.");
            }
            layout.set("sections", groups.deepCopy());
        }
        ObjectNode normalized = json.object();
        normalized.put("op", "setFormGroups");
        normalized.set("groups", groups.deepCopy());
        normalized.set("before", before);
        return new Applied(normalized, null, Set.of(), Set.of("groups"), Set.of("groups"), Set.of());
    }

    /** Adds a decision step with its own rule table (route it with {@code connect ... on next}). */
    private Applied addDecision(ObjectNode doc, JsonNode op) {
        String id = op.path("id").asString("");
        String label = plain(op.path("label").asString(""));
        JsonNode table = op.path("table");
        if (!NODE_ID.matcher(id).matches()) {
            return Applied.fail("The step id must start with a letter and use letters, digits, - and _ only.");
        }
        if (idInUse(doc, id)) {
            return Applied.fail("The id \"" + id + "\" is already used by a step or connection.");
        }
        if (label.isEmpty() || label.length() > MAX_LABEL) {
            return Applied.fail("The step needs a label of 1-" + MAX_LABEL + " characters.");
        }
        if (!table.isObject()) {
            return Applied.fail("The decision needs a rule table.");
        }
        String key = table.path("key").asString("");
        if (!TABLE_KEY.matcher(key).matches()) {
            return Applied.fail("A rule table key uses lowercase letters, digits and -, such as risk-policy.");
        }
        for (JsonNode existing : doc.path("decisions")) {
            if (key.equals(existing.path("key").asString())) {
                return Applied.fail("A rule table with the key \"" + key + "\" already exists.");
            }
        }
        ObjectNode copy = (ObjectNode) table.deepCopy();
        if (!copy.has("version")) {
            copy.put("version", 1);
        }
        if (!copy.has("hitPolicy")) {
            copy.put("hitPolicy", "UNIQUE");
        }
        array(doc, "decisions").add(copy);
        ObjectNode node = array(doc, "nodes").addObject();
        node.put("id", id).put("type", "decision").put("label", label).putObject("config").put("decisionRef", key);
        ObjectNode normalized = json.object();
        normalized.put("op", "addDecision").put("id", id).put("label", label);
        normalized.set("table", copy.deepCopy());
        return new Applied(normalized, null, Set.of(), Set.of(), Set.of("node:" + id, "table:" + key),
                Set.of("node:" + id, "table:" + key));
    }

    /**
     * Adds a call to a system: an http step on a registered connection's operation, pinned to the latest registered
     * version (the dependency is added when the workflow does not list the connection yet). The compiler then checks the
     * operation, the mappings, the binding and the time limit.
     */
    private Applied addCall(ObjectNode doc, JsonNode op) {
        String id = op.path("id").asString("");
        String label = plain(op.path("label").asString(""));
        String connectionRef = op.path("connectionRef").asString("");
        String operation = op.path("operation").asString("");
        if (!NODE_ID.matcher(id).matches()) {
            return Applied.fail("The step id must start with a letter and use letters, digits, - and _ only.");
        }
        if (idInUse(doc, id)) {
            return Applied.fail("The id \"" + id + "\" is already used by a step or connection.");
        }
        if (label.isEmpty() || label.length() > MAX_LABEL) {
            return Applied.fail("The step needs a label of 1-" + MAX_LABEL + " characters.");
        }
        DraftRules.ConnectionFact connection = connectionFacts.get().stream()
                .filter(c -> c.key().equals(connectionRef)).findFirst().orElse(null);
        if (connection == null) {
            return Applied.fail("There is no connection \"" + clip(connectionRef) + "\" in this workspace.");
        }
        JsonNode definition = connection.document().path("operations").path(operation);
        if (!definition.isObject()) {
            return Applied.fail("The connection \"" + connectionRef + "\" has no operation \"" + clip(operation) + "\".");
        }
        for (String name : List.of("inputMapping", "resultMapping")) {
            JsonNode mapping = op.path(name);
            if (!mapping.isMissingNode() && !mapping.isNull() && !mapping.isObject()) {
                return Applied.fail(name + " must be an object of names to field references.");
            }
            for (JsonNode value : mapping) {
                if (!value.isString()) {
                    return Applied.fail(name + " values are field references such as /input/amount.");
                }
            }
        }
        int max = definition.path("maxTimeoutSeconds").asInt(30);
        int timeout = op.path("timeoutSeconds").isIntegralNumber() ? op.get("timeoutSeconds").intValue() : Math.min(20, max);
        ObjectNode config = json.object();
        config.put("connectionRef", connectionRef).put("operation", operation);
        config.set("inputMapping", op.path("inputMapping").isObject() ? op.get("inputMapping").deepCopy() : json.object());
        config.set("resultMapping", op.path("resultMapping").isObject() ? op.get("resultMapping").deepCopy() : json.object());
        config.put("timeoutSeconds", timeout);
        ObjectNode node = array(doc, "nodes").addObject();
        node.put("id", id).put("type", "http").put("label", label).set("config", config);
        ObjectNode dependencies = doc.path("dependencies").isObject() ? (ObjectNode) doc.get("dependencies")
                : doc.putObject("dependencies");
        ArrayNode pinned = array(dependencies, "connections");
        int version = connection.version();
        boolean added = true;
        for (JsonNode dependency : pinned) {
            if (connectionRef.equals(dependency.path("key").asString())) {
                version = dependency.path("definitionVersion").asInt(version);
                added = false;
            }
        }
        if (added) {
            pinned.addObject().put("key", connectionRef).put("definitionVersion", version);
        }
        ObjectNode normalized = json.object();
        normalized.put("op", "addCall").put("id", id).put("label", label);
        normalized.set("config", config.deepCopy());
        normalized.put("definitionVersion", version).put("addsDependency", added);
        return new Applied(normalized, null, Set.of(), Set.of(), Set.of("node:" + id), Set.of("node:" + id));
    }

    private Applied addStep(ObjectNode doc, JsonNode op) {
        String id = op.path("id").asString("");
        String kind = op.path("kind").asString("");
        String label = plain(op.path("label").asString(""));
        if (!NODE_ID.matcher(id).matches()) {
            return Applied.fail("The step id must start with a letter and use letters, digits, - and _ only.");
        }
        if (idInUse(doc, id)) {
            return Applied.fail("The id \"" + id + "\" is already used by a step or connection.");
        }
        if (!ADDABLE_KINDS.contains(kind)) {
            return Applied.fail("Steps of kind \"" + clip(kind) + "\" cannot be added here; add one of "
                    + String.join(", ", ADDABLE_KINDS.stream().sorted().toList()) + ", or use the step list.");
        }
        if (label.isEmpty() || label.length() > MAX_LABEL) {
            return Applied.fail("The step needs a label of 1-" + MAX_LABEL + " characters.");
        }
        JsonNode config = op.path("config");
        if (!config.isMissingNode() && !config.isNull() && !config.isObject()) {
            return Applied.fail("The settings must be an object.");
        }
        ObjectNode merged = defaults(kind);
        if (config.isObject()) {
            for (Map.Entry<String, JsonNode> entry : config.properties()) {
                String problem = settingProblem(entry.getKey());
                if (problem != null) {
                    return Applied.fail(problem);
                }
                if (!entry.getValue().isNull()) {
                    merged.set(entry.getKey(), entry.getValue().deepCopy());
                }
            }
        }
        ObjectNode node = array(doc, "nodes").addObject();
        node.put("id", id).put("type", kind).put("label", label).set("config", merged);
        ObjectNode normalized = json.object();
        normalized.put("op", "addStep").put("id", id).put("kind", kind).put("label", label);
        normalized.set("config", merged.deepCopy());
        return new Applied(normalized, null, Set.of(), Set.of(), Set.of("node:" + id), Set.of("node:" + id));
    }

    private Applied updateStep(ObjectNode doc, JsonNode op) {
        String id = op.path("id").asString("");
        ObjectNode node = node(doc, id);
        if (node == null) {
            return Applied.fail("There is no step \"" + clip(id) + "\".");
        }
        if ("start".equals(node.path("type").asString())) {
            return Applied.fail("The request form step has no settings to change; change form questions instead.");
        }
        JsonNode patch = op.path("config");
        if (!patch.isObject() || patch.isEmpty()) {
            return Applied.fail("Name at least one setting to change.");
        }
        ObjectNode before = node.deepCopy();
        if (!node.path("config").isObject()) {
            node.putObject("config");
        }
        ObjectNode config = (ObjectNode) node.get("config");
        for (Map.Entry<String, JsonNode> entry : patch.properties()) {
            String problem = settingProblem(entry.getKey());
            if (problem != null) {
                return Applied.fail(problem);
            }
            if (entry.getValue().isNull()) {
                config.remove(entry.getKey());
            } else {
                config.set(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        if (before.equals(node)) {
            return Applied.fail("The step already has these settings.");
        }
        ObjectNode normalized = json.object();
        normalized.put("op", "updateStep").put("id", id);
        normalized.set("config", patch.deepCopy());
        normalized.set("before", before);
        return new Applied(normalized, null, Set.of(), Set.of("node:" + id), Set.of("node:" + id), Set.of());
    }

    private Applied renameStep(ObjectNode doc, JsonNode op) {
        String id = op.path("id").asString("");
        ObjectNode node = node(doc, id);
        if (node == null) {
            return Applied.fail("There is no step \"" + clip(id) + "\".");
        }
        String label = plain(op.path("label").asString(""));
        if (label.isEmpty() || label.length() > MAX_LABEL) {
            return Applied.fail("The new label must be 1-" + MAX_LABEL + " characters.");
        }
        if (label.equals(node.path("label").asString(""))) {
            return Applied.fail("The step already has this label.");
        }
        ObjectNode before = node.deepCopy();
        node.put("label", label);
        ObjectNode normalized = json.object();
        normalized.put("op", "renameStep").put("id", id).put("label", label);
        normalized.set("before", before);
        return new Applied(normalized, null, Set.of(), Set.of("node:" + id), Set.of("node:" + id), Set.of());
    }

    private Applied removeStep(ObjectNode doc, JsonNode op) {
        String id = op.path("id").asString("");
        ObjectNode node = node(doc, id);
        if (node == null) {
            return Applied.fail("There is no step \"" + clip(id) + "\".");
        }
        if ("start".equals(node.path("type").asString())) {
            return Applied.fail("The request form step cannot be removed.");
        }
        ObjectNode before = node.deepCopy();
        Set<String> touched = new LinkedHashSet<>();
        touched.add("node:" + id);
        for (JsonNode transition : doc.path("transitions")) {
            if (id.equals(transition.path("from").asString()) || id.equals(transition.path("to").asString())) {
                touched.add("route:" + transition.path("id").asString(""));
            }
        }
        removeNode(doc, node);
        ObjectNode normalized = json.object();
        normalized.put("op", "removeStep").put("id", id);
        normalized.set("before", before);
        return new Applied(normalized, null, Set.of(), Set.of("node:" + id), touched, Set.of());
    }

    private Applied connect(ObjectNode doc, JsonNode op) {
        String from = op.path("from").asString("");
        String to = op.path("to").asString("");
        String on = op.path("on").asString("");
        if (node(doc, from) == null) {
            return Applied.fail("There is no step \"" + clip(from) + "\" to connect from.");
        }
        if (node(doc, to) == null) {
            return Applied.fail("There is no step \"" + clip(to) + "\" to connect to.");
        }
        if (!ROUTES.contains(on)) {
            return Applied.fail("The route must be one of " + String.join(", ", ROUTES.stream().sorted().toList())
                    + ".");
        }
        for (JsonNode transition : doc.path("transitions")) {
            if (from.equals(transition.path("from").asString()) && to.equals(transition.path("to").asString())
                    && on.equals(transition.path("on").asString())) {
                return Applied.fail("These steps are already connected this way.");
            }
        }
        JsonNode condition = op.path("condition");
        if ("condition".equals(on) && !condition.isObject()) {
            return Applied.fail("A \"condition\" route needs a condition.");
        }
        String label = op.path("label").isString() ? plain(op.path("label").asString()) : "";
        if (label.length() > MAX_LABEL) {
            return Applied.fail("A route label has at most " + MAX_LABEL + " characters.");
        }
        String transitionId = nextTransitionId(doc);
        ObjectNode transition = array(doc, "transitions").addObject();
        transition.put("id", transitionId).put("from", from).put("to", to).put("on", on);
        if (!label.isEmpty()) {
            transition.put("label", label);
        }
        if ("condition".equals(on)) {
            transition.set("condition", condition.deepCopy());
        }
        ObjectNode normalized = transition.deepCopy();
        normalized.put("transitionId", transitionId);
        normalized.remove("id");
        normalized.put("op", "connect");
        return new Applied(normalized, null, Set.of("node:" + from, "node:" + to), Set.of(),
                Set.of("route:" + transitionId), Set.of("route:" + transitionId));
    }

    private Applied disconnect(ObjectNode doc, JsonNode op) {
        String transitionId = op.path("transitionId").asString("");
        ArrayNode transitions = array(doc, "transitions");
        for (int i = 0; i < transitions.size(); i++) {
            if (transitionId.equals(transitions.get(i).path("id").asString())) {
                ObjectNode before = (ObjectNode) transitions.get(i).deepCopy();
                transitions.remove(i);
                ObjectNode normalized = json.object();
                normalized.put("op", "disconnect").put("transitionId", transitionId);
                normalized.set("before", before);
                return new Applied(normalized, null, Set.of(), Set.of("route:" + transitionId),
                        Set.of("route:" + transitionId), Set.of());
            }
        }
        return Applied.fail("There is no connection \"" + clip(transitionId) + "\".");
    }

    private Applied changeFormField(ObjectNode doc, JsonNode op) {
        String action = op.path("action").asString("");
        String key = op.path("key").asString("");
        if (!Set.of("add", "update", "remove").contains(action)) {
            return Applied.fail("The form change must be add, update or remove.");
        }
        if (!FIELD_KEY.matcher(key).matches()) {
            return Applied.fail("A question key starts with a lowercase letter and uses letters and digits only.");
        }
        ObjectNode schema = doc.path("inputSchema").isObject() ? (ObjectNode) doc.get("inputSchema")
                : doc.putObject("inputSchema");
        if (!schema.path("properties").isObject()) {
            schema.putObject("properties");
        }
        ObjectNode properties = (ObjectNode) schema.get("properties");
        boolean exists = properties.has(key);
        if ("add".equals(action) && exists) {
            return Applied.fail("A question with the key \"" + key + "\" already exists.");
        }
        if (!"add".equals(action) && !exists) {
            return Applied.fail("There is no question with the key \"" + key + "\".");
        }
        JsonNode field = op.path("field");
        if (!"remove".equals(action) && !field.isObject()) {
            return Applied.fail("The change does not describe the question.");
        }
        if ("add".equals(action) && properties.size() >= FormSuggestions.MAX_FIELDS) {
            return Applied.fail("A request form has at most " + FormSuggestions.MAX_FIELDS + " questions.");
        }
        JsonNode before = exists ? properties.get(key).deepCopy() : null;
        boolean wasRequired = isRequired(schema, key);
        ArrayNode required = schema.path("required").isArray() ? (ArrayNode) schema.get("required")
                : schema.putArray("required");
        removeString(required, key);
        boolean isRequired;
        ObjectNode ui = doc.path("inputUi").isObject() ? (ObjectNode) doc.get("inputUi") : null;
        if ("remove".equals(action)) {
            properties.remove(key);
            isRequired = false;
            if (ui != null) {
                if (ui.path("order").isArray()) {
                    removeString((ArrayNode) ui.get("order"), key);
                }
                for (String name : List.of("widgets", "visibleWhen", "requiredWhen")) {
                    if (ui.path(name).isObject()) {
                        ((ObjectNode) ui.get(name)).remove(key);
                    }
                }
                for (JsonNode section : ui.path("layout").path("sections")) {
                    if (section.path("fields").isArray()) {
                        removeString((ArrayNode) section.get("fields"), key);
                    }
                }
            }
        } else {
            properties.set(key, field.deepCopy());
            JsonNode requiredNode = op.path("required");
            isRequired = requiredNode.isBoolean() ? requiredNode.booleanValue() : wasRequired;
            if (isRequired) {
                required.add(key);
            }
            if (ui != null) {
                if (ui.path("order").isArray() && !containsString(ui.get("order"), key)) {
                    ((ArrayNode) ui.get("order")).add(key);
                }
                if ("update".equals(action) && ui.path("widgets").isObject()) {
                    ((ObjectNode) ui.get("widgets")).remove(key);
                }
            }
        }
        ObjectNode normalized = json.object();
        normalized.put("op", "changeFormField").put("action", action).put("key", key);
        if (!"remove".equals(action)) {
            normalized.set("field", field.deepCopy());
        }
        normalized.put("required", isRequired);
        if (before != null) {
            normalized.set("before", before);
            normalized.put("wasRequired", wasRequired);
        }
        String ref = "field:" + key;
        return "add".equals(action)
                ? new Applied(normalized, null, Set.of(), Set.of(), Set.of(ref), Set.of(ref))
                : new Applied(normalized, null, Set.of(), Set.of(ref), Set.of(ref), Set.of());
    }

    // ---------------------------------------------------------------- document helpers

    /** Default settings of a new step, the same as the studio's step list (frontend model/kinds.ts). */
    ObjectNode defaults(String kind) {
        ObjectNode config = json.object();
        switch (kind) {
            case "approval" -> config.put("candidateGroupRef", "").put("rejectReasonRequired", true);
            case "notification" -> config.put("channel", "inApp").put("recipient", "initiator").put("message", "");
            case "wait" -> config.put("duration", "PT1H");
            case "end" -> config.put("businessOutcome", "Completed").put("outcomeKind", "success");
            default -> {
                // branch: no settings
            }
        }
        return config;
    }

    private static String settingProblem(String key) {
        if (!CONFIG_KEY.matcher(key).matches()) {
            return "Setting names use letters and digits only.";
        }
        if (SENSITIVE.matcher(key).matches()) {
            return "The setting \"" + clip(key) + "\" cannot be changed by the assistant.";
        }
        return null;
    }

    /** The draft a regeneration starts from: the request form and workflow settings, with only the start step. */
    ObjectNode emptied(JsonNode current) {
        ObjectNode doc = (ObjectNode) current.deepCopy();
        ObjectNode start = null;
        for (JsonNode node : doc.path("nodes")) {
            if ("start".equals(node.path("type").asString())) {
                start = (ObjectNode) node.deepCopy();
                break;
            }
        }
        if (start == null) {
            start = json.object();
            start.put("id", "start").put("type", "start").put("label", "Request submitted").putObject("config");
        }
        doc.putArray("nodes").add(start);
        doc.putArray("transitions");
        doc.putArray("decisions");
        doc.remove("stages");
        doc.remove("subprocesses");
        doc.putObject("outputMapping");
        ObjectNode output = doc.putObject("outputSchema");
        output.put("type", "object").put("additionalProperties", false);
        output.putObject("properties");
        ObjectNode dependencies = doc.putObject("dependencies");
        dependencies.putArray("connections");
        if (doc.path("metadata").isObject()) {
            ((ObjectNode) doc.get("metadata")).remove("stepNotes");
        }
        doc.putObject("uiLayout").putObject("positions");
        return doc;
    }

    /** Mirrors the studio's removeNode (frontend model/ops.ts), so both sides produce the same document. */
    private void removeNode(ObjectNode doc, ObjectNode node) {
        String id = node.path("id").asString();
        String type = node.path("type").asString();
        filter(array(doc, "nodes"), n -> id.equals(n.path("id").asString()));
        filter(array(doc, "transitions"),
                t -> id.equals(t.path("from").asString()) || id.equals(t.path("to").asString()));
        for (JsonNode stage : doc.path("stages")) {
            if (stage.path("nodes").isArray()) {
                removeString((ArrayNode) stage.get("nodes"), id);
            }
        }
        for (JsonNode scope : doc.path("subprocesses")) {
            if (!(scope instanceof ObjectNode object)) {
                continue;
            }
            if (object.path("nodes").isArray()) {
                removeString((ArrayNode) object.get("nodes"), id);
            }
            if (id.equals(object.path("onTimeout").asString(null))) {
                object.remove("onTimeout");
                object.remove("timeoutAfter");
            }
            if (id.equals(object.path("onError").path("to").asString(null))) {
                object.remove("onError");
            }
        }
        if (doc.path("uiLayout").path("positions").isObject()) {
            ((ObjectNode) doc.get("uiLayout").get("positions")).remove(id);
        }
        JsonNode notes = doc.path("metadata").path("stepNotes");
        if (notes.isObject()) {
            ((ObjectNode) notes).remove(id);
            if (notes.isEmpty()) {
                ((ObjectNode) doc.get("metadata")).remove("stepNotes");
            }
        }
        Set<String> droppedDecisions = new HashSet<>();
        if ("decision".equals(type)) {
            String ref = node.path("config").path("decisionRef").asString(null);
            boolean shared = false;
            for (JsonNode other : doc.path("nodes")) {
                if ("decision".equals(other.path("type").asString())
                        && ref != null && ref.equals(other.path("config").path("decisionRef").asString(null))) {
                    shared = true;
                }
            }
            if (ref != null && !shared) {
                droppedDecisions.add(ref);
                filter(array(doc, "decisions"), d -> ref.equals(d.path("key").asString()));
            }
        }
        JsonNode assets = doc.path("dependencies").path("assets");
        if (assets.isArray()) {
            filter((ArrayNode) assets, a -> "decision".equals(a.path("kind").asString())
                    ? droppedDecisions.contains(a.path("target").asString())
                    : id.equals(a.path("target").asString()));
            if (assets.isEmpty()) {
                ((ObjectNode) doc.get("dependencies")).remove("assets");
            }
        }
        String prefix = "/steps/" + id + "/";
        if (doc.path("outputMapping").isObject()) {
            ObjectNode mapping = (ObjectNode) doc.get("outputMapping");
            List<String> drop = new ArrayList<>();
            mapping.properties().forEach(e -> {
                if (e.getValue().path("fieldRef").asString("").startsWith(prefix)) {
                    drop.add(e.getKey());
                }
            });
            drop.forEach(mapping::remove);
        }
        if ("http".equals(type)) {
            String key = node.path("config").path("connectionRef").asString(null);
            boolean used = false;
            for (JsonNode other : doc.path("nodes")) {
                if ("http".equals(other.path("type").asString())
                        && key != null && key.equals(other.path("config").path("connectionRef").asString(null))) {
                    used = true;
                }
            }
            JsonNode connections = doc.path("dependencies").path("connections");
            if (key != null && !used && connections.isArray()) {
                filter((ArrayNode) connections, c -> key.equals(c.path("key").asString()));
            }
        }
    }

    /** Blockers of a candidate by identity (code and the element it is about), with their messages. */
    private Map<String, String> blockers(ObjectNode doc, CompileContext context) {
        ValidationReport report = compiler.validate(json.write(doc), context);
        Map<String, String> found = new LinkedHashMap<>();
        for (ValidationIssue issue : report.issues()) {
            if (issue.severity() != Severity.BLOCKER) {
                continue;
            }
            String anchor = issue.nodeId() != null ? "node:" + issue.nodeId()
                    : issue.transitionId() != null ? "route:" + issue.transitionId()
                    : normalizedPath(issue.path() == null ? "" : issue.path(), doc);
            found.putIfAbsent(issue.code() + " " + anchor, issue.message());
        }
        return found;
    }

    /** Array positions in a path are replaced by the element's id, so removing a step does not renumber findings. */
    private static String normalizedPath(String path, JsonNode doc) {
        Matcher matcher = INDEXED.matcher(path);
        if (!matcher.find()) {
            return path;
        }
        String container = matcher.group(1);
        JsonNode element = doc.path(container).path(Integer.parseInt(matcher.group(2)));
        String id = element.path("decisions".equals(container) ? "key" : "id").asString("?");
        return "/" + container + "/" + id + path.substring(matcher.end());
    }

    private static ArrayNode array(ObjectNode doc, String name) {
        return doc.path(name).isArray() ? (ArrayNode) doc.get(name) : doc.putArray(name);
    }

    private static ObjectNode node(ObjectNode doc, String id) {
        for (JsonNode node : doc.path("nodes")) {
            if (id.equals(node.path("id").asString()) && node instanceof ObjectNode object) {
                return object;
            }
        }
        return null;
    }

    private static boolean idInUse(JsonNode doc, String id) {
        for (String container : List.of("nodes", "transitions")) {
            for (JsonNode element : doc.path(container)) {
                if (id.equals(element.path("id").asString())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The studio's rule for new connection ids (frontend model/ops.ts nextTransitionId). */
    static String nextTransitionId(JsonNode doc) {
        for (int i = doc.path("transitions").size() + 1; i < 10_000; i++) {
            if (!idInUse(doc, "t" + i)) {
                return "t" + i;
            }
        }
        throw new IllegalStateException("No free connection id");
    }

    private static boolean isRequired(JsonNode schema, String key) {
        return containsString(schema.path("required"), key);
    }

    private static boolean containsString(JsonNode array, String value) {
        for (JsonNode item : array) {
            if (value.equals(item.asString(null))) {
                return true;
            }
        }
        return false;
    }

    private static void removeString(ArrayNode array, String value) {
        filter(array, item -> value.equals(item.asString(null)));
    }

    private static void filter(ArrayNode array, java.util.function.Predicate<JsonNode> drop) {
        for (int i = array.size() - 1; i >= 0; i--) {
            if (drop.test(array.get(i))) {
                array.remove(i);
            }
        }
    }

    private static boolean hasExpressionMarker(JsonNode value) {
        if (value.isString()) {
            String text = value.asString();
            return text.contains("${") || text.contains("#{");
        }
        for (JsonNode child : value) {
            if (hasExpressionMarker(child)) {
                return true;
            }
        }
        if (value.isObject()) {
            for (String name : value.propertyNames()) {
                if (name.contains("${") || name.contains("#{")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String optionalText(JsonNode node) {
        if (!node.isString()) {
            return null;
        }
        String text = plain(node.asString());
        return text.isEmpty() ? null : text;
    }

    /** One line of plain text, at most 300 characters; control characters and expression markers removed. */
    static String plain(String text) {
        return FormSuggestions.plain(text);
    }

    private static String clip(String text) {
        return text.length() > 64 ? text.substring(0, 64) : text;
    }
}
