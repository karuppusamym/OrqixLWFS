package com.orqix.integration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.orqix.integration.health.CallGuard;
import com.orqix.integration.internal.SecretStore;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One read of a lookup field's pinned read-only operation (CG-F02, docs/build/S140). It goes through the binding, the
 * egress policy, the credential, the circuit breaker and the binding's request limit exactly like a workflow call
 * (S131), but nothing is journaled or stored: the rows are returned to the caller and only the health counters (status
 * and duration, never values) are recorded.
 */
@Service
public class FormLookupCalls {

    /** At most this many rows are read from one answer. */
    public static final int MAX_ROWS = 50;
    /** A lookup is interactive: never more than this, whatever the operation allows. */
    static final int MAX_TIMEOUT_SECONDS = 10;

    public enum Status {
        /** The operation answered; {@link Answer#rows} holds its rows. */
        OK,
        /** The value does not fit the operation's input (for example too short for a pattern): nothing was sent. */
        NOT_APPLICABLE,
        /** Circuit open or request limit used up; try again at {@link Answer#retryAt}. */
        DEFERRED,
        /** Binding missing or disabled, endpoint not allowed, credential missing, or the call failed. */
        UNAVAILABLE
    }

    /** A row of text columns (other column types are given as text). */
    public record Answer(Status status, List<Map<String, String>> rows, boolean truncated, Instant retryAt,
            String code) {
        static Answer of(Status status, String code) {
            return new Answer(status, List.of(), false, null, code);
        }
    }

    private final ConnectionService connections;
    private final EgressPolicy egress;
    private final SecretStore secrets;
    private final HttpExecutor executor;
    private final CallGuard guard;
    private final Json json;
    private final SchemaRegistry schemas = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    public FormLookupCalls(ConnectionService connections, EgressPolicy egress, SecretStore secrets,
            HttpExecutor executor, CallGuard guard, Json json) {
        this.connections = connections;
        this.egress = egress;
        this.secrets = secrets;
        this.executor = executor;
        this.guard = guard;
        this.json = json;
    }

    /**
     * Runs the operation with one text input.
     *
     * @param rowsPointer a v1 row projection such as {@code /result/[*]}
     */
    public Answer read(UUID workspaceId, String connectionKey, int version, String operationName, String input,
            String value, String rowsPointer) {
        ConnectionService.DefinitionRecord record = connections.definition(workspaceId, connectionKey, version)
                .orElse(null);
        if (record == null) {
            return Answer.of(Status.UNAVAILABLE, "CONNECTION_UNKNOWN");
        }
        ConnectionDefinition.Operation operation = record.definition().operations().get(operationName);
        if (operation == null || !operation.v1()
                || operation.effectClass() != ConnectionDefinition.EffectClass.READ_ONLY) {
            return Answer.of(Status.UNAVAILABLE, "OPERATION_UNKNOWN");
        }
        ObjectNode body = json.object();
        body.put(input, value);
        if (!fits(operation, input, body)) {
            return Answer.of(Status.NOT_APPLICABLE, "INPUT_NOT_APPLICABLE");
        }
        ConnectionService.Binding binding = connections.binding(workspaceId, connectionKey)
                .filter(ConnectionService.Binding::enabled).orElse(null);
        if (binding == null) {
            return Answer.of(Status.UNAVAILABLE, "CONNECTION_NOT_BOUND");
        }
        try {
            egress.check(binding.baseUrl());
        } catch (EgressPolicy.EgressDeniedException e) {
            return Answer.of(Status.UNAVAILABLE, "EGRESS_NOT_ALLOWED");
        }
        String secret = null;
        if (!"none".equals(binding.authType())) {
            secret = secrets.credential(binding.authType(), binding.secretRef(), workspaceId).orElse(null);
            if (secret == null) {
                if (binding.authType().startsWith("oauth2")) {
                    guard.tokenFailure(workspaceId, connectionKey, operationName);
                }
                return Answer.of(Status.UNAVAILABLE, "CREDENTIAL_UNAVAILABLE");
            }
        }
        int timeout = Math.max(1, Math.min(MAX_TIMEOUT_SECONDS, operation.maxTimeoutSeconds()));
        CallGuard.Ticket ticket;
        try {
            ticket = guard.before(workspaceId, connectionKey, operationName, timeout);
        } catch (CallDeferredException e) {
            return new Answer(Status.DEFERRED, List.of(), false, e.until(), e.reason());
        }
        Map<String, String> mapping = Map.of("rows", rowsPointer);
        HttpExecutor.CallResult call = executor.call(new HttpExecutor.Target(binding.baseUrl(), binding.authType(),
                binding.authHeaderName(), binding.secretRef(), workspaceId), record.definition(), operation, body,
                mapping, "lookup-" + UUID.randomUUID(), timeout, secret);
        guard.after(workspaceId, connectionKey, operationName, ticket, call);
        if (call.outcome() != HttpExecutor.Outcome.SUCCEEDED || call.result() == null) {
            return Answer.of(Status.UNAVAILABLE, call.errorCode() == null ? "LOOKUP_FAILED" : call.errorCode());
        }
        JsonNode rows = call.result().get("rows");
        List<Map<String, String>> out = new ArrayList<>();
        boolean truncated = call.page() != null && call.page().truncated() != null && !call.page().truncated().isEmpty();
        if (rows != null && rows.isArray()) {
            for (JsonNode row : rows) {
                if (out.size() >= MAX_ROWS) {
                    truncated = true;
                    break;
                }
                if (!row.isObject()) {
                    continue;
                }
                Map<String, String> columns = new LinkedHashMap<>();
                for (Map.Entry<String, JsonNode> e : row.properties()) {
                    JsonNode v = e.getValue();
                    if (v != null && v.isValueNode() && !v.isNull()) {
                        columns.put(e.getKey(), v.isString() ? v.stringValue() : v.toString());
                    }
                }
                out.add(columns);
            }
        }
        return new Answer(Status.OK, List.copyOf(out), truncated, null, null);
    }

    /** The value must satisfy the input's own schema (pattern, length), so no call is made that must fail. */
    private boolean fits(ConnectionDefinition.Operation operation, String input, ObjectNode body) {
        JsonNode field = operation.requestSchema().path("properties").get(input);
        if (field == null) {
            JsonNode params = operation.source() == null ? null : operation.source().path("pathParams").get(input);
            field = params;
        }
        if (field == null || !field.isObject()) {
            return false;
        }
        ObjectNode schema = json.object();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        schema.putObject("properties").set(input, field.deepCopy());
        try {
            return schemas.getSchema(schema).validate(body).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
