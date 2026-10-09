package com.orqix.runtime.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.shared.json.Json;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code workflow_run} projection (S2 section 4). Every write happens in the transaction of the engine change it
 * describes; every query is scoped by workspace.
 */
@Repository
public class RunStore {

    public static final String RUNNING = "running";
    public static final String WAITING = "waiting";
    public static final String NEEDS_ATTENTION = "needsAttention";
    public static final String COMPLETED = "completed";
    public static final String CANCELLED = "cancelled";

    /** Prefix of the human request reference (ORQ-1001, ORQ-1002, ...), unique per workspace. */
    public static final String REFERENCE_PREFIX = "ORQ-";

    public record RunRow(UUID id, UUID workspaceId, String reference, UUID workflowId, UUID releaseId,
            String engineProcessInstanceId, String businessKey, UUID initiator, String lifecycle, String outcome, String outcomeKind,
            ArrayNode currentSteps, JsonNode attention, JsonNode input, JsonNode output, long eventSeq, int version,
            Instant createdAt, Instant updatedAt, Instant endedAt, UUID parentRunId, String parentNodeId,
            Integer parentOccurrence, int callDepth) {

        public boolean isActive() {
            return RUNNING.equals(lifecycle) || WAITING.equals(lifecycle) || NEEDS_ATTENTION.equals(lifecycle);
        }

        /** True when another run started this one ("Start another workflow", S12). */
        public boolean isChild() {
            return parentRunId != null;
        }
    }

    /** Step kinds that wait for something other than a job: a person, a timer or a child run. */
    private static final java.util.Set<String> WAITING_KINDS = java.util.Set.of("approval", "wait", "child");

    /** Mutable view used by {@link #mutate}. */
    public static final class Mutation {
        public String lifecycle;
        public String outcome;
        public String outcomeKind;
        public ArrayNode steps;
        public JsonNode attention;
        public JsonNode output;
        public Instant endedAt;
        private final RunRow before;

        Mutation(RunRow row) {
            this.before = row;
            this.lifecycle = row.lifecycle();
            this.outcome = row.outcome();
            this.outcomeKind = row.outcomeKind();
            this.steps = row.currentSteps().deepCopy();
            this.attention = row.attention();
            this.output = row.output();
            this.endedAt = row.endedAt();
        }

        public RunRow before() {
            return before;
        }

        public boolean hasStep(String nodeId) {
            for (JsonNode step : steps) {
                if (nodeId.equals(step.path("nodeId").asString(null))) {
                    return true;
                }
            }
            return false;
        }

        public boolean hasStepOfKind(String kind) {
            for (JsonNode step : steps) {
                if (kind.equals(step.path("kind").asString(null))) {
                    return true;
                }
            }
            return false;
        }

        public void addStep(ObjectNode step) {
            if (!hasStep(step.path("nodeId").asString())) {
                steps.add(step);
            }
        }

        public void removeStep(String nodeId) {
            Iterator<JsonNode> it = steps.iterator();
            while (it.hasNext()) {
                if (nodeId.equals(it.next().path("nodeId").asString(null))) {
                    it.remove();
                }
            }
        }

        /**
         * Sets running or waiting from the current steps: {@code waiting} when something is open and every open step
         * waits for a person, a timer or a child run, {@code running} otherwise (a job is executing or pending, or the
         * engine is between steps). Other lifecycles are left alone.
         */
        public void settle() {
            if (!RUNNING.equals(lifecycle) && !WAITING.equals(lifecycle)) {
                return;
            }
            boolean onlyWaiting = steps.size() > 0;
            for (JsonNode step : steps) {
                if (!WAITING_KINDS.contains(step.path("kind").asString(""))) {
                    onlyWaiting = false;
                }
            }
            lifecycle = onlyWaiting ? WAITING : RUNNING;
        }

        public void updateStep(String nodeId, Consumer<ObjectNode> change) {
            for (JsonNode step : steps) {
                if (nodeId.equals(step.path("nodeId").asString(null)) && step instanceof ObjectNode object) {
                    change.accept(object);
                }
            }
        }
    }

    private static final String COLUMNS = """
            id, workspace_id, reference, workflow_id, release_id, engine_process_instance_id, business_key,
            initiator_subject_id, lifecycle, outcome, outcome_kind, current_steps::text AS current_steps, attention::text AS attention,
            input::text AS input, output::text AS output, event_seq, version, created_at, updated_at, ended_at,
            parent_run_id, parent_node_id, parent_occurrence, call_depth
            """;

    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;

    public RunStore(JdbcClient jdbc, Json json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(UUID id, UUID workspaceId, UUID workflowId, UUID releaseId, String businessKey, UUID initiator,
            JsonNode input) {
        Instant now = clock.instant();
        // Human reference ORQ-<n>: the workspace counter row is locked until this transaction ends, so concurrent starts
        // are numbered one after the other and a rolled-back start leaves no gap.
        long sequence = jdbc.sql("UPDATE workspace SET run_seq = run_seq + 1 WHERE id = :ws RETURNING run_seq")
                .param("ws", workspaceId).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO workflow_run (id, workspace_id, reference, workflow_id, release_id, business_key,
                                          initiator_subject_id, lifecycle, current_steps, input, created_at, updated_at)
                VALUES (:id, :ws, :reference, :wf, :rel, :bkey, :initiator, 'running', '[]'::jsonb, CAST(:input AS jsonb),
                        :now, :now)
                """)
                .param("id", id).param("ws", workspaceId).param("reference", REFERENCE_PREFIX + sequence)
                .param("wf", workflowId).param("rel", releaseId)
                .param("bkey", businessKey).param("initiator", initiator).param("input", json.write(input))
                .param("now", Sql.ts(now))
                .update();
    }

    /**
     * Inserts the child run of a "Start another workflow" step (S12). The unique index on (parent run, step, visit)
     * makes a duplicate start impossible: callers look the child up with {@link #findChild} first, and a concurrent
     * duplicate fails here.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void insertChild(UUID id, UUID workspaceId, UUID workflowId, UUID releaseId, String businessKey,
            UUID initiator, JsonNode input, RunRow parent, String parentNodeId, int occurrence) {
        insert(id, workspaceId, workflowId, releaseId, businessKey, initiator, input);
        jdbc.sql("""
                UPDATE workflow_run SET parent_run_id = :parent, parent_node_id = :node, parent_occurrence = :occ,
                       call_depth = :depth
                WHERE workspace_id = :ws AND id = :id
                """)
                .param("parent", parent.id()).param("node", parentNodeId).param("occ", occurrence)
                .param("depth", parent.callDepth() + 1).param("ws", workspaceId).param("id", id).update();
    }

    @Transactional(readOnly = true)
    public Optional<RunRow> findChild(UUID workspaceId, UUID parentRunId, String parentNodeId, int occurrence) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM workflow_run
                 WHERE workspace_id = :ws AND parent_run_id = :parent AND parent_node_id = :node
                   AND parent_occurrence = :occ
                """)
                .param("ws", workspaceId).param("parent", parentRunId).param("node", parentNodeId)
                .param("occ", occurrence).query(this::map).optional();
    }

    /** Direct children of a run, oldest first. */
    @Transactional(readOnly = true)
    public java.util.List<RunRow> children(UUID workspaceId, UUID parentRunId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM workflow_run WHERE workspace_id = :ws AND parent_run_id = :parent
                 ORDER BY created_at, id
                """)
                .param("ws", workspaceId).param("parent", parentRunId).query(this::map).list();
    }

    /** Every descendant of a run, deepest first (the order in which a cancel locks them, S12 section 4). */
    @Transactional(readOnly = true)
    public java.util.List<RunRow> descendantsDeepestFirst(UUID workspaceId, UUID runId) {
        return jdbc.sql("""
                WITH RECURSIVE tree AS (
                    SELECT id, call_depth FROM workflow_run WHERE workspace_id = :ws AND parent_run_id = :id
                    UNION ALL
                    SELECT r.id, r.call_depth FROM workflow_run r JOIN tree t ON r.parent_run_id = t.id
                    WHERE r.workspace_id = :ws
                )
                """ + "SELECT " + COLUMNS + """
                 FROM workflow_run WHERE workspace_id = :ws AND id IN (SELECT id FROM tree)
                 ORDER BY call_depth DESC, created_at, id
                """)
                .param("ws", workspaceId).param("id", runId).query(this::map).list();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void setEngineProcessInstance(UUID workspaceId, UUID runId, String processInstanceId) {
        jdbc.sql("UPDATE workflow_run SET engine_process_instance_id = :pid WHERE workspace_id = :ws AND id = :id")
                .param("pid", processInstanceId).param("ws", workspaceId).param("id", runId).update();
    }

    @Transactional(readOnly = true)
    public Optional<RunRow> find(UUID workspaceId, UUID runId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM workflow_run WHERE workspace_id = :ws AND id = :id")
                .param("ws", workspaceId).param("id", runId).query(this::map).optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<RunRow> lock(UUID workspaceId, UUID runId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM workflow_run WHERE workspace_id = :ws AND id = :id FOR UPDATE")
                .param("ws", workspaceId).param("id", runId).query(this::map).optional();
    }

    /** Locks the run, applies {@code change} and writes the projection columns back with {@code version + 1}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public RunRow mutate(UUID workspaceId, UUID runId, Consumer<Mutation> change) {
        RunRow row = lock(workspaceId, runId)
                .orElseThrow(() -> new IllegalStateException("Run " + runId + " not found in workspace"));
        Mutation m = new Mutation(row);
        change.accept(m);
        jdbc.sql("""
                UPDATE workflow_run SET lifecycle = :lifecycle, outcome = :outcome, outcome_kind = :kind,
                       current_steps = CAST(:steps AS jsonb), attention = CAST(:attention AS jsonb),
                       output = CAST(:output AS jsonb), ended_at = :ended, version = version + 1, updated_at = :now
                WHERE workspace_id = :ws AND id = :id
                """)
                .param("lifecycle", m.lifecycle).param("outcome", m.outcome).param("kind", m.outcomeKind)
                .param("steps", json.write(m.steps))
                .param("attention", m.attention == null || m.attention.isNull() ? null : json.write(m.attention))
                .param("output", m.output == null || m.output.isNull() ? null : json.write(m.output))
                .param("ended", Sql.ts(m.endedAt)).param("now", Sql.ts(clock.instant()))
                .param("ws", workspaceId).param("id", runId)
                .update();
        return find(workspaceId, runId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public int countActive(UUID workspaceId, UUID workflowId) {
        return jdbc.sql("""
                SELECT count(*) FROM workflow_run
                WHERE workspace_id = :ws AND workflow_id = :wf AND lifecycle IN ('running', 'waiting', 'needsAttention')
                """)
                .param("ws", workspaceId).param("wf", workflowId).query(Integer.class).single();
    }

    public RunRow map(ResultSet rs, int n) throws SQLException {
        String steps = rs.getString("current_steps");
        String attention = rs.getString("attention");
        String output = rs.getString("output");
        JsonNode stepsNode = steps == null ? JsonNodeFactory.instance.arrayNode() : json.parse(steps);
        return new RunRow(Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id"), rs.getString("reference"),
                Sql.uuid(rs, "workflow_id"), Sql.uuid(rs, "release_id"), rs.getString("engine_process_instance_id"),
                rs.getString("business_key"),
                Sql.uuid(rs, "initiator_subject_id"), rs.getString("lifecycle"), rs.getString("outcome"),
                rs.getString("outcome_kind"), (ArrayNode) stepsNode, attention == null ? null : json.parse(attention),
                json.parse(rs.getString("input")), output == null ? null : json.parse(output), rs.getLong("event_seq"),
                rs.getInt("version"), Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"),
                Sql.instant(rs, "ended_at"), Sql.uuid(rs, "parent_run_id"), rs.getString("parent_node_id"),
                (Integer) rs.getObject("parent_occurrence"), rs.getInt("call_depth"));
    }

    public static String columns() {
        return COLUMNS;
    }
}
