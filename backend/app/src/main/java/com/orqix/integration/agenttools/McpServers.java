package com.orqix.integration.agenttools;

import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.orqix.integration.ConnectionDefinition;
import com.orqix.integration.ConnectionService;
import com.orqix.integration.EgressPolicy;
import com.orqix.integration.McpClient;
import com.orqix.integration.internal.SecretStore;

/**
 * Resolves the MCP endpoint of a connection for an agent (CG-M01, S135): a registered tool server ({@code mcpServer}
 * connection, its latest definition version and its workspace binding) or the agent connection's own binding. Applies
 * the egress policy and reads the credential at every resolution, so a binding change or a revoked secret is effective
 * at once. The credential goes only to that server (per-binding credentials, no token passthrough).
 */
@Component
public class McpServers {

    /** A server an agent may talk to now. {@code definition} is null for the agent's own binding. */
    public record Server(String key, ConnectionDefinition definition, McpClient.Endpoint endpoint) {

        public ConnectionDefinition.McpServerSpec spec() {
            return definition == null ? null : definition.mcpServer();
        }
    }

    /** The server cannot be used; {@code code} is a stable error code, the message names no URL or secret. */
    public static final class Unavailable extends RuntimeException {

        private final String code;

        public Unavailable(String code, String message) {
            super(message, null, false, false);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final ConnectionService connections;
    private final EgressPolicy egress;
    private final SecretStore secrets;

    public McpServers(ConnectionService connections, EgressPolicy egress, SecretStore secrets) {
        this.connections = connections;
        this.egress = egress;
        this.secrets = secrets;
    }

    /** The latest registered version of an {@code mcpServer} connection, or empty. */
    public Optional<ConnectionDefinition> definition(UUID workspaceId, String key) {
        return connections.definitions(workspaceId, key).stream()
                .max(Comparator.comparingInt(ConnectionService.DefinitionRecord::version))
                .map(ConnectionService.DefinitionRecord::definition)
                .filter(ConnectionDefinition::isMcpServer);
    }

    /** A registered tool server with a usable binding. */
    public Server server(UUID workspaceId, String key) {
        ConnectionDefinition definition = definition(workspaceId, key)
                .orElseThrow(() -> new Unavailable("TOOL_SERVER_UNKNOWN", "The tool server '" + key
                        + "' is not registered in this workspace."));
        return new Server(key, definition, endpoint(workspaceId, key, definition.name()));
    }

    /** The binding of any connection key as an MCP endpoint (the agent's own binding, or a server). */
    public McpClient.Endpoint endpoint(UUID workspaceId, String key, String name) {
        ConnectionService.Binding binding = connections.binding(workspaceId, key)
                .filter(ConnectionService.Binding::enabled)
                .orElseThrow(() -> new Unavailable("CONNECTION_UNAVAILABLE", "The binding of " + name
                        + " is missing or disabled."));
        try {
            egress.check(binding.baseUrl());
        } catch (EgressPolicy.EgressDeniedException e) {
            throw new Unavailable(e.denial() == EgressPolicy.Denial.DNS_FAILURE ? "DNS_FAILURE" : "EGRESS_DENIED",
                    "The endpoint of " + name + " is not allowed by the egress policy (" + e.denial() + ").");
        }
        String bearer = null;
        if (!"none".equals(binding.authType())) {
            if ("apiKeyHeader".equals(binding.authType())) {
                throw new Unavailable("CONNECTION_UNAVAILABLE", name + " uses an authentication type that MCP servers "
                        + "do not support.");
            }
            bearer = secrets.credential(binding.authType(), binding.secretRef(), workspaceId)
                    .orElseThrow(() -> new Unavailable("CONNECTION_UNAVAILABLE", "The credential of " + name
                            + " is not available."));
        }
        return new McpClient.Endpoint(binding.baseUrl(), bearer);
    }
}
