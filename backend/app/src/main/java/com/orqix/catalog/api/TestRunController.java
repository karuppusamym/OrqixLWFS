package com.orqix.catalog.api;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.catalog.internal.TestRunService;
import com.orqix.identity.CallerResolver;

import tools.jackson.databind.JsonNode;

/** CG-Q01 (S148): run a test suite (a test file or the shared suite) with JUnit XML in the result. */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/workflows/{workflowId}/test-runs")
public class TestRunController {

    private final TestRunService service;
    private final CallerResolver callers;

    public TestRunController(TestRunService service, CallerResolver callers) {
        this.service = service;
        this.callers = callers;
    }

    @PostMapping
    public JsonNode run(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @Valid @RequestBody TestRunService.RunRequest request) {
        return service.run(callers.current(), workspaceId, workflowId, request);
    }
}
