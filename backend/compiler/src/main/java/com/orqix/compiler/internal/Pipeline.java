package com.orqix.compiler.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.orqix.compiler.CompileContext;
import com.orqix.compiler.ValidationIssue;
import com.orqix.compiler.ValidationReport;
import com.orqix.compiler.internal.json.Jcs;
import com.orqix.compiler.internal.json.JsonPointers;
import com.orqix.compiler.internal.json.JsonProblem;
import com.orqix.compiler.internal.json.StrictJson;
import com.orqix.compiler.internal.model.ModelReader;
import com.orqix.compiler.internal.model.WorkflowDefinition;
import com.orqix.compiler.internal.rules.Issues;
import com.orqix.compiler.internal.rules.RuleContext;
import com.orqix.compiler.internal.rules.Rules;
import com.orqix.compiler.internal.schema.SchemaValidation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Pipeline stages 1-7 (S4 section 1). Thread-safe. */
public final class Pipeline {

    /** Supported canonical format version. */
    public static final String SCHEMA_VERSION = "0.1";
    /** Maximum WF_SCHEMA_INVALID issues reported for one document. */
    public static final int MAX_SCHEMA_ISSUES = 50;

    private static final String SCHEMA_INVALID = "WF_SCHEMA_INVALID";
    private static final Pattern NODE_PATH = Pattern.compile("^/nodes/(\\d+)(/.*)?$");
    private static final Pattern TRANSITION_PATH = Pattern.compile("^/transitions/(\\d+)(/.*)?$");
    private static final CompileContext EMPTY_CONTEXT = new CompileContext(false, Map.of(), List.of(), Set.of());

    /**
     * Result of stages 1-7.
     *
     * @param definition typed model; null when stages 1-3 failed
     * @param analysis   rule context; null when stages 1-3 failed
     * @param sourceHash null when the document could not be parsed
     */
    public record Outcome(ValidationReport report, WorkflowDefinition definition, RuleContext analysis,
                          String sourceHash) {
    }

    private final String compilerVersion;
    private final SchemaValidation schemas;

    public Pipeline(String compilerVersion, SchemaValidation schemas) {
        this.compilerVersion = compilerVersion;
        this.schemas = schemas;
    }

    public Outcome run(String json, CompileContext context) {
        CompileContext workspace = context == null ? EMPTY_CONTEXT : context;
        Issues issues = new Issues();
        // Stage 1: parse with limits.
        if (json == null) {
            issues.blocker(SCHEMA_INVALID, "", null, null, "No workflow definition was supplied.");
            return failed(issues, null);
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > StrictJson.MAX_DEFINITION_BYTES) {
            issues.blocker("WF_PAYLOAD_TOO_LARGE", "", null, null, "The workflow definition is larger than 256 KiB."
                    + " Remove unused steps or shorten long texts.");
            return failed(issues, null);
        }
        JsonNode root;
        try {
            root = StrictJson.parse(json, StrictJson.MAX_DEFINITION_DEPTH);
        } catch (JsonProblem p) {
            parseIssue(issues, p);
            return failed(issues, null);
        }
        String sourceHash = Jcs.sha256Hex(root);
        String semanticHash = semanticHash(root);
        // Stage 2: version.
        if (!root.isObject()) {
            issues.blocker(SCHEMA_INVALID, "", null, null, "The workflow definition must be a JSON object.");
            return failed(issues, semanticHash, sourceHash);
        }
        JsonNode version = root.get("schemaVersion");
        if (version == null || !version.isString() || !SCHEMA_VERSION.equals(version.stringValue())) {
            String message = version == null
                    ? "The workflow definition does not state its format version (schemaVersion). This compiler"
                    + " supports version " + SCHEMA_VERSION + "."
                    : "This workflow uses format version " + (version.isString() ? version.stringValue()
                    : StrictJson.write(version)) + ", but only version " + SCHEMA_VERSION + " is supported.";
            issues.blocker("WF_SCHEMA_VERSION_UNSUPPORTED", "/schemaVersion", null, null, message);
            return failed(issues, semanticHash, sourceHash);
        }
        // Stage 3: JSON Schema.
        List<SchemaValidation.Problem> problems = schemas.validateWorkflow(root);
        if (!problems.isEmpty()) {
            Issues schemaIssues = new Issues();
            for (SchemaValidation.Problem p : new LinkedHashSet<>(problems)) {
                schemaIssues.blocker(SCHEMA_INVALID, p.path(), idAt(root, p.path(), NODE_PATH, "nodes"),
                        idAt(root, p.path(), TRANSITION_PATH, "transitions"), p.message());
            }
            List<ValidationIssue> sorted = schemaIssues.sorted();
            List<ValidationIssue> limited = new ArrayList<>(sorted.subList(0,
                    Math.min(sorted.size(), MAX_SCHEMA_ISSUES)));
            return new Outcome(new ValidationReport(compilerVersion, limited, semanticHash), null, null, sourceHash);
        }
        // Stages 4-7.
        WorkflowDefinition definition = ModelReader.read(root);
        RuleContext analysis = Rules.run(definition, workspace, schemas, issues);
        return new Outcome(new ValidationReport(compilerVersion, issues.sorted(), semanticHash), definition,
                analysis, sourceHash);
    }

    private Outcome failed(Issues issues, String semanticHash) {
        return new Outcome(new ValidationReport(compilerVersion, issues.sorted(), semanticHash), null, null, null);
    }

    private Outcome failed(Issues issues, String semanticHash, String sourceHash) {
        return new Outcome(new ValidationReport(compilerVersion, issues.sorted(), semanticHash), null, null,
                sourceHash);
    }

    /** @return SHA-256 of the JCS form without {@code uiLayout} */
    public static String semanticHash(JsonNode root) {
        if (root.isObject() && root.has("uiLayout")) {
            ObjectNode copy = ((ObjectNode) root).deepCopy();
            copy.remove("uiLayout");
            return Jcs.sha256Hex(copy);
        }
        return Jcs.sha256Hex(root);
    }

    private static void parseIssue(Issues issues, JsonProblem p) {
        switch (p.kind()) {
            case TOO_DEEP -> issues.blocker("WF_JSON_DEPTH", p.pointer(), null, null, "The workflow definition is"
                    + " nested more than " + StrictJson.MAX_DEFINITION_DEPTH + " levels deep.");
            case DUPLICATE_KEY -> {
                List<String> tokens = JsonPointers.parse(p.pointer());
                String name = tokens.isEmpty() ? "" : tokens.get(tokens.size() - 1);
                issues.blocker(SCHEMA_INVALID, p.pointer(), null, null, "The property \"" + name
                        + "\" appears more than once.");
            }
            case NUMBER_OUT_OF_RANGE -> issues.blocker(SCHEMA_INVALID, p.pointer(), null, null,
                    "This number is too large.");
            case SYNTAX -> issues.blocker(SCHEMA_INVALID, "", null, null, "The workflow definition is not valid JSON"
                    + (p.line() > 0 ? " (line " + p.line() + ", column " + p.column() + ")" : "") + ".");
        }
    }

    private static String idAt(JsonNode root, String path, Pattern pattern, String array) {
        Matcher m = pattern.matcher(path);
        if (!m.matches()) {
            return null;
        }
        JsonNode id = root.path(array).path(Integer.parseInt(m.group(1))).path("id");
        return id.isString() ? id.stringValue() : null;
    }
}
