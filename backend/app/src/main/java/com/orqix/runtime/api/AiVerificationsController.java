package com.orqix.runtime.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.runtime.internal.AiVerifications;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Verification tasks of document extraction (contracts/openapi.yaml tag Completion; CG-A06). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class AiVerificationsController {

    private final CallerResolver callers;
    private final AiVerifications verifications;

    public AiVerificationsController(CallerResolver callers, AiVerifications verifications) {
        this.callers = callers;
        this.verifications = verifications;
    }

    @GetMapping("/ai-verifications")
    public ObjectNode listAiVerifications(@PathVariable UUID workspaceId) {
        return verifications.queue(callers.current(), workspaceId);
    }

    @GetMapping("/runs/{runId}/ai-verifications")
    public ObjectNode listRunAiVerifications(@PathVariable UUID workspaceId, @PathVariable UUID runId) {
        return verifications.list(callers.current(), workspaceId, runId);
    }

    @GetMapping("/runs/{runId}/ai-verifications/{verificationId}")
    public ObjectNode getAiVerification(@PathVariable UUID workspaceId, @PathVariable UUID runId,
            @PathVariable UUID verificationId) {
        return verifications.get(callers.current(), workspaceId, runId, verificationId);
    }

    @PostMapping("/runs/{runId}/ai-verifications/{verificationId}/submit")
    public ObjectNode submitAiVerification(@PathVariable UUID workspaceId, @PathVariable UUID runId,
            @PathVariable UUID verificationId, @RequestBody(required = false) JsonNode body) {
        return verifications.submit(callers.current(), workspaceId, runId, verificationId, body);
    }
}
