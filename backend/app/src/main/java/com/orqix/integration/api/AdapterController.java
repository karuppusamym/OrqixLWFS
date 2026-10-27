package com.orqix.integration.api;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.integration.adapters.AdapterService;
import com.orqix.integration.adapters.InboundService;

/** Outbound adapters, inbound triggers and the signed receive endpoint (contracts/openapi.yaml, tag Adapters). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class AdapterController {

    private final CallerResolver callers;
    private final AdapterService adapters;
    private final InboundService inbound;

    public AdapterController(CallerResolver callers, AdapterService adapters, InboundService inbound) {
        this.callers = callers;
        this.adapters = adapters;
        this.inbound = inbound;
    }

    @GetMapping("/adapters")
    public AdapterService.ViewList listAdapters(@PathVariable UUID workspaceId) {
        return adapters.list(callers.current(), workspaceId);
    }

    @PostMapping("/adapters")
    public ResponseEntity<AdapterService.View> createAdapter(@PathVariable UUID workspaceId,
            @Valid @RequestBody AdapterService.CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adapters.create(callers.current(), workspaceId, request));
    }

    @PostMapping("/adapters/{key}/test-send")
    public AdapterService.SendResult testAdapterSend(@PathVariable UUID workspaceId, @PathVariable String key,
            @Valid @RequestBody AdapterService.SendRequest request) {
        return adapters.testSend(callers.current(), workspaceId, key, request);
    }

    @PutMapping("/adapters/{key}")
    public AdapterService.View updateAdapter(@PathVariable UUID workspaceId, @PathVariable String key,
            @Valid @RequestBody AdapterService.UpdateRequest request) {
        return adapters.update(callers.current(), workspaceId, key, request);
    }

    @PutMapping("/inbound-triggers/{triggerId}")
    public InboundService.TriggerView updateInboundTrigger(@PathVariable UUID workspaceId, @PathVariable UUID triggerId,
            @Valid @RequestBody InboundService.UpdateTrigger request) {
        return inbound.update(callers.current(), workspaceId, triggerId, request);
    }

    @PostMapping("/inbound-events/purge")
    public InboundService.PurgeResult purgeInboundEvents(@PathVariable UUID workspaceId,
            @Valid @RequestBody InboundService.PurgeRequest request) {
        return inbound.purge(callers.current(), workspaceId, request);
    }

    @GetMapping("/inbound-triggers")
    public InboundService.TriggerList listInboundTriggers(@PathVariable UUID workspaceId) {
        return inbound.list(callers.current(), workspaceId);
    }

    @PostMapping("/inbound-triggers")
    public ResponseEntity<InboundService.TriggerView> createInboundTrigger(@PathVariable UUID workspaceId,
            @Valid @RequestBody InboundService.CreateTrigger request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inbound.create(callers.current(), workspaceId, request));
    }

    @GetMapping("/inbound-events")
    public InboundService.EventList listInboundEvents(@PathVariable UUID workspaceId) {
        return inbound.events(callers.current(), workspaceId);
    }

    /** Signature-authenticated; no session or token is read here. */
    @PostMapping("/inbound/{triggerId}")
    public ResponseEntity<InboundService.Accepted> receiveInboundEvent(@PathVariable UUID workspaceId,
            @PathVariable String triggerId,
            @RequestHeader(name = "X-Orqix-Event-Id", required = false) String eventId,
            @RequestHeader(name = "X-Orqix-Timestamp", required = false) String timestamp,
            @RequestHeader(name = "X-Orqix-Signature", required = false) String signature,
            @RequestHeader(name = "X-Orqix-Callback-Token", required = false) String callbackToken,
            @RequestBody(required = false) byte[] body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(inbound.receive(workspaceId, triggerId, eventId, timestamp, signature, callbackToken, body));
    }
}
