package com.orqix.integration.kafka;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.audit.AuditWriter;
import com.orqix.integration.KafkaDispatch;
import com.orqix.integration.adapters.AdapterRules;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Handles one Kafka record for one trigger (docs/build/S99 K4-K8, K12). The record row and the start or message are one
 * transaction, so the offset may be committed as soon as it returns; a repeat of the dedupe key is dropped. Anything
 * that can never succeed becomes a dead-letter row (no value text, only its digest and size). Topic, workflow, message
 * name and mapping come from the trigger: the record's content only fills mapped values and the correlation key.
 */
@Component
public class KafkaRecordHandler {

    public static final int MAX_VALUE_BYTES = 64 * 1024;
    private static final Pattern PRINTABLE_KEY = Pattern.compile("^[\\x21-\\x7e]{1,200}$");

    /** One consumed record: partition, offset, raw key and value (either may be null). */
    public record Incoming(int partition, long offset, byte[] key, byte[] value) {
    }

    /**
     * @param outcome {@code started}, {@code resumed}, {@code buffered}, {@code duplicate}, {@code dead} or {@code retry}
     * @param code    error code for {@code dead} and {@code retry}
     */
    public record Result(String outcome, String code, UUID runId) {

        public boolean retry() {
            return "retry".equals(outcome);
        }
    }

    private final KafkaDispatch dispatch;
    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;
    private final AuditWriter audit;
    private final Json json;
    private final Clock clock;

    public KafkaRecordHandler(KafkaDispatch dispatch, TransactionTemplate transactions, JdbcClient jdbc,
            AuditWriter audit, Json json, Clock clock) {
        this.dispatch = dispatch;
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param attempt     1 for the first try of this record on this consumer
     * @param lastAttempt true when a transient failure must now become a dead letter
     */
    public Result handle(KafkaTriggerService.Trigger trigger, Incoming in, String instance, int attempt,
            boolean lastAttempt) {
        String dedupe = dedupeKey(in);
        byte[] value = in.value() == null ? new byte[0] : in.value();
        String sha = Json.sha256Hex(value);
        if (exists(trigger.id(), dedupe)) {
            return new Result("duplicate", null, null);
        }
        // Permanent checks first: these records can never succeed.
        if (value.length == 0) {
            return dead(trigger, in, dedupe, sha, value.length, attempt, instance, "empty_value");
        }
        if (value.length > MAX_VALUE_BYTES) {
            return dead(trigger, in, dedupe, sha, value.length, attempt, instance, "too_large");
        }
        JsonNode payload;
        try {
            payload = json.parse(value);
        } catch (RuntimeException e) {
            return dead(trigger, in, dedupe, sha, value.length, attempt, instance, "malformed");
        }
        if (!payload.isObject()) {
            return dead(trigger, in, dedupe, sha, value.length, attempt, instance, "not_an_object");
        }
        if (Json.depth(payload) > 10) {
            return dead(trigger, in, dedupe, sha, value.length, attempt, instance, "too_deep");
        }
        ObjectNode mapped;
        try {
            mapped = trigger.mapping().isEmpty() ? json.object() : AdapterRules.mapInput(trigger.mapping(), payload);
        } catch (AdapterRules.Invalid e) {
            return dead(trigger, in, dedupe, sha, value.length, attempt, instance, "unmapped");
        }
        String correlation = null;
        if ("resume".equals(trigger.action())) {
            JsonNode c;
            try {
                c = payload.at(trigger.correlationPointer());
            } catch (RuntimeException e) {
                c = null;
            }
            if (c == null || !(c.isString() || c.isIntegralNumber()) || c.asString().isEmpty() || c.asString().length() > 200) {
                return dead(trigger, in, dedupe, sha, value.length, attempt, instance, "no_correlation_key");
            }
            correlation = c.asString();
        }
        String digest = Json.sha256Hex(trigger.id() + ":" + dedupe);
        String idempotencyKey = "kafka:" + digest.substring(0, 40);
        String finalCorrelation = correlation;
        try {
            Result result = transactions.execute(status -> {
                UUID id = Ids.newId();
                int inserted = jdbc.sql("""
                        INSERT INTO kafka_inbound_record (id, workspace_id, trigger_id, dedupe_key, topic_partition, record_offset,
                                                          status, value_sha256, value_bytes, attempts, instance_id, received_at)
                        VALUES (:id, :ws, :t, :key, :p, :o, 'started', :sha, :bytes, :attempts, :instance, :now)
                        ON CONFLICT (trigger_id, dedupe_key) DO NOTHING
                        """).param("id", id).param("ws", trigger.workspaceId()).param("t", trigger.id())
                        .param("key", dedupe).param("p", in.partition()).param("o", in.offset()).param("sha", sha)
                        .param("bytes", value.length).param("attempts", attempt).param("instance", instance)
                        .param("now", Sql.ts(clock.instant())).update();
                if (inserted == 0) {
                    return new Result("duplicate", null, null);
                }
                KafkaDispatch.Dispatched done = "start".equals(trigger.action())
                        ? dispatch.start(trigger.workspaceId(), trigger.runAs(), trigger.workflowId(), idempotencyKey,
                                "kf-" + digest.substring(0, 12), mapped)
                        : dispatch.resume(trigger.workspaceId(), trigger.runAs(), trigger.messageName(), finalCorrelation,
                                idempotencyKey, mapped);
                String outcome = done.outcome().name().toLowerCase(java.util.Locale.ROOT);
                jdbc.sql("UPDATE kafka_inbound_record SET status = :s, run_id = :run, message_id = :m WHERE id = :id")
                        .param("s", outcome).param("run", done.runId()).param("m", done.messageId()).param("id", id)
                        .update();
                return new Result(outcome, null, done.runId());
            });
            return result;
        } catch (KafkaDispatch.Refused e) {
            return dead(trigger, in, dedupe, sha, value.length, attempt, instance, e.code());
        } catch (KafkaDispatch.Retry e) {
            return lastAttempt ? dead(trigger, in, dedupe, sha, value.length, attempt, instance, "retries_exhausted")
                    : new Result("retry", e.code(), null);
        } catch (RuntimeException e) {
            return lastAttempt ? dead(trigger, in, dedupe, sha, value.length, attempt, instance, "retries_exhausted")
                    : new Result("retry", "unavailable", null);
        }
    }

    private boolean exists(UUID triggerId, String dedupe) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM kafka_inbound_record WHERE trigger_id = :t AND dedupe_key = :k)")
                .param("t", triggerId).param("k", dedupe).query(Boolean.class).single());
    }

    private Result dead(KafkaTriggerService.Trigger trigger, Incoming in, String dedupe, String sha, int bytes,
            int attempt, String instance, String code) {
        String error = code == null || !code.matches("^[a-z_]{1,40}$") ? "refused" : code;
        Boolean recorded = transactions.execute(status -> {
            int inserted = jdbc.sql("""
                    INSERT INTO kafka_inbound_record (id, workspace_id, trigger_id, dedupe_key, topic_partition, record_offset,
                                                      status, error_code, value_sha256, value_bytes, attempts, instance_id,
                                                      received_at)
                    VALUES (:id, :ws, :t, :key, :p, :o, 'dead', :error, :sha, :bytes, :attempts, :instance, :now)
                    ON CONFLICT (trigger_id, dedupe_key) DO NOTHING
                    """).param("id", Ids.newId()).param("ws", trigger.workspaceId()).param("t", trigger.id())
                    .param("key", dedupe).param("p", in.partition()).param("o", in.offset()).param("error", error)
                    .param("sha", sha).param("bytes", bytes).param("attempts", attempt).param("instance", instance)
                    .param("now", Sql.ts(clock.instant())).update();
            if (inserted == 1) {
                audit.write(new AuditWriter.Entry(trigger.workspaceId(), AuditWriter.ActorType.SYSTEM, null,
                        "kafka.record-dead", "kafkaTrigger", trigger.id().toString(), AuditWriter.Outcome.FAILED,
                        KafkaTriggerService.deadAudit(trigger.id(), in.partition(), in.offset(), error)));
            }
            return inserted == 1;
        });
        return Boolean.TRUE.equals(recorded) ? new Result("dead", error, null) : new Result("duplicate", null, null);
    }

    /** The record key when it is short printable text, else the record's position (K5). */
    static String dedupeKey(Incoming in) {
        if (in.key() != null && in.key().length > 0 && in.key().length <= 200) {
            try {
                String key = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(in.key())).toString();
                if (PRINTABLE_KEY.matcher(key).matches()) {
                    return "k:" + key;
                }
            } catch (CharacterCodingException e) {
                // fall through to the position
            }
        }
        return "o:" + in.partition() + "/" + in.offset();
    }
}
