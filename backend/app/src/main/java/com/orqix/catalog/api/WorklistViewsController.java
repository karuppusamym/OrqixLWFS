package com.orqix.catalog.api;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import com.orqix.catalog.internal.WorklistViews;
import com.orqix.identity.CallerResolver;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/worklist-view")
public class WorklistViewsController {
    private final WorklistViews views;
    private final CallerResolver callers;
    public WorklistViewsController(WorklistViews views,CallerResolver callers) { this.views=views;this.callers=callers; }
    @GetMapping public WorklistViews.State get(@PathVariable UUID workspaceId) { return views.get(callers.current(),workspaceId); }
    @PutMapping public WorklistViews.State assign(@PathVariable UUID workspaceId,@RequestBody WorklistViews.Assign request) { return views.assign(callers.current(),workspaceId,request); }
}
