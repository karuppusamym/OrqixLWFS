package com.orqix.integration.adapters;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.integration.AdapterSteps;
import com.orqix.integration.EffectDispatcher;
import com.orqix.integration.EffectJournal;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sends a workflow step through an adapter with the effect journal (S68). The adapter row is read fresh for every attempt,
 * so a disabled adapter or a narrowed allowlist stops later sends; widening an allowlist adds nothing, because recipients
 * and topics are fixed in the step. Database reads are {@code readOnly} and may be repeated; email, Teams and Kafka sends
 * are {@code nonIdempotent}, so an attempt that ended without a recorded outcome becomes {@code unknown} and needs an
 * operator. The only transport is {@link LocalStubTransport}.
 */
@Service
public class AdapterStepService implements AdapterSteps {

    /** The stub answers at once; the lease only has to cover the journal writes. */
    static final Duration LEASE = Duration.ofSeconds(45);

    private record Row(String kind, String name, JsonNode config, boolean enabled, int version) {
    }

    private final JdbcClient jdbc;
    private final EffectJournal journal;
    private final AdapterTransport transport;
    private final Json json;

    public AdapterStepService(JdbcClient jdbc, EffectJournal journal, AdapterTransport transport, Json json) {
        this.jdbc = jdbc;
        this.journal = journal;
        this.transport = transport;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Fact> facts(UUID workspaceId) {
        return jdbc.sql("SELECT key, kind, name, enabled, config::text AS config FROM adapter_config WHERE workspace_id = :ws ORDER BY key")
                .param("ws", workspaceId)
                .query((rs, n) -> new Fact(rs.getString("key"), rs.getString("kind"), rs.getString("name"),
                        rs.getBoolean("enabled"), rs.getString("config"))).list();
    }

    @Override
    public EffectDispatcher.DispatchResult send(Send request) {
        AdapterRules.Kind kind = AdapterRules.Kind.of(request.kind());
        String effectClass = kind == AdapterRules.Kind.JDBC ? "readOnly" : "nonIdempotent";
        Row row = jdbc.sql("""
                SELECT kind, name, config::text AS config, enabled, version FROM adapter_config
                WHERE workspace_id = :ws AND key = :key
                """).param("ws", request.workspaceId()).param("key", request.adapterKey())
                .query((rs, n) -> new Row(rs.getString("kind"), rs.getString("name"), json.parse(rs.getString("config")),
                        rs.getBoolean("enabled"), rs.getInt("version"))).optional().orElse(null);
        String name = row == null ? request.adapterKey() : row.name();

        String denial = null;
        ObjectNode payload = null;
        if (row == null) {
            denial = "The adapter no longer exists";
        } else if (!row.enabled()) {
            denial = "The adapter is disabled";
        } else if (!row.kind().equals(kind.value())) {
            denial = "The adapter is no longer a " + kind.value() + " adapter";
        } else {
            try {
                payload = AdapterRules.validateSend(kind, row.config(), request.payload());
            } catch (AdapterRules.Invalid e) {
                // Recorded in the journal; the message itself is never stored.
                denial = "The adapter's current settings refuse this message (" + e.pointer() + "): " + e.getMessage();
            }
        }

        EffectJournal.EffectRequest effect = new EffectJournal.EffectRequest(request.workspaceId(), request.runId(),
                request.nodeId(), request.occurrence(),
                EffectDispatcher.effectKey(request.workspaceId(), request.runId(), request.nodeId(), request.occurrence()),
                request.adapterKey(), row == null ? 1 : row.version(), operation(kind), effectClass,
                json.canonicalSha256(request.payload()));
        EffectJournal.BeginResult begin = journal.begin(effect, request.leaseOwner(), LEASE, denial);
        EffectJournal.Effect entry = begin.effect();
        switch (begin.action()) {
            case ALREADY_SUCCEEDED -> {
                return new EffectDispatcher.Succeeded(entry.id(), entry.attempts(), (ObjectNode) entry.result(), null,
                        entry.remoteReference(), effectClass, true, null, null);
            }
            case UNKNOWN -> {
                return new EffectDispatcher.Failed(entry.id(), entry.attempts(), false, "EFFECT_UNKNOWN", "EFFECT_UNKNOWN",
                        "unknown", null, effectClass, "It is not known whether " + name + " took the message. An operator"
                        + " must confirm the outcome before anything is sent again.");
            }
            case BUSY -> {
                return new EffectDispatcher.Failed(entry.id(), entry.attempts(), true, "RETRIES_EXHAUSTED", "BUSY",
                        "retryable", null, effectClass, "Another attempt for this step is still in progress.");
            }
            case HASH_MISMATCH -> {
                return new EffectDispatcher.Failed(entry.id(), entry.attempts(), false, "INTERNAL_ERROR",
                        "REQUEST_HASH_MISMATCH", "permanent", null, effectClass,
                        "The message for this step changed unexpectedly.");
            }
            case POLICY_DENIED -> {
                return new EffectDispatcher.Failed(entry.id(), entry.attempts(), false, "CONNECTION_UNAVAILABLE",
                        "CONNECTION_UNAVAILABLE", "permanent", null, effectClass, "The adapter " + name
                        + " cannot send this message: " + denial + ". An administrator must fix the adapter, then an"
                        + " operator can retry.");
            }
            case PROCEED -> {
                // continue below
            }
        }
        AdapterTransport.Result delivered;
        try {
            delivered = transport.execute(kind, request.workspaceId(), request.adapterKey(), row.config(), payload);
        } catch (RuntimeException e) {
            // A read can simply be repeated; for a send we cannot tell whether it left, so it is never repeated blindly.
            boolean read = "readOnly".equals(effectClass);
            journal.finish(request.workspaceId(), entry.id(), read ? EffectJournal.RETRYABLE_FAILURE : EffectJournal.UNKNOWN,
                    null, "ADAPTER_FAILED", "The adapter transport failed", null, null);
            return read
                    ? new EffectDispatcher.Failed(entry.id(), entry.attempts(), true, "RETRIES_EXHAUSTED", "ADAPTER_FAILED",
                            "retryable", null, effectClass, "The read through " + name + " failed and will be tried again.")
                    : new EffectDispatcher.Failed(entry.id(), entry.attempts(), false, "EFFECT_UNKNOWN", "EFFECT_UNKNOWN",
                            "unknown", null, effectClass, "It is not known whether " + name + " took the message. An"
                            + " operator must confirm the outcome before anything is sent again.");
        }
        ObjectNode result = json.object();
        result.put("outcome", delivered.outcome());
        result.set("detail", delivered.detail());
        journal.finish(request.workspaceId(), entry.id(), EffectJournal.SUCCEEDED, null, null, null, result, null);
        return new EffectDispatcher.Succeeded(entry.id(), entry.attempts(), result, null, null, effectClass, false, null,
                null);
    }

    static String operation(AdapterRules.Kind kind) {
        return switch (kind) {
            case EMAIL -> "email.send";
            case TEAMS -> "teams.post";
            case KAFKA -> "kafka.produce";
            case JDBC -> "jdbc.read";
        };
    }
}
