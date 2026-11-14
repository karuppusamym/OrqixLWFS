package com.orqix.operations.api;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.operations.retention.RetentionService;

/** Run retention policies, legal holds, sweeps and archives (CG-O07, CG-P04, docs/build/S144). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class RetentionController {

    private final CallerResolver callers;
    private final RetentionService retention;

    public RetentionController(CallerResolver callers, RetentionService retention) {
        this.callers = callers;
        this.retention = retention;
    }

    @GetMapping("/retention-policies")
    public RetentionService.PolicyList listRetentionPolicies(@PathVariable UUID workspaceId) {
        return retention.listPolicies(callers.current(), workspaceId);
    }

    @PutMapping("/workflows/{workflowId}/retention-policy")
    public RetentionService.PolicyView setRetentionPolicy(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @Valid @RequestBody RetentionService.SetPolicy request) {
        return retention.setPolicy(callers.current(), workspaceId, workflowId, request);
    }

    @DeleteMapping("/workflows/{workflowId}/retention-policy")
    public ResponseEntity<Void> removeRetentionPolicy(@PathVariable UUID workspaceId, @PathVariable UUID workflowId) {
        retention.removePolicy(callers.current(), workspaceId, workflowId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/legal-holds")
    public RetentionService.HoldList listLegalHolds(@PathVariable UUID workspaceId,
            @RequestParam(required = false) Boolean active) {
        return retention.listHolds(callers.current(), workspaceId, active);
    }

    @PostMapping("/legal-holds")
    public ResponseEntity<RetentionService.HoldView> placeLegalHold(@PathVariable UUID workspaceId,
            @Valid @RequestBody RetentionService.PlaceHold request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(retention.placeHold(callers.current(), workspaceId, request));
    }

    @PostMapping("/legal-holds/{holdId}/release")
    public RetentionService.HoldView releaseLegalHold(@PathVariable UUID workspaceId, @PathVariable UUID holdId,
            @Valid @RequestBody RetentionService.ReleaseHold request) {
        return retention.releaseHold(callers.current(), workspaceId, holdId, request);
    }

    @GetMapping("/retention-sweeps")
    public RetentionService.SweepList listRetentionSweeps(@PathVariable UUID workspaceId) {
        return retention.listSweeps(callers.current(), workspaceId);
    }

    @PostMapping("/retention-sweeps")
    public RetentionService.SweepView startRetentionSweep(@PathVariable UUID workspaceId,
            @Valid @RequestBody RetentionService.StartSweep request) {
        return retention.startSweep(callers.current(), workspaceId, request);
    }

    @GetMapping("/run-archives")
    public RetentionService.ArchiveList listRunArchives(@PathVariable UUID workspaceId,
            @RequestParam(required = false) String reference) {
        return retention.listArchives(callers.current(), workspaceId, reference);
    }

    @GetMapping("/run-archives/{archiveId}/content")
    public ResponseEntity<byte[]> downloadRunArchive(@PathVariable UUID workspaceId, @PathVariable UUID archiveId) {
        RetentionService.Download download = retention.download(callers.current(), workspaceId, archiveId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(download.fileName()).build().toString())
                .body(download.bytes());
    }
}
