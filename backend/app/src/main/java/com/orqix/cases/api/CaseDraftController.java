package com.orqix.cases.api;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.orqix.cases.CaseDraftService;
import com.orqix.cases.CaseService;
import com.orqix.identity.CallerResolver;

/** S126: case type drafts, their check and their publication (docs/build/S126-case-designer-page.md). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/cases/drafts")
public class CaseDraftController {
    private final CaseDraftService drafts;
    private final CallerResolver callers;
    public CaseDraftController(CaseDraftService drafts, CallerResolver callers) { this.drafts = drafts; this.callers = callers; }

    @GetMapping
    public List<CaseDraftService.DraftSummary> list(@PathVariable UUID workspaceId) {
        return drafts.list(callers.current(), workspaceId);
    }
    @PostMapping
    public ResponseEntity<CaseDraftService.Draft> create(@PathVariable UUID workspaceId, @RequestBody CaseDraftService.DraftInput request) {
        return ResponseEntity.status(201).body(drafts.create(callers.current(), workspaceId, request));
    }
    @GetMapping("/{draftId}")
    public CaseDraftService.Draft get(@PathVariable UUID workspaceId, @PathVariable UUID draftId) {
        return drafts.get(callers.current(), workspaceId, draftId);
    }
    @PutMapping("/{draftId}")
    public CaseDraftService.Draft save(@PathVariable UUID workspaceId, @PathVariable UUID draftId, @RequestBody CaseDraftService.SaveDraft request) {
        return drafts.save(callers.current(), workspaceId, draftId, request);
    }
    @DeleteMapping("/{draftId}")
    public ResponseEntity<Void> delete(@PathVariable UUID workspaceId, @PathVariable UUID draftId) {
        drafts.delete(callers.current(), workspaceId, draftId);
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/{draftId}/check")
    public CaseDraftService.Check check(@PathVariable UUID workspaceId, @PathVariable UUID draftId) {
        return drafts.check(callers.current(), workspaceId, draftId);
    }
    @PostMapping("/{draftId}/publish")
    public ResponseEntity<CaseService.Definition> publish(@PathVariable UUID workspaceId, @PathVariable UUID draftId,
            @RequestBody CaseDraftService.Publish request) {
        return ResponseEntity.status(201).body(drafts.publish(callers.current(), workspaceId, draftId, request));
    }
}
