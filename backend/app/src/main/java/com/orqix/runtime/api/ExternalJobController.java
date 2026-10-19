package com.orqix.runtime.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.runtime.internal.ExternalJobService;
import com.orqix.shared.idempotency.IdempotencyService;

import jakarta.validation.Valid;

/** Worker protocol for external jobs (docs/build/S43). Every call needs the workspace permission {@code job.work}. */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/external-jobs")
public class ExternalJobController {

    private final ExternalJobService jobs;
    private final CallerResolver callers;

    public ExternalJobController(ExternalJobService jobs, CallerResolver callers) {
        this.jobs = jobs;
        this.callers = callers;
    }

    @PostMapping("/fetch-and-lock")
    public ExternalJobDtos.JobBatch fetchAndLock(@PathVariable UUID workspaceId,
            @Valid @RequestBody ExternalJobDtos.FetchRequest request) {
        return jobs.fetchAndLock(callers.current(), workspaceId, request);
    }

    @PostMapping("/{jobId}/extend-lock")
    public ExternalJobDtos.ExtendResult extendLock(@PathVariable UUID workspaceId, @PathVariable UUID jobId,
            @Valid @RequestBody ExternalJobDtos.ExtendRequest request) {
        return jobs.extendLock(callers.current(), workspaceId, jobId, request);
    }

    @PostMapping("/{jobId}/complete")
    public ResponseEntity<Void> complete(@PathVariable UUID workspaceId, @PathVariable UUID jobId,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody ExternalJobDtos.CompleteRequest request) {
        jobs.complete(callers.current(), workspaceId, jobId, idempotencyKey, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{jobId}/await-callback")
    public ExternalJobDtos.CallbackRegistration awaitCallback(@PathVariable UUID workspaceId, @PathVariable UUID jobId,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody ExternalJobDtos.AwaitCallbackRequest request) {
        return jobs.awaitCallback(callers.current(), workspaceId, jobId, idempotencyKey, request);
    }

    @PostMapping("/{jobId}/callbacks/{callbackId}/complete")
    public ResponseEntity<Void> completeCallback(@PathVariable UUID workspaceId, @PathVariable UUID jobId,
            @PathVariable UUID callbackId,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody ExternalJobDtos.CallbackResult request) {
        jobs.completeCallback(callers.current(), workspaceId, jobId, callbackId, idempotencyKey, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{jobId}/callbacks/{callbackId}/fail")
    public ResponseEntity<Void> failCallback(@PathVariable UUID workspaceId, @PathVariable UUID jobId,
            @PathVariable UUID callbackId,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody ExternalJobDtos.CallbackFailure request) {
        jobs.failCallback(callers.current(), workspaceId, jobId, callbackId, idempotencyKey, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{jobId}/fail")
    public ResponseEntity<Void> fail(@PathVariable UUID workspaceId, @PathVariable UUID jobId,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody ExternalJobDtos.FailRequest request) {
        jobs.fail(callers.current(), workspaceId, jobId, idempotencyKey, request);
        return ResponseEntity.noContent().build();
    }
}
