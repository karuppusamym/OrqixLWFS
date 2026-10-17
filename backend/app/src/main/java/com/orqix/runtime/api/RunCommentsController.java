package com.orqix.runtime.api;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.orqix.identity.CallerResolver;
import com.orqix.runtime.internal.RunComments;
import com.orqix.shared.idempotency.IdempotencyService;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/runs/{runId}/comments")
public class RunCommentsController {
    public record Request(String text) {}
    private final CallerResolver callers; private final RunComments comments;
    public RunCommentsController(CallerResolver callers, RunComments comments) { this.callers=callers; this.comments=comments; }
    @GetMapping public RunComments.CommentPage list(@PathVariable UUID workspaceId,@PathVariable UUID runId,@RequestParam(required=false) UUID before) {
        return comments.list(callers.current(),workspaceId,runId,before);
    }
    @PostMapping public ResponseEntity<RunComments.Comment> post(@PathVariable UUID workspaceId,@PathVariable UUID runId,
        @RequestHeader(name=IdempotencyService.HEADER,required=false) String key,@RequestBody(required=false) Request request) {
        // The body is validated by the service after the access check, so callers without access always get 404/403.
        var created=comments.post(callers.current(),workspaceId,runId,key,request==null?null:request.text());
        var response=ResponseEntity.status(created.replayed()?200:201);
        if(created.replayed()) response.header(IdempotencyService.REPLAYED_HEADER,"true");
        return response.body(created.comment());
    }
}
