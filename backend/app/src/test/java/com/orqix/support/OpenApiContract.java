package com.orqix.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.springframework.test.web.servlet.MvcResult;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-API-01: validates every integration-test response against {@code contracts/openapi.yaml} (OpenAPI 3.1, JSON
 * Schema 2020-12). Pragmatic validator: the YAML is converted to JSON and registered with the networknt validator
 * under a synthetic base URI, so component {@code $ref}s and the relative refs to the workflow/connection schemas
 * resolve; the response schema is addressed by JSON Pointer ({@code #/paths/...}). Checked: the status is declared for
 * the operation (problem statuses that the contract omits are recorded as gaps and validated against
 * {@code Problem}), the content type, required headers (ETag, Idempotent-Replayed, Retry-After), and the body.
 */
public final class OpenApiContract {

    static final String BASE = "https://contract.orqix.test/";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static volatile OpenApiContract instance;

    /** Problem statuses returned by the implementation although the operation does not declare them. */
    public static final Set<String> UNDECLARED_PROBLEM_STATUSES = ConcurrentHashMap.newKeySet();

    private final JsonNode document;
    private final SchemaRegistry registry;
    private final List<Operation> operations = new ArrayList<>();
    private final Map<String, Schema> schemas = new ConcurrentHashMap<>();

    record Operation(String method, String template, Pattern pattern, String pointer, JsonNode node, String id) {
    }

    private OpenApiContract() {
        try {
            Path contracts = Path.of(System.getProperty("orqix.repoRoot", "../.."), "contracts");
            // Jackson's YAML tree reader leaves aliases (*id001, emitted by the contract generator) as plain strings.
            document = JSON.valueToTree(new Load(LoadSettings.builder().build())
                    .loadFromString(Files.readString(contracts.resolve("openapi.yaml"))));
            Map<String, String> docs = new HashMap<>();
            docs.put(BASE + "openapi.json", JSON.writeValueAsString(document));
            docs.put(BASE + "workflow-v0.schema.json",
                    Files.readString(contracts.resolve("workflow-v0.schema.json"), StandardCharsets.UTF_8));
            docs.put(BASE + "connection-definition-v0.schema.json",
                    Files.readString(contracts.resolve("connection-definition-v0.schema.json"), StandardCharsets.UTF_8));
            registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> builder
                    .schemas(docs)
                    .schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load contracts/openapi.yaml", e);
        }
        for (Map.Entry<String, JsonNode> path : document.path("paths").properties()) {
            String template = path.getKey();
            Pattern pattern = Pattern.compile("^" + template.replaceAll("\\{[^/]+}", "[^/]+") + "$");
            for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
                if (op.getValue().has("operationId")) {
                    String pointer = "/paths/" + escape(template) + "/" + op.getKey();
                    operations.add(new Operation(op.getKey().toUpperCase(), template, pattern, pointer, op.getValue(),
                            op.getValue().get("operationId").asString()));
                }
            }
        }
    }

    public static OpenApiContract get() {
        if (instance == null) {
            synchronized (OpenApiContract.class) {
                if (instance == null) {
                    instance = new OpenApiContract();
                }
            }
        }
        return instance;
    }

    public JsonNode document() {
        return document;
    }

    public List<Operation> operations() {
        return List.copyOf(operations);
    }

    /** Validates one MockMvc exchange under {@code /api/v1}; throws AssertionError on a contract violation. */
    public static void validate(MvcResult result) {
        get().check(result);
    }

    private void check(MvcResult result) {
        String uri = result.getRequest().getRequestURI();
        if (!uri.startsWith("/api/v1")) {
            return;
        }
        String path = uri.substring("/api/v1".length());
        String method = result.getRequest().getMethod();
        int status = result.getResponse().getStatus();
        Operation operation = operations.stream()
                .filter(o -> o.method().equals(method) && o.pattern().matcher(path).matches())
                .min((a, b) -> Integer.compare(a.template().split("\\{").length, b.template().split("\\{").length))
                .orElse(null);
        String contentType = result.getResponse().getContentType();
        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        if (operation == null) {
            if (status >= 400) {
                validateProblem(method + " " + path, contentType, body);
                return;
            }
            throw new AssertionError("No contract operation for " + method + " " + path + " (status " + status + ")");
        }
        JsonNode responses = operation.node().path("responses");
        String statusKey = String.valueOf(status);
        if (!responses.has(statusKey)) {
            if (status >= 400) {
                UNDECLARED_PROBLEM_STATUSES.add(operation.id() + " " + status);
                validateProblem(operation.id(), contentType, body);
                return;
            }
            throw new AssertionError(operation.id() + ": undeclared status " + status + " body " + body);
        }
        JsonNode response = responses.get(statusKey);
        String responsePointer = operation.pointer() + "/responses/" + statusKey;
        if (response.has("$ref")) {
            responsePointer = response.get("$ref").asString().substring(1);
            response = document.at(responsePointer);
        }
        for (Map.Entry<String, JsonNode> header : response.path("headers").properties()) {
            String name = header.getKey();
            if (Set.of("ETag", "Idempotent-Replayed", "Retry-After").contains(name)
                    && result.getResponse().getHeader(name) == null
                    && !(name.equals("Retry-After"))) {
                throw new AssertionError(operation.id() + " " + status + ": missing header " + name);
            }
        }
        JsonNode content = response.path("content");
        if (content.isMissingNode() || content.isEmpty()) {
            if (!body.isEmpty()) {
                throw new AssertionError(operation.id() + " " + status + ": unexpected body " + body);
            }
            return;
        }
        String mediaType = contentType == null ? "" : contentType.split(";")[0].trim();
        if (!content.has(mediaType)) {
            throw new AssertionError(operation.id() + " " + status + ": content type " + contentType
                    + " not in " + content.propertyNames());
        }
        if ("application/zip".equals(mediaType) || "application/octet-stream".equals(mediaType)
                || "application/pdf".equals(mediaType)
                || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mediaType)) {
            return;
        }
        String schemaPointer = responsePointer + "/content/" + escape(mediaType) + "/schema";
        validateBody(operation.id() + " " + status, schemaPointer, mediaType.startsWith("text/") || "application/xml".equals(mediaType) ? JSON.writeValueAsString(body) : body);
    }

    private void validateProblem(String what, String contentType, String body) {
        if (contentType == null || !contentType.startsWith("application/problem+json")) {
            throw new AssertionError(what + ": error without problem+json (" + contentType + "): " + body);
        }
        validateBody(what, "/components/schemas/Problem", body);
    }

    private void validateBody(String what, String pointer, String body) {
        Schema schema = schemas.computeIfAbsent(pointer,
                p -> registry.getSchema(SchemaLocation.of(BASE + "openapi.json#"
                        + p.replace("{", "%7B").replace("}", "%7D"))));
        JsonNode node;
        try {
            node = JSON.readTree(body);
        } catch (RuntimeException e) {
            throw new AssertionError(what + ": body is not JSON: " + body);
        }
        List<Error> errors = schema.validate(node);
        if (!errors.isEmpty()) {
            throw new AssertionError(what + ": response violates contract: " + errors + "\nbody: " + body);
        }
    }

    private static String escape(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }
}
