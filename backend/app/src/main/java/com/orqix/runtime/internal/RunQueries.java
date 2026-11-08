package com.orqix.runtime.internal;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.shared.pagination.CursorCodec;
import com.orqix.shared.web.Sql;

/** Run list queries, keyset on (created_at DESC, id DESC), always scoped by workspace (S2 section 8). */
@Repository
public class RunQueries {

    /**
     * Run filters. {@code fields} (CG-O04, S142) are conditions on the workflow's searchable request fields;
     * {@code attentionReason} and {@code errorCode} match the step that needs attention.
     */
    public record Filter(UUID workflowId, String lifecycle, String outcomeKind, String businessKey, Instant createdFrom,
            Instant createdTo, UUID initiator, boolean overdue, List<RunSearchIndex.Condition> fields,
            String attentionReason, String errorCode) {

        public Filter {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }

        public Filter(UUID workflowId, String lifecycle, String outcomeKind, String businessKey, Instant createdFrom,
                Instant createdTo, UUID initiator, boolean overdue) {
            this(workflowId, lifecycle, outcomeKind, businessKey, createdFrom, createdTo, initiator, overdue, List.of(),
                    null, null);
        }

        public Filter(UUID workflowId, String lifecycle, String outcomeKind, String businessKey, Instant createdFrom,
                Instant createdTo, UUID initiator) {
            this(workflowId, lifecycle, outcomeKind, businessKey, createdFrom, createdTo, initiator, false);
        }

        /** True when the filter reads request data (and so needs run.read-data). */
        public boolean readsData() {
            return !fields.isEmpty();
        }
    }

    private final JdbcClient jdbc;
    private final RunStore runs;
    private final java.time.Clock clock;

    public RunQueries(JdbcClient jdbc, RunStore runs, java.time.Clock clock) {
        this.clock = clock;
        this.jdbc = jdbc;
        this.runs = runs;
    }

    @Transactional(readOnly = true)
    public List<RunStore.RunRow> list(UUID workspaceId, Filter f, CursorCodec.Position after, int limitPlusOne) {
        Map<String, Object> params = new HashMap<>();
        String where = where(workspaceId, f, params);
        params.put("afterAt", after == null ? null : Sql.ts(after.at()));
        params.put("afterId", after == null ? null : UUID.fromString(after.id()));
        params.put("limit", limitPlusOne);
        return jdbc.sql("SELECT " + RunStore.columns() + " FROM workflow_run WHERE " + where + """
                   AND (CAST(:afterAt AS timestamptz) IS NULL
                        OR (created_at, id) < (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))
                 ORDER BY created_at DESC, id DESC
                 LIMIT :limit
                """)
                .params(params).query(runs::map).list();
    }

    /**
     * The WHERE clause of {@code f} on {@code workflow_run} (without the keyset), shared by the run list and the bulk
     * operation dry run so both match exactly the same runs. Parameters are added to {@code params}.
     */
    String where(UUID workspaceId, Filter f, Map<String, Object> params) {
        params.put("ws", workspaceId);
        params.put("wf", f.workflowId());
        params.put("lifecycle", f.lifecycle());
        params.put("kind", f.outcomeKind());
        params.put("bkey", f.businessKey());
        params.put("from", Sql.ts(f.createdFrom()));
        params.put("to", Sql.ts(f.createdTo()));
        params.put("initiator", f.initiator());
        params.put("overdue", f.overdue());
        params.put("now", Sql.ts(clock.instant()));
        params.put("attReason", f.attentionReason());
        params.put("errCode", f.errorCode());
        return """
                workspace_id = :ws
                   AND (CAST(:wf AS uuid) IS NULL OR workflow_id = :wf)
                   AND (CAST(:lifecycle AS text) IS NULL OR lifecycle = :lifecycle)
                   AND (CAST(:kind AS text) IS NULL OR outcome_kind = :kind)
                   AND (CAST(:bkey AS text) IS NULL OR business_key = :bkey OR reference = :bkey)
                   AND (CAST(:from AS timestamptz) IS NULL OR created_at >= :from)
                   AND (CAST(:to AS timestamptz) IS NULL OR created_at < :to)
                   AND (CAST(:initiator AS uuid) IS NULL OR initiator_subject_id = :initiator)
                   AND (:overdue = false OR EXISTS (SELECT 1 FROM run_stage s WHERE s.run_id = workflow_run.id
                        AND s.completed_at IS NULL AND s.due_at IS NOT NULL AND s.due_at < :now))
                   AND (CAST(:attReason AS text) IS NULL
                        OR (lifecycle = 'needsAttention' AND attention ->> 'reason' = :attReason))
                   AND (CAST(:errCode AS text) IS NULL
                        OR (lifecycle = 'needsAttention' AND attention ->> 'errorCode' = :errCode))
                """ + RunSearchIndex.predicates(f.fields(), params) + "\n";
    }
}
