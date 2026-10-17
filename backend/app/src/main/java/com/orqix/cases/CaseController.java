package com.orqix.cases;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.orqix.identity.CallerResolver;
import com.orqix.shared.idempotency.IdempotencyService;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/cases")
public class CaseController {
    private final CaseService service;
    private final CallerResolver callers;
    public CaseController(CaseService service, CallerResolver callers) { this.service=service; this.callers=callers; }

    @GetMapping("/definitions")
    public List<CaseService.Definition> definitions(@PathVariable UUID workspaceId) {
        return service.definitions(callers.current(),workspaceId);
    }
    @GetMapping("/definitions/{definitionId}")
    public CaseService.DefinitionDetail definition(@PathVariable UUID workspaceId,@PathVariable UUID definitionId) {
        return service.definition(callers.current(),workspaceId,definitionId);
    }
    @PostMapping("/definitions")
    public ResponseEntity<CaseService.Definition> createDefinition(@PathVariable UUID workspaceId,
            @RequestBody CaseService.CreateDefinition request) {
        return ResponseEntity.status(201).body(service.createDefinition(callers.current(),workspaceId,request));
    }
    @GetMapping("/instances")
    public List<CaseService.Instance> instances(@PathVariable UUID workspaceId) {
        return service.instances(callers.current(),workspaceId);
    }
    @GetMapping("/instances/{instanceId}")
    public CaseService.Instance instance(@PathVariable UUID workspaceId,@PathVariable UUID instanceId) {
        return service.instance(callers.current(),workspaceId,instanceId);
    }
    @PostMapping("/definitions/{definitionId}/instances")
    public ResponseEntity<CaseService.Instance> start(@PathVariable UUID workspaceId,@PathVariable UUID definitionId,
            @RequestHeader(name=IdempotencyService.HEADER,required=false) String key) {
        var result=service.start(callers.current(),workspaceId,definitionId,key);
        var response=ResponseEntity.status(result.replayed()?200:201);
        if(result.replayed()) response.header(IdempotencyService.REPLAYED_HEADER,"true");
        return response.body(result.value());
    }
    @PostMapping("/instances/{instanceId}/tasks/{taskId}/complete")
    public ResponseEntity<CaseService.Instance> complete(@PathVariable UUID workspaceId,@PathVariable UUID instanceId,
            @PathVariable String taskId,@RequestHeader(name=IdempotencyService.HEADER,required=false) String key,
            @RequestBody CaseService.CompleteTask request) {
        var result=service.complete(callers.current(),workspaceId,instanceId,taskId,key,request);
        var response=ResponseEntity.ok();
        if(result.replayed()) response.header(IdempotencyService.REPLAYED_HEADER,"true");
        return response.body(result.value());
    }
}
