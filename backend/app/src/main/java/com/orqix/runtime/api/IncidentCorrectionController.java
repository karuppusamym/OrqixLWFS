package com.orqix.runtime.api;

import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import com.orqix.identity.CallerResolver;
import com.orqix.runtime.internal.IncidentCorrectionService;
import com.orqix.shared.idempotency.IdempotencyService;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/runs/{runId}/correct-input")
public class IncidentCorrectionController {
    public record Request(@Min(1) int expectedVersion,@NotBlank @Size(max=500) String reason,@NotNull JsonNode changes) {}
    private final CallerResolver callers; private final IncidentCorrectionService corrections;
    public IncidentCorrectionController(CallerResolver callers,IncidentCorrectionService corrections) { this.callers=callers;this.corrections=corrections; }
    @PostMapping
    public IncidentCorrectionService.Result correct(@PathVariable UUID workspaceId,@PathVariable UUID runId,
            @RequestHeader(name=IdempotencyService.HEADER,required=false) String key,@Valid @RequestBody Request request) {
        return corrections.correct(callers.current(),workspaceId,runId,key,request);
    }
}
