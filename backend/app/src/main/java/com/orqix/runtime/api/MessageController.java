package com.orqix.runtime.api;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.runtime.internal.MessageService;
import com.orqix.shared.idempotency.IdempotencyService;

/** Inbound messages: an outside system or another process tells waiting requests something happened (S16). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class MessageController {

    private final CallerResolver callers;
    private final MessageService messages;

    public MessageController(CallerResolver callers, MessageService messages) {
        this.callers = callers;
        this.messages = messages;
    }

    @PostMapping("/messages")
    public ResponseEntity<MessageDtos.MessageAccepted> sendMessage(@PathVariable UUID workspaceId,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody MessageDtos.SendMessageRequest request) {
        MessageService.Sent sent = messages.send(callers.current(), workspaceId, idempotencyKey, request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.ACCEPTED);
        if (sent.replayed()) {
            builder.header(IdempotencyService.REPLAYED_HEADER, "true");
        }
        return builder.body(sent.body());
    }
}
