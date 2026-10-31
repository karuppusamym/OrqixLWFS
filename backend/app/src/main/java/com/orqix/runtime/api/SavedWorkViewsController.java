package com.orqix.runtime.api;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import com.orqix.identity.CallerResolver;
import com.orqix.runtime.internal.SavedWorkViews;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/work-views")
public class SavedWorkViewsController {
    private final SavedWorkViews views;
    private final CallerResolver callers;
    public SavedWorkViewsController(SavedWorkViews views,CallerResolver callers) { this.views=views; this.callers=callers; }
    /** kind is validated after workspace visibility, so a caller outside the workspace always gets 404. */
    @GetMapping
    public Map<String,List<SavedWorkViews.Item>> list(@PathVariable UUID workspaceId,@RequestParam(required=false) String kind) {
        return Map.of("items",views.list(callers.current(),workspaceId,kind));
    }
    @PutMapping("/{viewId}")
    public SavedWorkViews.Item save(@PathVariable UUID workspaceId,@PathVariable UUID viewId,@RequestBody SavedWorkViews.Save input) {
        return views.save(callers.current(),workspaceId,viewId,input);
    }
    @DeleteMapping("/{viewId}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID workspaceId,@PathVariable UUID viewId,@RequestParam int expectedRevision) {
        views.delete(callers.current(),workspaceId,viewId,expectedRevision);
    }
}
