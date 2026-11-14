package com.orqix.integration;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.integration.health.ConnectionCircuit;
import com.orqix.integration.health.ConnectionHealth;

/**
 * Read-only state of one binding for other modules (S146 part C, "Explain this request"): binding presence, circuit
 * (S131) and the last day's failures. No permission check: callers decide who sees what. Holds codes and statuses only,
 * never base URLs, secret references or remote error details.
 */
@Component
public class ConnectionStatus {

    /**
     * @param circuitState closed, open or halfOpen
     * @param authType     authentication kind of the binding (none, bearer, oauth2...), null when unbound
     */
    public record Snapshot(String key, String name, boolean bound, boolean enabled, String authType,
            String circuitState, Instant circuitOpenUntil, String circuitReason, int consecutiveFailures,
            int tokenRefreshFailuresLastHour, Integer lastErrorHttpStatus, String lastErrorCode, Instant lastErrorAt) {
    }

    private final ConnectionService connections;
    private final ConnectionHealth health;
    private final ConnectionCircuit circuits;

    public ConnectionStatus(ConnectionService connections, ConnectionHealth health, ConnectionCircuit circuits) {
        this.connections = connections;
        this.health = health;
        this.circuits = circuits;
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot(UUID workspaceId, String key) {
        var versions = connections.definitions(workspaceId, key);
        String name = versions.isEmpty() ? null : versions.get(versions.size() - 1).definition().name();
        var binding = connections.binding(workspaceId, key).orElse(null);
        ConnectionHealth.Summary summary = health.summary(workspaceId, key);
        ConnectionCircuit.Status circuit = circuits.status(workspaceId, key).orElse(null);
        ConnectionHealth.LastError last = summary.lastError();
        boolean closed = circuit == null || circuit.state() == ConnectionCircuit.State.CLOSED;
        return new Snapshot(key, name, binding != null, binding != null && binding.enabled(),
                binding == null ? null : binding.authType(), circuit == null ? "closed" : circuit.state().value(),
                closed ? null : circuit.openUntil(), closed ? null : circuit.reason(),
                circuit == null ? 0 : circuit.consecutiveFailures(), summary.lastHour().tokenRefreshFailures(),
                last == null ? null : last.httpStatus(), last == null ? null : last.errorCode(),
                last == null ? null : last.at());
    }
}
