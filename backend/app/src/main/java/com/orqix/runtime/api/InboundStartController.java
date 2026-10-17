package com.orqix.runtime.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.runtime.internal.InboundEventStarter;

/** Starts a run from a received inbound event, as the signed-in person (S47). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class InboundStartController {

    private final CallerResolver callers;
    private final InboundEventStarter starter;

    public InboundStartController(CallerResolver callers, InboundEventStarter starter) {
        this.callers = callers;
        this.starter = starter;
    }

    @PostMapping("/inbound-events/{eventId}/start")
    public InboundEventStarter.Started startInboundEvent(@PathVariable UUID workspaceId, @PathVariable UUID eventId) {
        return starter.start(callers.current(), workspaceId, eventId);
    }
}
