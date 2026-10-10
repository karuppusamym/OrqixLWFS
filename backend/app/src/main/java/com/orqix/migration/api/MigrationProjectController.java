package com.orqix.migration.api;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.orqix.identity.CallerResolver;
import com.orqix.migration.camunda.ProjectAnalyzer;
import com.orqix.migration.internal.MigrationProjects;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/migrations/projects")
public class MigrationProjectController {
    public record Upload(@NotBlank @Size(max=200) String name,@NotEmpty @Size(max=10) List<ProjectAnalyzer.Source> files) {}
    public record Convert(@NotNull UUID expectedMigrationId,@NotNull JsonNode mappings) {}
    private final CallerResolver callers; private final MigrationProjects projects;
    public MigrationProjectController(CallerResolver callers,MigrationProjects projects) { this.callers=callers;this.projects=projects; }
    @PostMapping public ResponseEntity<MigrationProjects.Project> upload(@PathVariable UUID workspaceId,@Valid @RequestBody Upload request) {
        return ResponseEntity.status(201).body(projects.upload(callers.current(),workspaceId,request.name(),request.files()));
    }
    @GetMapping("/{projectId}") public MigrationProjects.Project get(@PathVariable UUID workspaceId,@PathVariable UUID projectId) { return projects.get(callers.current(),workspaceId,projectId); }
    @PostMapping("/{projectId}/convert-next") public ResponseEntity<MigrationProjects.Converted> convert(@PathVariable UUID workspaceId,@PathVariable UUID projectId,@Valid @RequestBody Convert request) {
        return ResponseEntity.status(201).body(projects.convertNext(callers.current(),workspaceId,projectId,request.expectedMigrationId(),request.mappings()));
    }
}
