package com.orqix.integration.api;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
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
import com.orqix.integration.internal.ConnectionAdminService;
import com.orqix.shared.problem.ApiException;

/** Connections, bindings and connection tests (contracts/openapi.yaml, tag Connections). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/connections")
public class ConnectionController {

    private static final String KEY = "^[a-z][a-z0-9-]{0,62}[a-z0-9]$";

    private final CallerResolver callers;
    private final ConnectionAdminService service;

    public ConnectionController(CallerResolver callers, ConnectionAdminService service) {
        this.callers = callers;
        this.service = service;
    }

    @GetMapping
    public ConnectionDtos.ConnectionList listConnections(@PathVariable UUID workspaceId) {
        return service.list(callers.current(), workspaceId);
    }

    @GetMapping("/{connectionKey}")
    public ConnectionDtos.ConnectionDetail getConnection(@PathVariable UUID workspaceId,
            @PathVariable String connectionKey) {
        return service.get(callers.current(), workspaceId, key(connectionKey));
    }

    @PostMapping("/{connectionKey}/versions")
    public ResponseEntity<ConnectionDtos.DefinitionRecord> registerConnectionVersion(@PathVariable UUID workspaceId,
            @PathVariable String connectionKey, @Valid @RequestBody ConnectionDtos.RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.register(callers.current(), workspaceId, key(connectionKey), request.definition()));
    }

    @PutMapping("/{connectionKey}/binding")
    public ResponseEntity<ConnectionDtos.Binding> putConnectionBinding(@PathVariable UUID workspaceId,
            @PathVariable String connectionKey,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ConnectionDtos.PutBindingRequest request) {
        ConnectionDtos.Binding binding = service.putBinding(callers.current(), workspaceId, key(connectionKey), ifMatch,
                request);
        return ResponseEntity.ok().eTag("\"r" + binding.revision() + "\"").body(binding);
    }

    @PostMapping("/{connectionKey}/test")
    public ConnectionDtos.TestResult testConnection(@PathVariable UUID workspaceId, @PathVariable String connectionKey,
            @Valid @RequestBody ConnectionDtos.TestRequest request) {
        return service.test(callers.current(), workspaceId, key(connectionKey), request);
    }

    private static String key(String value) {
        if (!value.matches(KEY)) {
            throw ApiException.notFound();
        }
        return value;
    }
}
