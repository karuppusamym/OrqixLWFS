package com.orqix.runtime.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.shared.pagination.CursorCodec;
import com.orqix.shared.web.Sql;

/** Run list queries, keyset on (created_at DESC, id DESC), always scoped by workspace (S2 section 8). */
@Repository
public class RunQueries {

    public record Filter(UUID workflowId, String lifecycle, String outcomeKind, String businessKey, Instant createdFrom,
            Instant createdTo, UUID initiator) {
    }

    private final JdbcClient jdbc;
    private final RunStore runs;

    public RunQueries(JdbcClient jdbc, RunStore runs) {
        this.jdbc = jdbc;
        this.runs = runs;
    }

    @Transactional(readOnly = true)
    public List<RunStore.RunRow> list(UUID workspaceId, Filter f, CursorCodec.Position after, int limitPlusOne) {
        return jdbc.sql("SELECT " + RunStore.columns() + """
                 FROM workflow_run
                 WHERE workspace_id = :ws
                   AND (CAST(:wf AS uuid) IS NULL OR workflow_id = :wf)
                   AND (CAST(:lifecycle AS text) IS NULL OR lifecycle = :lifecycle)
                   AND (CAST(:kind AS text) IS NULL OR outcome_kind = :kind)
                   AND (CAST(:bkey AS text) IS NULL OR business_key = :bkey OR reference = :bkey)
                   AND (CAST(:from AS timestamptz) IS NULL OR created_at >= :from)
                   AND (CAST(:to AS timestamptz) IS NULL OR created_at < :to)
                   AND (CAST(:initiator AS uuid) IS NULL OR initiator_subject_id = :initiator)
                   AND (CAST(:afterAt AS timestamptz) IS NULL
                        OR (created_at, id) < (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))
                 ORDER BY created_at DESC, id DESC
                 LIMIT :limit
                """)
                .param("ws", workspaceId).param("wf", f.workflowId()).param("lifecycle", f.lifecycle())
                .param("kind", f.outcomeKind()).param("bkey", f.businessKey()).param("from", Sql.ts(f.createdFrom()))
                .param("to", Sql.ts(f.createdTo())).param("initiator", f.initiator())
                .param("afterAt", after == null ? null : Sql.ts(after.at()))
                .param("afterId", after == null ? null : UUID.fromString(after.id()))
                .param("limit", limitPlusOne)
                .query(runs::map).list();
    }
}
