package com.orqix.catalog.api;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.orqix.catalog.internal.MigrationGovernance;
import com.orqix.identity.CallerProvider;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class MigrationGovernanceController {
    public record PolicyRequest(@NotNull Boolean required, @Min(0) int expectedVersion, @NotBlank @Size(max=1000) String reason) {}
    public record ReviewRequest(@Min(1) int draftRevision, @Min(0) int expectedVersion, @NotBlank @Size(max=1000) String reason) {}
    private final MigrationGovernance governance;
    private final CallerProvider callers;
    public MigrationGovernanceController(MigrationGovernance governance, CallerProvider callers) { this.governance=governance; this.callers=callers; }
    @GetMapping("/migration-policy")
    public MigrationGovernance.Policy policy(@PathVariable UUID workspaceId) { return governance.policy(callers.current(),workspaceId); }
    @PutMapping("/migration-policy")
    public MigrationGovernance.Policy policy(@PathVariable UUID workspaceId, @Valid @RequestBody PolicyRequest request) {
        return governance.updatePolicy(callers.current(),workspaceId,request.required(),request.expectedVersion(),request.reason());
    }
    @GetMapping("/workflows/{workflowId}/migration-review")
    public MigrationGovernance.ReviewState review(@PathVariable UUID workspaceId,@PathVariable UUID workflowId) {
        return governance.review(callers.current(),workspaceId,workflowId);
    }
    @PostMapping("/workflows/{workflowId}/migration-review")
    public MigrationGovernance.ReviewState approve(@PathVariable UUID workspaceId,@PathVariable UUID workflowId,@Valid @RequestBody ReviewRequest request) {
        return governance.approve(callers.current(),workspaceId,workflowId,request.draftRevision(),request.expectedVersion(),request.reason());
    }
}
