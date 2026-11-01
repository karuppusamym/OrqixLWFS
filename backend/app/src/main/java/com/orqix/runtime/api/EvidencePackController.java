package com.orqix.runtime.api;

import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.runtime.evidencepack.EvidenceRenderer;
import com.orqix.runtime.evidencepack.ReleaseEvidencePack;
import com.orqix.runtime.evidencepack.RunEvidencePack;

/** Control evidence packs for a release and for a run, as PDF, DOCX or JSON (docs/27 section 3, Create). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class EvidencePackController {

    private final CallerResolver callers;
    private final ReleaseEvidencePack releases;
    private final RunEvidencePack runs;

    public EvidencePackController(CallerResolver callers, ReleaseEvidencePack releases, RunEvidencePack runs) {
        this.callers = callers;
        this.releases = releases;
        this.runs = runs;
    }

    @GetMapping("/workflows/{workflowId}/releases/{releaseId}/evidence-pack")
    public ResponseEntity<byte[]> exportReleaseEvidencePack(@PathVariable UUID workspaceId,
            @PathVariable UUID workflowId, @PathVariable UUID releaseId,
            @RequestParam(defaultValue = "pdf") String format) {
        return file(releases.export(callers.current(), workspaceId, workflowId, releaseId, format));
    }

    @GetMapping("/runs/{runId}/evidence-pack")
    public ResponseEntity<byte[]> exportRunEvidencePack(@PathVariable UUID workspaceId, @PathVariable UUID runId,
            @RequestParam(defaultValue = "pdf") String format) {
        return file(runs.export(callers.current(), workspaceId, runId, format));
    }

    private static ResponseEntity<byte[]> file(EvidenceRenderer.Rendered rendered) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, rendered.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(rendered.fileName()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(rendered.bytes());
    }
}
