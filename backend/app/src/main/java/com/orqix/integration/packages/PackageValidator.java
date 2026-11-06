package com.orqix.integration.packages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.orqix.compiler.CompileContext;
import com.orqix.compiler.ConnectionContracts;
import com.orqix.compiler.Severity;
import com.orqix.compiler.ValidationIssue;
import com.orqix.compiler.ValidationReport;
import com.orqix.compiler.WorkflowCompiler;
import com.orqix.integration.ConnectionDefinition;
import com.orqix.integration.HttpExecutor;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Full validation of a package on import (CG-C03, docs/build/S133 section 3): every file against its schema, every
 * connection through the registration rules, every step template and starter workflow through the compiler, every
 * fixture replayed through the runtime's response handling, the manifest's declared summary against the real content,
 * and host patterns concrete enough. Also computes the facts of the review screen (CG-C04). Problems are collected,
 * never thrown one at a time, so the report lists everything that is wrong.
 */
@Component
public class PackageValidator {

    /** One problem: the file, a JSON Pointer inside it (may be empty) and a stable code. */
    public record Problem(String file, String pointer, String code, String message) {
    }

    /**
     * A package that passed validation.
     *
     * @param connections   connection definitions by key (as in the package, package-local versions)
     * @param triggers      trigger files by key
     * @param stepTemplates step templates by key
     * @param workflows     starter workflows by key
     * @param review        the review facts (hosts, operations by effect class, data leaving Orqix, auth, triggers)
     */
    public record Validated(JsonNode manifest, Map<String, JsonNode> connections, Map<String, JsonNode> triggers,
            Map<String, JsonNode> stepTemplates, Map<String, JsonNode> workflows, ObjectNode review) {
    }

    /** Validation failed; {@code problems} is never empty. */
    public static final class Failed extends RuntimeException {
        private final List<Problem> problems;

        Failed(List<Problem> problems) {
            super("Package validation failed", null, false, false);
            this.problems = List.copyOf(problems);
        }

        public List<Problem> problems() {
            return problems;
        }
    }

    private static final String SCHEMA = "classpath:orqix/schemas/connector-package-v1.schema.json#/$defs/";
    private static final Set<String> CONTENT_ROLES = Set.of("connections", "triggers", "stepTemplates",
            "workflowTemplates", "fixtures", "docs");

    private final Json json;
    private final WorkflowCompiler compiler;
    private final HttpExecutor executor;
    private final Schema manifestSchema;
    private final Schema triggerSchema;
    private final Schema stepSchema;
    private final Schema workflowSchema;
    private final Schema fixtureSchema;
    private final Schema connectionV0;
    private final Schema connectionV1;

    public PackageValidator(Json json, WorkflowCompiler compiler, HttpExecutor executor) {
        this.json = json;
        this.compiler = compiler;
        this.executor = executor;
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        this.manifestSchema = registry.getSchema(SchemaLocation.of(SCHEMA + "manifest"));
        this.triggerSchema = registry.getSchema(SchemaLocation.of(SCHEMA + "trigger"));
        this.stepSchema = registry.getSchema(SchemaLocation.of(SCHEMA + "stepTemplate"));
        this.workflowSchema = registry.getSchema(SchemaLocation.of(SCHEMA + "workflowTemplate"));
        this.fixtureSchema = registry.getSchema(SchemaLocation.of(SCHEMA + "fixture"));
        this.connectionV0 = registry.getSchema(SchemaLocation.of("classpath:orqix/schemas/connection-definition-v0.schema.json"));
        this.connectionV1 = registry.getSchema(SchemaLocation.of("classpath:orqix/schemas/connection-definition-v1.schema.json"));
    }

    public Validated validate(PackageArchive.Archive archive) {
        List<Problem> problems = new ArrayList<>();
        JsonNode manifest = parse(archive, PackageArchive.MANIFEST, problems);
        if (manifest == null) {
            throw new Failed(problems);
        }
        if (!schema(manifestSchema, manifest, PackageArchive.MANIFEST, problems)) {
            throw new Failed(problems);
        }
        // Every file is listed in contents under the role of its folder, and every listed file exists.
        Map<String, List<String>> roles = new LinkedHashMap<>();
        Set<String> listed = new LinkedHashSet<>();
        JsonNode contents = manifest.path("contents");
        for (String role : CONTENT_ROLES) {
            List<String> paths = new ArrayList<>();
            for (JsonNode p : contents.path(role)) {
                String path = p.asString();
                paths.add(path);
                listed.add(path);
                if (!folderFits(role, path)) {
                    problems.add(new Problem(PackageArchive.MANIFEST, "/contents/" + role, "PKG_CONTENTS",
                            path + " is not in the folder for " + role + "."));
                } else if (!archive.files().containsKey(path)) {
                    problems.add(new Problem(PackageArchive.MANIFEST, "/contents/" + role, "PKG_CONTENTS",
                            path + " is listed but not in the package."));
                }
            }
            roles.put(role, paths);
        }
        for (String path : archive.files().keySet()) {
            if (!path.equals(PackageArchive.MANIFEST) && !listed.contains(path)) {
                problems.add(new Problem(path, "", "PKG_CONTENTS", path + " is in the package but not listed in"
                        + " manifest.json contents."));
            }
        }
        hosts(manifest, problems);

        // Connections: v0 or v1 http definitions through the registration schema and rules.
        Map<String, JsonNode> connections = new LinkedHashMap<>();
        Map<String, Integer> effects = new TreeMap<>();
        for (String path : roles.get("connections")) {
            JsonNode definition = parse(archive, path, problems);
            if (definition == null) {
                continue;
            }
            boolean v1 = ConnectionContracts.isV1(definition);
            if (!schema(v1 ? connectionV1 : connectionV0, definition, path, problems)) {
                continue;
            }
            if (!"http".equals(definition.path("kind").asString("http"))) {
                problems.add(new Problem(path, "/kind", "PKG_CONNECTION_KIND", "Packages hold only http connections."));
                continue;
            }
            for (ConnectionContracts.Problem p : ConnectionContracts.check(definition)) {
                problems.add(new Problem(path, p.pointer(), p.code(), p.message()));
            }
            String key = definition.path("key").asString();
            if (connections.put(key, definition) != null) {
                problems.add(new Problem(path, "/key", "PKG_DUPLICATE", "Two connection files use the key " + key + "."));
            }
            for (JsonNode op : definition.path("operations")) {
                effects.merge(op.path("effectClass").asString(), 1, Integer::sum);
            }
        }
        Map<String, Integer> declared = new TreeMap<>();
        manifest.path("operations").properties().forEach(e -> declared.put(e.getKey(), e.getValue().asInt()));
        if (!declared.equals(effects)) {
            problems.add(new Problem(PackageArchive.MANIFEST, "/operations", "PKG_MANIFEST_MISMATCH",
                    "The manifest declares operations " + declared + " but the connections have " + effects + "."));
        }

        // Triggers.
        Map<String, JsonNode> triggers = new LinkedHashMap<>();
        for (String path : roles.get("triggers")) {
            JsonNode trigger = parse(archive, path, problems);
            if (trigger == null || !schema(triggerSchema, trigger, path, problems)) {
                continue;
            }
            String expected = trigger.path("key").asString() + "." + trigger.path("kind").asString() + ".json";
            if (!path.endsWith("/" + expected)) {
                problems.add(new Problem(path, "", "PKG_CONTENTS", "A trigger file is named <key>.<kind>.json: "
                        + expected + "."));
            }
            if (triggers.put(trigger.path("key").asString(), trigger) != null) {
                problems.add(new Problem(path, "/key", "PKG_DUPLICATE", "Two triggers use the key "
                        + trigger.path("key").asString() + "."));
            }
            if ("poll".equals(trigger.path("kind").asString())) {
                pollTrigger(trigger, connections, path, problems);
            }
        }
        Set<String> declaredTriggers = new LinkedHashSet<>();
        manifest.path("triggers").forEach(t -> declaredTriggers.add(t.asString()));
        if (!declaredTriggers.equals(triggers.keySet())) {
            problems.add(new Problem(PackageArchive.MANIFEST, "/triggers", "PKG_MANIFEST_MISMATCH",
                    "The manifest declares triggers " + declaredTriggers + " but the package has " + triggers.keySet() + "."));
        }

        // Step templates and starter workflows through the compiler.
        CompileContext context = context(connections);
        Map<String, JsonNode> steps = new LinkedHashMap<>();
        for (String path : roles.get("stepTemplates")) {
            JsonNode template = parse(archive, path, problems);
            if (template == null || !schema(stepSchema, template, path, problems)) {
                continue;
            }
            if (steps.put(template.path("key").asString(), template) != null) {
                problems.add(new Problem(path, "/key", "PKG_DUPLICATE", "Two step templates use the key "
                        + template.path("key").asString() + "."));
            }
            JsonNode definition = connections.get(template.path("connection").asString());
            if (definition == null || !definition.path("operations").has(template.path("operation").asString())) {
                problems.add(new Problem(path, "/operation", "PKG_REFERENCE", "The template uses an operation that"
                        + " no connection of this package has."));
                continue;
            }
            compile(templateWorkflow(template, definition), context, path, null, problems);
        }
        Map<String, JsonNode> workflows = new LinkedHashMap<>();
        for (String path : roles.get("workflowTemplates")) {
            JsonNode template = parse(archive, path, problems);
            if (template == null || !schema(workflowSchema, template, path, problems)) {
                continue;
            }
            if (workflows.put(template.path("key").asString(), template) != null) {
                problems.add(new Problem(path, "/key", "PKG_DUPLICATE", "Two starter workflows use the key "
                        + template.path("key").asString() + "."));
            }
            for (JsonNode dep : template.path("workflow").path("dependencies").path("connections")) {
                JsonNode definition = connections.get(dep.path("key").asString());
                if (definition == null || definition.path("version").asInt() != dep.path("definitionVersion").asInt()) {
                    problems.add(new Problem(path, "/workflow/dependencies/connections", "PKG_REFERENCE",
                            "A starter workflow may only use this package's connections at their package version."));
                }
            }
            compile(template.path("workflow"), context, path, "/workflow", problems);
        }

        // Fixtures replayed through the runtime's response handling.
        int fixturesPassed = 0;
        String replayBase = replayBase(manifest);
        for (String path : roles.get("fixtures")) {
            JsonNode fixture = parse(archive, path, problems);
            if (fixture == null || !schema(fixtureSchema, fixture, path, problems)) {
                continue;
            }
            if (replay(fixture, connections, replayBase, path, problems)) {
                fixturesPassed++;
            }
        }
        for (String path : roles.get("docs")) {
            if (!path.endsWith(".md")) {
                problems.add(new Problem(path, "", "PKG_CONTENTS", "Documentation files are Markdown (.md)."));
            }
        }
        if (!problems.isEmpty()) {
            throw new Failed(problems);
        }
        ObjectNode review = review(manifest, connections, triggers, steps, workflows, roles, fixturesPassed);
        return new Validated(manifest, connections, triggers, steps, workflows, review);
    }

    /**
     * The content of an archive that passed {@link #validate} at import (its bytes are immutable): parsed without
     * running the checks again.
     */
    public Validated contentsOf(PackageArchive.Archive archive, JsonNode manifest, JsonNode review) {
        JsonNode contents = manifest.path("contents");
        return new Validated(manifest, byKey(archive, contents.path("connections")), byKey(archive, contents.path("triggers")),
                byKey(archive, contents.path("stepTemplates")), byKey(archive, contents.path("workflowTemplates")),
                (ObjectNode) review);
    }

    private Map<String, JsonNode> byKey(PackageArchive.Archive archive, JsonNode paths) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (JsonNode path : paths) {
            JsonNode node = json.parse(archive.text(path.asString()));
            out.put(node.path("key").asString(), node);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------- pieces

    private JsonNode parse(PackageArchive.Archive archive, String path, List<Problem> problems) {
        String text = archive.text(path);
        if (text == null) {
            return null;
        }
        try {
            JsonNode node = json.parse(text);
            if (!node.isObject() || Json.depth(node) > 40) {
                problems.add(new Problem(path, "", "PKG_JSON", path + " must be one JSON object."));
                return null;
            }
            return node;
        } catch (RuntimeException e) {
            problems.add(new Problem(path, "", "PKG_JSON", path + " is not valid JSON."));
            return null;
        }
    }

    private static boolean schema(Schema schema, JsonNode node, String path, List<Problem> problems) {
        List<Error> errors = schema.validate(node);
        errors.stream().limit(20).forEach(e -> problems.add(new Problem(path,
                e.getInstanceLocation() == null ? "" : e.getInstanceLocation().toString().replaceFirst("^\\$", "")
                        .replace('.', '/'), "SCHEMA", e.getMessage())));
        return errors.isEmpty();
    }

    private static boolean folderFits(String role, String path) {
        return switch (role) {
            case "connections" -> path.startsWith("connections/") && path.endsWith(".json");
            case "triggers" -> path.startsWith("triggers/") && (path.endsWith(".webhook.json") || path.endsWith(".poll.json"));
            case "stepTemplates" -> path.startsWith("templates/steps/") && path.endsWith(".json");
            case "workflowTemplates" -> path.startsWith("templates/workflows/") && path.endsWith(".json");
            case "fixtures" -> path.startsWith("fixtures/") && path.endsWith(".json");
            case "docs" -> path.startsWith("docs/");
            default -> false;
        };
    }

    /** Host patterns: at most one wildcard, the whole leftmost label, then at least two concrete labels. */
    private static void hosts(JsonNode manifest, List<Problem> problems) {
        int i = 0;
        for (JsonNode host : manifest.path("hosts")) {
            String pattern = host.path("pattern").asString();
            String hostPart = pattern.substring("https://".length()).replaceFirst(":[0-9]+$", "");
            String[] labels = hostPart.split("\\.");
            boolean wildcard = labels[0].equals("*");
            int concrete = wildcard ? labels.length - 1 : labels.length;
            if (concrete < 2 || hostPart.indexOf('*', 1) >= 0) {
                problems.add(new Problem(PackageArchive.MANIFEST, "/hosts/" + i + "/pattern", "PKG_HOST_PATTERN",
                        "The host pattern must name at least two concrete labels after a wildcard (no '*.com')."));
            }
            i++;
        }
    }

    private void pollTrigger(JsonNode trigger, Map<String, JsonNode> connections, String path, List<Problem> problems) {
        JsonNode definition = connections.get(trigger.path("connection").asString());
        JsonNode op = definition == null ? null : definition.path("operations").get(trigger.path("operation").asString());
        if (op == null) {
            problems.add(new Problem(path, "/operation", "PKG_REFERENCE", "The polling trigger uses an operation that"
                    + " no connection of this package has."));
            return;
        }
        if (!"readOnly".equals(op.path("effectClass").asString()) || !"GET".equals(op.path("method").asString())) {
            problems.add(new Problem(path, "/operation", "PKG_POLL_READONLY", "A polling trigger reads with a readOnly GET."));
        }
        if (!ConnectionContracts.isV1(definition)) {
            problems.add(new Problem(path, "/connection", "PKG_POLL_OPERATION", "A polling trigger needs a v1 connection."));
            return;
        }
        Map<String, JsonNode> inputs = ConnectionContracts.inputs(op);
        String input = trigger.path("watermark").path("input").asString();
        if (!inputs.containsKey(input)) {
            problems.add(new Problem(path, "/watermark/input", "PKG_POLL_OPERATION", "The watermark input " + input
                    + " is not an input of " + trigger.path("operation").asString() + "."));
        }
        for (String required : ConnectionContracts.requiredInputs(op)) {
            if (!required.equals(input)) {
                problems.add(new Problem(path, "/operation", "PKG_POLL_OPERATION", "The polling operation may require"
                        + " only the watermark input."));
            }
        }
        ConnectionContracts.SchemaAt items = ConnectionContracts.schemaAt(op.path("responseSchema"),
                tokens(trigger.path("itemsPointer").asString()));
        if (items == null || !"array".equals(items.schema().path("type").asString(""))) {
            problems.add(new Problem(path, "/itemsPointer", "PKG_POLL_OPERATION", "itemsPointer must point at a list"
                    + " in the operation's response."));
            return;
        }
        Map<String, JsonNode> columns = ConnectionContracts.rowColumns(items.schema().get("items"));
        List<String> pointers = new ArrayList<>();
        pointers.add(trigger.path("watermark").path("field").asString());
        trigger.path("dedupeKey").forEach(p -> pointers.add(p.asString()));
        trigger.path("mapping").forEach(p -> pointers.add(p.asString()));
        if (trigger.path("action").has("correlationFrom")) {
            pointers.add(trigger.path("action").path("correlationFrom").asString());
        }
        for (String pointer : pointers) {
            List<String> t = tokens(pointer);
            if (t.size() != 1 || !columns.containsKey(t.get(0))) {
                problems.add(new Problem(path, "", "PKG_POLL_POINTER", pointer + " must name a single value of each"
                        + " listed record."));
            }
        }
    }

    private static List<String> tokens(String pointer) {
        List<String> tokens = new ArrayList<>();
        if (pointer == null || pointer.isEmpty()) {
            return tokens;
        }
        for (String t : pointer.substring(1).split("/", -1)) {
            tokens.add(t.replace("~1", "/").replace("~0", "~"));
        }
        return tokens;
    }

    private CompileContext context(Map<String, JsonNode> connections) {
        List<CompileContext.ConnectionVersion> versions = new ArrayList<>();
        connections.forEach((key, definition) -> {
            String text = json.write(definition);
            versions.add(new CompileContext.ConnectionVersion(key, definition.path("version").asInt(), text,
                    Json.canonicalSha256(text)));
        });
        return new CompileContext(false, Map.of(), versions, connections.keySet(), Map.of());
    }

    private void compile(JsonNode workflow, CompileContext context, String path, String base, List<Problem> problems) {
        ValidationReport report = compiler.validate(json.write(workflow), context);
        for (ValidationIssue issue : report.issues()) {
            if (issue.severity() == Severity.BLOCKER) {
                String at = issue.path() == null ? "" : issue.path();
                // A step template's problems point into its step (node 1 of the generated workflow).
                String pointer = base != null ? base + at : at.startsWith("/nodes/1") ? "/step" + at.substring(8) : "/step";
                problems.add(new Problem(path, pointer, issue.code(), issue.message()));
            }
        }
    }

    /**
     * The smallest workflow around a step template: a request form with the template's fields, the step, and the
     * routes its settings need (timed out for checks, failed when a failed condition is set).
     */
    ObjectNode templateWorkflow(JsonNode template, JsonNode definition) {
        ObjectNode wf = json.object();
        wf.put("schemaVersion", "0.1").put("workflowKey", "package-template-check").put("name", "Template check");
        wf.set("metadata", json.object().put("description", "Generated to check a package step template."));
        ObjectNode properties = json.object();
        ArrayNode required = json.mapper().createArrayNode();
        ArrayNode order = json.mapper().createArrayNode();
        template.path("requestFields").properties().forEach(e -> {
            ObjectNode field = (ObjectNode) e.getValue().deepCopy();
            if ("string".equals(field.path("type").asString()) && !field.has("maxLength") && !field.has("enum")) {
                field.put("maxLength", 200);
            }
            properties.set(e.getKey(), field);
            required.add(e.getKey());
            order.add(e.getKey());
        });
        ObjectNode input = json.object().put("type", "object").put("additionalProperties", false);
        input.set("required", required);
        input.set("properties", properties);
        wf.set("inputSchema", input);
        ObjectNode ui = json.object();
        ui.set("order", order);
        ui.set("widgets", json.object());
        wf.set("inputUi", ui);
        wf.set("outputSchema", json.object().put("type", "object").put("additionalProperties", false)
                .set("properties", json.object()));
        wf.set("triggers", json.mapper().createArrayNode().add("manual"));
        wf.set("decisions", json.mapper().createArrayNode());
        wf.set("execution", json.object().put("maxConcurrentRuns", 10).put("maxAttempts", 3).put("retryDelaySeconds", 30));
        wf.set("security", json.object().put("selfApproval", "deny"));
        ArrayNode deps = json.mapper().createArrayNode();
        deps.add(json.object().put("key", definition.path("key").asString()).put("definitionVersion",
                definition.path("version").asInt()));
        wf.set("dependencies", json.object().set("connections", deps));
        wf.set("outputMapping", json.object());
        ArrayNode nodes = json.mapper().createArrayNode();
        ArrayNode transitions = json.mapper().createArrayNode();
        nodes.add(json.object().put("id", "start").put("type", "start").put("label", "Start").set("config", json.object()));
        ObjectNode step = (ObjectNode) template.path("step").deepCopy();
        step.put("id", "templateStep");
        nodes.add(step);
        nodes.add(end("done", "Done", "success"));
        transitions.add(transition("t1", "start", "templateStep", "next"));
        transitions.add(transition("t2", "templateStep", "done", "next"));
        JsonNode poll = step.path("config").path("poll");
        if (poll.isObject()) {
            nodes.add(end("gaveUp", "Gave up", "rejected"));
            transitions.add(transition("t3", "templateStep", "gaveUp", "timedOut"));
            if (poll.has("failWhen")) {
                nodes.add(end("remoteFailed", "Failed", "rejected"));
                transitions.add(transition("t4", "templateStep", "remoteFailed", "failed"));
            }
        }
        wf.set("nodes", nodes);
        wf.set("transitions", transitions);
        return wf;
    }

    private ObjectNode end(String id, String label, String kind) {
        ObjectNode end = json.object().put("id", id).put("type", "end").put("label", label);
        end.set("config", json.object().put("businessOutcome", label).put("outcomeKind", kind));
        return end;
    }

    private ObjectNode transition(String id, String from, String to, String on) {
        return json.object().put("id", id).put("from", from).put("to", to).put("on", on);
    }

    private static String replayBase(JsonNode manifest) {
        String pattern = manifest.path("hosts").path(0).path("pattern").asString("https://fixture.example.com");
        return pattern.replace("*.", "fixture.");
    }

    private boolean replay(JsonNode fixture, Map<String, JsonNode> connections, String base, String path,
            List<Problem> problems) {
        JsonNode document = connections.get(fixture.path("connection").asString());
        if (document == null) {
            problems.add(new Problem(path, "/connection", "PKG_REFERENCE", "The fixture names a connection that is not"
                    + " in this package."));
            return false;
        }
        ConnectionDefinition definition = ConnectionDefinition.parse(document, Json.canonicalSha256(json.write(document)));
        ConnectionDefinition.Operation operation = definition.operations().get(fixture.path("operation").asString());
        if (operation == null) {
            problems.add(new Problem(path, "/operation", "PKG_REFERENCE", "The fixture names an operation that the"
                    + " connection does not have."));
            return false;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        fixture.path("response").path("headers").properties().forEach(e -> headers.put(e.getKey(), e.getValue().asString()));
        if (!headers.keySet().stream().anyMatch("content-type"::equalsIgnoreCase)) {
            headers.put("Content-Type", "application/json");
        }
        JsonNode body = fixture.path("response").get("body");
        byte[] bytes = body == null ? new byte[0] : json.write(body).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Map<String, String> mapping = new LinkedHashMap<>();
        fixture.path("resultMapping").properties().forEach(e -> mapping.put(e.getKey(), e.getValue().asString()));
        HttpExecutor.CallResult result;
        try {
            result = executor.replay(definition, operation, fixture.path("response").path("status").asInt(), headers,
                    bytes, mapping, base);
        } catch (RuntimeException e) {
            problems.add(new Problem(path, "", "PKG_FIXTURE", "The fixture could not be replayed."));
            return false;
        }
        JsonNode expect = fixture.path("expect");
        String outcome = switch (result.outcome()) {
            case SUCCEEDED -> "succeeded";
            case PERMANENT -> "rejected";
            case RETRYABLE -> "retryable";
            case UNKNOWN -> "unknown";
        };
        if (!outcome.equals(expect.path("outcome").asString())) {
            problems.add(new Problem(path, "/expect/outcome", "PKG_FIXTURE", "Replayed, the answer is " + outcome
                    + (result.errorCode() == null ? "" : " (" + result.errorCode() + ")") + ", not "
                    + expect.path("outcome").asString() + "."));
            return false;
        }
        if (expect.has("code") && !expect.path("code").asString().equals(result.errorCode())) {
            problems.add(new Problem(path, "/expect/code", "PKG_FIXTURE", "Replayed, the code is " + result.errorCode()
                    + ", not " + expect.path("code").asString() + "."));
            return false;
        }
        for (Map.Entry<String, JsonNode> e : expect.path("results").properties()) {
            JsonNode actual = result.result() == null ? null : result.result().get(e.getKey());
            if (actual == null || !actual.asString().equals(e.getValue().asString())) {
                problems.add(new Problem(path, "/expect/results/" + e.getKey(), "PKG_FIXTURE", "Replayed, "
                        + e.getKey() + " is not the expected value."));
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------- review

    private ObjectNode review(JsonNode manifest, Map<String, JsonNode> connections, Map<String, JsonNode> triggers,
            Map<String, JsonNode> steps, Map<String, JsonNode> workflows, Map<String, List<String>> roles,
            int fixturesPassed) {
        ObjectNode review = json.object();
        review.set("hosts", manifest.path("hosts").deepCopy());
        review.set("authSchemes", manifest.path("authSchemes").deepCopy());
        review.set("dataLeavingOrqix", manifest.path("dataLeavingOrqix").deepCopy());
        ArrayNode conns = json.mapper().createArrayNode();
        ArrayNode ops = json.mapper().createArrayNode();
        ObjectNode byEffect = json.object();
        connections.forEach((key, def) -> {
            conns.add(json.object().put("key", key).put("name", def.path("name").asString())
                    .put("schemaVersion", def.path("schemaVersion").asString()).put("version", def.path("version").asInt())
                    .put("operations", def.path("operations").size()));
            def.path("operations").properties().forEach(e -> {
                JsonNode op = e.getValue();
                ObjectNode o = json.object().put("connection", key).put("operation", e.getKey())
                        .put("label", op.path("label").asString()).put("method", op.path("method").asString("POST"))
                        .put("path", op.path("path").asString()).put("effectClass", op.path("effectClass").asString());
                ArrayNode sent = json.mapper().createArrayNode();
                ConnectionContracts.inputs(op).keySet().forEach(sent::add);
                o.set("inputs", sent);
                o.put("asynchronous", op.has("async"));
                ops.add(o);
                byEffect.put(op.path("effectClass").asString(), byEffect.path(op.path("effectClass").asString()).asInt() + 1);
            });
        });
        review.set("connections", conns);
        review.set("operations", ops);
        review.set("operationsByEffect", byEffect);
        ArrayNode trig = json.mapper().createArrayNode();
        triggers.forEach((key, t) -> {
            ObjectNode o = json.object().put("key", key).put("label", t.path("label").asString())
                    .put("kind", t.path("kind").asString()).put("action", t.path("action").path("type").asString());
            if (t.path("action").has("message")) {
                o.put("message", t.path("action").path("message").asString());
            }
            if ("webhook".equals(t.path("kind").asString())) {
                String scheme = t.path("verification").path("scheme").asString();
                o.put("verification", scheme).put("weaker", "basic".equals(scheme));
            } else {
                o.put("connection", t.path("connection").asString()).put("operation", t.path("operation").asString())
                        .put("every", t.path("every").asString());
            }
            trig.add(o);
        });
        review.set("triggers", trig);
        ArrayNode templates = json.mapper().createArrayNode();
        steps.forEach((key, t) -> templates.add(json.object().put("key", key).put("label", t.path("label").asString())
                .put("connection", t.path("connection").asString()).put("operation", t.path("operation").asString())));
        review.set("stepTemplates", templates);
        ArrayNode starters = json.mapper().createArrayNode();
        workflows.forEach((key, t) -> starters.add(json.object().put("key", key).put("label", t.path("label").asString())));
        review.set("workflowTemplates", starters);
        review.set("fixtures", json.object().put("count", roles.get("fixtures").size()).put("passed", fixturesPassed));
        ArrayNode docs = json.mapper().createArrayNode();
        roles.get("docs").forEach(docs::add);
        review.set("docs", docs);
        return review;
    }
}
