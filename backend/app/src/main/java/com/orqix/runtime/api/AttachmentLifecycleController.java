package com.orqix.runtime.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.runtime.internal.AttachmentLifecycle;

import jakarta.validation.Valid;

/** Quarantine, retention and text extraction for request files (docs/build/S52). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class AttachmentLifecycleController {

    private final CallerResolver callers;
    private final AttachmentLifecycle lifecycle;

    public AttachmentLifecycleController(CallerResolver callers, AttachmentLifecycle lifecycle) {
        this.callers = callers;
        this.lifecycle = lifecycle;
    }

    @PostMapping("/runs/{runId}/attachments/{attachmentId}/quarantine")
    public ResponseEntity<Void> quarantine(@PathVariable UUID workspaceId, @PathVariable UUID runId,
            @PathVariable UUID attachmentId, @Valid @RequestBody AttachmentLifecycle.QuarantineRequest request) {
        lifecycle.quarantine(callers.current(), workspaceId, runId, attachmentId, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/runs/{runId}/attachments/{attachmentId}/release")
    public ResponseEntity<Void> release(@PathVariable UUID workspaceId, @PathVariable UUID runId,
            @PathVariable UUID attachmentId) {
        lifecycle.release(callers.current(), workspaceId, runId, attachmentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/runs/{runId}/attachments/{attachmentId}/ocr")
    public AttachmentLifecycle.Ocr extract(@PathVariable UUID workspaceId, @PathVariable UUID runId,
            @PathVariable UUID attachmentId) {
        return lifecycle.extract(callers.current(), workspaceId, runId, attachmentId);
    }

    @GetMapping("/runs/{runId}/attachments/{attachmentId}/ocr")
    public AttachmentLifecycle.Ocr ocr(@PathVariable UUID workspaceId, @PathVariable UUID runId,
            @PathVariable UUID attachmentId) {
        return lifecycle.ocrResult(callers.current(), workspaceId, runId, attachmentId);
    }

    @GetMapping("/attachment-retention")
    public AttachmentLifecycle.Retention retention(@PathVariable UUID workspaceId) {
        return lifecycle.retention(callers.current(), workspaceId);
    }

    @PutMapping("/attachment-retention")
    public AttachmentLifecycle.Retention setRetention(@PathVariable UUID workspaceId,
            @Valid @RequestBody AttachmentLifecycle.RetentionRequest request) {
        return lifecycle.setRetention(callers.current(), workspaceId, request);
    }
}
