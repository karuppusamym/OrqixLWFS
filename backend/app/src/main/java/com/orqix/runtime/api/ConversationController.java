package com.orqix.runtime.api;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.orqix.identity.CallerResolver;
import com.orqix.runtime.internal.ConversationIntentService;
import com.orqix.runtime.internal.ConversationService;
import com.orqix.shared.idempotency.IdempotencyService;
import tools.jackson.databind.JsonNode;

/** Guided conversation uses the same authorized services as forms and the task inbox. */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/conversations")
public class ConversationController {
    private final CallerResolver callers;
    private final ConversationService conversations;
    private final ConversationIntentService intents;
    public ConversationController(CallerResolver callers, ConversationService conversations,
            ConversationIntentService intents) {
        this.callers = callers;
        this.conversations = conversations;
        this.intents = intents;
    }
    @PostMapping("/messages")
    public ResponseEntity<JsonNode> sendConversationMessage(@PathVariable UUID workspaceId,
            @RequestHeader(name=IdempotencyService.HEADER, required=false) String key,
            @RequestBody(required=false) JsonNode body) {
        var caller = callers.current();
        conversations.validateMessage(caller, workspaceId, key, body);
        var interpretation = conversations.isCompletedReplay(caller, workspaceId, key, body)
                ? null : intents.interpret(caller, workspaceId, body);
        var reply = conversations.message(caller, workspaceId, key, body, interpretation);
        return ResponseEntity.ok().header(IdempotencyService.REPLAYED_HEADER, Boolean.toString(reply.replayed()))
                .body(reply.body());
    }
    @GetMapping("/{conversationId}")
    public JsonNode getConversation(@PathVariable UUID workspaceId, @PathVariable UUID conversationId) {
        return conversations.get(callers.current(), workspaceId, conversationId);
    }
}
