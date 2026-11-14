/**
 * Orqix as an MCP server (CG-M04, docs/build/S136): {@code /mcp} over Streamable HTTP for AI assistants that act with
 * the person's own permissions. Every tool calls the same catalog and runtime services as the public operations, with
 * the person as caller, so membership, permissions, eligibility, separation of duties, idempotency and the response
 * shapes (what the UI shows) are unchanged. Writes are prepared, shown and confirmed. Nothing depends on this module.
 */
@ApplicationModule(displayName = "MCP server",
        allowedDependencies = {"shared", "identity", "audit", "catalog", "runtime"})
package com.orqix.mcp;

import org.springframework.modulith.ApplicationModule;
