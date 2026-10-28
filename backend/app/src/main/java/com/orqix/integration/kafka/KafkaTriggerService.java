package com.orqix.integration.kafka;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.integration.adapters.AdapterRules;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Kafka trigger configuration, status and dead letters (docs/build/S99 (c)). Brokers are named clusters of the
 * deployment; the topic must be on that cluster's allow-list; credentials are only a secret reference. The run-as person
 * is whoever creates or last enables the trigger, and must hold the right to do what the trigger does.
 */
@Service
public class KafkaTriggerService {

    public static final int MAX_TRIGGERS_PER_WORKSPACE = 5;
    static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9-]{0,62}[a-z0-9]$");
    static final Pattern GROUP = Pattern.compile("^[A-Za-z0-9._-]{1,200}$");
    static final Pattern SECRET = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");
    static final Pattern MESSAGE = Pattern.compile("^[a-z][a-zA-Z0-9]*(-[a-z0-9][a-zA-Z0-9]*)*$");

    public record CreateTrigger(@NotBlank String key, @NotBlank @Size(max = 100) String name, @NotBlank String cluster,
            @NotBlank String topic, String consumerGroup, String secretRef, @NotBlank String action, UUID workflowId,
            String messageName, String correlationPointer, @NotNull JsonNode mapping,
            @Min(1) @Max(100) Integer batchSize, @Min(1) @Max(200) Integer maxPerSecond, String startFrom) {
    }

    /** Key, cluster, topic, group, action and target never change; {@code expectedVersion} is the version loaded. */
    public record UpdateTrigger(@NotBlank @Size(max = 100) String name, String secretRef, @NotNull JsonNode mapping,
            @NotNull @Min(1) @Max(100) Integer batchSize, @NotNull @Min(1) @Max(200) Integer maxPerSecond,
            @NotNull Boolean enabled, @NotNull @Min(1) Integer expectedVersion) {
    }

    public record ReplicaStatus(String instanceId, List<Integer> partitions, long lag, boolean running,
            Instant lastPollAt) {
    }

    /** Status summed over replicas that reported in the last two minutes. */
    public record Status(long lag, long handled, long duplicates, long dead, String lastError, Instant lastErrorAt,
            List<ReplicaStatus> replicas) {
    }

    public record TriggerView(UUID id, String key, String name, String cluster, String topic, String consumerGroup,
            String secretRef, String action, UUID workflowId, String messageName, String correlationPointer,
            JsonNode mapping, int batchSize, int maxPerSecond, String startFrom, UUID runAs, boolean enabled,
            int version, Instant createdAt, Instant updatedAt, Status status) {
    }

    public record ClusterView(String name, List<String> allowedTopics) {
    }

    public record TriggerList(String mode, List<ClusterView> clusters, List<TriggerView> items) {
    }

    public record DeadLetter(UUID id, UUID triggerId, String triggerKey, int partition, long offset, String keyDigest,
            String errorCode, int valueBytes, String valueSha256, int attempts, Instant receivedAt) {
    }

    public record DeadLetterList(List<DeadLetter> items) {
    }

    public record TestMessage(String key, @NotNull JsonNode value) {
    }

    /** What {@link KafkaRecordHandler} needs about a trigger. */
    public record Trigger(UUID id, UUID workspaceId, String key, String cluster, String topic, String consumerGroup,
            String secretRef, String action, UUID workflowId, String messageName, String correlationPointer,
            JsonNode mapping, int batchSize, int maxPerSecond, String startFrom, UUID runAs, boolean enabled,
            int version) {
    }

    private final PolicyService policy;
    private final KafkaSettings settings;
    private final KafkaRecordHandler handler;
    private final AuditWriter audit;
    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;

    public KafkaTriggerService(PolicyService policy, KafkaSettings settings, KafkaRecordHandler handler,
            AuditWriter audit, JdbcClient jdbc, Json json, Clock clock) {
        this.policy = policy;
        this.settings = settings;
        this.handler = handler;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- configuration

    @Transactional
    public TriggerView create(Caller caller, UUID workspaceId, CreateTrigger r) {
        WorkspaceAccess access = manage(caller, workspaceId, "createKafkaTrigger");
        if (!KEY.matcher(r.key()).matches()) {
            throw invalid("/key", "Use lowercase letters, digits and dashes, 2-64 characters.");
        }
        if (!settings.clusters().containsKey(r.cluster())) {
            throw invalid("/cluster", "Choose a cluster this installation defines.");
        }
        if (!settings.topics(r.cluster()).contains(r.topic())) {
            throw invalid("/topic", "The topic is not on the cluster's allow-list.");
        }
        String group = r.consumerGroup() == null || r.consumerGroup().isBlank() ? "orqix-" + r.key() : r.consumerGroup();
        if (!GROUP.matcher(group).matches()) {
            throw invalid("/consumerGroup", "Use letters, digits, dot, dash or underscore, at most 200 characters.");
        }
        secretRef(r.secretRef());
        String startFrom = r.startFrom() == null ? "latest" : r.startFrom();
        if (!List.of("earliest", "latest").contains(startFrom)) {
            throw invalid("/startFrom", "Choose earliest or latest.");
        }
        ObjectNode mapping;
        if ("start".equals(r.action())) {
            if (r.workflowId() == null || r.messageName() != null || r.correlationPointer() != null) {
                throw invalid("/workflowId", "A start trigger names a workflow and nothing to correlate.");
            }
            boolean exists = jdbc.sql("SELECT count(*) FROM workflow WHERE id = :id AND workspace_id = :ws")
                    .param("id", r.workflowId()).param("ws", workspaceId).query(Long.class).single() > 0;
            if (!exists) {
                throw invalid("/workflowId", "Choose a workflow of this workspace.");
            }
            if (!access.canStart(r.workflowId())) {
                // The trigger starts requests as this person; they must be allowed to start them now.
                throw policy.deny(workspaceId, caller.subjectId(), "workflow.start", "createKafkaTrigger", true,
                        new ApiException(ProblemCode.FORBIDDEN, "You may not start this workflow, so a trigger cannot start it as you."));
            }
            mapping = mapping(r.mapping(), false);
        } else if ("resume".equals(r.action())) {
            if (r.workflowId() != null || r.messageName() == null || !MESSAGE.matcher(r.messageName()).matches()
                    || r.messageName().length() > 64) {
                throw invalid("/messageName", "A resume trigger names the message the waiting step listens for.");
            }
            if (r.correlationPointer() == null || !r.correlationPointer().startsWith("/") || r.correlationPointer().length() > 200) {
                throw invalid("/correlationPointer", "Use a JSON Pointer to the value that identifies the request, such as /orderNumber.");
            }
            if (!access.has(Permission.MESSAGE_SEND)) {
                throw policy.deny(workspaceId, caller.subjectId(), "message.send", "createKafkaTrigger", true,
                        new ApiException(ProblemCode.FORBIDDEN, "You may not send messages, so a trigger cannot send them as you."));
            }
            mapping = mapping(r.mapping(), true);
        } else {
            throw invalid("/action", "Choose start or resume.");
        }
        requireRoom(workspaceId, true);
        UUID id = Ids.newId();
        Instant now = clock.instant();
        try {
            jdbc.sql("""
                    INSERT INTO kafka_trigger (id, workspace_id, key, name, cluster, topic, consumer_group, secret_ref, action,
                                               workflow_id, message_name, correlation_pointer, mapping, batch_size,
                                               max_per_second, start_from, run_as, enabled, created_by, created_at)
                    VALUES (:id, :ws, :key, :name, :cluster, :topic, :group, :secret, :action, :wf, :msg, :ptr,
                            CAST(:mapping AS jsonb), :batch, :rate, :from, :by, true, :by, :now)
                    """).param("id", id).param("ws", workspaceId).param("key", r.key()).param("name", r.name().strip())
                    .param("cluster", r.cluster()).param("topic", r.topic()).param("group", group)
                    .param("secret", r.secretRef() == null || r.secretRef().isBlank() ? null : r.secretRef())
                    .param("action", r.action()).param("wf", r.workflowId()).param("msg", r.messageName())
                    .param("ptr", r.correlationPointer()).param("mapping", json.write(mapping))
                    .param("batch", r.batchSize() == null ? 50 : r.batchSize())
                    .param("rate", r.maxPerSecond() == null ? 20 : r.maxPerSecond()).param("from", startFrom)
                    .param("by", caller.subjectId()).param("now", Sql.ts(now)).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ProblemCode.DUPLICATE_KEY,
                    "The key or the consumer group of this cluster is already used by another trigger.");
        }
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "kafka.trigger-created", "kafkaTrigger", id.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("key", r.key(), "action", r.action(), "cluster", r.cluster())));
        return view(workspaceId, id, true);
    }

    /**
     * At most {@link #MAX_TRIGGERS_PER_WORKSPACE} triggers are switched on at once, so a trigger that is switched off
     * (triggers cannot be deleted) never blocks a new one; switched-off rows are bounded too, so the table cannot grow
     * without limit.
     */
    private void requireRoom(UUID workspaceId, boolean enabling) {
        long on = jdbc.sql("SELECT count(*) FROM kafka_trigger WHERE workspace_id = :ws AND enabled").param("ws", workspaceId)
                .query(Long.class).single();
        long all = jdbc.sql("SELECT count(*) FROM kafka_trigger WHERE workspace_id = :ws").param("ws", workspaceId)
                .query(Long.class).single();
        if ((enabling && on >= MAX_TRIGGERS_PER_WORKSPACE) || all >= MAX_TRIGGERS_PER_WORKSPACE * 5) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "A workspace can have at most " + MAX_TRIGGERS_PER_WORKSPACE
                    + " Kafka triggers switched on (and " + MAX_TRIGGERS_PER_WORKSPACE * 5 + " in all). Switch one off first.");
        }
    }

    @Transactional
    public TriggerView update(Caller caller, UUID workspaceId, UUID id, UpdateTrigger r) {
        WorkspaceAccess access = manage(caller, workspaceId, "updateKafkaTrigger");
        Trigger current = lock(workspaceId, id).orElseThrow(ApiException::notFound);
        if (current.version() != r.expectedVersion()) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT,
                    "The trigger changed since you loaded it (now version " + current.version() + ").");
        }
        secretRef(r.secretRef());
        ObjectNode mapping = mapping(r.mapping(), "resume".equals(current.action()));
        if (r.enabled() && ("start".equals(current.action()) ? !access.canStart(current.workflowId())
                : !access.has(Permission.MESSAGE_SEND))) {
            throw policy.deny(workspaceId, caller.subjectId(), "start".equals(current.action()) ? "workflow.start"
                    : "message.send", "updateKafkaTrigger", true, new ApiException(ProblemCode.FORBIDDEN,
                            "An enabled trigger acts as you; you need the right to do what it does."));
        }
        if (r.enabled() && !current.enabled()) {
            requireRoom(workspaceId, true);
        }
        int version = current.version() + 1;
        jdbc.sql("""
                UPDATE kafka_trigger SET name = :name, secret_ref = :secret, mapping = CAST(:mapping AS jsonb),
                       batch_size = :batch, max_per_second = :rate, enabled = :enabled,
                       run_as = CASE WHEN :enabled THEN :by ELSE run_as END, version = :version, updated_by = :by,
                       updated_at = :now
                WHERE id = :id AND workspace_id = :ws
                """).param("name", r.name().strip())
                .param("secret", r.secretRef() == null || r.secretRef().isBlank() ? null : r.secretRef())
                .param("mapping", json.write(mapping)).param("batch", r.batchSize()).param("rate", r.maxPerSecond())
                .param("enabled", r.enabled()).param("by", caller.subjectId()).param("version", version)
                .param("now", Sql.ts(clock.instant())).param("id", id).param("ws", workspaceId).update();
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "kafka.trigger-updated", "kafkaTrigger", id.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("key", current.key(), "version", version, "enabled", r.enabled())));
        return view(workspaceId, id, true);
    }

    /**
     * Deletes a trigger that is switched off, with its status rows and its record rows (de-duplication memory and dead
     * letters). A trigger that is on is refused, so no consumer is reading it while it goes.
     */
    @Transactional
    public void delete(Caller caller, UUID workspaceId, UUID id) {
        manage(caller, workspaceId, "deleteKafkaTrigger");
        Trigger current = lock(workspaceId, id).orElseThrow(ApiException::notFound);
        if (current.enabled()) {
            throw new ApiException(ProblemCode.RESOURCE_IN_USE, "Switch the trigger off before deleting it.");
        }
        long dead = jdbc.sql("SELECT count(*) FROM kafka_inbound_record WHERE trigger_id = :id AND status = 'dead'")
                .param("id", id).query(Long.class).single();
        jdbc.sql("DELETE FROM kafka_trigger_status WHERE trigger_id = :id").param("id", id).update();
        int records = jdbc.sql("DELETE FROM kafka_inbound_record WHERE trigger_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM kafka_trigger WHERE id = :id AND workspace_id = :ws").param("id", id).param("ws", workspaceId).update();
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "kafka.trigger-deleted", "kafkaTrigger", id.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("key", current.key(), "records", records, "deadLetters", dead)));
    }

    @Transactional(readOnly = true)
    public TriggerList list(Caller caller, UUID workspaceId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.CONNECTION_READ, "listKafkaTriggers", false);
        boolean manager = access.has(Permission.CONNECTION_MANAGE);
        List<TriggerView> items = new ArrayList<>();
        for (UUID id : jdbc.sql("SELECT id FROM kafka_trigger WHERE workspace_id = :ws ORDER BY key").param("ws", workspaceId)
                .query(UUID.class).list()) {
            items.add(view(workspaceId, id, manager));
        }
        List<ClusterView> clusters = settings.clusters().keySet().stream().sorted()
                .map(name -> new ClusterView(name, settings.topics(name))).toList();
        return new TriggerList(settings.mode(), clusters, items);
    }

    @Transactional(readOnly = true)
    public DeadLetterList deadLetters(Caller caller, UUID workspaceId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.RUN_OPERATE, "listKafkaDeadLetters", false);
        return new DeadLetterList(jdbc.sql("""
                SELECT r.id, r.trigger_id, t.key, r.topic_partition, r.record_offset, r.dedupe_key, r.error_code,
                       r.value_bytes, r.value_sha256, r.attempts, r.received_at
                FROM kafka_inbound_record r JOIN kafka_trigger t ON t.id = r.trigger_id
                WHERE r.workspace_id = :ws AND r.status = 'dead' ORDER BY r.received_at DESC, r.id LIMIT 200
                """).param("ws", workspaceId).query((rs, n) -> new DeadLetter(rs.getObject("id", UUID.class),
                rs.getObject("trigger_id", UUID.class), rs.getString("key"), rs.getInt("topic_partition"),
                rs.getLong("record_offset"), Json.sha256Hex(rs.getString("dedupe_key")).substring(0, 16),
                rs.getString("error_code"), rs.getInt("value_bytes"), rs.getString("value_sha256"), rs.getInt("attempts"),
                Sql.instant(rs, "received_at"))).list());
    }

    /** Stub mode only (K11): one JSON value through exactly the consumer's handler, without a broker. */
    public KafkaRecordHandler.Result testMessage(Caller caller, UUID workspaceId, UUID id, TestMessage message) {
        manageFresh(caller, workspaceId, "sendKafkaTestMessage");
        if (!settings.stub()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED,
                    "Test messages are only accepted when this environment runs Kafka triggers in stub mode.");
        }
        Trigger trigger = find(workspaceId, id).orElseThrow(ApiException::notFound);
        String key = message.key() == null || message.key().isBlank() ? "stub-" + UUID.randomUUID() : message.key();
        byte[] value = json.write(message.value()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long offset = jdbc.sql("SELECT count(*) FROM kafka_inbound_record WHERE trigger_id = :t").param("t", id)
                .query(Long.class).single();
        KafkaRecordHandler.Result result = handler.handle(trigger, new KafkaRecordHandler.Incoming(0, offset,
                key.getBytes(java.nio.charset.StandardCharsets.UTF_8), value), "stub", 1, true);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "kafka.test-message", "kafkaTrigger", id.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("triggerId", id.toString(), "outcome", result.outcome())));
        return result;
    }

    // ---------------------------------------------------------------- for the runner and handler

    @Transactional(readOnly = true)
    public List<Trigger> enabled() {
        return jdbc.sql(TRIGGER + " WHERE enabled ORDER BY workspace_id, key").query(this::trigger).list();
    }

    @Transactional(readOnly = true)
    public Optional<Trigger> find(UUID workspaceId, UUID id) {
        return jdbc.sql(TRIGGER + " WHERE workspace_id = :ws AND id = :id").param("ws", workspaceId).param("id", id)
                .query(this::trigger).optional();
    }

    private Optional<Trigger> lock(UUID workspaceId, UUID id) {
        return jdbc.sql(TRIGGER + " WHERE workspace_id = :ws AND id = :id FOR UPDATE").param("ws", workspaceId)
                .param("id", id).query(this::trigger).optional();
    }

    private static final String TRIGGER = """
            SELECT id, workspace_id, key, cluster, topic, consumer_group, secret_ref, action, workflow_id, message_name,
                   correlation_pointer, mapping::text AS mapping, batch_size, max_per_second, start_from, run_as, enabled,
                   version
            FROM kafka_trigger
            """;

    private Trigger trigger(ResultSet rs, int n) throws SQLException {
        return new Trigger(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class), rs.getString("key"),
                rs.getString("cluster"), rs.getString("topic"), rs.getString("consumer_group"), rs.getString("secret_ref"),
                rs.getString("action"), rs.getObject("workflow_id", UUID.class), rs.getString("message_name"),
                rs.getString("correlation_pointer"), json.parse(rs.getString("mapping")), rs.getInt("batch_size"),
                rs.getInt("max_per_second"), rs.getString("start_from"), rs.getObject("run_as", UUID.class),
                rs.getBoolean("enabled"), rs.getInt("version"));
    }

    private TriggerView view(UUID workspaceId, UUID id, boolean manager) {
        TriggerView base = jdbc.sql("""
                SELECT id, key, name, cluster, topic, consumer_group, secret_ref, action, workflow_id, message_name,
                       correlation_pointer, mapping::text AS mapping, batch_size, max_per_second, start_from, run_as, enabled,
                       version, created_at, updated_at
                FROM kafka_trigger WHERE workspace_id = :ws AND id = :id
                """).param("ws", workspaceId).param("id", id).query((rs, n) -> new TriggerView(rs.getObject("id", UUID.class),
                rs.getString("key"), rs.getString("name"), rs.getString("cluster"), rs.getString("topic"),
                rs.getString("consumer_group"), manager ? rs.getString("secret_ref") : null, rs.getString("action"),
                rs.getObject("workflow_id", UUID.class), rs.getString("message_name"), rs.getString("correlation_pointer"),
                json.parse(rs.getString("mapping")), rs.getInt("batch_size"), rs.getInt("max_per_second"),
                rs.getString("start_from"), rs.getObject("run_as", UUID.class), rs.getBoolean("enabled"),
                rs.getInt("version"), Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"), null)).single();
        Instant fresh = clock.instant().minus(Duration.ofMinutes(2));
        record Row(String instance, List<Integer> partitions, long lag, long handled, long duplicates, long dead,
                boolean running, Instant lastPoll, String error, Instant errorAt, Instant updated) {
        }
        List<Row> rows = jdbc.sql("""
                SELECT instance_id, partitions, lag, handled, duplicates, dead, running, last_poll_at, last_error,
                       last_error_at, updated_at
                FROM kafka_trigger_status WHERE trigger_id = :t ORDER BY instance_id
                """).param("t", id).query((rs, n) -> new Row(rs.getString("instance_id"),
                List.of((Integer[]) rs.getArray("partitions").getArray()), rs.getLong("lag"), rs.getLong("handled"),
                rs.getLong("duplicates"), rs.getLong("dead"), rs.getBoolean("running"), Sql.instant(rs, "last_poll_at"),
                rs.getString("last_error"), Sql.instant(rs, "last_error_at"), Sql.instant(rs, "updated_at"))).list();
        long lag = 0, handled = 0, duplicates = 0, dead = 0;
        String error = null;
        Instant errorAt = null;
        List<ReplicaStatus> replicas = new ArrayList<>();
        for (Row row : rows) {
            handled += row.handled();
            duplicates += row.duplicates();
            dead += row.dead();
            if (row.errorAt() != null && (errorAt == null || row.errorAt().isAfter(errorAt))) {
                errorAt = row.errorAt();
                error = row.error();
            }
            boolean live = row.running() && row.updated() != null && row.updated().isAfter(fresh);
            if (live) {
                lag += row.lag();
                replicas.add(new ReplicaStatus(row.instance(), row.partitions(), row.lag(), true, row.lastPoll()));
            }
        }
        long deadRecords = jdbc.sql("SELECT count(*) FROM kafka_inbound_record WHERE trigger_id = :t AND status = 'dead'")
                .param("t", id).query(Long.class).single();
        return new TriggerView(base.id(), base.key(), base.name(), base.cluster(), base.topic(), base.consumerGroup(),
                base.secretRef(), base.action(), base.workflowId(), base.messageName(), base.correlationPointer(),
                base.mapping(), base.batchSize(), base.maxPerSecond(), base.startFrom(), base.runAs(), base.enabled(),
                base.version(), base.createdAt(), base.updatedAt(),
                new Status(lag, handled, duplicates, Math.max(dead, deadRecords), error, errorAt, replicas));
    }

    private ObjectNode mapping(JsonNode mapping, boolean emptyAllowed) {
        if (emptyAllowed && mapping != null && mapping.isObject() && mapping.isEmpty()) {
            return json.object();
        }
        try {
            return AdapterRules.validateMapping(mapping);
        } catch (AdapterRules.Invalid e) {
            throw invalid(e.pointer(), e.getMessage());
        }
    }

    /** Broker credentials are a secret reference only (K2), never a webhook signing secret. */
    private void secretRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return;
        }
        if (!SECRET.matcher(ref).matches()) {
            throw invalid("/secretRef", "Name a secret file: lowercase letters, digits and dashes.");
        }
        com.orqix.integration.SecretNamespaces.refuseReserved(ref, "/secretRef");
    }

    private WorkspaceAccess manage(Caller caller, UUID workspaceId, String operation) {
        return manageFresh(caller, workspaceId, operation);
    }

    private WorkspaceAccess manageFresh(Caller caller, UUID workspaceId, String operation) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.CONNECTION_MANAGE, operation, true);
        return access;
    }

    private static ApiException invalid(String pointer, String message) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, message)
                .withFieldErrors(List.of(new ApiException.FieldError(pointer, "invalid", message)));
    }

    /** Metadata keys for audit, kept here so they match the whitelist. */
    static Map<String, Object> deadAudit(UUID triggerId, int partition, long offset, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("triggerId", triggerId.toString());
        m.put("partition", partition);
        m.put("offset", offset);
        m.put("error", error);
        return m;
    }
}
