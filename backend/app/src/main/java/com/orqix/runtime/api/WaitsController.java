package com.orqix.runtime.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.runtime.internal.PollWaits;
import com.orqix.runtime.internal.WaitsService;
import com.orqix.shared.idempotency.IdempotencyService;

/**
 * "Waiting for" (docs/build/S129, CG-W08): what a request waits for, the workspace search of waits, and the operator
 * actions on checks (Check now, Extend deadline, Stop waiting). Bodies are optional at the HTTP level so that access is
 * decided before the body is (a caller outside the workspace always gets 404); the service then requires a reason.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class WaitsController {

    private final CallerResolver callers;
    private final WaitsService waits;
    private final PollWaits polls;

    public WaitsController(CallerResolver callers, WaitsService waits, PollWaits polls) {
        this.callers = callers;
        this.waits = waits;
        this.polls = polls;
    }

    @GetMapping("/runs/{runId}/waits")
    public WaitDtos.RunWaitList listRunWaits(@PathVariable UUID workspaceId, @PathVariable UUID runId) {
        return waits.forRun(callers.current(), workspaceId, runId);
    }

    @GetMapping("/waits")
    public WaitDtos.WaitSearchResult searchWaits(@PathVariable UUID workspaceId,
            @RequestParam(required = false) String kind, @RequestParam(required = false) String connectionKey,
            @RequestParam(required = false) String waitingLongerThan, @RequestParam(required = false) Integer limit) {
        return waits.search(callers.current(), workspaceId, kind, connectionKey, waitingLongerThan, limit);
    }

    @PostMapping("/runs/{runId}/waits/{waitId}/check-now")
    public ResponseEntity<WaitDtos.PollCheck> checkWaitNow(@PathVariable UUID workspaceId, @PathVariable UUID runId,
            @PathVariable UUID waitId, @RequestHeader(name = IdempotencyService.HEADER, required = false) String key,
            @RequestBody(required = false) WaitDtos.WaitActionRequest request) {
        return answer(polls.operate(callers.current(), workspaceId, runId, waitId, PollWaits.Action.CHECK_NOW,
                request == null ? null : request.reason(), null, "checkWaitNow", key));
    }

    @PostMapping("/runs/{runId}/waits/{waitId}/extend-deadline")
    public ResponseEntity<WaitDtos.PollCheck> extendWaitDeadline(@PathVariable UUID workspaceId, @PathVariable UUID runId,
            @PathVariable UUID waitId, @RequestHeader(name = IdempotencyService.HEADER, required = false) String key,
            @RequestBody(required = false) WaitDtos.ExtendWaitRequest request) {
        return answer(polls.operate(callers.current(), workspaceId, runId, waitId, PollWaits.Action.EXTEND,
                request == null ? null : request.reason(), request == null ? null : request.extendBy(),
                "extendWaitDeadline", key));
    }

    @PostMapping("/runs/{runId}/waits/{waitId}/stop")
    public ResponseEntity<WaitDtos.PollCheck> stopWaiting(@PathVariable UUID workspaceId, @PathVariable UUID runId,
            @PathVariable UUID waitId, @RequestHeader(name = IdempotencyService.HEADER, required = false) String key,
            @RequestBody(required = false) WaitDtos.WaitActionRequest request) {
        return answer(polls.operate(callers.current(), workspaceId, runId, waitId, PollWaits.Action.STOP,
                request == null ? null : request.reason(), null, "stopWaiting", key));
    }

    private static ResponseEntity<WaitDtos.PollCheck> answer(PollWaits.Operated operated) {
        // Shown values are left out here; the run page reads them through listRunWaits with its own access check.
        var body = WaitsService.view(operated.row(), false, true);
        var builder = ResponseEntity.ok();
        if (operated.replayed()) {
            builder.header(IdempotencyService.REPLAYED_HEADER, "true");
        }
        return builder.body(body);
    }
}
