package com.orqix.runtime.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.orqix.identity.CallerResolver;
import com.orqix.runtime.internal.RunAttachments;
import com.orqix.shared.idempotency.IdempotencyService;
import com.orqix.shared.problem.ApiException;

/**
 * Files on requests (docs/build/S17, contracts/openapi.yaml tag Attachments). Uploads are multipart (one {@code file}
 * part, optional {@code description}); downloads are always {@code attachment} with {@code nosniff} and a sandboxing
 * Content-Security-Policy, and always {@code application/octet-stream}, so a browser never renders a stored file.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class AttachmentsController {

    private final CallerResolver callers;
    private final RunAttachments attachments;

    public AttachmentsController(CallerResolver callers, RunAttachments attachments) {
        this.callers = callers;
        this.attachments = attachments;
    }

    @PostMapping(path = "/attachment-uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RunAttachments.Staged> upload(@PathVariable UUID workspaceId,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @RequestPart(name = "file", required = false) MultipartFile file) {
        RunAttachments.StagedResult result = attachments.stage(callers.current(), workspaceId, idempotencyKey, upload(file));
        return replayAware(result.replayed(), result.staged());
    }

    @DeleteMapping("/attachment-uploads/{uploadId}")
    public ResponseEntity<Void> discardUpload(@PathVariable UUID workspaceId, @PathVariable UUID uploadId) {
        attachments.discard(callers.current(), workspaceId, uploadId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/runs/{runId}/attachments")
    public RunAttachments.AttachmentList list(@PathVariable UUID workspaceId, @PathVariable UUID runId) {
        return attachments.list(callers.current(), workspaceId, runId);
    }

    @PostMapping(path = "/runs/{runId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RunAttachments.Attachment> add(@PathVariable UUID workspaceId, @PathVariable UUID runId,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @RequestPart(name = "file", required = false) MultipartFile file,
            @RequestParam(name = "description", required = false) String description) {
        RunAttachments.Added added = attachments.add(callers.current(), workspaceId, runId, idempotencyKey, upload(file),
                description);
        return replayAware(added.replayed(), added.attachment());
    }

    @GetMapping("/runs/{runId}/attachments/{attachmentId}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID workspaceId, @PathVariable UUID runId,
            @PathVariable UUID attachmentId) {
        RunAttachments.Content content = attachments.content(callers.current(), workspaceId, runId, attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(content.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(content.name(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox")
                .cacheControl(CacheControl.noStore())
                .body(content.bytes());
    }

    @DeleteMapping("/runs/{runId}/attachments/{attachmentId}")
    public ResponseEntity<Void> remove(@PathVariable UUID workspaceId, @PathVariable UUID runId,
            @PathVariable UUID attachmentId) {
        attachments.remove(callers.current(), workspaceId, runId, attachmentId);
        return ResponseEntity.noContent().build();
    }

    private static RunAttachments.Upload upload(MultipartFile file) {
        if (file == null) {
            return null;
        }
        try {
            return new RunAttachments.Upload(file.getOriginalFilename(), file.getContentType(), file.getInputStream());
        } catch (IOException e) {
            throw ApiException.malformed("The file could not be read.");
        }
    }

    private static <T> ResponseEntity<T> replayAware(boolean replayed, T body) {
        if (replayed) {
            return ResponseEntity.ok().header(IdempotencyService.REPLAYED_HEADER, "true").body(body);
        }
        return ResponseEntity.status(201).body(body);
    }
}
