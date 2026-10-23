package com.orqix.integration.api;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.integration.agentwrite.AgentWriteRetention;
import com.orqix.integration.agentwrite.AgentWriteService;

/** Approved write-capable agent tools (IN04, docs/build/S75; contracts/openapi.yaml, tag Agent write tools). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class AgentWriteToolController {

    private final CallerResolver callers;
    private final AgentWriteService writes;
    private final AgentWriteRetention retention;

    public AgentWriteToolController(CallerResolver callers, AgentWriteService writes, AgentWriteRetention retention) {
        this.callers = callers;
        this.writes = writes;
        this.retention = retention;
    }

    @GetMapping("/agent-write-tools")
    public AgentWriteService.Overview listAgentWriteTools(@PathVariable UUID workspaceId) {
        return writes.overview(callers.current(), workspaceId);
    }

    @PostMapping("/agent-write-tools/approvals")
    public ResponseEntity<AgentWriteService.Approval> approveAgentWriteTool(@PathVariable UUID workspaceId,
            @Valid @RequestBody AgentWriteService.ApproveRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(writes.approve(callers.current(), workspaceId, request));
    }

    @PostMapping("/agent-write-tools/approvals/{approvalId}/revoke")
    public AgentWriteService.Approval revokeAgentWriteTool(@PathVariable UUID workspaceId, @PathVariable UUID approvalId,
            @Valid @RequestBody AgentWriteService.RevokeRequest request) {
        return writes.revoke(callers.current(), workspaceId, approvalId, request);
    }

    @GetMapping("/agent-writes")
    public AgentWriteService.WriteCallList listAgentWriteCalls(@PathVariable UUID workspaceId,
            @RequestParam(name = "state", required = false) String state) {
        return writes.calls(callers.current(), workspaceId, state);
    }

    @PostMapping("/agent-writes/{writeId}/reconcile")
    public AgentWriteService.WriteCall reconcileAgentWrite(@PathVariable UUID workspaceId, @PathVariable UUID writeId,
            @Valid @RequestBody AgentWriteService.ReconcileRequest request) {
        return writes.reconcile(callers.current(), workspaceId, writeId, request);
    }

    @GetMapping("/agent-write-retention")
    public AgentWriteRetention.Retention getAgentWriteRetention(@PathVariable UUID workspaceId) {
        return retention.retention(callers.current(), workspaceId);
    }

    @PutMapping("/agent-write-retention")
    public AgentWriteRetention.Retention setAgentWriteRetention(@PathVariable UUID workspaceId,
            @Valid @RequestBody AgentWriteRetention.RetentionRequest request) {
        return retention.setRetention(callers.current(), workspaceId, request);
    }
}
