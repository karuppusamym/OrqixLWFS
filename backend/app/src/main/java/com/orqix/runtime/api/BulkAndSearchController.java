package com.orqix.runtime.api;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.runtime.internal.BulkOperations;
import com.orqix.runtime.internal.JobDashboard;
import com.orqix.runtime.internal.OperatorService;
import com.orqix.runtime.internal.RunService;
import com.orqix.shared.idempotency.IdempotencyService;

/** Bulk operations, searchable fields and the job dashboard (contracts/openapi.yaml, tag BulkAndSearch; S142). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class BulkAndSearchController {

    private final CallerResolver callers;
    private final RunService runs;
    private final BulkOperations bulk;
    private final JobDashboard dashboard;

    public BulkAndSearchController(CallerResolver callers, RunService runs, BulkOperations bulk,
            JobDashboard dashboard) {
        this.callers = callers;
        this.runs = runs;
        this.bulk = bulk;
        this.dashboard = dashboard;
    }

    @GetMapping("/workflows/{workflowId}/searchable-fields")
    public BulkDtos.SearchableFields listSearchableFields(@PathVariable UUID workspaceId,
            @PathVariable UUID workflowId) {
        return new BulkDtos.SearchableFields(runs.searchableFields(callers.current(), workspaceId, workflowId).stream()
                .map(f -> new BulkDtos.SearchableField(f.key(), f.title(), f.kind(), f.options())).toList());
    }

    @PostMapping("/bulk-operations/preview")
    public ResponseEntity<BulkDtos.Operation> previewBulkOperation(@PathVariable UUID workspaceId,
            @Valid @RequestBody BulkDtos.PreviewRequest request) {
        BulkDtos.Operation op = bulk.preview(callers.current(), workspaceId, request);
        return ResponseEntity.created(URI.create("/api/v1/workspaces/" + workspaceId + "/bulk-operations/" + op.id()))
                .body(op);
    }

    @GetMapping("/bulk-operations")
    public BulkDtos.OperationPage listBulkOperations(@PathVariable UUID workspaceId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return bulk.list(callers.current(), workspaceId, cursor, limit);
    }

    @GetMapping("/bulk-operations/{bulkOperationId}")
    public BulkDtos.Operation getBulkOperation(@PathVariable UUID workspaceId, @PathVariable UUID bulkOperationId) {
        return bulk.get(callers.current(), workspaceId, bulkOperationId);
    }

    @GetMapping("/bulk-operations/{bulkOperationId}/items")
    public BulkDtos.ItemPage listBulkOperationItems(@PathVariable UUID workspaceId,
            @PathVariable UUID bulkOperationId, @RequestParam(required = false) String state,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return bulk.items(callers.current(), workspaceId, bulkOperationId, state, cursor, limit);
    }

    @PostMapping("/bulk-operations/{bulkOperationId}/execute")
    public ResponseEntity<BulkDtos.Operation> executeBulkOperation(@PathVariable UUID workspaceId,
            @PathVariable UUID bulkOperationId,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody BulkDtos.ExecuteRequest request) {
        OperatorService.Result<BulkDtos.Operation> result = bulk.execute(callers.current(), workspaceId,
                bulkOperationId, idempotencyKey, request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (result.replayed()) {
            builder.header(IdempotencyService.REPLAYED_HEADER, "true");
        }
        return builder.body(result.body());
    }

    @PostMapping("/bulk-operations/{bulkOperationId}/pause")
    public BulkDtos.Operation pauseBulkOperation(@PathVariable UUID workspaceId, @PathVariable UUID bulkOperationId,
            @Valid @RequestBody BulkDtos.ControlRequest request) {
        return bulk.pause(callers.current(), workspaceId, bulkOperationId, request);
    }

    @PostMapping("/bulk-operations/{bulkOperationId}/resume")
    public BulkDtos.Operation resumeBulkOperation(@PathVariable UUID workspaceId, @PathVariable UUID bulkOperationId,
            @Valid @RequestBody BulkDtos.ControlRequest request) {
        return bulk.resume(callers.current(), workspaceId, bulkOperationId, request);
    }

    @PostMapping("/bulk-operations/{bulkOperationId}/cancel")
    public BulkDtos.Operation cancelBulkOperation(@PathVariable UUID workspaceId, @PathVariable UUID bulkOperationId,
            @Valid @RequestBody BulkDtos.ControlRequest request) {
        return bulk.cancel(callers.current(), workspaceId, bulkOperationId, request);
    }

    @GetMapping("/job-dashboard")
    public BulkDtos.JobDashboard getJobDashboard(@PathVariable UUID workspaceId) {
        return dashboard.get(callers.current(), workspaceId);
    }
}
