package com.orqix.compiler.internal.schema;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.PathType;
import com.orqix.compiler.internal.json.JsonPointers;
import com.orqix.compiler.internal.json.StrictJson;
import com.orqix.compiler.internal.model.Enums.NodeKind;

import tools.jackson.databind.JsonNode;

/**
 * Pipeline stage 3: JSON Schema 2020-12 validation of workflow definitions and connection definitions. Schemas are
 * loaded from the classpath ({@code orqix/schemas/}); remote {@code $ref} loading is disabled.
 * <p>
 * Errors inside {@code nodes[i]} are narrowed to the {@code oneOf} branch named by the node's {@code type}, so a
 * business user sees "Finance review needs an approver group." instead of seven alternative explanations.
 */
public final class SchemaValidation {

    /** Problem found by the schema, before conversion to an issue. */
    public record Problem(String path, String message) {
    }

    static final String WORKFLOW_ID = "https://orqix.example/schemas/workflow-v0.schema.json";
    static final String CONNECTION_ID = "https://orqix.example/schemas/connection-definition-v0.schema.json";
    private static final String NODES_ONE_OF = "/properties/nodes/items/oneOf";
    private static final Pattern NODE_INSTANCE = Pattern.compile("^/nodes/(\\d+)(/.*)?$");
    private static final Pattern BRANCH_EVAL = Pattern.compile("^/properties/nodes/items/oneOf/(\\d+)(/.*)?$");
    /** Order of the {@code oneOf} alternatives in the workflow schema. */
    private static final List<NodeKind> BRANCH_ORDER = List.of(NodeKind.START, NodeKind.APPROVAL,
            NodeKind.DECISION, NodeKind.BRANCH, NodeKind.HTTP, NodeKind.NOTIFICATION, NodeKind.PARALLEL_SPLIT,
            NodeKind.PARALLEL_JOIN, NodeKind.WAIT, NodeKind.START_WORKFLOW, NodeKind.WAIT_FOR_MESSAGE, NodeKind.END, NodeKind.GENERATE_DOCUMENT, NodeKind.EXTERNAL_TASK,
            NodeKind.RAISE_ERROR, NodeKind.SEND_SIGNAL, NodeKind.WAIT_FOR_SIGNAL, NodeKind.COMPENSATE, NodeKind.SEND_ADAPTER);

    private final Schema workflowSchema;
    private final Schema connectionSchema;

    public SchemaValidation() {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> builder
                .schemaRegistryConfig(SchemaRegistryConfig.builder().pathType(PathType.JSON_POINTER).build())
                .schemaLoader(loader -> loader.fetchRemoteResources(false)));
        this.workflowSchema = registry.getSchema(SchemaLocation.of(WORKFLOW_ID),
                loadSchema("orqix/schemas/workflow-v0.schema.json"));
        this.connectionSchema = registry.getSchema(SchemaLocation.of(CONNECTION_ID),
                loadSchema("orqix/schemas/connection-definition-v0.schema.json"));
        this.workflowSchema.initializeValidators();
        this.connectionSchema.initializeValidators();
    }

    private static JsonNode loadSchema(String resource) {
        try (InputStream in = SchemaValidation.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Schema resource missing from the classpath: " + resource);
            }
            return StrictJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read schema resource " + resource, e);
        }
    }

    /** @return problems in a workflow definition (unsorted, possibly with duplicates) */
    public List<Problem> validateWorkflow(JsonNode document) {
        List<Error> errors = workflowSchema.validate(document);
        List<Problem> problems = new ArrayList<>();
        for (Error error : errors) {
            String eval = error.getEvaluationPath().toString();
            String instance = error.getInstanceLocation().toString();
            if (eval.startsWith(NODES_ONE_OF)) {
                Problem p = nodeProblem(document, error, eval, instance);
                if (p != null) {
                    problems.add(p);
                }
                continue;
            }
            problems.add(SchemaMessages.describe(document, error, instance));
        }
        if (problems.isEmpty()) {
            problems.addAll(IntegerRangeCheck.check(document));
        }
        return problems;
    }

    /** @return human-readable problems in a connection definition; empty when valid */
    public List<String> validateConnection(JsonNode document) {
        List<String> problems = new ArrayList<>();
        for (Error error : connectionSchema.validate(document)) {
            String at = error.getInstanceLocation().toString();
            problems.add((at.isEmpty() ? "" : at + ": ") + error.getMessage());
        }
        return problems;
    }

    private static Problem nodeProblem(JsonNode document, Error error, String eval, String instance) {
        Matcher m = NODE_INSTANCE.matcher(instance);
        if (!m.matches()) {
            return SchemaMessages.describe(document, error, instance);
        }
        int index = Integer.parseInt(m.group(1));
        JsonNode node = document.path("nodes").path(index);
        String nodePath = "/nodes/" + index;
        NodeKind kind = node.isObject() && node.path("type").isString()
                ? NodeKind.fromJson(node.get("type").stringValue()) : null;
        if (kind == null) {
            // One explanation per node, attached to the oneOf error itself.
            if (!eval.equals(NODES_ONE_OF)) {
                return null;
            }
            if (!node.isObject()) {
                return new Problem(nodePath, "Each step must be an object with id, type, label and config.");
            }
            if (!node.has("type")) {
                return new Problem(JsonPointers.append(nodePath, "type"), "This step has no type. Use one of: "
                        + supportedKinds() + ".");
            }
            String text = node.get("type").isString() ? "\"" + node.get("type").stringValue() + "\" " : "";
            return new Problem(JsonPointers.append(nodePath, "type"), "The step type " + text
                    + "is not supported. Use one of: " + supportedKinds() + ".");
        }
        Matcher b = BRANCH_EVAL.matcher(eval);
        if (!b.matches() || Integer.parseInt(b.group(1)) != BRANCH_ORDER.indexOf(kind)) {
            return null;
        }
        return SchemaMessages.describe(document, error, instance);
    }

    private static String supportedKinds() {
        StringBuilder sb = new StringBuilder();
        for (NodeKind k : BRANCH_ORDER) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(k.json());
        }
        return sb.toString();
    }
}
