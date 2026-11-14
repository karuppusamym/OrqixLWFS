package com.orqix.integration;

import java.util.UUID;

/**
 * Published once per new drift row of a pinned MCP tool (CG-M01, docs/build/S135), inside the transaction that records
 * it, so the runtime can tell the administrator who pinned the catalog.
 *
 * @param kind          {@code changed}, {@code missing} or {@code unpinned}
 * @param notifySubject the person who took the pin, or null for a tool that was never pinned
 */
public record McpToolDriftDetected(UUID workspaceId, String connectionKey, String toolName, String kind,
        UUID notifySubject) {
}
