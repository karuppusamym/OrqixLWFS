package com.orqix.runtime.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.compiler.PollSchedule;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.runtime.api.WaitDtos;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

/** Reads of the "Waiting for" panel and API (docs/build/S129, CG-W08). */
@Service
public class WaitsService {

    static final Set<String> KINDS = Set.of("approval", "timer", "message", "callback", "poll", "externalJob",
            "childRequest", "signal");

    private final RunAccess runAccess;
    private final PolicyService policy;
    private final WaitStates waits;
    private final PollWaits polls;

    public WaitsService(RunAccess runAccess, PolicyService policy, WaitStates waits, PollWaits polls) {
        this.runAccess = runAccess;
        this.policy = policy;
        this.waits = waits;
        this.polls = polls;
    }

    /** Readable by whoever may read the request (run.read, the initiator, task participants), like the run page. */
    @Transactional(readOnly = true)
    public WaitDtos.RunWaitList forRun(Caller caller, UUID workspaceId, UUID runId) {
        RunAccess.Visible visible = runAccess.require(caller, workspaceId, runId);
        boolean data = visible.dataVisible();
        boolean operate = visible.access().has(Permission.RUN_OPERATE);
        Map<UUID, PollWaits.Row> checks = polls.forRun(workspaceId, runId).stream()
                .collect(Collectors.toMap(PollWaits.Row::id, Function.identity()));
        List<WaitDtos.RunWait> items = new ArrayList<>();
        java.util.Set<UUID> open = new java.util.HashSet<>();
        for (WaitStates.WaitRow w : waits.forRun(workspaceId, runId)) {
            PollWaits.Row check = w.pollWaitId() == null ? null : checks.get(w.pollWaitId());
            if (check != null) {
                open.add(check.id());
            }
            items.add(new WaitDtos.RunWait(w.nodeId(), w.kind(), w.label(), data ? w.subject() : withoutValues(w),
                    w.since(), w.nextCheckAt(), w.dueAt(), w.deadlineAt(), w.attempts(), w.correlationRef(),
                    w.connectionKey(), check == null ? null : view(check, data, operate)));
        }
        List<WaitDtos.PollCheck> ended = checks.values().stream().filter(c -> !open.contains(c.id()))
                .sorted(java.util.Comparator.comparing(PollWaits.Row::startedAt))
                .map(c -> view(c, data, operate)).toList();
        return new WaitDtos.RunWaitList(items, ended);
    }

    /** Runs filter "waiting on connection X for longer than Y" across the workspace; needs run.read. */
    @Transactional(readOnly = true)
    public WaitDtos.WaitSearchResult search(Caller caller, UUID workspaceId, String kind, String connectionKey,
            String waitingLongerThan, Integer limit) {
        var access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.RUN_READ, "searchWaits", false);
        if (kind != null && !KINDS.contains(kind)) {
            throw invalid("/kind", "Use one of " + String.join(", ", new java.util.TreeSet<>(KINDS)) + ".");
        }
        Duration longer = null;
        if (waitingLongerThan != null) {
            long seconds = PollSchedule.seconds(waitingLongerThan);
            if (seconds < 0) {
                throw invalid("/waitingLongerThan", "Use an ISO-8601 duration such as PT1H or P2D.");
            }
            longer = Duration.ofSeconds(seconds);
        }
        int max = limit == null ? 50 : limit;
        if (max < 1 || max > 200) {
            throw invalid("/limit", "From 1 to 200.");
        }
        boolean data = access.has(Permission.RUN_READ_DATA);
        return new WaitDtos.WaitSearchResult(waits.search(workspaceId, kind, connectionKey, longer, max).stream()
                .map(w -> new WaitDtos.WaitSearchItem(w.runId(), w.runReference(), w.nodeId(), w.kind(), w.label(),
                        data ? w.subject() : withoutValues(w), w.since(), w.nextCheckAt(), w.deadlineAt(), w.attempts(),
                        w.connectionKey()))
                .toList());
    }

    /** @return the API view of one check */
    public static WaitDtos.PollCheck view(PollWaits.Row c, boolean data, boolean operate) {
        Instant available = c.lastManualCheckAt() == null ? null : c.lastManualCheckAt().plus(PollWaits.MANUAL_CHECK_SPACING);
        return new WaitDtos.PollCheck(c.id(), c.kind(), c.state(), c.checksDone(), c.maxChecks(), c.consecutiveErrors(),
                c.startedAt(), c.lastCheckedAt(), c.lastHttpStatus(), c.lastOutcome(), c.deadlineAt(),
                c.authoredDeadlineAt(), PollWaits.extensionLimit(c), c.deadlineExtensions(),
                data ? c.shown() : null, operate, available, c.endedAt(), c.endReason());
    }

    /** The subject of a poll wait may carry shown values; without request-data access only the label is named. */
    private static String withoutValues(WaitStates.WaitRow w) {
        String subject = w.subject();
        int paren = "poll".equals(w.kind()) ? subject.lastIndexOf(" (") : -1;
        return paren > 0 && subject.endsWith(")") ? subject.substring(0, paren) : subject;
    }

    private static ApiException invalid(String pointer, String message) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, "Some values are not valid.")
                .withFieldErrors(List.of(new ApiException.FieldError(pointer, "invalid", message)));
    }
}
