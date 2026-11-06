package com.orqix.integration.agenttools;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes conversation trace rows whose retention passed (CG-M05, S135); hourly by default. */
@Component
@Profile("!migrate")
public class AgentTraceRetention {

    private static final Logger LOG = LoggerFactory.getLogger(AgentTraceRetention.class);

    private final AgentTrace trace;
    private final Clock clock;

    public AgentTraceRetention(AgentTrace trace, Clock clock) {
        this.trace = trace;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${orqix.agents.trace-purge:1h}", initialDelayString = "${orqix.agents.trace-purge:1h}")
    public void purge() {
        try {
            trace.purge(clock.instant());
        } catch (RuntimeException e) {
            LOG.warn("Agent trace purge failed; it runs again later ({})", e.getClass().getSimpleName());
        }
    }
}
