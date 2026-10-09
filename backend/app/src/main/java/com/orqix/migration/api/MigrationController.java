package com.orqix.migration.api;

import java.net.URI;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.orqix.identity.CallerResolver;
import com.orqix.migration.internal.MigrationService;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/migrations")
public class MigrationController {
    public record Upload(@NotBlank @Size(max=200) String fileName, @NotBlank @Size(max=1_398_104) String content,
            @Pattern(regexp="bpmn|pegaArchive|pegaMapping") String sourceFormat) { }
    private final CallerResolver callers; private final MigrationService service;
    public MigrationController(CallerResolver callers, MigrationService service) { this.callers=callers; this.service=service; }
    @PostMapping
    public ResponseEntity<?> upload(@PathVariable UUID workspaceId, @Valid @RequestBody Upload request) {
        var result=service.upload(callers.current(),workspaceId,request.fileName(),request.content(),request.sourceFormat());
        return ResponseEntity.created(URI.create("/api/v1/workspaces/"+workspaceId+"/migrations/"+result.id())).body(result);
    }
    @GetMapping("/{migrationId}")
    public Object get(@PathVariable UUID workspaceId,@PathVariable UUID migrationId) { return service.get(callers.current(),workspaceId,migrationId); }
    @PostMapping("/{migrationId}/convert")
    public ResponseEntity<?> convert(@PathVariable UUID workspaceId,@PathVariable UUID migrationId,@RequestBody JsonNode request) {
        return ResponseEntity.status(201).body(service.convert(callers.current(),workspaceId,migrationId,request));
    }
}
