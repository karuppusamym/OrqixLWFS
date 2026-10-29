package com.orqix.apps.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.apps.PinMigrations;
import com.orqix.identity.CallerResolver;

/** S114 reviewed version migration of the pins of one view or page. */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/views/{viewId}")
public class PinMigrationsController {
    private final PinMigrations migrations;
    private final CallerResolver callers;

    public PinMigrationsController(PinMigrations migrations, CallerResolver callers) {
        this.migrations = migrations;
        this.callers = callers;
    }

    @GetMapping("/pins")
    public PinMigrations.Inventory pins(@PathVariable UUID workspaceId, @PathVariable UUID viewId,
            @RequestParam(required = false) Integer targetVersion) {
        return migrations.inventory(callers.current(), workspaceId, viewId, targetVersion);
    }

    @GetMapping("/pin-migrations")
    public PinMigrations.History history(@PathVariable UUID workspaceId, @PathVariable UUID viewId) {
        return migrations.history(callers.current(), workspaceId, viewId);
    }

    @PostMapping("/pin-migrations")
    public PinMigrations.Migration apply(@PathVariable UUID workspaceId, @PathVariable UUID viewId,
            @RequestBody PinMigrations.Apply request) {
        return migrations.apply(callers.current(), workspaceId, viewId, request);
    }
}
