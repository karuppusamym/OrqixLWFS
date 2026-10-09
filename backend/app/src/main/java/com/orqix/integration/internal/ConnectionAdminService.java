package com.orqix.integration.internal;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.SubjectService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.integration.AgentExecutor;
import com.orqix.integration.ConnectionDefinition;
import com.orqix.integration.ConnectionService;
import com.orqix.integration.EgressPolicy;
import com.orqix.integration.HttpExecutor;
import com.orqix.integration.api.ConnectionDtos;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Connections API (B05.2): definitions are immutable versions validated against connection-definition-v0.schema.json;
 * bindings pass the egress policy on save; secrets are referenced, never accepted or returned; tests call only
 * {@code testable} read-only operations through the real binding and egress policy.
 */
@Service
public class ConnectionAdminService {

    private static final Pattern BASE_URL = Pattern.compile("^https?://[^/?#@]+(/[^?#]*)?$");

    private final ConnectionService connections;
    private final PolicyService policy;
    private final SubjectService subjects;
    private final EgressPolicy egress;
    private final SecretStore secrets;
    private final HttpExecutor executor;
    private final AgentExecutor agents;
    private final AuditWriter audit;
    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;
    private final Schema definitionSchema;

    public ConnectionAdminService(ConnectionService connections, PolicyService policy, SubjectService subjects,
            EgressPolicy egress, SecretStore secrets, HttpExecutor executor, AgentExecutor agents, AuditWriter audit,
            JdbcClient jdbc, Json json, Clock clock) {
        this.connections = connections;
        this.policy = policy;
        this.subjects = subjects;
        this.egress = egress;
        this.secrets = secrets;
        this.executor = executor;
        this.agents = agents;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.definitionSchema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(SchemaLocation.of("classpath:orqix/schemas/connection-definition-v0.schema.json"));
    }

    // ---------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public ConnectionDtos.ConnectionList list(Caller caller, UUID workspaceId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.CONNECTION_READ, "listConnections", false);
        Map<String, List<ConnectionService.DefinitionRecord>> byKey = new LinkedHashMap<>();
        connections.definitions(workspaceId).forEach(d -> byKey.computeIfAbsent(d.key(), k -> new ArrayList<>()).add(d));
        List<ConnectionDtos.ConnectionSummary> items = new ArrayList<>();
        byKey.forEach((key, versions) -> {
            ConnectionService.DefinitionRecord latest = versions.get(versions.size() - 1);
            Optional<ConnectionService.Binding> binding = connections.binding(workspaceId, key);
            items.add(new ConnectionDtos.ConnectionSummary(key, latest.definition().name(), latest.definition().kind(),
                    latest.version(),
                    versions.stream().map(ConnectionService.DefinitionRecord::version).toList(),
                    new ConnectionDtos.BindingState(binding.isPresent(), binding.map(ConnectionService.Binding::enabled)
                            .orElse(false), binding.map(b -> host(b.baseUrl())).orElse(null)),
                    latest.definition().operations().values().stream()
                            .map(o -> new ConnectionDtos.OperationSummary(o.name(), o.label(), o.effectClass().value(),
                                    o.testable()))
                            .toList()));
        });
        return new ConnectionDtos.ConnectionList(items);
    }

    @Transactional(readOnly = true)
    public ConnectionDtos.ConnectionDetail get(Caller caller, UUID workspaceId, String key) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        List<ConnectionService.DefinitionRecord> versions = connections.definitions(workspaceId, key);
        if (versions.isEmpty()) {
            throw ApiException.notFound();
        }
        policy.require(access, Permission.CONNECTION_READ, "getConnection", false);
        Map<UUID, String> names = subjects.displayNames(versions.stream()
                .map(ConnectionService.DefinitionRecord::createdBy).toList());
        ConnectionDtos.Binding binding = access.has(Permission.CONNECTION_MANAGE)
                ? connections.binding(workspaceId, key).map(this::bindingView).orElse(null) : null;
        return new ConnectionDtos.ConnectionDetail(key, versions.stream().map(v -> record(v, names)).toList(), binding);
    }

    // ---------------------------------------------------------------- register a version

    @Transactional
    public ConnectionDtos.DefinitionRecord register(Caller caller, UUID workspaceId, String key, JsonNode definition) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.CONNECTION_MANAGE, "registerConnectionVersion", true);
        if (definition == null || !definition.isObject()) {
            throw ApiException.malformed("definition must be a JSON object.");
        }
        List<Error> errors = definitionSchema.validate(definition);
        if (!errors.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The connection definition is not valid.")
                    .withFieldErrors(errors.stream().limit(50).map(e -> new ApiException.FieldError(
                            "/definition" + pointer(e), e.getKeyword() == null ? "schema" : e.getKeyword(),
                            e.getMessage())).toList());
        }
        if (!key.equals(definition.path("key").asString())) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "definition.key must equal the connection key.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/definition/key", "const", "Must equal " + key)));
        }
        int version = definition.path("version").asInt();
        List<ConnectionService.DefinitionRecord> existing = connections.definitions(workspaceId, key);
        List<ApiException.FieldError> semantic = agentChecks(definition, existing);
        if (!semantic.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The connection definition is not valid.")
                    .withFieldErrors(semantic);
        }
        int latest = existing.isEmpty() ? 0 : existing.get(existing.size() - 1).version();
        if (existing.stream().anyMatch(d -> d.version() == version)) {
            throw new ApiException(ProblemCode.DUPLICATE_KEY, "This version is already registered; versions are immutable.");
        }
        if (version != latest + 1) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The next version must be " + (latest + 1) + ".")
                    .withFieldErrors(List.of(new ApiException.FieldError("/definition/version", "const",
                            "Expected " + (latest + 1))));
        }
        String text = json.write(definition);
        String hash = Json.canonicalSha256(text);
        Instant now = clock.instant();
        jdbc.sql("""
                INSERT INTO connection_definition (id, workspace_id, key, version, definition, definition_hash, created_by,
                                                   created_at)
                VALUES (:id, :ws, :key, :version, CAST(:definition AS jsonb), :hash, :by, :at)
                """)
                .param("id", Ids.newId()).param("ws", workspaceId).param("key", key).param("version", version)
                .param("definition", text).param("hash", hash).param("by", caller.subjectId()).param("at", Sql.ts(now))
                .update();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("key", key);
        metadata.put("version", version);
        metadata.put("definitionHash", hash);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "connection.version.created", "connection", key, AuditWriter.Outcome.SUCCESS, metadata));
        ConnectionService.DefinitionRecord record = connections.definition(workspaceId, key, version).orElseThrow();
        return record(record, subjects.displayNames(List.of(caller.subjectId())));
    }

    // ---------------------------------------------------------------- binding

    @Transactional
    public ConnectionDtos.Binding putBinding(Caller caller, UUID workspaceId, String key, String ifMatch,
            ConnectionDtos.PutBindingRequest request) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        if (connections.definitions(workspaceId, key).isEmpty()) {
            throw ApiException.notFound();
        }
        policy.require(access, Permission.CONNECTION_MANAGE, "putConnectionBinding", true);
        ConnectionDtos.Auth auth = request.auth();
        List<ApiException.FieldError> errors = new ArrayList<>();
        switch (auth.type()) {
            case "none" -> {
                if (auth.secretRef() != null || auth.headerName() != null) {
                    errors.add(new ApiException.FieldError("/auth", "additionalProperties",
                            "No secretRef or headerName for auth type none"));
                }
            }
            case "bearerToken" -> {
                if (auth.secretRef() == null || auth.headerName() != null) {
                    errors.add(new ApiException.FieldError("/auth/secretRef", "required", "secretRef is required"));
                }
            }
            case "apiKeyHeader" -> {
                if (auth.secretRef() == null || auth.headerName() == null) {
                    errors.add(new ApiException.FieldError("/auth", "required", "secretRef and headerName are required"));
                }
                if (connections.definitions(workspaceId, key).stream().anyMatch(d -> d.definition().isAgent())) {
                    errors.add(new ApiException.FieldError("/auth/type", "enum",
                            "An agent connection supports the auth types none and bearerToken"));
                }
            }
            default -> errors.add(new ApiException.FieldError("/auth/type", "enum", "Unknown auth type"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The credential reference is incomplete.")
                    .withFieldErrors(errors);
        }
        String host;
        try {
            egress.check(request.baseUrl());
            host = host(request.baseUrl());
        } catch (EgressPolicy.EgressDeniedException e) {
            throw new ApiException(ProblemCode.EGRESS_NOT_ALLOWED, e.getMessage());
        }
        if (!BASE_URL.matcher(request.baseUrl()).matches()) {
            throw new ApiException(ProblemCode.EGRESS_NOT_ALLOWED, "The base URL must be scheme://host[:port][/path].");
        }
        Optional<ConnectionService.Binding> existing = connections.binding(workspaceId, key);
        int revision;
        Instant now = clock.instant();
        if (existing.isPresent()) {
            if (ifMatch == null || ifMatch.isBlank()) {
                throw new ApiException(ProblemCode.PRECONDITION_REQUIRED);
            }
            int current = existing.get().revision();
            if (!("\"r" + current + "\"").equals(ifMatch.trim()) && !("r" + current).equals(ifMatch.trim())) {
                throw new ApiException(ProblemCode.STALE_REVISION, "The binding changed since you loaded it.")
                        .with("currentRevision", current);
            }
            revision = current + 1;
            int updated = jdbc.sql("""
                    UPDATE connection_binding SET base_url = :url, auth_type = :type, auth_header_name = :header,
                           secret_ref = :secret, enabled = :enabled, revision = :revision, updated_by = :by,
                           updated_at = :at
                    WHERE workspace_id = :ws AND connection_key = :key AND revision = :current
                    """)
                    .param("url", request.baseUrl()).param("type", auth.type()).param("header", auth.headerName())
                    .param("secret", auth.secretRef()).param("enabled", request.enabled()).param("revision", revision)
                    .param("by", caller.subjectId()).param("at", Sql.ts(now)).param("ws", workspaceId).param("key", key)
                    .param("current", current).update();
            if (updated != 1) {
                throw new ApiException(ProblemCode.STALE_REVISION).with("currentRevision", current);
            }
        } else {
            revision = 1;
            int inserted = jdbc.sql("""
                    INSERT INTO connection_binding (workspace_id, connection_key, base_url, auth_type, auth_header_name,
                                                    secret_ref, enabled, revision, updated_by, updated_at)
                    VALUES (:ws, :key, :url, :type, :header, :secret, :enabled, 1, :by, :at)
                    ON CONFLICT DO NOTHING
                    """)
                    .param("ws", workspaceId).param("key", key).param("url", request.baseUrl()).param("type", auth.type())
                    .param("header", auth.headerName()).param("secret", auth.secretRef())
                    .param("enabled", request.enabled()).param("by", caller.subjectId()).param("at", Sql.ts(now))
                    .update();
            if (inserted != 1) {
                throw new ApiException(ProblemCode.STALE_REVISION).with("currentRevision", 1);
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("key", key);
        metadata.put("host", host);
        metadata.put("authType", auth.type());
        metadata.put("secretRef", auth.secretRef());
        metadata.put("enabled", request.enabled());
        metadata.put("revision", revision);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "connection.binding.updated", "connection", key, AuditWriter.Outcome.SUCCESS, metadata));
        return bindingView(connections.binding(workspaceId, key).orElseThrow());
    }

    // ---------------------------------------------------------------- test

    @Transactional
    public ConnectionDtos.TestResult test(Caller caller, UUID workspaceId, String key, ConnectionDtos.TestRequest request) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        if (connections.definitions(workspaceId, key).isEmpty()) {
            throw ApiException.notFound();
        }
        policy.require(access, Permission.CONNECTION_MANAGE, "testConnection", true);
        ConnectionService.DefinitionRecord record = connections.definition(workspaceId, key, request.version())
                .orElseThrow(() -> new ApiException(ProblemCode.VALIDATION_FAILED, "Unknown definition version.")
                        .withFieldErrors(List.of(new ApiException.FieldError("/version", "enum", "Unknown version"))));
        ConnectionDefinition.Operation operation = record.definition().operations().get(request.operation());
        if (operation == null) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Unknown operation.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/operation", "enum", "Unknown operation")));
        }
        if (!operation.testable() || operation.effectClass() != ConnectionDefinition.EffectClass.READ_ONLY) {
            throw new ApiException(ProblemCode.OPERATION_NOT_TESTABLE, "Only read-only, testable operations can be tested.");
        }
        ConnectionService.Binding binding = connections.binding(workspaceId, key).filter(ConnectionService.Binding::enabled)
                .orElseThrow(() -> new ApiException(ProblemCode.CONNECTION_NOT_BOUND,
                        "Configure and enable the binding first."));
        long started = System.nanoTime();
        ConnectionDtos.TestResult result = record.definition().isAgent()
                ? testAgent(caller, workspaceId, record, operation, binding, request.input(), started)
                : testHttp(record, operation, binding, started);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("key", key);
        metadata.put("operation", request.operation());
        metadata.put("outcome", result.outcome());
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "connection.tested", "connection", key, AuditWriter.Outcome.SUCCESS, metadata));
        return result;
    }

    private ConnectionDtos.TestResult testHttp(ConnectionService.DefinitionRecord record,
            ConnectionDefinition.Operation operation, ConnectionService.Binding binding, long started) {
        ConnectionDtos.TestResult result;
        try {
            egress.check(binding.baseUrl());
            String secret = null;
            if (!"none".equals(binding.authType())) {
                secret = secrets.read(binding.secretRef()).orElse(null);
            }
            if (!"none".equals(binding.authType()) && secret == null) {
                result = new ConnectionDtos.TestResult("failed", null, elapsed(started), "CONNECTION_UNAVAILABLE",
                        "The credential is not available.");
            } else {
                HttpExecutor.CallResult call = executor.call(new HttpExecutor.Target(binding.baseUrl(),
                        binding.authType(), binding.authHeaderName(), binding.secretRef()), record.definition(), operation,
                        null, Map.of(), "connection-test-" + UUID.randomUUID(), operation.maxTimeoutSeconds(), secret);
                boolean ok = call.outcome() == HttpExecutor.Outcome.SUCCEEDED;
                result = new ConnectionDtos.TestResult(ok ? "succeeded" : "failed", call.httpStatus(), call.durationMs(),
                        ok ? null : call.errorCode(), ok ? null : call.detail());
            }
        } catch (EgressPolicy.EgressDeniedException e) {
            result = new ConnectionDtos.TestResult("failed", null, elapsed(started), "EGRESS_NOT_ALLOWED", e.getMessage());
        }
        return result;
    }

    /** Runs an agent operation once with a sample input (uses AI, metered like any AI use; S13 section 8). */
    private ConnectionDtos.TestResult testAgent(Caller caller, UUID workspaceId, ConnectionService.DefinitionRecord record,
            ConnectionDefinition.Operation operation, ConnectionService.Binding binding, JsonNode input, long started) {
        String unavailable = agents.unavailableReason();
        if (unavailable != null) {
            throw new ApiException(ProblemCode.AI_NOT_CONFIGURED, unavailable);
        }
        ObjectNode sample = sampleInput(operation, input);
        try {
            egress.check(binding.baseUrl());
        } catch (EgressPolicy.EgressDeniedException e) {
            return new ConnectionDtos.TestResult("failed", null, elapsed(started), "EGRESS_NOT_ALLOWED", e.getMessage());
        }
        String secret = null;
        if (!"none".equals(binding.authType())) {
            secret = secrets.read(binding.secretRef()).orElse(null);
            if (secret == null) {
                return new ConnectionDtos.TestResult("failed", null, elapsed(started), "CONNECTION_UNAVAILABLE",
                        "The credential is not available.");
            }
        }
        AgentExecutor.Result run = agents.execute(new AgentExecutor.Request(workspaceId, null, null,
                caller.subjectId(), "test", 1, record.definition(), operation, sample, Map.of(), binding.baseUrl(),
                secret, operation.maxTimeoutSeconds()));
        if (run.outcome() == HttpExecutor.Outcome.SUCCEEDED) {
            return new ConnectionDtos.TestResult("succeeded", null, elapsed(started), null, run.detail(), run.output(),
                    run.summary());
        }
        return new ConnectionDtos.TestResult("failed", null, elapsed(started), run.errorCode(), run.businessMessage());
    }

    /** The sample input must satisfy the operation's request schema (a flat object of scalars). */
    private ObjectNode sampleInput(ConnectionDefinition.Operation operation, JsonNode input) {
        if (input == null || !input.isObject()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "A sample input is required to test an agent.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/input", "required",
                            "Provide an object with the operation's input fields")));
        }
        ObjectNode schemaNode = (ObjectNode) operation.requestSchema().deepCopy();
        schemaNode.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        List<Error> errors = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schemaNode)
                .validate(input);
        if (!errors.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The sample input does not match the operation.")
                    .withFieldErrors(errors.stream().limit(20).map(e -> new ApiException.FieldError(
                            "/input" + pointer(e), e.getKeyword() == null ? "schema" : e.getKeyword(), e.getMessage()))
                            .toList());
        }
        return (ObjectNode) input;
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");

    /**
     * Checks that the JSON Schema cannot express: an agent keeps its kind across versions (the binding of the key is an
     * MCP endpoint or an HTTP service, never both), tool names are unique, and every summary placeholder names an
     * input field, a result field or {tools}.
     */
    private static List<ApiException.FieldError> agentChecks(JsonNode definition,
            List<ConnectionService.DefinitionRecord> existing) {
        List<ApiException.FieldError> errors = new ArrayList<>();
        String kind = definition.path("kind").asString("http");
        for (ConnectionService.DefinitionRecord previous : existing) {
            if (!previous.definition().kind().equals(kind)) {
                errors.add(new ApiException.FieldError("/definition/kind", "const", "A connection keeps its kind ("
                        + previous.definition().kind() + ") across versions"));
                break;
            }
        }
        if (!"agent".equals(kind)) {
            return errors;
        }
        for (Map.Entry<String, JsonNode> operation : definition.path("operations").properties()) {
            String base = "/definition/operations/" + operation.getKey() + "/agent";
            JsonNode agent = operation.getValue().path("agent");
            java.util.Set<String> names = new java.util.HashSet<>();
            for (JsonNode tool : agent.path("tools")) {
                if (!names.add(tool.path("name").asString())) {
                    errors.add(new ApiException.FieldError(base + "/tools", "uniqueItems",
                            "Tool " + tool.path("name").asString() + " is listed twice"));
                }
            }
            java.util.Set<String> known = new java.util.HashSet<>(java.util.Set.of("tools"));
            operation.getValue().path("requestSchema").path("properties").propertyNames().forEach(known::add);
            operation.getValue().path("responseSchema").path("properties").propertyNames().forEach(known::add);
            java.util.regex.Matcher matcher = PLACEHOLDER.matcher(agent.path("summary").asString(""));
            while (matcher.find()) {
                if (!known.contains(matcher.group(1))) {
                    errors.add(new ApiException.FieldError(base + "/summary", "pattern",
                            "{" + matcher.group(1) + "} is neither an input field, a result field nor {tools}"));
                }
            }
        }
        return errors;
    }

    // ---------------------------------------------------------------- helpers

    private ConnectionDtos.DefinitionRecord record(ConnectionService.DefinitionRecord record, Map<UUID, String> names) {
        return new ConnectionDtos.DefinitionRecord(record.version(), record.definition().document(), record.definitionHash(),
                record.createdAt(), subjects.ref(record.createdBy(), names));
    }

    private ConnectionDtos.Binding bindingView(ConnectionService.Binding binding) {
        return new ConnectionDtos.Binding(binding.connectionKey(), binding.baseUrl(),
                new ConnectionDtos.Auth(binding.authType(), binding.authHeaderName(), binding.secretRef()),
                binding.enabled(), binding.revision(), binding.updatedAt(), subjects.ref(binding.updatedBy()));
    }

    private static String host(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            return uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String pointer(Error error) {
        String location = error.getInstanceLocation() == null ? "" : error.getInstanceLocation().toString();
        if (location.startsWith("$")) {
            location = location.substring(1).replace('.', '/');
        }
        return location.isEmpty() || location.startsWith("/") ? location : "/" + location;
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
