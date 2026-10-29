package com.orqix.migration.api;

import java.net.URI;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.orqix.identity.CallerResolver;
import com.orqix.migration.internal.MigrationService;
import com.orqix.migration.internal.PegaRuleArchives;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/migrations")
public class MigrationController {
    public record Upload(@NotBlank @Size(max=200) String fileName, @NotBlank @Size(max=1_398_104) String content,
            @Pattern(regexp="bpmn|dmn|camundaForm|pegaArchive|pegaMapping|pegaXpdl|cmmn|pegaRules") String sourceFormat) { }
    private final CallerResolver callers; private final MigrationService service; private final PegaRuleArchives pegaRules;
    public MigrationController(CallerResolver callers, MigrationService service, PegaRuleArchives pegaRules) { this.callers=callers; this.service=service; this.pegaRules=pegaRules; }
    @PostMapping
    public ResponseEntity<?> upload(@PathVariable UUID workspaceId, @Valid @RequestBody Upload request) {
        var result=service.upload(callers.current(),workspaceId,request.fileName(),request.content(),request.sourceFormat());
        return ResponseEntity.created(URI.create("/api/v1/workspaces/"+workspaceId+"/migrations/"+result.id())).body(result);
    }
    @GetMapping("/{migrationId}")
    public Object get(@PathVariable UUID workspaceId,@PathVariable UUID migrationId) { return service.get(callers.current(),workspaceId,migrationId); }
    /** Inventory, effective-rule resolution and per-rule conversion results of a native Pega rule archive (docs/build/S112). */
    @GetMapping("/{migrationId}/pega-rules")
    public PegaRuleArchives.View pegaRules(@PathVariable UUID workspaceId,@PathVariable UUID migrationId) { return pegaRules.view(callers.current(),workspaceId,migrationId); }
    /** Converts a native Pega case type to a CMMN case definition, or previews it with dryRun (docs/build/S117). */
    @PostMapping("/{migrationId}/case-definition")
    public ResponseEntity<?> caseDefinition(@PathVariable UUID workspaceId,@PathVariable UUID migrationId,@RequestBody JsonNode request) {
        var result=pegaRules.caseDefinition(callers.current(),workspaceId,migrationId,request);
        return ResponseEntity.status(result.dryRun() ? 200 : 201).body(result);
    }
    @PostMapping("/{migrationId}/convert")
    public ResponseEntity<?> convert(@PathVariable UUID workspaceId,@PathVariable UUID migrationId,@RequestBody JsonNode request) {
        return ResponseEntity.status(201).body(service.convert(callers.current(),workspaceId,migrationId,request));
    }
}
