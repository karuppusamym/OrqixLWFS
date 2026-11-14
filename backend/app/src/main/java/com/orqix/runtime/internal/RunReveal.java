package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.catalog.ReleaseQueries;
import com.orqix.compiler.FieldClassification;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-I05 (docs/build/S151): the only way to read a personal or sensitive answer in plain text. Needs the request's
 * data to be visible to the caller (initiator, participant or run.read-data) and the grant-only {@code data.reveal};
 * every reveal is audited with the request and field names (never the values).
 */
@Service
public class RunReveal {

    public record Revealed(UUID runId, ObjectNode values, Instant revealedAt) {
    }

    private final RunAccess runAccess;
    private final PolicyService policy;
    private final ReleaseQueries releases;
    private final AuditWriter audit;
    private final Json json;
    private final Clock clock;

    public RunReveal(RunAccess runAccess, PolicyService policy, ReleaseQueries releases, AuditWriter audit, Json json,
            Clock clock) {
        this.runAccess = runAccess;
        this.policy = policy;
        this.releases = releases;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public Revealed reveal(Caller caller, UUID workspaceId, UUID runId, List<String> fields) {
        RunAccess.Visible visible = runAccess.require(caller, workspaceId, runId);
        if (!visible.dataVisible()) {
            throw policy.deny(workspaceId, caller.subjectId(), Permission.RUN_READ_DATA.value(), "revealRunData", true,
                    new ApiException(ProblemCode.FORBIDDEN));
        }
        policy.require(visible.access(), Permission.DATA_REVEAL, "revealRunData", true);
        RunStore.RunRow run = visible.run();
        JsonNode schema = releases.require(workspaceId, run.releaseId()).inputSchema();
        Map<String, String> protectedFields = FieldClassification.protectedFields(schema);
        List<String> requested = fields == null || fields.isEmpty() ? new ArrayList<>(protectedFields.keySet())
                : fields;
        if (requested.size() > 100) {
            throw ApiException.malformed("At most 100 fields can be revealed at once.");
        }
        List<ApiException.FieldError> errors = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            if (!protectedFields.containsKey(requested.get(i))) {
                errors.add(new ApiException.FieldError("/fields/" + i, "enum", "Not a masked field of this request."));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Only masked fields can be revealed.")
                    .withFieldErrors(errors);
        }
        ObjectNode values = json.object();
        List<String> classifications = new ArrayList<>();
        for (String field : requested) {
            JsonNode value = run.input().path(field);
            values.set(field, value.isMissingNode() ? json.object().nullNode() : value);
            classifications.add(protectedFields.get(field));
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("runId", runId.toString());
        metadata.put("fields", List.copyOf(requested));
        metadata.put("classifications", classifications);
        audit.write(new AuditWriter.Entry(workspaceId, caller.machine() ? AuditWriter.ActorType.SERVICE
                : AuditWriter.ActorType.USER, caller.subjectId(), "data.revealed", "run", runId.toString(),
                AuditWriter.Outcome.SUCCESS, metadata));
        return new Revealed(runId, values, clock.instant());
    }
}
