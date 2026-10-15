package com.orqix.integration.api;

import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.orqix.identity.CallerResolver;
import com.orqix.integration.openapi.OpenApiReview;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/connections/import-openapi")
public final class OpenApiReviewController {
    private final CallerResolver callers;
    private final OpenApiReview reviews;
    public OpenApiReviewController(CallerResolver callers,OpenApiReview reviews) { this.callers=callers; this.reviews=reviews; }
    @PostMapping public OpenApiReview.Result review(@PathVariable UUID workspaceId,@Valid @RequestBody OpenApiReview.Request request) {
        return reviews.review(callers.current(),workspaceId,request);
    }
}
