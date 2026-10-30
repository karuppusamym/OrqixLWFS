package com.orqix.catalog.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.orqix.compiler.CanonicalJson;
import com.orqix.compiler.Severity;
import com.orqix.compiler.ValidationIssue;
import com.orqix.compiler.ValidationReport;
import com.orqix.compiler.WorkflowCompiler;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Shared form content (docs/build/S128): request questions in the workflow {@code inputSchema} shape plus their order,
 * widgets, rules ({@code visibleWhen}, {@code requiredWhen}) and groups ({@code layout.sections}) in the {@code inputUi}
 * shape. A version is checked with the publishing compiler on the smallest workflow that holds only this form, so a
 * shared form never holds a question or rule a workflow could not publish.
 * <p>
 * A workflow copies questions into its own request form and records, per copied question, the key it got there
 * (renamed when the workflow already had that key). {@link #projection} reduces either side to the copied questions in
 * the shared form's keys, so the reference check can tell whether the copy still equals the pinned version. Question
 * order and groups are presentation and not compared: a designer may rearrange the form without unlinking it.
 */
final class FormAssets {

    static final int MAX_QUESTIONS = 30;
    private static final Pattern FIELD_KEY = Pattern.compile("^[a-z][A-Za-z0-9]{0,63}$");
    private static final Set<String> CONTENT_KEYS = Set.of("inputSchema", "inputUi");
    private static final Set<String> UI_KEYS = Set.of("order", "widgets", "visibleWhen", "requiredWhen", "layout");
    private static final String INPUT = "/input/";
    /** Marks a condition reference to a question that is not one of the copied ones. */
    private static final String OTHER = "~";

    private FormAssets() {
    }

    /** The checked, normalized content; throws 422 with field errors naming {@code /content/...}. */
    static ObjectNode content(JsonNode content, Json json, WorkflowCompiler compiler) {
        if (content == null || !content.isObject()) {
            throw invalid("content must be an object with inputSchema and, optionally, inputUi.", "/content");
        }
        if (json.write(content).getBytes(StandardCharsets.UTF_8).length > SharedAssets.MAX_CONTENT_BYTES
                || Json.depth(content) > 20) {
            throw invalid("The form is too large.", "/content");
        }
        List<String> problems = new ArrayList<>();
        for (String name : content.propertyNames()) {
            if (!CONTENT_KEYS.contains(name)) {
                problems.add("Unknown property " + name + ".");
            }
        }
        JsonNode schema = content.path("inputSchema");
        JsonNode properties = schema.path("properties");
        if (!schema.isObject() || !properties.isObject() || properties.isEmpty() || properties.size() > MAX_QUESTIONS) {
            problems.add("inputSchema.properties must hold 1 to " + MAX_QUESTIONS + " questions.");
        }
        JsonNode ui = content.path("inputUi");
        if (!ui.isMissingNode() && !ui.isNull()) {
            if (!ui.isObject()) {
                problems.add("inputUi must be an object.");
            } else {
                for (String name : ui.propertyNames()) {
                    if (!UI_KEYS.contains(name)) {
                        problems.add("inputUi." + name + " belongs to the workflow, not to a shared form.");
                    }
                }
                JsonNode layout = ui.path("layout");
                if (layout.isObject()) {
                    for (String name : layout.propertyNames()) {
                        if (!"sections".equals(name)) {
                            problems.add("inputUi.layout." + name + " belongs to the workflow, not to a shared form.");
                        }
                    }
                }
            }
        }
        if (!problems.isEmpty()) {
            throw invalid(String.join(" ", problems), "/content");
        }
        ObjectNode normalized = json.object();
        normalized.set("inputSchema", schema.deepCopy());
        if (ui.isObject() && !ui.isEmpty()) {
            normalized.set("inputUi", ui.deepCopy());
        }
        List<ApiException.FieldError> errors = new ArrayList<>();
        ValidationReport report = compiler.validate(json.write(wrap(normalized, json)), null);
        for (ValidationIssue issue : report.issues()) {
            String path = issue.path() == null ? "" : issue.path();
            if (issue.severity() == Severity.BLOCKER
                    && (path.isEmpty() || path.startsWith("/inputSchema") || path.startsWith("/inputUi"))) {
                errors.add(new ApiException.FieldError("/content" + path, issue.code(), issue.message()));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED,
                    "The shared form has problems a workflow could not publish: " + errors.get(0).message())
                    .withFieldErrors(List.copyOf(errors.subList(0, Math.min(10, errors.size()))));
        }
        return normalized;
    }

    private static ApiException invalid(String message, String path) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, message)
                .withFieldErrors(List.of(new ApiException.FieldError(path, "form", message)));
    }

    /** The smallest publishable workflow around a request form: start, finish, one route. */
    static ObjectNode wrap(ObjectNode content, Json json) {
        ObjectNode doc = json.object();
        doc.put("schemaVersion", "0.1");
        doc.put("workflowKey", "shared-form-check");
        doc.put("name", "Shared form check");
        doc.putObject("metadata").put("description", "");
        doc.set("inputSchema", content.get("inputSchema").deepCopy());
        ObjectNode ui = content.has("inputUi") ? (ObjectNode) content.get("inputUi").deepCopy() : json.object();
        if (ui.path("widgets").isObject()) {
            for (JsonNode widget : ui.path("widgets")) {
                if ("money".equals(widget.asString(""))) {
                    ui.put("currency", "USD");
                    break;
                }
            }
        }
        if (!ui.isEmpty()) {
            doc.set("inputUi", ui);
        }
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

    /** SHA-256 of the RFC 8785 form of the whole normalized content (identical-version check and display). */
    static String hash(JsonNode content, Json json) {
        return CanonicalJson.sha256Hex(json.write(content));
    }

    static int questions(JsonNode content) {
        return content.path("inputSchema").path("properties").size();
    }

    /** The {@code fields} map of a form reference (shared key to workflow key); null when it is not a valid map. */
    static Map<String, String> fields(JsonNode ref) {
        JsonNode fields = ref.path("fields");
        if (!fields.isObject() || fields.isEmpty()) {
            return null;
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (var e : fields.properties()) {
            String target = e.getValue().asString("");
            if (!FIELD_KEY.matcher(e.getKey()).matches() || !FIELD_KEY.matcher(target).matches()) {
                return null;
            }
            map.put(e.getKey(), target);
        }
        return map;
    }

    /**
     * The copied questions of one side, in the shared form's keys: definition, required flag, widget and rules. On the
     * shared side {@code keys} maps each shared key to itself; on the workflow side it maps shared keys to workflow
     * keys. Condition references to questions outside the copy are marked, so a rule that now points at another
     * question of the workflow counts as a change.
     */
    static ObjectNode projection(JsonNode inputSchema, JsonNode inputUi, Map<String, String> keys, Json json) {
        Map<String, String> back = new HashMap<>();
        keys.forEach((shared, local) -> back.put(local, shared));
        ObjectNode out = json.object();
        ObjectNode questions = out.putObject("questions");
        ObjectNode widgets = out.putObject("widgets");
        ObjectNode visible = out.putObject("visibleWhen");
        ObjectNode required = out.putObject("requiredWhen");
        Set<String> requiredKeys = new TreeSet<>();
        Set<String> requiredLocal = new HashSet<>();
        inputSchema.path("required").forEach(r -> requiredLocal.add(r.asString("")));
        for (var e : new TreeMap<>(keys).entrySet()) {
            String shared = e.getKey();
            String local = e.getValue();
            JsonNode question = inputSchema.path("properties").path(local);
            questions.set(shared, question.isMissingNode() ? NullNode.getInstance() : question.deepCopy());
            if (requiredLocal.contains(local)) {
                requiredKeys.add(shared);
            }
            JsonNode widget = inputUi.path("widgets").path(local);
            if (!widget.isMissingNode()) {
                widgets.set(shared, widget.deepCopy());
            }
            JsonNode when = inputUi.path("visibleWhen").path(local);
            if (when.isObject()) {
                visible.set(shared, rename(when.deepCopy(), back));
            }
            JsonNode req = inputUi.path("requiredWhen").path(local);
            if (req.isObject()) {
                required.set(shared, rename(req.deepCopy(), back));
            }
        }
        ArrayNode list = out.putArray("required");
        requiredKeys.forEach(list::add);
        return out;
    }

    /** Projection of a shared version restricted to the copied questions. */
    static ObjectNode assetProjection(JsonNode content, Map<String, String> fields, Json json) {
        Map<String, String> identity = new LinkedHashMap<>();
        fields.keySet().forEach(k -> identity.put(k, k));
        return projection(content.path("inputSchema"), content.path("inputUi"), identity, json);
    }

    static String projectionHash(ObjectNode projection, Json json) {
        return CanonicalJson.sha256Hex(json.write(projection));
    }

    /** Rewrites every {@code fieldRef} in a condition from local keys to shared keys (others are marked). */
    private static JsonNode rename(JsonNode node, Map<String, String> back) {
        if (node instanceof ObjectNode object) {
            JsonNode ref = object.get("fieldRef");
            if (ref != null && ref.isString() && ref.asString().startsWith(INPUT)) {
                String local = ref.asString().substring(INPUT.length());
                String shared = back.get(local);
                object.put("fieldRef", INPUT + (shared != null ? shared : OTHER + local));
            }
            for (var e : object.properties()) {
                if (!"fieldRef".equals(e.getKey())) {
                    rename(e.getValue(), back);
                }
            }
        } else if (node != null && node.isArray()) {
            node.forEach(child -> rename(child, back));
        }
        return node;
    }
}
