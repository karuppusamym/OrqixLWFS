package com.orqix.runtime.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.shared.Ids;
import com.orqix.shared.idempotency.IdempotencyService;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.web.Sql;

/** Immutable collaboration notes follow request-data visibility, never run-summary visibility alone. */
@Service
public class RunComments {
    public record Author(UUID id, String displayName) {}
    public record Comment(UUID id, UUID runId, Author author, String text, Instant createdAt) {}
    public record CommentPage(List<Comment> comments, boolean hasMore) {}
    public record Created(Comment comment, boolean replayed) {}
    private final RunAccess access; private final JdbcClient jdbc; private final IdempotencyService idempotency; private final AuditWriter audit;
    public RunComments(RunAccess access, JdbcClient jdbc, IdempotencyService idempotency, AuditWriter audit) {
        this.access=access; this.jdbc=jdbc; this.idempotency=idempotency; this.audit=audit;
    }
    @Transactional(readOnly=true)
    public CommentPage list(Caller caller, UUID ws, UUID run, UUID before) {
        if (!access.require(caller,ws,run).dataVisible()) throw ApiException.forbidden();
        var comments=jdbc.sql("SELECT c.id,c.run_id,c.author_subject_id,s.display_name,c.text,c.created_at FROM run_comment c JOIN subject s ON s.id=c.author_subject_id WHERE c.workspace_id=:ws AND c.run_id=:run AND (CAST(:before AS uuid) IS NULL OR c.id<:before) ORDER BY c.id DESC LIMIT 51")
            .param("ws",ws).param("run",run).param("before",before).query((rs,n)->new Comment(Sql.uuid(rs,"id"),Sql.uuid(rs,"run_id"),new Author(Sql.uuid(rs,"author_subject_id"),rs.getString("display_name")),rs.getString("text"),Sql.instant(rs,"created_at"))).list();
        return new CommentPage(comments.stream().limit(50).toList(),comments.size()>50);
    }
    @Transactional
    public Created post(Caller caller, UUID ws, UUID run, String key, String text) {
        var visible=access.require(caller,ws,run);
        if (!visible.dataVisible() || !(visible.initiator() || visible.participant() || visible.access().has(Permission.RUN_OPERATE))) throw ApiException.forbidden();
        if (text==null || text.isBlank() || text.length()>2000) throw ApiException.malformed("Comment must contain 1 to 2000 characters.");
        String normalized=text.strip();
        var claim=idempotency.claim(ws,caller.subjectId(),"createRunComment",IdempotencyService.requireKey(key),idempotency.requestHash("createRunComment",Map.of("runId",run.toString()),Map.of("text",normalized)));
        UUID id=claim.isReplay()?UUID.fromString(claim.replay().resourceId()):Ids.newId();
        if (!claim.isReplay()) {
            jdbc.sql("INSERT INTO run_comment(id,workspace_id,run_id,author_subject_id,text) VALUES(:id,:ws,:run,:author,:text)")
                .param("id",id).param("ws",ws).param("run",run).param("author",caller.subjectId()).param("text",normalized).update();
            audit.write(new AuditWriter.Entry(ws,AuditWriter.ActorType.USER,caller.subjectId(),"comment.created","runComment",id.toString(),AuditWriter.Outcome.SUCCESS,Map.of("runId",run.toString())));
            idempotency.complete(claim,"runComment",id.toString(),201);
        }
        Comment comment=jdbc.sql("SELECT c.id,c.run_id,c.author_subject_id,s.display_name,c.text,c.created_at FROM run_comment c JOIN subject s ON s.id=c.author_subject_id WHERE c.workspace_id=:ws AND c.run_id=:run AND c.id=:id")
            .param("ws",ws).param("run",run).param("id",id).query((rs,n)->new Comment(Sql.uuid(rs,"id"),Sql.uuid(rs,"run_id"),new Author(Sql.uuid(rs,"author_subject_id"),rs.getString("display_name")),rs.getString("text"),Sql.instant(rs,"created_at"))).single();
        return new Created(comment,claim.isReplay());
    }
}
