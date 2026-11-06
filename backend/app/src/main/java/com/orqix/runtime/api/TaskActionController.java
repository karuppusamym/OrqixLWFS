package com.orqix.runtime.api;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.runtime.internal.TaskActionService;
import com.orqix.runtime.internal.TaskActionSettings;

import tools.jackson.databind.JsonNode;

/**
 * Actionable tasks in Teams, Slack and email (CG-T01, contracts/openapi.yaml tag TaskActions). The link and interaction
 * routes read no session or token: a link is its own signed credential, an interaction carries the platform signature.
 */
@RestController
@RequestMapping("/api/v1")
public class TaskActionController {

    private final CallerResolver callers;
    private final TaskActionSettings settings;
    private final TaskActionService actions;

    public TaskActionController(CallerResolver callers, TaskActionSettings settings, TaskActionService actions) {
        this.callers = callers;
        this.settings = settings;
        this.actions = actions;
    }

    @GetMapping("/workspaces/{workspaceId}/task-actions/settings")
    public TaskActionSettings.SettingsView getTaskActionSettings(@PathVariable UUID workspaceId) {
        return settings.get(callers.current(), workspaceId);
    }

    @PutMapping("/workspaces/{workspaceId}/task-actions/settings")
    public TaskActionSettings.SettingsView updateTaskActionSettings(@PathVariable UUID workspaceId,
            @Valid @RequestBody TaskActionSettings.SettingsRequest request) {
        return settings.update(callers.current(), workspaceId, request);
    }

    @GetMapping("/workspaces/{workspaceId}/task-actions/identities")
    public TaskActionSettings.IdentityList listChatIdentities(@PathVariable UUID workspaceId) {
        return settings.identities(callers.current(), workspaceId);
    }

    @PutMapping("/workspaces/{workspaceId}/task-actions/identities")
    public TaskActionSettings.IdentityView linkChatIdentity(@PathVariable UUID workspaceId,
            @Valid @RequestBody TaskActionSettings.IdentityRequest request) {
        return settings.link(callers.current(), workspaceId, request);
    }

    @DeleteMapping("/workspaces/{workspaceId}/task-actions/identities/{platform}/{platformUserId}")
    public ResponseEntity<Void> unlinkChatIdentity(@PathVariable UUID workspaceId, @PathVariable String platform,
            @PathVariable String platformUserId) {
        settings.unlink(callers.current(), workspaceId, platform, platformUserId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/workspaces/{workspaceId}/task-actions/messages")
    public TaskActionSettings.MessageList listTaskActionMessages(@PathVariable UUID workspaceId,
            @RequestParam(required = false) Integer limit) {
        return settings.messages(callers.current(), workspaceId, limit);
    }

    /** Slack interactivity request URL; authenticated by the Slack signature only. */
    @PostMapping("/workspaces/{workspaceId}/task-actions/slack/interactions")
    public JsonNode receiveSlackInteraction(@PathVariable UUID workspaceId,
            @RequestHeader(name = "X-Slack-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(name = "X-Slack-Signature", required = false) String signature,
            @RequestBody(required = false) byte[] body) {
        return actions.slack(workspaceId, timestamp, signature, body == null ? new byte[0] : body);
    }

    /** Teams outgoing-webhook URL; authenticated by its HMAC Authorization header only. */
    @PostMapping("/workspaces/{workspaceId}/task-actions/teams/interactions")
    public JsonNode receiveTeamsInteraction(@PathVariable UUID workspaceId,
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody(required = false) byte[] body) {
        return actions.teams(workspaceId, authorization, body == null ? new byte[0] : body);
    }

    /** The confirmation page of a signed action link. */
    @GetMapping("/task-action-links/{token}")
    public TaskActionService.LinkView getTaskActionLink(@PathVariable String token) {
        return actions.link(token);
    }

    /** Executes the confirmed action of a signed action link as the person it was issued to. */
    @PostMapping("/task-action-links/{token}")
    public TaskActionService.ActionResult useTaskActionLink(@PathVariable String token,
            @Valid @RequestBody TaskActionService.LinkActRequest request) {
        return actions.act(token, request);
    }
}
