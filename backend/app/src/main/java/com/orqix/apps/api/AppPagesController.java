package com.orqix.apps.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.apps.AppPages;
import com.orqix.apps.ChainRunner;
import com.orqix.identity.CallerResolver;
import com.orqix.shared.idempotency.IdempotencyService;

/** S111 application page routes, runtime payload, drafts, extension data and chain runs. */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/app-pages")
public class AppPagesController {
    private final AppPages pages;
    private final ChainRunner chains;
    private final CallerResolver callers;

    public AppPagesController(AppPages pages, ChainRunner chains, CallerResolver callers) {
        this.pages = pages;
        this.chains = chains;
        this.callers = callers;
    }

    @GetMapping
    public AppPages.RouteList list(@PathVariable UUID workspaceId) {
        return pages.list(callers.current(), workspaceId);
    }

    @GetMapping("/{slug}")
    public AppPages.Runtime get(@PathVariable UUID workspaceId, @PathVariable String slug) {
        return pages.runtime(callers.current(), workspaceId, slug);
    }

    @PutMapping("/{slug}")
    public AppPages.Route assign(@PathVariable UUID workspaceId, @PathVariable String slug,
            @RequestBody AppPages.Assign request) {
        return pages.assign(callers.current(), workspaceId, slug, request);
    }

    @GetMapping("/{slug}/history")
    public AppPages.History history(@PathVariable UUID workspaceId, @PathVariable String slug) {
        return pages.history(callers.current(), workspaceId, slug);
    }

    @GetMapping("/{slug}/drafts")
    public AppPages.Drafts drafts(@PathVariable UUID workspaceId, @PathVariable String slug) {
        return pages.drafts(callers.current(), workspaceId, slug);
    }

    @GetMapping("/{slug}/components/{componentId}/data")
    public AppPages.ComponentData data(@PathVariable UUID workspaceId, @PathVariable String slug,
            @PathVariable String componentId, @RequestParam(required = false) String context) {
        return pages.componentData(callers.current(), workspaceId, slug, componentId, context);
    }

    @PostMapping("/{slug}/chains/{chainId}/run")
    public ChainRunner.Result run(@PathVariable UUID workspaceId, @PathVariable String slug, @PathVariable String chainId,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @RequestBody ChainRunner.Request request) {
        return chains.run(callers.current(), workspaceId, slug, chainId, idempotencyKey, request);
    }
}
