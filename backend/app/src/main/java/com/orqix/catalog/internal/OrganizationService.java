package com.orqix.catalog.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.catalog.api.CatalogDtos;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.SubjectService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.shared.Ids;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * PL01 (docs/build/S46): flat workflow folders and owner reassignment. A folder is a label: reading the organization
 * needs {@code workflow.read} (so it only ever describes workflows the caller can already see) and changing it needs
 * {@code workflow.edit}. Folder membership never grants, narrows or inherits any permission.
 */
@Service
public class OrganizationService {

    public static final int MAX_FOLDERS = 50;

    public record Folder(UUID id, String name, int version, int workflowCount, Instant createdAt, Instant updatedAt) {
    }

    public record OwnerCount(UUID id, String displayName, int workflowCount) {
    }

    public record Organization(List<Folder> folders, int unfiledCount, List<OwnerCount> owners, int folderLimit) {
    }

    public record NameRequest(@NotBlank @Size(max = 80) String name) {
    }

    public record RenameRequest(@NotBlank @Size(max = 80) String name, @Min(1) int expectedVersion) {
    }

    /** {@code folderId} null returns the workflow to "unfiled". The folder version is required with a folder. */
    public record AssignRequest(UUID folderId, Integer expectedFolderVersion) {
    }

    public record OwnerRequest(@NotNull UUID ownerSubjectId, @Min(1) int expectedVersion) {
    }

    private final JdbcClient jdbc;
    private final WorkflowService workflows;
    private final CatalogStore store;
    private final PolicyService policy;
    private final SubjectService subjects;
    private final AuditWriter audit;

    public OrganizationService(JdbcClient jdbc, WorkflowService workflows, CatalogStore store, PolicyService policy,
            SubjectService subjects, AuditWriter audit) {
        this.jdbc = jdbc;
        this.workflows = workflows;
        this.store = store;
        this.policy = policy;
        this.subjects = subjects;
        this.audit = audit;
    }

    private WorkspaceAccess reader(Caller caller, UUID ws, String operation) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        policy.require(access, Permission.WORKFLOW_READ, operation, false);
        return access;
    }

    private WorkspaceAccess editor(Caller caller, UUID ws, String operation) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        policy.require(access, Permission.WORKFLOW_EDIT, operation, true);
        return access;
    }

    private Folder mapFolder(ResultSet rs, int n) throws SQLException {
        return new Folder(rs.getObject("id", UUID.class), rs.getString("name"), rs.getInt("version"),
                rs.getInt("workflow_count"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static final String FOLDER_SELECT = """
            SELECT f.id, f.name, f.version, f.created_at, f.updated_at,
                   (SELECT count(*) FROM workflow_folder_item i JOIN workflow w
                        ON w.workspace_id = i.workspace_id AND w.id = i.workflow_id AND w.archived_at IS NULL
                    WHERE i.workspace_id = f.workspace_id AND i.folder_id = f.id) AS workflow_count
            FROM workflow_folder f
            """;

    private Folder folder(UUID ws, UUID id) {
        return jdbc.sql(FOLDER_SELECT + " WHERE f.workspace_id = :ws AND f.id = :id").param("ws", ws).param("id", id)
                .query(this::mapFolder).optional().orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    public Organization organization(Caller caller, UUID ws) {
        reader(caller, ws, "getWorkflowOrganization");
        List<Folder> folders = jdbc.sql(FOLDER_SELECT + " WHERE f.workspace_id = :ws ORDER BY lower(f.name), f.id")
                .param("ws", ws).query(this::mapFolder).list();
        int unfiled = jdbc.sql("""
                SELECT count(*) FROM workflow w
                WHERE w.workspace_id = :ws AND w.archived_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM workflow_folder_item i WHERE i.workspace_id = w.workspace_id AND i.workflow_id = w.id)
                """).param("ws", ws).query(Integer.class).single();
        List<OwnerCount> owners = jdbc.sql("""
                SELECT w.owner_subject_id AS id, s.display_name, count(*) AS workflow_count
                FROM workflow w JOIN subject s ON s.id = w.owner_subject_id
                WHERE w.workspace_id = :ws AND w.archived_at IS NULL
                GROUP BY w.owner_subject_id, s.display_name ORDER BY lower(s.display_name), w.owner_subject_id
                """).param("ws", ws).query((rs, n) -> new OwnerCount(rs.getObject("id", UUID.class),
                        rs.getString("display_name"), rs.getInt("workflow_count"))).list();
        return new Organization(folders, unfiled, owners, MAX_FOLDERS);
    }

    @Transactional
    public Folder create(Caller caller, UUID ws, NameRequest request) {
        editor(caller, ws, "createWorkflowFolder");
        String name = request.name().trim();
        // Serialize concurrent creations so the folder limit and the unique name are exact.
        jdbc.sql("SELECT id FROM workspace WHERE id = :ws FOR UPDATE").param("ws", ws).query(UUID.class).optional();
        int count = jdbc.sql("SELECT count(*) FROM workflow_folder WHERE workspace_id = :ws").param("ws", ws)
                .query(Integer.class).single();
        if (count >= MAX_FOLDERS) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "A workspace can have at most " + MAX_FOLDERS + " folders.");
        }
        UUID id = Ids.newId();
        int inserted = jdbc.sql("""
                INSERT INTO workflow_folder (id, workspace_id, name, created_by) VALUES (:id, :ws, :name, :by)
                ON CONFLICT DO NOTHING
                """).param("id", id).param("ws", ws).param("name", name).param("by", caller.subjectId()).update();
        if (inserted != 1) {
            throw new ApiException(ProblemCode.DUPLICATE_KEY, "A folder with this name already exists.");
        }
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "workflow.folder.created",
                "workflowFolder", id.toString(), AuditWriter.Outcome.SUCCESS, Map.of()));
        return folder(ws, id);
    }

    @Transactional
    public Folder rename(Caller caller, UUID ws, UUID id, RenameRequest request) {
        editor(caller, ws, "renameWorkflowFolder");
        String name = request.name().trim();
        Integer current = jdbc.sql("SELECT version FROM workflow_folder WHERE workspace_id = :ws AND id = :id FOR UPDATE")
                .param("ws", ws).param("id", id).query(Integer.class).optional().orElseThrow(ApiException::notFound);
        if (current != request.expectedVersion()) {
            throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT, "The folder changed. Reload before saving.")
                    .with("currentVersion", current);
        }
        boolean taken = jdbc.sql("SELECT count(*) FROM workflow_folder WHERE workspace_id = :ws AND lower(name) = lower(:name) AND id <> :id")
                .param("ws", ws).param("name", name).param("id", id).query(Integer.class).single() > 0;
        if (taken) {
            throw new ApiException(ProblemCode.DUPLICATE_KEY, "A folder with this name already exists.");
        }
        jdbc.sql("UPDATE workflow_folder SET name = :name, version = version + 1, updated_at = now() WHERE workspace_id = :ws AND id = :id")
                .param("name", name).param("ws", ws).param("id", id).update();
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "workflow.folder.renamed",
                "workflowFolder", id.toString(), AuditWriter.Outcome.SUCCESS, Map.of()));
        return folder(ws, id);
    }

    @Transactional
    public void delete(Caller caller, UUID ws, UUID id) {
        editor(caller, ws, "deleteWorkflowFolder");
        jdbc.sql("SELECT id FROM workflow_folder WHERE workspace_id = :ws AND id = :id FOR UPDATE").param("ws", ws)
                .param("id", id).query(UUID.class).optional().orElseThrow(ApiException::notFound);
        int moved = jdbc.sql("SELECT count(*) FROM workflow_folder_item WHERE workspace_id = :ws AND folder_id = :id")
                .param("ws", ws).param("id", id).query(Integer.class).single();
        jdbc.sql("DELETE FROM workflow_folder WHERE workspace_id = :ws AND id = :id").param("ws", ws).param("id", id).update();
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "workflow.folder.deleted",
                "workflowFolder", id.toString(), AuditWriter.Outcome.SUCCESS, Map.of("unfiledWorkflows", moved)));
    }

    @Transactional
    public CatalogDtos.WorkflowView assign(Caller caller, UUID ws, UUID workflowId, AssignRequest request) {
        WorkspaceAccess access = editor(caller, ws, "setWorkflowFolder");
        workflows.visible(caller, ws, workflowId, "setWorkflowFolder");
        store.lockWorkflow(ws, workflowId).orElseThrow(ApiException::notFound);
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (request.folderId() == null) {
            if (request.expectedFolderVersion() != null) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Leave expectedFolderVersion out when removing a workflow from its folder.");
            }
            jdbc.sql("DELETE FROM workflow_folder_item WHERE workspace_id = :ws AND workflow_id = :wf").param("ws", ws)
                    .param("wf", workflowId).update();
        } else {
            if (request.expectedFolderVersion() == null) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "expectedFolderVersion is required with a folder.");
            }
            // The share lock keeps the folder from being renamed or deleted between this check and the write.
            Integer current = jdbc.sql("SELECT version FROM workflow_folder WHERE workspace_id = :ws AND id = :id FOR SHARE")
                    .param("ws", ws).param("id", request.folderId()).query(Integer.class).optional()
                    .orElseThrow(ApiException::notFound);
            if (current != request.expectedFolderVersion()) {
                throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT, "The folder changed. Reload before moving.")
                        .with("currentVersion", current);
            }
            jdbc.sql("""
                    INSERT INTO workflow_folder_item (workspace_id, workflow_id, folder_id, assigned_by)
                    VALUES (:ws, :wf, :folder, :by)
                    ON CONFLICT (workspace_id, workflow_id) DO UPDATE
                      SET folder_id = EXCLUDED.folder_id, assigned_by = EXCLUDED.assigned_by, assigned_at = now()
                    """).param("ws", ws).param("wf", workflowId).param("folder", request.folderId())
                    .param("by", caller.subjectId()).update();
            metadata.put("folderId", request.folderId().toString());
        }
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "workflow.folder.assigned",
                "workflow", workflowId.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        return workflows.view(store.workflow(ws, workflowId).orElseThrow(ApiException::notFound), access);
    }

    private boolean canEdit(SubjectService.Subject subject, UUID ws) {
        return policy.freshAccess(new Caller(subject.id(), subject.displayName(), subject.email(), subject.issuer(),
                subject.externalSubject(), subjects.storedGroups(subject.id()), false), ws)
                .map(a -> a.has(Permission.WORKFLOW_EDIT)).orElse(false);
    }

    /** People the caller may choose as the new owner (at most 50 of the first 300 known people). */
    @Transactional(readOnly = true)
    public List<SubjectService.SubjectRef> ownerCandidates(Caller caller, UUID ws) {
        editor(caller, ws, "listWorkflowOwnerCandidates");
        return subjects.knownSubjects(300).stream().filter(s -> canEdit(s, ws)).limit(50)
                .map(s -> new SubjectService.SubjectRef(s.id(), s.displayName())).toList();
    }

    @Transactional
    public CatalogDtos.WorkflowView changeOwner(Caller caller, UUID ws, UUID workflowId, OwnerRequest request) {
        WorkspaceAccess access = editor(caller, ws, "setWorkflowOwner");
        workflows.visible(caller, ws, workflowId, "setWorkflowOwner");
        CatalogStore.WorkflowRow row = store.lockWorkflow(ws, workflowId).orElseThrow(ApiException::notFound);
        if (row.version() != request.expectedVersion()) {
            throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT, "The workflow changed. Reload before changing the owner.")
                    .with("currentVersion", row.version());
        }
        // The new owner must be a real, enabled person who can edit workflows here today, so an owner is always someone
        // who can actually maintain the workflow. Their access is computed fresh, never from a cached session.
        boolean eligible = subjects.find(request.ownerSubjectId()).filter(s -> !s.disabled())
                .map(s -> canEdit(s, ws)).orElse(false);
        if (!eligible) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The new owner must be a person who can edit workflows in this workspace.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/ownerSubjectId", "eligibility",
                            "Choose someone with workflow edit access")));
        }
        int changed = jdbc.sql("""
                UPDATE workflow SET owner_subject_id = :owner, version = version + 1, updated_at = now()
                WHERE workspace_id = :ws AND id = :id AND version = :expected
                """).param("owner", request.ownerSubjectId()).param("ws", ws).param("id", workflowId)
                .param("expected", request.expectedVersion()).update();
        if (changed != 1) {
            throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT, "The workflow changed. Reload before changing the owner.");
        }
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "workflow.owner.changed",
                "workflow", workflowId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("from", row.owner().toString(), "to", request.ownerSubjectId().toString())));
        return workflows.view(store.workflow(ws, workflowId).orElseThrow(ApiException::notFound), access);
    }
}
