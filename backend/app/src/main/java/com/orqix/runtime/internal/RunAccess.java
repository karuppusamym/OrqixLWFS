package com.orqix.runtime.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.shared.problem.ApiException;

/**
 * Run visibility (S3 section 3, S6 section 4): {@code run.read}, the initiator, or a task participant (holds or held a
 * task, decided one, or is an eligible candidate for an open one). Request data needs initiator, participant or
 * {@code run.read-data}.
 */
@Component
public class RunAccess {

    public record Visible(WorkspaceAccess access, RunStore.RunRow run, boolean initiator, boolean participant) {

        public boolean dataVisible() {
            return initiator || participant || access.has(Permission.RUN_READ_DATA);
        }
    }

    private final PolicyService policy;
    private final RunStore runs;
    private final TaskStore tasks;
    private final TaskQueries taskQueries;

    public RunAccess(PolicyService policy, RunStore runs, TaskStore tasks, TaskQueries taskQueries) {
        this.policy = policy;
        this.runs = runs;
        this.tasks = tasks;
        this.taskQueries = taskQueries;
    }

    /** Loads the run in the caller's workspace; 404 when it does not exist or is not visible to the caller. */
    public Visible require(Caller caller, UUID workspaceId, UUID runId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        RunStore.RunRow run = runs.find(workspaceId, runId).orElseThrow(ApiException::notFound);
        boolean initiator = run.initiator().equals(caller.subjectId());
        boolean participant = participant(access, run);
        if (!access.has(Permission.RUN_READ) && !initiator && !participant) {
            throw ApiException.notFound();
        }
        return new Visible(access, run, initiator, participant);
    }

    boolean participant(WorkspaceAccess access, RunStore.RunRow run) {
        if (taskQueries.heldOrDecided(run.workspaceId(), run.id(), access.subjectId())) {
            return true;
        }
        if (!access.has(Permission.TASK_ACT)) {
            return false;
        }
        for (TaskStore.TaskRow task : tasks.forRun(run.workspaceId(), run.id())) {
            if (task.isOpen() && eligible(access, task)) {
                return true;
            }
        }
        return false;
    }

    /** The runs among {@code runs} the caller participates in, with a constant number of queries (list views). */
    java.util.Set<UUID> participantRuns(WorkspaceAccess access, java.util.Collection<RunStore.RunRow> runs) {
        if (runs.isEmpty()) {
            return java.util.Set.of();
        }
        UUID workspaceId = runs.iterator().next().workspaceId();
        java.util.List<UUID> ids = runs.stream().map(RunStore.RunRow::id).toList();
        java.util.Set<UUID> result = taskQueries.heldOrDecidedAmong(workspaceId, ids, access.subjectId());
        if (access.has(Permission.TASK_ACT)) {
            for (TaskStore.TaskRow task : tasks.openForRuns(workspaceId, ids)) {
                if (eligible(access, task)) {
                    result.add(task.runId());
                }
            }
        }
        return result;
    }

    /** Eligibility (S5 section 4): task.act, member of the candidate group, separation of duties. */
    public static boolean eligible(WorkspaceAccess access, TaskStore.TaskRow task) {
        return access.has(Permission.TASK_ACT) && access.groupKeys().contains(task.candidateGroupKey())
                && (task.selfApprovalAllowed() || !task.initiator().equals(access.subjectId()));
    }
}
