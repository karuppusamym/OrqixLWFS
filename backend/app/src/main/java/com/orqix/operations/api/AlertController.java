package com.orqix.operations.api;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.operations.alerts.AlertService;

/** Alert rules, destinations and incidents (CG-O06, docs/build/S144). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class AlertController {

    private final CallerResolver callers;
    private final AlertService alerts;

    public AlertController(CallerResolver callers, AlertService alerts) {
        this.callers = callers;
        this.alerts = alerts;
    }

    @GetMapping("/alert-destinations")
    public AlertService.DestinationList listAlertDestinations(@PathVariable UUID workspaceId) {
        return alerts.listDestinations(callers.current(), workspaceId);
    }

    @PostMapping("/alert-destinations")
    public ResponseEntity<AlertService.DestinationView> createAlertDestination(@PathVariable UUID workspaceId,
            @Valid @RequestBody AlertService.CreateDestination request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(alerts.createDestination(callers.current(), workspaceId, request));
    }

    @PutMapping("/alert-destinations/{destinationId}")
    public AlertService.DestinationView updateAlertDestination(@PathVariable UUID workspaceId,
            @PathVariable UUID destinationId, @Valid @RequestBody AlertService.UpdateDestination request) {
        return alerts.updateDestination(callers.current(), workspaceId, destinationId, request);
    }

    @PostMapping("/alert-destinations/{destinationId}/test")
    public AlertService.TestResult testAlertDestination(@PathVariable UUID workspaceId,
            @PathVariable UUID destinationId) {
        return alerts.testDestination(callers.current(), workspaceId, destinationId);
    }

    @GetMapping("/alert-rules")
    public AlertService.RuleList listAlertRules(@PathVariable UUID workspaceId) {
        return alerts.listRules(callers.current(), workspaceId);
    }

    @PostMapping("/alert-rules")
    public ResponseEntity<AlertService.RuleView> createAlertRule(@PathVariable UUID workspaceId,
            @Valid @RequestBody AlertService.SaveRule request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(alerts.createRule(callers.current(), workspaceId, request));
    }

    @PutMapping("/alert-rules/{ruleId}")
    public AlertService.RuleView updateAlertRule(@PathVariable UUID workspaceId, @PathVariable UUID ruleId,
            @Valid @RequestBody AlertService.SaveRule request) {
        return alerts.updateRule(callers.current(), workspaceId, ruleId, request);
    }

    @DeleteMapping("/alert-rules/{ruleId}")
    public ResponseEntity<Void> deleteAlertRule(@PathVariable UUID workspaceId, @PathVariable UUID ruleId) {
        alerts.deleteRule(callers.current(), workspaceId, ruleId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/alert-rules/{ruleId}/silence")
    public AlertService.RuleView silenceAlertRule(@PathVariable UUID workspaceId, @PathVariable UUID ruleId,
            @Valid @RequestBody AlertService.SilenceRequest request) {
        return alerts.silenceRule(callers.current(), workspaceId, ruleId, request);
    }

    @GetMapping("/alert-incidents")
    public AlertService.IncidentList listAlertIncidents(@PathVariable UUID workspaceId,
            @RequestParam(required = false) UUID ruleId, @RequestParam(required = false) String status) {
        return alerts.listIncidents(callers.current(), workspaceId, ruleId, status);
    }
}
