package com.orqix.integration.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.integration.health.ConnectionHealthService;
import com.orqix.shared.problem.ApiException;

/** Connection health and binding request limits (contracts/openapi.yaml, tag ConnectionHealth, docs/build/S131). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class ConnectionHealthController {

    private static final String KEY = "^[a-z][a-z0-9-]{0,62}[a-z0-9]$";

    private final CallerResolver callers;
    private final ConnectionHealthService service;

    public ConnectionHealthController(CallerResolver callers, ConnectionHealthService service) {
        this.callers = callers;
        this.service = service;
    }

    @GetMapping("/connection-health")
    public ConnectionHealthService.HealthList listConnectionHealth(@PathVariable UUID workspaceId) {
        return service.list(callers.current(), workspaceId);
    }

    @GetMapping("/connections/{connectionKey}/health")
    public ConnectionHealthService.HealthView getConnectionHealth(@PathVariable UUID workspaceId,
            @PathVariable String connectionKey) {
        return service.get(callers.current(), workspaceId, key(connectionKey));
    }

    @PutMapping("/connections/{connectionKey}/rate-limit")
    public ConnectionHealthService.HealthView putConnectionRateLimit(@PathVariable UUID workspaceId,
            @PathVariable String connectionKey,
            @RequestBody ConnectionHealthService.PutRateLimitRequest request) {
        return service.putRateLimit(callers.current(), workspaceId, key(connectionKey), request);
    }

    private static String key(String value) {
        if (!value.matches(KEY)) {
            throw ApiException.notFound();
        }
        return value;
    }
}
