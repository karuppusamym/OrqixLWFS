package com.orqix.catalog.api;

import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.orqix.catalog.internal.TestSuiteService;
import com.orqix.identity.CallerResolver;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/workflows/{workflowId}/test-suite")
public class TestSuiteController {
    private final TestSuiteService service;
    private final CallerResolver callers;
    public TestSuiteController(TestSuiteService service, CallerResolver callers) {
        this.service = service; this.callers = callers;
    }
    @GetMapping
    public TestSuiteService.Suite get(@PathVariable UUID workspaceId, @PathVariable UUID workflowId) {
        return service.get(callers.current(), workspaceId, workflowId);
    }
    @PutMapping
    public TestSuiteService.Suite save(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @Valid @RequestBody TestSuiteService.Save request) {
        return service.save(callers.current(), workspaceId, workflowId, request);
    }
    @PostMapping("/run")
    public TestSuiteService.Report run(@PathVariable UUID workspaceId, @PathVariable UUID workflowId,
            @Valid @RequestBody TestSuiteService.Run request) {
        return service.run(callers.current(), workspaceId, workflowId, request);
    }
}
