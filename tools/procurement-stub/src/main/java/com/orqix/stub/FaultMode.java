package com.orqix.stub;

import java.util.Optional;

/** Fault modes accepted by {@code POST /__faults}. They apply to {@code POST /orders} only. */
public enum FaultMode {
    /** Clears any active fault. */
    none,
    /** Sleeps {@code delayMs}, then processes the request normally. */
    delay,
    status500,
    status400,
    status429,
    /** Creates the order, then sleeps past a typical 20 s client timeout before responding. */
    commitThenTimeout,
    /** Closes the socket without sending a response, before the body is read. */
    dropConnection;

    public static Optional<FaultMode> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (FaultMode mode : values()) {
            if (mode.name().equals(value)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
