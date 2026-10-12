package com.orqix.integration.api;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import com.orqix.identity.CallerResolver;
import com.orqix.integration.DocumentSources;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/document-sources")
public final class DocumentSourcesController {
    private final CallerResolver callers; private final DocumentSources service;
    public DocumentSourcesController(CallerResolver callers, DocumentSources service) { this.callers = callers; this.service = service; }
    @GetMapping public List<DocumentSources.Source> list(@PathVariable UUID workspaceId) { return service.list(callers.current(), workspaceId); }
    @GetMapping("/grants") public List<DocumentSources.AdminSource> grants(@PathVariable UUID workspaceId) { return service.adminList(callers.current(), workspaceId); }
    @PutMapping("/grants/{sourceId}") public DocumentSources.AdminSource save(@PathVariable UUID workspaceId, @PathVariable UUID sourceId, @RequestBody DocumentSources.Save request) {
        return service.save(callers.current(), workspaceId, sourceId, request);
    }
    @GetMapping("/{sourceId}/files") public JsonNode files(@PathVariable UUID workspaceId, @PathVariable UUID sourceId) { return service.files(callers.current(), workspaceId, sourceId); }
    @GetMapping("/{sourceId}/files/{itemId}/versions") public JsonNode versions(@PathVariable UUID workspaceId, @PathVariable UUID sourceId, @PathVariable String itemId) { return service.versions(callers.current(), workspaceId, sourceId, itemId); }
}
