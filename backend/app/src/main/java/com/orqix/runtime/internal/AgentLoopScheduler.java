package com.orqix.runtime.internal;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background work of durable agent loops (CG-M03, S135) on worker and all-in-one pods: starts the child requests that
 * waiting loops asked for (outside any engine job), wakes loops whose wait ended while their turn was running, stops
 * loops of ended requests and clears expired turn state.
 */
@Component
@Profile("!migrate")
public class AgentLoopScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(AgentLoopScheduler.class);

    private final AgentLoops loops;
    private final Clock clock;

    public AgentLoopScheduler(AgentLoops loops, Clock clock) {
        this.loops = loops;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${orqix.agents.tick:2s}", initialDelayString = "${orqix.agents.tick:2s}")
    public void tick() {
        try {
            loops.startPendingChildren();
            loops.wakeResolved(clock.instant());
        } catch (RuntimeException e) {
            LOG.warn("Agent loop tick failed; it runs again shortly ({})", e.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${orqix.agents.sweep:1m}", initialDelayString = "${orqix.agents.sweep:1m}")
    public void sweep() {
        try {
            loops.sweep(clock.instant());
        } catch (RuntimeException e) {
            LOG.warn("Agent loop sweep failed; it runs again later ({})", e.getClass().getSimpleName());
        }
    }
}
