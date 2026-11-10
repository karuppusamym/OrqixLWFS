package com.orqix.runtime.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.runtime.internal.RunReveal;

/** Reveal of masked request answers (CG-I05, docs/build/S151; contracts/openapi.yaml tag SecurityAndAudit). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class FieldProtectionController {

    public record RevealRequest(@Size(max = 100) List<@Size(min = 1, max = 64) String> fields) {
    }

    private final CallerResolver callers;
    private final RunReveal reveal;

    public FieldProtectionController(CallerResolver callers, RunReveal reveal) {
        this.callers = callers;
        this.reveal = reveal;
    }

    @PostMapping("/runs/{runId}/reveal")
    public RunReveal.Revealed revealRunData(@PathVariable UUID workspaceId, @PathVariable UUID runId,
            @Valid @RequestBody(required = false) RevealRequest request) {
        return reveal.reveal(callers.current(), workspaceId, runId, request == null ? null : request.fields());
    }
}
