package com.orqix.ai.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.ai.api.AiDtos;
import com.orqix.catalog.DraftAuthoring;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Ask AI conversation saved with a workflow draft (S124, docs/26 finding A6). Whoever can edit the draft reads,
 * saves and clears it. It is text the people editing the draft wrote or the assistant proposed; it is bounded,
 * shape-checked, never sent to audit and never sent to the model except as the last turns of a new request, which the
 * Studio sends itself. It works whether or not AI is switched on, so a saved conversation stays readable.
 */
@Service
public class DraftConversationService {

    public static final int MAX_TURNS = 40;
    public static final int MAX_TEXT = 2000;
    public static final int MAX_BYTES = 200_000;
    private static final Set<String> TURN_FIELDS = Set.of("role", "text", "mode", "questions", "proposal");

    private final PolicyService policy;
    private final DraftAuthoring drafts;
    private final ConversationStore store;
    private final Json json;
    private final com.orqix.audit.AuditWriter audit;

    public DraftConversationService(PolicyService policy, DraftAuthoring drafts, ConversationStore store, Json json,
            com.orqix.audit.AuditWriter audit) {
        this.policy = policy;
        this.drafts = drafts;
        this.store = store;
        this.json = json;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public AiDtos.Conversation get(Caller caller, UUID workspaceId, UUID workflowId) {
        require(caller, workspaceId, workflowId, "getAiConversation");
        return store.find(workspaceId, workflowId)
                .map(s -> new AiDtos.Conversation(s.turns(), s.updatedAt(), s.version()))
                .orElse(new AiDtos.Conversation(json.object().putArray("turns"), null, 0));
    }

    @Transactional
    public AiDtos.Conversation save(Caller caller, UUID workspaceId, UUID workflowId, JsonNode body) {
        require(caller, workspaceId, workflowId, "saveAiConversation");
        ArrayNode turns = validate(body);
        if (turns.isEmpty()) {
            store.delete(workspaceId, workflowId);
            return new AiDtos.Conversation(turns, null, 0);
        }
        ConversationStore.Saved saved = store.save(workspaceId, workflowId, turns, caller.subjectId());
        return new AiDtos.Conversation(saved.turns(), saved.updatedAt(), saved.version());
    }

    @Transactional
    public void delete(Caller caller, UUID workspaceId, UUID workflowId) {
        require(caller, workspaceId, workflowId, "deleteAiConversation");
        if (store.delete(workspaceId, workflowId)) {
            // The act is recorded, never the text.
            audit.write(new com.orqix.audit.AuditWriter.Entry(workspaceId, com.orqix.audit.AuditWriter.ActorType.USER,
                    caller.subjectId(), "ai.conversation.cleared", "workflow", workflowId.toString(),
                    com.orqix.audit.AuditWriter.Outcome.SUCCESS, java.util.Map.of("workflowId", workflowId.toString())));
        }
    }

    private void require(Caller caller, UUID workspaceId, UUID workflowId, String operation) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.WORKFLOW_EDIT, operation, false);
        drafts.latestDraft(caller, workspaceId, workflowId);
    }

    /** Body {@code {"turns": [...]}}: at most 40 turns of {role, text, mode?, questions?, proposal?}, 200 kB in all. */
    ArrayNode validate(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw ApiException.malformed("A request body {\"turns\": [...]} is required.");
        }
        List<ApiException.FieldError> errors = new ArrayList<>();
        for (String name : body.propertyNames()) {
            if (!"turns".equals(name)) {
                errors.add(new ApiException.FieldError("/" + name, "additionalProperties", "Invalid value"));
            }
        }
        JsonNode turns = body.path("turns");
        if (!turns.isArray()) {
            errors.add(new ApiException.FieldError("/turns", turns.isMissingNode() ? "required" : "type", "Invalid value"));
        } else if (turns.size() > MAX_TURNS) {
            errors.add(new ApiException.FieldError("/turns", "maxItems", "At most " + MAX_TURNS + " turns are kept"));
        } else {
            for (int i = 0; i < turns.size(); i++) {
                turn(turns.get(i), "/turns/" + i, errors);
            }
        }
        if (errors.isEmpty() && json.write(turns).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) {
            errors.add(new ApiException.FieldError("/turns", "maxLength", "The conversation is too long to keep; clear older turns"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The conversation cannot be saved.").withFieldErrors(errors);
        }
        return (ArrayNode) turns.deepCopy();
    }

    private static void turn(JsonNode turn, String pointer, List<ApiException.FieldError> errors) {
        if (!turn.isObject()) {
            errors.add(new ApiException.FieldError(pointer, "type", "Invalid value"));
            return;
        }
        for (String name : turn.propertyNames()) {
            if (!TURN_FIELDS.contains(name)) {
                errors.add(new ApiException.FieldError(pointer + "/" + name, "additionalProperties", "Invalid value"));
            }
        }
        String role = turn.path("role").asString("");
        if (!"author".equals(role) && !"assistant".equals(role)) {
            errors.add(new ApiException.FieldError(pointer + "/role", "enum", "Invalid value"));
        }
        JsonNode text = turn.path("text");
        if (!text.isString() || text.asString().isEmpty() || text.asString().codePointCount(0, text.asString().length()) > MAX_TEXT) {
            errors.add(new ApiException.FieldError(pointer + "/text", text.isString() ? "maxLength" : "required", "Invalid value"));
        }
        if (turn.has("mode") && !Set.of("edit", "regenerate").contains(turn.path("mode").asString(""))) {
            errors.add(new ApiException.FieldError(pointer + "/mode", "enum", "Invalid value"));
        }
        JsonNode questions = turn.path("questions");
        if (!questions.isMissingNode() && (!questions.isArray() || questions.size() > DraftEdits.MAX_QUESTIONS)) {
            errors.add(new ApiException.FieldError(pointer + "/questions", "maxItems", "Invalid value"));
        }
        if (turn.has("proposal") && !(turn.get("proposal") instanceof ObjectNode)) {
            errors.add(new ApiException.FieldError(pointer + "/proposal", "type", "Invalid value"));
        }
    }
}
