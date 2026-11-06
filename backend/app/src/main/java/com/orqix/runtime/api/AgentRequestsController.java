package com.orqix.runtime.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.runtime.internal.AgentHumanRequests;

/** Durable agents (docs/build/S135; contracts/openapi.yaml, tag AgentTools): requests the agents raised for people. */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class AgentRequestsController {

    private final CallerResolver callers;
    private final AgentHumanRequests requests;

    public AgentRequestsController(CallerResolver callers, AgentHumanRequests requests) {
        this.callers = callers;
        this.requests = requests;
    }

    @GetMapping("/agent-requests")
    public List<AgentHumanRequests.HumanRequest> listAgentRequests(@PathVariable UUID workspaceId,
            @RequestParam(name = "status", required = false) String status) {
        return requests.list(callers.current(), workspaceId, status);
    }

    @PostMapping("/agent-requests/{requestId}/answer")
    public AgentHumanRequests.HumanRequest answerAgentRequest(@PathVariable UUID workspaceId,
            @PathVariable UUID requestId, @Valid @RequestBody AgentHumanRequests.AnswerRequest request) {
        return requests.answer(callers.current(), workspaceId, requestId, request);
    }
}
