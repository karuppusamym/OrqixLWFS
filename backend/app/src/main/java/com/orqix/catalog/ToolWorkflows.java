package com.orqix.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.catalog.api.CatalogDtos;
import com.orqix.catalog.internal.WorkflowService;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;

import tools.jackson.databind.JsonNode;

/**
 * Workflows a person may start now, for the MCP server (CG-M04, docs/build/S136): the catalog list with
 * {@code startable=true} (active release and a workflow.start grant), with the active release's input schema and the
 * {@code exposeAsTool} flag of its definition.
 */
@Service
public class ToolWorkflows {

    /**
     * @param exposeAsTool the active release's definition sets {@code exposeAsTool: true}
     */
    public record Startable(UUID id, String key, String name, String description, UUID releaseId, int releaseNumber,
            boolean exposeAsTool, JsonNode inputSchema, JsonNode inputUi) {
    }

    private final WorkflowService workflows;
    private final ReleaseQueries releases;
    private final PolicyService policy;

    public ToolWorkflows(WorkflowService workflows, ReleaseQueries releases, PolicyService policy) {
        this.workflows = workflows;
        this.releases = releases;
        this.policy = policy;
    }

    /** At most {@code max} workflows the caller may start now, in catalog order; empty when they may start none. */
    @Transactional(readOnly = true)
    public List<Startable> startable(Caller caller, UUID workspaceId, int max) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        if (!access.has(Permission.WORKFLOW_READ) && !access.canStartAnything()) {
            return List.of();
        }
        List<Startable> result = new ArrayList<>();
        String cursor = null;
        do {
            CatalogDtos.WorkflowPage page = workflows.listWorkflows(caller, workspaceId, null, true, cursor, 100);
            for (CatalogDtos.WorkflowView w : page.items()) {
                if (result.size() >= max) {
                    return result;
                }
                if (!w.canStart() || w.activeRelease() == null) {
                    continue;
                }
                releases.find(workspaceId, w.activeRelease().id()).ifPresent(release -> {
                    JsonNode definition = release.definition();
                    result.add(new Startable(w.id(), w.key(), w.name(), w.description(), release.id(),
                            release.releaseNumber(), definition.path("exposeAsTool").asBoolean(false),
                            definition.path("inputSchema"), definition.path("inputUi")));
                });
            }
            cursor = page.nextCursor();
        } while (cursor != null && result.size() < max);
        return result;
    }
}
