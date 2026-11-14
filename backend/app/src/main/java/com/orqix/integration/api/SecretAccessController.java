package com.orqix.integration.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.integration.secrets.SecretAccessQueries;

/** Secret-access audit (docs/build/S132): how often each secretRef was resolved, by provider. Never values. */
@RestController
public class SecretAccessController {

    private final CallerResolver callers;
    private final SecretAccessQueries queries;

    public SecretAccessController(CallerResolver callers, SecretAccessQueries queries) {
        this.callers = callers;
        this.queries = queries;
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/secret-access")
    public SecretAccessQueries.Report getSecretAccess(@PathVariable UUID workspaceId,
            @RequestParam(name = "days", required = false, defaultValue = "7") int days) {
        return queries.report(callers.current(), workspaceId, days);
    }
}
