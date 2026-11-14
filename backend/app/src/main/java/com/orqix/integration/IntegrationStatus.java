package com.orqix.integration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only integration figures for the operations dashboard (CG-O05, docs/build/S142): call volume and errors per
 * connection (the S131 health windows) and the lag of connector package triggers (S133). No permission checks: the
 * caller (the job dashboard) checks them. Never returns bodies, headers, secrets or request data.
 */
public interface IntegrationStatus {

    record ConnectionVolume(String connectionKey, String name, boolean bound, int callsLastHour,
            Double successRateLastHour, int callsLastDay, int failedLastDay, String lastErrorCode, String circuitState,
            int notices) {
    }

    /** {@code lagSeconds}: for poll triggers, how far the last poll was behind the newest record it saw. */
    record TriggerLag(UUID id, String packageId, String triggerKey, String kind, boolean enabled, Instant lastRunAt,
            Instant lastSuccessAt, Long lagSeconds, String lastErrorCode, int deadLastDay, Instant nextRunAt) {
    }

    List<ConnectionVolume> connections(UUID workspaceId);

    List<TriggerLag> packageTriggers(UUID workspaceId);
}
