package com.orqix.integration.health;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.orqix.integration.CallDeferredException;
import com.orqix.integration.HttpExecutor;

/**
 * The two hooks of the effect dispatcher for CG-W04 and CG-C09 (docs/build/S131): {@link #before} runs ahead of the
 * effect journal and defers the call when the binding's circuit is open or its request rate is used up (nothing is
 * registered, so no attempt is used); {@link #after} records the outcome for health and the circuit.
 */
@Component
public class CallGuard {

    /** Time a half-open trial may take beyond the call timeout before another request may try. */
    static final Duration TRIAL_MARGIN = Duration.ofSeconds(15);

    /** Passed from {@link #before} to {@link #after}. */
    public record Ticket(boolean trial) {
    }

    private final ConnectionCircuit circuit;
    private final BindingRateLimiter limiter;
    private final ConnectionHealth health;

    public CallGuard(ConnectionCircuit circuit, BindingRateLimiter limiter, ConnectionHealth health) {
        this.circuit = circuit;
        this.limiter = limiter;
        this.health = health;
    }

    /** @throws CallDeferredException when the call must wait; nothing was sent or registered */
    public Ticket before(UUID workspaceId, String connectionKey, String operation, int timeoutSeconds) {
        ConnectionCircuit.Admission admission = circuit.admit(workspaceId, connectionKey,
                Duration.ofSeconds(timeoutSeconds).plus(TRIAL_MARGIN));
        if (!admission.allowed()) {
            health.record(workspaceId, connectionKey, operation, ConnectionHealth.Outcome.DEFERRED, null,
                    admission.reason(), null);
            throw new CallDeferredException(admission.reason(), connectionKey, admission.until());
        }
        Instant free = limiter.acquire(workspaceId, connectionKey);
        if (free != null) {
            health.record(workspaceId, connectionKey, operation, ConnectionHealth.Outcome.DEFERRED, null,
                    "RATE_LIMITED", null);
            throw new CallDeferredException("RATE_LIMITED", connectionKey, free);
        }
        return new Ticket(admission.trial());
    }

    public void after(UUID workspaceId, String connectionKey, String operation, Ticket ticket,
            HttpExecutor.CallResult call) {
        ConnectionHealth.Outcome outcome = switch (call.outcome()) {
            case SUCCEEDED -> ConnectionHealth.Outcome.SUCCEEDED;
            case RETRYABLE -> ConnectionHealth.Outcome.RETRYABLE;
            case PERMANENT -> ConnectionHealth.Outcome.PERMANENT;
            case UNKNOWN -> ConnectionHealth.Outcome.UNKNOWN;
        };
        health.record(workspaceId, connectionKey, operation, outcome, call.httpStatus(), call.errorCode(),
                call.durationMs());
        circuit.record(workspaceId, connectionKey, curable(call.observation()), call.errorCode(), ticket.trial());
    }

    /** A host that did not resolve: not sent, so it counts for the circuit like a refused connection. */
    public void notResolved(UUID workspaceId, String connectionKey, String operation, Ticket ticket) {
        health.record(workspaceId, connectionKey, operation, ConnectionHealth.Outcome.RETRYABLE, null, "DNS_FAILURE",
                null);
        circuit.record(workspaceId, connectionKey, true, "DNS_FAILURE", ticket.trial());
    }

    /** The OAuth token or delegated authorization could not be obtained (no call was made). */
    public void tokenFailure(UUID workspaceId, String connectionKey, String operation) {
        health.record(workspaceId, connectionKey, operation, ConnectionHealth.Outcome.TOKEN_FAILURE, null,
                "TOKEN_REFRESH_FAILED", null);
    }

    /** Failures a later attempt may cure: not sent, maybe applied (5xx, timeout, reset) and 429. */
    static boolean curable(HttpExecutor.Observation observation) {
        return observation == HttpExecutor.Observation.NOT_SENT
                || observation == HttpExecutor.Observation.MAYBE_APPLIED
                || observation == HttpExecutor.Observation.DECLINED_429;
    }
}
