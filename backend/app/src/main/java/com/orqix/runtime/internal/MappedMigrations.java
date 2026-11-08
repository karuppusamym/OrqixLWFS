package com.orqix.runtime.internal;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.catalog.ReleaseQueries;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.notification.NotificationService;
import com.orqix.runtime.api.MigrationMoveDtos;
import com.orqix.runtime.api.RuntimeDtos;
import com.orqix.runtime.engine.EngineCommands;
import com.orqix.runtime.engine.ProcessData;
import com.orqix.shared.idempotency.IdempotencyService;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

/**
 * Migration of one request to another release with an explicit step mapping (CG-O02, docs/build/S143). The S38
 * operation ({@code POST /runs/{id}/migrate}) is unchanged; this one accepts what S38 refuses only when the rules in
 * {@link MigrationRules} show it is safe, and keeps every S38 guarantee: run.operate, idempotency key, reason, version
 * check, a request that cannot change meanwhile, the engine's own validation, audit and a timeline entry.
 */
@Service
public class MappedMigrations {

    /** Why the request can or cannot be moved now, and what holds it still. */
    public record Readiness(boolean ready, String reason, String hold) {
    }

    private final PolicyService policy;
    private final RunAccess runAccess;
    private final RunStore runs;
    private final TaskStore tasks;
    private final RunEvents events;
    private final RunService runService;
    private final ReleaseQueries releases;
    private final EngineCommands engine;
    private final IdempotencyService idempotency;
    private final AuditWriter audit;
    private final NotificationService notifications;
    private final RunSuspensions suspensions;
    private final RunMigrations migrations;
    private final RunFacts facts;
    private final Clock clock;

    public MappedMigrations(PolicyService policy, RunAccess runAccess, RunStore runs, TaskStore tasks, RunEvents events,
            RunService runService, ReleaseQueries releases, EngineCommands engine, IdempotencyService idempotency,
            AuditWriter audit, NotificationService notifications, RunSuspensions suspensions, RunMigrations migrations,
            RunFacts facts, Clock clock) {
        this.policy = policy;
        this.runAccess = runAccess;
        this.runs = runs;
        this.tasks = tasks;
        this.events = events;
        this.runService = runService;
        this.releases = releases;
        this.engine = engine;
        this.idempotency = idempotency;
        this.audit = audit;
        this.notifications = notifications;
        this.suspensions = suspensions;
        this.migrations = migrations;
        this.facts = facts;
        this.clock = clock;
    }

    /** Dry run: the per-request plan and every reason it would be refused; changes nothing. */
    @Transactional
    public MigrationMoveDtos.MigrationPlan plan(Caller caller, UUID workspaceId, UUID runId,
            MigrationMoveDtos.MigrationPlanRequest request) {
        RunAccess.Visible visible = runAccess.require(caller, workspaceId, runId);
        policy.require(visible.access(), Permission.RUN_OPERATE, "planRunMigration", true);
        RunStore.RunRow run = visible.run();
        ReleaseQueries.ReleaseView source = releases.require(workspaceId, run.releaseId());
        ReleaseQueries.ReleaseView target = target(run, request.targetReleaseId());
        return plan(run, source, target, mappings(request.mappingsOrEmpty()), readiness(run, false));
    }

    MigrationMoveDtos.MigrationPlan plan(RunStore.RunRow run, ReleaseQueries.ReleaseView source,
            ReleaseQueries.ReleaseView target, List<MigrationRules.Mapping> mappings, Readiness readiness) {
        RuntimeDtos.ReleaseRef current = new RuntimeDtos.ReleaseRef(source.id(), source.releaseNumber());
        RuntimeDtos.ReleaseRef to = new RuntimeDtos.ReleaseRef(target.id(), target.releaseNumber());
        if (!run.isActive()) {
            return new MigrationMoveDtos.MigrationPlan(run.id(), run.reference(), current, to, false,
                    readiness.reason(), null, false, run.version(), List.of(), List.of(), List.of(), List.of());
        }
        MigrationRules.Plan plan = rules(run, source, target, mappings);
        return view(run, current, to, plan, readiness);
    }

    MigrationRules.Plan rules(RunStore.RunRow run, ReleaseQueries.ReleaseView source, ReleaseQueries.ReleaseView target,
            List<MigrationRules.Mapping> mappings) {
        MigrationRules.Plan plan = MigrationRules.plan(source.definition(), target.definition(),
                facts.migrationFacts(run), mappings);
        List<ReleaseCompatibility.Finding> findings = new ArrayList<>(plan.findings());
        if (source.id().equals(target.id())) {
            findings.add(new ReleaseCompatibility.Finding("SAME_RELEASE", "The request is already on that release.", null));
        }
        findings.addAll(migrations.releaseFindings(run, source, target));
        return new MigrationRules.Plan(plan.steps(), plan.dataCopies(), findings, plan.suggestedMappings());
    }

    private static MigrationMoveDtos.MigrationPlan view(RunStore.RunRow run, RuntimeDtos.ReleaseRef current,
            RuntimeDtos.ReleaseRef target, MigrationRules.Plan plan, Readiness readiness) {
        return new MigrationMoveDtos.MigrationPlan(run.id(), run.reference(), current, target, readiness.ready(),
                readiness.reason(), readiness.hold(), plan.allowed() && readiness.ready(), run.version(),
                plan.steps().stream().map(s -> new MigrationMoveDtos.MigrationPlanStep(s.from(), s.to(), s.kind(),
                        s.action(), s.label())).toList(),
                plan.dataCopies().stream().map(c -> new MigrationMoveDtos.MigrationDataCopy(c.from(), c.to())).toList(),
                findings(plan.findings()),
                plan.suggestedMappings().stream().map(m -> new MigrationMoveDtos.StepMapping(m.from(), m.to())).toList());
    }

    /**
     * A request can move while nothing can change under it: it is suspended (S37), or every step it waits at is a
     * child request still running (the parent's engine instance has no job and its child completion takes the run lock
     * this operation holds). {@code suspendDuring} reports a request a batch would suspend for the move.
     */
    Readiness readiness(RunStore.RunRow run, boolean suspendDuring) {
        if (!run.isActive()) {
            return new Readiness(false, "The request has ended.", null);
        }
        if (RunStore.NEEDS_ATTENTION.equals(run.lifecycle())) {
            return new Readiness(false, "The request needs attention. Recover it (retry, reconcile or move) first.", null);
        }
        if (run.isChild()) {
            return new Readiness(false, "A child request follows the release its parent started it on; move the"
                    + " parent request instead.", null);
        }
        if (suspensions.isSuspended(run.workspaceId(), run.id())) {
            return new Readiness(true, null, "suspended");
        }
        List<String> active = facts.activeSteps(run);
        Set<String> held = facts.childSteps(run, runs);
        if (!active.isEmpty() && held.containsAll(active) && engine.processExists(run.engineProcessInstanceId())
                && engine.jobCount(run.engineProcessInstanceId()) == 0) {
            return new Readiness(true, null, "childRequests");
        }
        if (suspendDuring && OperatorService.suspendBlocker(run, runs, engine) == null) {
            return new Readiness(true, null, "suspendDuring");
        }
        return new Readiness(false, "Suspend the request first. A request is moved only while nothing can change"
                + " under it.", null);
    }

    @Transactional
    public OperatorService.Result<RuntimeDtos.RunDetail> migrate(Caller caller, UUID workspaceId, UUID runId,
            String idempotencyKey, MigrationMoveDtos.MappedMigrationRequest request) {
        String key = IdempotencyService.requireKey(idempotencyKey);
        RunAccess.Visible visible = runAccess.require(caller, workspaceId, runId);
        policy.require(visible.access(), Permission.RUN_OPERATE, "migrateRunWithMapping", true);
        String hash = idempotency.requestHash("migrateRunWithMapping", Map.of("workspaceId", workspaceId.toString(),
                "runId", runId.toString()), request);
        IdempotencyService.Claim claim = idempotency.claim(workspaceId, caller.subjectId(), "migrateRunWithMapping",
                key, hash);
        if (claim.isReplay()) {
            return new OperatorService.Result<>(runService.detail(runAccess.require(caller, workspaceId, runId),
                    List.of()), true);
        }
        RunStore.RunRow run = runs.lock(workspaceId, runId).orElseThrow(ApiException::notFound);
        if (!run.isActive()) {
            throw new ApiException(ProblemCode.RUN_NOT_ACTIVE, "The run has already ended.")
                    .with("currentVersion", run.version());
        }
        if (run.version() != request.expectedVersion()) {
            throw new ApiException(ProblemCode.RUN_NOT_ACTIVE, "The run changed since you loaded it.")
                    .with("currentVersion", run.version());
        }
        Readiness readiness = readiness(run, false);
        if (!readiness.ready()) {
            throw new ApiException(RunStore.NEEDS_ATTENTION.equals(run.lifecycle()) || run.isChild()
                    ? ProblemCode.RUN_NOT_MIGRATABLE : ProblemCode.RUN_NOT_SUSPENDED, readiness.reason())
                    .with("currentVersion", run.version());
        }
        ReleaseQueries.ReleaseView source = releases.require(workspaceId, run.releaseId());
        ReleaseQueries.ReleaseView target = target(run, request.targetReleaseId());
        MigrationRules.Plan plan = rules(run, source, target, mappings(request.mappingsOrEmpty()));
        if (!plan.allowed()) {
            throw new ApiException(ProblemCode.RUN_NOT_MIGRATABLE, plan.findings().getFirst().message())
                    .with("findings", findings(plan.findings()));
        }
        Map<String, Object> variables = new LinkedHashMap<>();
        for (MigrationRules.DataCopy copy : plan.dataCopies()) {
            Object value = facts.stepData(run, copy.from());
            if (value != null) {
                variables.put(ProcessData.stepVariable(copy.to()), value);
            }
        }
        try {
            List<String> problems = engine.migrationProblems(run.engineProcessInstanceId(),
                    target.processDefinitionId(), plan.activityMappings(), variables);
            if (!problems.isEmpty()) {
                throw new ApiException(ProblemCode.RUN_NOT_MIGRATABLE,
                        "The workflow engine cannot move this request: " + String.join("; ", problems));
            }
            engine.migrateProcessInstance(run.engineProcessInstanceId(), target.processDefinitionId(),
                    plan.activityMappings(), variables, caller.subjectId());
        } catch (EngineCommands.EngineConflictException e) {
            throw new ApiException(ProblemCode.RUN_BUSY, "The run is busy; try again shortly.")
                    .withHeader("Retry-After", "5");
        }
        runs.setRelease(workspaceId, runId, target.id());
        carryOverSteps(run, plan);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fromReleaseNumber", source.releaseNumber());
        data.put("toReleaseNumber", target.releaseNumber());
        data.put("reason", request.reason());
        data.put("mapped", plan.steps().stream().filter(s -> !s.from().equals(s.to()))
                .map(s -> Map.of("from", s.from(), "to", s.to())).toList());
        data.put("dataCopies", plan.dataCopies().size());
        if (!"suspended".equals(readiness.hold())) {
            data.put("heldBy", readiness.hold());
        }
        events.append(workspaceId, runId, "RUN_MIGRATED", null, caller.subjectId(), data);
        notifyInitiator(run, "migrated", "Your request now follows version " + target.releaseNumber()
                + " of the workflow.");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("fromRelease", source.releaseNumber());
        metadata.put("toRelease", target.releaseNumber());
        metadata.put("mapped", plan.activityMappings().size() + plan.dataCopies().size());
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(), "run.migrated",
                "run", runId.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        idempotency.complete(claim, "run", runId.toString(), 200);
        return new OperatorService.Result<>(runService.detail(runAccess.require(caller, workspaceId, runId),
                List.of()), false);
    }

    /**
     * The projection after the engine moved: kept steps take the target's label (as in S38); a renamed approval keeps
     * its open task under the new step id, or, when the engine opened a new task for it, the old one is closed.
     */
    private void carryOverSteps(RunStore.RunRow run, MigrationRules.Plan plan) {
        UUID ws = run.workspaceId();
        List<TaskStore.TaskRow> open = tasks.forRun(ws, run.id()).stream().filter(TaskStore.TaskRow::isOpen).toList();
        List<String> removed = new ArrayList<>();
        for (MigrationRules.Step step : plan.steps()) {
            if (step.from().equals(step.to())) {
                tasks.relabelOpenForNode(ws, run.id(), step.from(), step.label());
            } else if (open.stream().anyMatch(t -> t.nodeId().equals(step.to()))) {
                tasks.cancelOpenForNode(ws, run.id(), step.from(), clock.instant());
                removed.add(step.from());
            } else {
                tasks.renameOpenForNode(ws, run.id(), step.from(), step.to(), step.label());
            }
        }
        runs.mutate(ws, run.id(), m -> {
            for (MigrationRules.Step step : plan.steps()) {
                if (removed.contains(step.from())) {
                    m.removeStep(step.from());
                } else if (m.hasStep(step.from())) {
                    m.updateStep(step.from(), s -> s.put("nodeId", step.to()).put("label", step.label()));
                }
            }
            m.settle();
        });
    }

    ReleaseQueries.ReleaseView target(RunStore.RunRow run, UUID targetReleaseId) {
        return releases.find(run.workspaceId(), targetReleaseId)
                .filter(r -> r.workflowId().equals(run.workflowId())).orElseThrow(ApiException::notFound);
    }

    static List<MigrationRules.Mapping> mappings(List<MigrationMoveDtos.StepMapping> mappings) {
        return mappings.stream().map(m -> new MigrationRules.Mapping(m.from(), m.to())).toList();
    }

    static List<RuntimeDtos.MigrationFinding> findings(List<ReleaseCompatibility.Finding> findings) {
        return findings.stream().map(f -> new RuntimeDtos.MigrationFinding(f.code(), f.message(), f.nodeId())).toList();
    }

    private void notifyInitiator(RunStore.RunRow run, String what, String message) {
        if (notifications.create(run.workspaceId(), run.initiator(), run.id(), NotificationService.Kind.WORKFLOW_MESSAGE,
                run.id() + ":" + what + ":" + clock.instant().toEpochMilli(), message)) {
            events.append(run.workspaceId(), run.id(), "NOTIFICATION_CREATED", null, null,
                    Map.of("kind", NotificationService.Kind.WORKFLOW_MESSAGE.value(), "recipient", "initiator"));
        }
    }
}
