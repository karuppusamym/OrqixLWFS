package com.orqix.runtime.api;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.runtime.internal.CallbackService;
import com.orqix.shared.idempotency.IdempotencyService;

import tools.jackson.databind.JsonNode;

/**
 * Callback waits (docs/build/S98, EX10). Operators list waits and issue or revoke tokens; the external system polls and
 * resumes with the token alone ({@code X-Orqix-Callback-Token}), on a stateless route without session or CSRF.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class CallbackController {

    private final CallerResolver callers;
    private final CallbackService callbacks;

    public CallbackController(CallerResolver callers, CallbackService callbacks) {
        this.callers = callers;
        this.callbacks = callbacks;
    }

    @GetMapping("/callback-waits")
    public CallbackDtos.CallbackWaitList listCallbackWaits(@PathVariable UUID workspaceId,
            @RequestParam(required = false) UUID runId) {
        return callbacks.list(callers.current(), workspaceId, runId);
    }

    @PostMapping("/callback-waits/{waitId}/token")
    public ResponseEntity<CallbackDtos.CallbackTokenIssued> issueCallbackToken(@PathVariable UUID workspaceId,
            @PathVariable UUID waitId) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(callbacks.issue(callers.current(), workspaceId, waitId));
    }

    @DeleteMapping("/callback-waits/{waitId}/token")
    public ResponseEntity<Void> revokeCallbackToken(@PathVariable UUID workspaceId, @PathVariable UUID waitId) {
        callbacks.revoke(callers.current(), workspaceId, waitId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/callbacks/wait")
    public ResponseEntity<CallbackDtos.CallbackWaitState> pollCallbackWait(@PathVariable UUID workspaceId,
            @RequestHeader(name = CallbackService.TOKEN_HEADER, required = false) String token,
            @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch) {
        CallbackService.Poll poll = callbacks.poll(workspaceId, token, ifNoneMatch);
        if (poll.notModified()) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(poll.etag()).cacheControl(CacheControl.noStore())
                    .build();
        }
        return ResponseEntity.ok().eTag(poll.etag()).cacheControl(CacheControl.noStore()).body(poll.state());
    }

    @PostMapping("/callbacks/wait/resume")
    public ResponseEntity<CallbackDtos.CallbackResumed> resumeCallbackWait(@PathVariable UUID workspaceId,
            @RequestHeader(name = CallbackService.TOKEN_HEADER, required = false) String token,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        CallbackService.Resume resume = callbacks.resume(workspaceId, token, idempotencyKey, body);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok().cacheControl(CacheControl.noStore());
        if (resume.replayed()) {
            builder.header(IdempotencyService.REPLAYED_HEADER, "true");
        }
        return builder.body(resume.body());
    }
}
