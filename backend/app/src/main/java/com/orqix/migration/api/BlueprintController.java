package com.orqix.migration.api;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.orqix.identity.CallerResolver;
import com.orqix.integration.DocumentExtraction;
import com.orqix.migration.internal.BlueprintImports;
import com.orqix.migration.internal.MigrationService;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import tools.jackson.databind.JsonNode;

/** Pega Blueprint PDF import (docs/build/S91, openapi tag Migration). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/migrations")
public class BlueprintController {
    private final CallerResolver callers; private final BlueprintImports blueprints; private final MigrationService migrations;
    public BlueprintController(CallerResolver callers, BlueprintImports blueprints, MigrationService migrations) {
        this.callers = callers; this.blueprints = blueprints; this.migrations = migrations;
    }

    @PostMapping(path = "/blueprints", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@PathVariable UUID workspaceId, @RequestPart(name = "file", required = false) MultipartFile file,
            @RequestParam(name = "caseType", required = false) String caseType) {
        var caller = callers.current();
        if (file == null || file.isEmpty()) throw ApiException.malformed("file is required.");
        if (file.getSize() > DocumentExtraction.BLUEPRINT_MAX_FILE_BYTES) throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE, "The PDF is larger than 25 MiB.");
        byte[] bytes;
        try { bytes = file.getBytes(); } catch (IOException e) { throw ApiException.malformed("The file could not be read."); }
        UUID id = blueprints.upload(caller, workspaceId, file.getOriginalFilename(), bytes, caseType);
        return ResponseEntity.created(URI.create("/api/v1/workspaces/" + workspaceId + "/migrations/" + id)).body(migrations.get(caller, workspaceId, id));
    }

    @GetMapping("/{migrationId}/blueprint")
    public BlueprintImports.View get(@PathVariable UUID workspaceId, @PathVariable UUID migrationId) {
        return blueprints.view(callers.current(), workspaceId, migrationId);
    }

    @PutMapping("/{migrationId}/blueprint")
    public BlueprintImports.View save(@PathVariable UUID workspaceId, @PathVariable UUID migrationId, @RequestBody JsonNode body) {
        if (body == null || !body.isObject() || !body.path("expectedRevision").isIntegralNumber() || !body.path("transcription").isObject()
                || body.size() != 2) throw ApiException.malformed("Send exactly expectedRevision and transcription.");
        return blueprints.save(callers.current(), workspaceId, migrationId, body.path("expectedRevision").asInt(), body.path("transcription"));
    }

    @PostMapping("/{migrationId}/blueprint/ai-proposal")
    public BlueprintImports.AiProposal propose(@PathVariable UUID workspaceId, @PathVariable UUID migrationId, @RequestBody(required = false) JsonNode body) {
        String profile = null;
        if (body != null && !body.isNull()) {
            if (!body.isObject() || body.size() > (body.has("profile") ? 1 : 0)) throw ApiException.malformed("Only profile may be sent.");
            if (body.has("profile")) {
                if (!body.path("profile").isString() || !body.path("profile").asString().matches("^[a-z][a-z0-9-]{0,39}$")) throw ApiException.malformed("profile must be a profile id.");
                profile = body.path("profile").asString();
            }
        }
        return blueprints.aiProposal(callers.current(), workspaceId, migrationId, profile);
    }
}
