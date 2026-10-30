package com.orqix.catalog.api;

import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.catalog.internal.DecisionDmnService;
import com.orqix.catalog.internal.WorkflowService;
import com.orqix.identity.CallerResolver;

import jakarta.servlet.http.HttpServletRequest;

/** Standard DMN download and upload of decision tables (docs/build/S121). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class DecisionDmnController {

    private final CallerResolver callers;
    private final DecisionDmnService dmn;

    public DecisionDmnController(CallerResolver callers, DecisionDmnService dmn) {
        this.callers = callers;
        this.dmn = dmn;
    }

    @GetMapping("/workflows/{workflowId}/decisions/{decisionKey}/dmn")
    public ResponseEntity<byte[]> exportDecisionDmn(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @PathVariable String decisionKey, @RequestParam(required = false) Integer revision) {
        return file(dmn.exportDraft(callers.current(), workspaceId, workflowId, decisionKey, revision));
    }

    @PutMapping("/workflows/{workflowId}/decisions/{decisionKey}/dmn")
    public ResponseEntity<CatalogDtos.WorkflowDraft> importDecisionDmn(@PathVariable UUID workspaceId,
            @PathVariable UUID workflowId, @PathVariable String decisionKey,
            @RequestParam(required = false) String decisionId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch, HttpServletRequest request)
            throws java.io.IOException {
        CatalogDtos.WorkflowDraft draft = dmn.importIntoDraft(callers.current(), workspaceId, workflowId, decisionKey,
                decisionId, ifMatch, request.getContentType(), request.getInputStream());
        return ResponseEntity.ok().eTag(WorkflowService.etag(draft.revision())).body(draft);
    }

    @GetMapping("/workflows/{workflowId}/releases/{releaseId}/decisions/{decisionKey}/dmn")
    public ResponseEntity<byte[]> exportReleaseDecisionDmn(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @PathVariable UUID releaseId, @PathVariable String decisionKey) {
        return file(dmn.exportRelease(callers.current(), workspaceId, workflowId, releaseId, decisionKey));
    }

    @GetMapping("/shared-assets/decision/{assetKey}/versions/{version}/dmn")
    public ResponseEntity<byte[]> exportSharedDecisionDmn(@PathVariable UUID workspaceId, @PathVariable String assetKey,
            @PathVariable int version) {
        return file(dmn.exportShared(callers.current(), workspaceId, assetKey, version));
    }

    @PostMapping("/shared-assets/decision/dmn")
    public DecisionDmnService.Converted convertDecisionDmn(@PathVariable UUID workspaceId,
            @RequestParam(required = false) String decisionId, HttpServletRequest request) throws java.io.IOException {
        return dmn.convertForShared(callers.current(), workspaceId, decisionId, request.getContentType(),
                request.getInputStream());
    }

    private static ResponseEntity<byte[]> file(DecisionDmnService.DmnFile file) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(file.content());
    }
}
