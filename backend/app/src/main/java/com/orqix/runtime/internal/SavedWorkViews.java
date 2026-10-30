package com.orqix.runtime.internal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;
import tools.jackson.databind.JsonNode;

/** A saved query never grants access to its results. Only its owner can modify or delete it. */
@Service
public class SavedWorkViews {
    public record Item(UUID id, UUID ownerId, String kind, String name, boolean shared, JsonNode filters, int revision) {}
    public record Save(String kind, String name, boolean shared, Map<String,String> filters, int expectedRevision) {}
    private final JdbcClient jdbc;
    private final PolicyService policy;
    private final Json json;
    public SavedWorkViews(JdbcClient jdbc, PolicyService policy, Json json) { this.jdbc=jdbc; this.policy=policy; this.json=json; }

    private void access(Caller caller, UUID ws, String kind) {
        if (!List.of("tasks","runs").contains(kind == null ? "" : kind)) throw ApiException.malformed("kind must be tasks or runs.");
        var access=policy.freshAccess(caller,ws).filter(a -> a.visible()).orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND));
        policy.require(access,"tasks".equals(kind) ? Permission.TASK_ACT : Permission.RUN_READ,"savedWorkViews",false);
    }
    @Transactional(readOnly=true)
    public List<Item> list(Caller caller, UUID ws, String kind) {
        access(caller,ws,kind);
        return jdbc.sql("SELECT * FROM saved_work_view WHERE workspace_id=:ws AND kind=:kind AND (owner_id=:owner OR shared) ORDER BY name,id")
            .param("ws",ws).param("kind",kind).param("owner",caller.subjectId()).query(this::map).list();
    }
    @Transactional
    public Item save(Caller caller, UUID ws, UUID id, Save input) {
        access(caller,ws,input.kind());
        if (input.name()==null || input.name().isBlank() || input.name().strip().length()>80 || input.expectedRevision()<0 || input.filters()==null)
            throw ApiException.malformed("Name, filters and a non-negative expected revision are required.");
        validate(input.kind(),input.filters());
        // Serialize capacity checks with creates in this workspace.
        jdbc.sql("SELECT id FROM workspace WHERE id=:ws FOR UPDATE").param("ws",ws).query(UUID.class).single();
        var old=jdbc.sql("SELECT * FROM saved_work_view WHERE workspace_id=:ws AND id=:id").param("ws",ws).param("id",id).query(this::map).optional();
        if (old.isPresent() && (!old.get().ownerId().equals(caller.subjectId()) || !old.get().kind().equals(input.kind()))) throw new ApiException(ProblemCode.NOT_FOUND);
        if (input.expectedRevision() != old.map(Item::revision).orElse(0)) throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT,"This saved view changed. Reload it before saving.");
        if (old.isEmpty() && jdbc.sql("SELECT count(*) FROM saved_work_view WHERE workspace_id=:ws").param("ws",ws).query(Integer.class).single()>=200)
            throw ApiException.malformed("This workspace already has 200 saved views. Remove an unused view first.");
        jdbc.sql("""
            INSERT INTO saved_work_view(workspace_id,id,owner_id,kind,name,shared,filters,revision)
            VALUES(:ws,:id,:owner,:kind,:name,:shared,CAST(:filters AS jsonb),1)
            ON CONFLICT(workspace_id,id) DO UPDATE SET name=excluded.name,shared=excluded.shared,filters=excluded.filters,
              revision=saved_work_view.revision+1,updated_at=now()
            """).param("ws",ws).param("id",id).param("owner",caller.subjectId()).param("kind",input.kind())
            .param("name",input.name().strip()).param("shared",input.shared()).param("filters",json.write(input.filters())).update();
        return jdbc.sql("SELECT * FROM saved_work_view WHERE workspace_id=:ws AND id=:id").param("ws",ws).param("id",id).query(this::map).single();
    }
    @Transactional
    public void delete(Caller caller,UUID ws,UUID id,int expectedRevision) {
        var item=jdbc.sql("SELECT * FROM saved_work_view WHERE workspace_id=:ws AND id=:id AND owner_id=:owner")
            .param("ws",ws).param("id",id).param("owner",caller.subjectId()).query(this::map).optional().orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND));
        access(caller,ws,item.kind());
        if (jdbc.sql("DELETE FROM saved_work_view WHERE workspace_id=:ws AND id=:id AND owner_id=:owner AND revision=:revision")
            .param("ws",ws).param("id",id).param("owner",caller.subjectId()).param("revision",expectedRevision).update()!=1)
            throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT,"This saved view changed. Reload it before deleting.");
    }
    private Item map(java.sql.ResultSet rs,int n) throws java.sql.SQLException {
        return new Item(Sql.uuid(rs,"id"),Sql.uuid(rs,"owner_id"),rs.getString("kind"),rs.getString("name"),rs.getBoolean("shared"),json.parse(rs.getString("filters")),rs.getInt("revision"));
    }
    private void validate(String kind,Map<String,String> filters) {
        Set<String> allowed="tasks".equals(kind) ? Set.of("view","q","queue","due","sort","minPriority") : Set.of("lifecycle","workflowId","businessKey","overdue","outcomeKind");
        if (!allowed.containsAll(filters.keySet()) || filters.values().stream().anyMatch(v -> v==null || v.length()>200)) throw ApiException.malformed("Unsupported saved-view filters.");
        if ("tasks".equals(kind)) {
            TaskFilter.of(filters.get("q"),filters.get("queue"),filters.get("due"),java.time.Instant.EPOCH);
            member(filters,"view",Set.of("assigned","claimable","completed"));
            member(filters,"sort",Set.of("priority","oldest","newest"));
            member(filters,"minPriority",Set.of("0","50"));
        } else {
            member(filters,"lifecycle",Set.of("waiting","running","needsAttention","completed","cancelled"));
            member(filters,"outcomeKind",Set.of("success","rejected","failed"));
            member(filters,"overdue",Set.of("true"));
            if (filters.containsKey("workflowId") && !filters.get("workflowId").isEmpty()) {
                try { UUID.fromString(filters.get("workflowId")); } catch (IllegalArgumentException e) { throw ApiException.malformed("Invalid workflow filter."); }
            }
        }
    }
    private void member(Map<String,String> filters,String key,Set<String> values) {
        if (filters.containsKey(key) && !filters.get(key).isEmpty() && !values.contains(filters.get(key))) throw ApiException.malformed("Invalid " + key + " filter.");
    }
}
