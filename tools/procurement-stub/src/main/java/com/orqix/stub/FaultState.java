package com.orqix.stub;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** The single active fault. Thread-safe; {@code times} counts requests, after which the fault clears itself. */
@Component
public class FaultState {

    private static final Logger log = LoggerFactory.getLogger(FaultState.class);

    /** A fault that applies to one request. {@code delayMs} may be null (the caller applies its default). */
    public record AppliedFault(FaultMode mode, Long delayMs) {
    }

    /** Snapshot for {@code GET /__faults}. {@code remaining} is null for an unbounded fault. */
    public record Snapshot(FaultMode mode, Integer remaining, Long delayMs, long applied) {
    }

    private FaultMode mode = FaultMode.none;
    private Integer remaining;          // null = unbounded
    private Long delayMs;
    private long applied;

    public synchronized void set(FaultMode newMode, Integer times, Long newDelayMs) {
        if (newMode == FaultMode.none) {
            clear();
            return;
        }
        this.mode = newMode;
        this.remaining = times;
        this.delayMs = newDelayMs;
        log.info("fault armed: mode={} times={} delayMs={}", newMode, times, newDelayMs);
    }

    public synchronized void clear() {
        this.mode = FaultMode.none;
        this.remaining = null;
        this.delayMs = null;
    }

    public synchronized void reset() {
        clear();
        this.applied = 0;
    }

    /** Takes one use of the active fault, if any. */
    public synchronized Optional<AppliedFault> consume() {
        if (mode == FaultMode.none) {
            return Optional.empty();
        }
        AppliedFault fault = new AppliedFault(mode, delayMs);
        applied++;
        if (remaining != null) {
            remaining = remaining - 1;
            if (remaining <= 0) {
                clear();
            }
        }
        log.info("fault applied: mode={} delayMs={}", fault.mode(), fault.delayMs());
        return Optional.of(fault);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(mode, remaining, delayMs, applied);
    }
}
