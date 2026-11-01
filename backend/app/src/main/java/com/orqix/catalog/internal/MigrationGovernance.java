package com.orqix.catalog.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.SubjectService;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

/** Optional workspace controls bind independent approval to immutable source, mappings and exact draft bytes. */
@Service
public class MigrationGovernance {
    public record Policy(boolean required, int version, String reason, Instant updatedAt) {}
    public record ReviewState(boolean migration, boolean required, int draftRevision, String definitionHash,
            String sourceHash, String mappingsHash, SubjectService.SubjectRef mapper,
            SubjectService.SubjectRef reviewedBy, Instant reviewedAt, String reviewReason, int reviewVersion,
            boolean approved, boolean canApprove, String detail) {}
    private record Source(String sourceHash, String mappingsHash, UUID mapper) {}
    private record Approval(int version, String definitionHash, String sourceHash, String mappingsHash,
            UUID mapper, UUID reviewer, Instant at, String reason) {}
    private final JdbcClient jdbc;
    private final CatalogStore store;
    private final WorkflowService workflows;
    private final PolicyService policy;
    private final SubjectService subjects;
    private final AuditWriter audit;
    private final Clock clock;
    public MigrationGovernance(JdbcClient jdbc, CatalogStore store, WorkflowService workflows, PolicyService policy,
            SubjectService subjects, AuditWriter audit, Clock clock) {
        this.jdbc=jdbc; this.store=store; this.workflows=workflows; this.policy=policy;
        this.subjects=subjects; this.audit=audit; this.clock=clock;
    }
    @Transactional(readOnly=true)
    public Policy policy(Caller caller, UUID ws) {
        var access=policy.requireVisible(caller,ws);
        policy.require(access,Permission.WORKSPACE_MANAGE_ACCESS,"getMigrationPolicy",false);
        return settings(ws);
    }
    @Transactional
    public Policy updatePolicy(Caller caller, UUID ws, boolean required, int expectedVersion, String reason) {
        var access=policy.requireVisible(caller,ws);
        policy.require(access,Permission.WORKSPACE_MANAGE_ACCESS,"setMigrationPolicy",true);
        jdbc.sql("SELECT id FROM workspace WHERE id=:ws FOR UPDATE").param("ws",ws).query(UUID.class).single();
        Policy current=settings(ws);
        if (current.version()!=expectedVersion) throw conflict("The migration policy changed. Reload settings.");
        jdbc.sql("INSERT INTO migration_policy(workspace_id,required,version,reason,updated_by,updated_at) VALUES(:ws,:required,:version,:reason,:actor,:now) ON CONFLICT(workspace_id) DO UPDATE SET required=EXCLUDED.required,version=EXCLUDED.version,reason=EXCLUDED.reason,updated_by=EXCLUDED.updated_by,updated_at=EXCLUDED.updated_at")
                .param("ws",ws).param("required",required).param("version",current.version()+1).param("reason",reason)
                .param("actor",caller.subjectId()).param("now",Sql.ts(clock.instant())).update();
        audit.write(new AuditWriter.Entry(ws,AuditWriter.ActorType.USER,caller.subjectId(),"migration.policy.changed",
                "workspace",ws.toString(),AuditWriter.Outcome.SUCCESS,Map.of("required",required,"version",current.version()+1)));
        return settings(ws);
    }
    @Transactional(readOnly=true)
    public ReviewState review(Caller caller, UUID ws, UUID wf) {
        var visible=workflows.visible(caller,ws,wf,"getMigrationReview");
        policy.require(visible.access(),Permission.WORKFLOW_READ,"getMigrationReview",false);
        var draft=store.draft(ws,wf,visible.workflow().draftRevision()).orElseThrow(ApiException::notFound);
        return state(caller,ws,wf,draft,settings(ws).required());
    }
    @Transactional
    public ReviewState approve(Caller caller, UUID ws, UUID wf, int revision, int expectedVersion, String reason) {
        var visible=workflows.visible(caller,ws,wf,"approveMigrationReview");
        var fresh=policy.freshAccess(caller,ws).orElseThrow(ApiException::notFound);
        policy.require(fresh,Permission.WORKFLOW_PUBLISH,"approveMigrationReview",true);
        var workflow=store.lockWorkflow(ws,wf).orElseThrow(ApiException::notFound);
        lockPolicy(ws);
        if (workflow.draftRevision()!=revision) throw conflict("The draft changed. Review its latest revision.");
        var draft=store.draft(ws,wf,revision).orElseThrow(ApiException::notFound);
        Source source=source(ws,wf);
        if (source==null || source.mapper()==null) throw blocked("A recorded conversion mapper is required. Historical conversions with unknown authors cannot be approved.");
        if (caller.subjectId().equals(source.mapper()) || caller.subjectId().equals(draft.savedBy()))
            throw new ApiException(ProblemCode.FORBIDDEN,"The mapper or draft author cannot approve their own migration.");
        Approval previous=approval(ws,wf,revision);
        int version=previous==null ? 0 : previous.version();
        if (version!=expectedVersion) throw conflict("The migration review changed. Reload before approving.");
        String hash=Json.canonicalSha256(draft.definitionJson());
        jdbc.sql("INSERT INTO migration_review(id,workspace_id,workflow_id,draft_revision,version,definition_hash,source_hash,mappings_hash,mapper_subject_id,reviewed_by,reason,reviewed_at) VALUES(:id,:ws,:wf,:revision,:version,:definition,:source,:mappings,:mapper,:reviewer,:reason,:now)")
                .param("id",Ids.newId()).param("ws",ws).param("wf",wf).param("revision",revision).param("version",version+1)
                .param("definition",hash).param("source",source.sourceHash()).param("mappings",source.mappingsHash())
                .param("mapper",source.mapper()).param("reviewer",caller.subjectId()).param("reason",reason)
                .param("now",Sql.ts(clock.instant())).update();
        audit.write(new AuditWriter.Entry(ws,AuditWriter.ActorType.USER,caller.subjectId(),"migration.review.approved",
                "workflow",wf.toString(),AuditWriter.Outcome.SUCCESS,Map.of("revision",revision,"definitionHash",hash,"reviewVersion",version+1)));
        return state(caller,ws,wf,draft,settings(ws).required());
    }
    /** Called under the workflow row lock, before deployment, in the same publication transaction. */
    void requirePublish(Caller caller, UUID ws, UUID wf, CatalogStore.DraftRow draft) {
        lockPolicy(ws);
        requireVerified(ws, wf);
        if (!settings(ws).required()) return;
        Source source=source(ws,wf);
        if (source==null) return; // This policy governs migrated workflows; ordinary authored workflows keep their controls.
        var current=store.workflow(ws,wf).orElseThrow(ApiException::notFound);
        if (draft.revision()!=current.draftRevision()) throw blocked("Only the latest reviewed migration draft may be published.");
        Approval approval=approval(ws,wf,draft.revision());
        if (source.mapper()==null || !matches(approval,source,draft)) throw blocked("An independent approval of this exact migration draft is required before publishing.");
        if (!eligibleReviewer(ws,approval.reviewer())) throw blocked("The migration reviewer no longer has publishing access. Obtain a new independent review.");
        if (caller.subjectId().equals(source.mapper()) || caller.subjectId().equals(approval.reviewer())
                || caller.subjectId().equals(draft.savedBy()))
            throw new ApiException(ProblemCode.FORBIDDEN,"Mapper, reviewer and publisher must be different people; draft authors cannot publish their own governed migration.");
    }
    /**
     * "Converted but changed — verify" items of the source conversion block publishing until a reviewer acknowledged each
     * one (docs/17-migration-workbench.md "Severity model"). Applies whether or not the independent review policy is on.
     */
    private void requireVerified(UUID ws, UUID wf) {
        int open = jdbc.sql("SELECT count(*) FROM migration_job WHERE workspace_id=:ws AND converted_workflow_id=:wf AND jsonb_path_exists(report, '$.issues[*] ? (@.severity == \"verify\" && @.resolutionStatus == \"open\")')")
                .param("ws",ws).param("wf",wf).query(Integer.class).single();
        if (open > 0) throw blocked("The migration has converted-but-changed items that a reviewer must verify and acknowledge before publishing.");
    }
    private void lockPolicy(UUID ws) {
        jdbc.sql("SELECT id FROM workspace WHERE id=:ws FOR SHARE").param("ws",ws).query(UUID.class).single();
    }
    private Policy settings(UUID ws) {
        return jdbc.sql("SELECT required,version,reason,updated_at FROM migration_policy WHERE workspace_id=:ws")
                .param("ws",ws).query((rs,n)->new Policy(rs.getBoolean("required"),rs.getInt("version"),rs.getString("reason"),Sql.instant(rs,"updated_at")))
                .optional().orElse(new Policy(false,0,null,null));
    }
    private Source source(UUID ws, UUID wf) {
        return jdbc.sql("SELECT source_sha256,mappings::text,converted_by_subject_id FROM migration_job WHERE workspace_id=:ws AND converted_workflow_id=:wf ORDER BY created_at,id LIMIT 1")
                .param("ws",ws).param("wf",wf).query((rs,n)->new Source(rs.getString("source_sha256"),
                        Json.canonicalSha256(rs.getString("mappings")==null ? "{}" : rs.getString("mappings")),Sql.uuid(rs,"converted_by_subject_id"))).optional().orElse(null);
    }
    private Approval approval(UUID ws, UUID wf, int revision) {
        return jdbc.sql("SELECT version,definition_hash,source_hash,mappings_hash,mapper_subject_id,reviewed_by,reviewed_at,reason FROM migration_review WHERE workspace_id=:ws AND workflow_id=:wf AND draft_revision=:revision ORDER BY version DESC LIMIT 1")
                .param("ws",ws).param("wf",wf).param("revision",revision).query((rs,n)->new Approval(rs.getInt("version"),rs.getString("definition_hash"),rs.getString("source_hash"),rs.getString("mappings_hash"),Sql.uuid(rs,"mapper_subject_id"),Sql.uuid(rs,"reviewed_by"),Sql.instant(rs,"reviewed_at"),rs.getString("reason"))).optional().orElse(null);
    }
    private boolean matches(Approval approval, Source source, CatalogStore.DraftRow draft) {
        return approval!=null && approval.definitionHash().equals(Json.canonicalSha256(draft.definitionJson()))
                && approval.sourceHash().equals(source.sourceHash()) && approval.mappingsHash().equals(source.mappingsHash())
                && approval.mapper().equals(source.mapper());
    }
    private boolean eligibleReviewer(UUID ws, UUID reviewer) {
        var subject=subjects.find(reviewer).orElse(null);
        if (subject==null || subject.disabled()) return false;
        var caller=new Caller(subject.id(),subject.displayName(),subject.email(),subject.issuer(),subject.externalSubject(),subjects.storedGroups(reviewer),false);
        return policy.freshAccess(caller,ws).map(a->a.has(Permission.WORKFLOW_PUBLISH)).orElse(false);
    }
    private SubjectService.SubjectRef ref(UUID id) {
        return id==null ? null : subjects.find(id).map(s->new SubjectService.SubjectRef(id,s.displayName())).orElse(null);
    }
    private ReviewState state(Caller caller, UUID ws, UUID wf, CatalogStore.DraftRow draft, boolean required) {
        Source source=source(ws,wf);
        Approval approval=approval(ws,wf,draft.revision());
        boolean approved=source!=null && matches(approval,source,draft) && eligibleReviewer(ws,approval.reviewer());
        boolean canApprove=source!=null && source.mapper()!=null && !caller.subjectId().equals(source.mapper())
                && !caller.subjectId().equals(draft.savedBy()) && policy.freshAccess(caller,ws).map(a->a.has(Permission.WORKFLOW_PUBLISH)).orElse(false);
        return new ReviewState(source!=null,required && source!=null,draft.revision(),Json.canonicalSha256(draft.definitionJson()),
                source==null ? null : source.sourceHash(),source==null ? null : source.mappingsHash(),source==null ? null : ref(source.mapper()),
                approval==null ? null : ref(approval.reviewer()),approval==null ? null : approval.at(),approval==null ? null : approval.reason(),
                approval==null ? 0 : approval.version(),approved,canApprove,
                source==null ? "This workflow has no recorded migration conversion." : approved ? "This exact draft has an independent review." : "Review source mappings and the latest draft before approving.");
    }
    private static ApiException conflict(String message) { return new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT,message); }
    private static ApiException blocked(String message) { return new ApiException(ProblemCode.MIGRATION_BLOCKED,message); }
}
