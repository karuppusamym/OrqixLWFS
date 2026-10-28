package com.orqix.integration.api;

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
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.integration.kafka.KafkaRecordHandler;
import com.orqix.integration.kafka.KafkaTriggerService;

/** Kafka consumer triggers, their status and dead letters (docs/build/S99 (c)). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class KafkaTriggerController {

    private final CallerResolver callers;
    private final KafkaTriggerService triggers;

    public KafkaTriggerController(CallerResolver callers, KafkaTriggerService triggers) {
        this.callers = callers;
        this.triggers = triggers;
    }

    @GetMapping("/kafka-triggers")
    public KafkaTriggerService.TriggerList listKafkaTriggers(@PathVariable UUID workspaceId) {
        return triggers.list(callers.current(), workspaceId);
    }

    @PostMapping("/kafka-triggers")
    public ResponseEntity<KafkaTriggerService.TriggerView> createKafkaTrigger(@PathVariable UUID workspaceId,
            @Valid @RequestBody KafkaTriggerService.CreateTrigger request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(triggers.create(callers.current(), workspaceId, request));
    }

    @PutMapping("/kafka-triggers/{kafkaTriggerId}")
    public KafkaTriggerService.TriggerView updateKafkaTrigger(@PathVariable UUID workspaceId,
            @PathVariable UUID kafkaTriggerId, @Valid @RequestBody KafkaTriggerService.UpdateTrigger request) {
        return triggers.update(callers.current(), workspaceId, kafkaTriggerId, request);
    }

    @DeleteMapping("/kafka-triggers/{kafkaTriggerId}")
    public ResponseEntity<Void> deleteKafkaTrigger(@PathVariable UUID workspaceId, @PathVariable UUID kafkaTriggerId) {
        triggers.delete(callers.current(), workspaceId, kafkaTriggerId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/kafka-dead-letters")
    public KafkaTriggerService.DeadLetterList listKafkaDeadLetters(@PathVariable UUID workspaceId) {
        return triggers.deadLetters(callers.current(), workspaceId);
    }

    @PostMapping("/kafka-triggers/{kafkaTriggerId}/test-message")
    public KafkaRecordHandler.Result sendKafkaTestMessage(@PathVariable UUID workspaceId,
            @PathVariable UUID kafkaTriggerId, @Valid @RequestBody KafkaTriggerService.TestMessage request) {
        return triggers.testMessage(callers.current(), workspaceId, kafkaTriggerId, request);
    }
}
