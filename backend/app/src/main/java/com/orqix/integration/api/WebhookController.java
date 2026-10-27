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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.integration.webhooks.WebhookService;

/** Outbound webhook subscriptions and their dead letters (docs/build/S99 (b)). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class WebhookController {

    private final CallerResolver callers;
    private final WebhookService webhooks;

    public WebhookController(CallerResolver callers, WebhookService webhooks) {
        this.callers = callers;
        this.webhooks = webhooks;
    }

    @GetMapping("/webhook-subscriptions")
    public WebhookService.SubscriptionList listWebhookSubscriptions(@PathVariable UUID workspaceId) {
        return webhooks.list(callers.current(), workspaceId);
    }

    @PostMapping("/webhook-subscriptions")
    public ResponseEntity<WebhookService.SubscriptionView> createWebhookSubscription(@PathVariable UUID workspaceId,
            @Valid @RequestBody WebhookService.CreateSubscription request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(webhooks.create(callers.current(), workspaceId, request));
    }

    @PutMapping("/webhook-subscriptions/{subscriptionId}")
    public WebhookService.SubscriptionView updateWebhookSubscription(@PathVariable UUID workspaceId,
            @PathVariable UUID subscriptionId, @Valid @RequestBody WebhookService.UpdateSubscription request) {
        return webhooks.update(callers.current(), workspaceId, subscriptionId, request);
    }

    @GetMapping("/webhook-deliveries")
    public WebhookService.DeliveryList listWebhookDeliveries(@PathVariable UUID workspaceId,
            @RequestParam(required = false) String status) {
        return webhooks.deliveries(callers.current(), workspaceId, status);
    }

    @PostMapping("/webhook-deliveries/{deliveryId}/replay")
    public WebhookService.DeliveryView replayWebhookDelivery(@PathVariable UUID workspaceId,
            @PathVariable UUID deliveryId) {
        return webhooks.replay(callers.current(), workspaceId, deliveryId);
    }
}
