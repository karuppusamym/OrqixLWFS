package com.orqix.mcp.api;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.mcp.McpResource;
import com.orqix.mcp.McpServerSwitch;
import com.orqix.mcp.McpSettings;

/** MCP server administration (contracts/openapi.yaml, tag McpServer; docs/build/S136 section 4). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/mcp-server")
public class McpServerController {

    public record SetMcpServer(@NotNull Boolean enabled) {
    }

    private final CallerResolver callers;
    private final McpServerSwitch switches;
    private final McpSettings settings;

    public McpServerController(CallerResolver callers, McpServerSwitch switches, McpSettings settings) {
        this.callers = callers;
        this.switches = switches;
        this.settings = settings;
    }

    @GetMapping
    public McpServerSwitch.State getMcpServer(@PathVariable UUID workspaceId, HttpServletRequest request) {
        return switches.get(callers.current(), workspaceId, McpResource.uri(settings, request),
                McpResource.metadataUri(settings, request));
    }

    @PutMapping
    public McpServerSwitch.State setMcpServer(@PathVariable UUID workspaceId, @Valid @RequestBody SetMcpServer body,
            HttpServletRequest request) {
        return switches.set(callers.current(), workspaceId, body.enabled(), McpResource.uri(settings, request),
                McpResource.metadataUri(settings, request));
    }

    @GetMapping("/calls")
    public McpServerSwitch.CallList listMcpCalls(@PathVariable UUID workspaceId,
            @RequestParam(required = false) Integer limit) {
        return switches.calls(callers.current(), workspaceId, limit);
    }
}
