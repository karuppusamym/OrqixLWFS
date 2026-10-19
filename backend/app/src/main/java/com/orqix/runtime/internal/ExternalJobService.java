package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.catalog.ReleaseQueries;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.runtime.api.ExternalJobDtos;
import com.orqix.runtime.engine.EngineCommands;
import com.orqix.runtime.engine.ProcessData;
import com.orqix.shared.idempotency.IdempotencyService;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Jobs for external workers (docs/build/S43). A step publishes one job; a worker leases it, does the work elsewhere and
 * completes it with the declared result. Every lock raises the job's fence, and every later call must present it, so a
 * worker that lost its lease cannot change the job. Leases that expire are sweeper-recovered up to the attempt limit,
 * after which the request needs attention and an operator decides.
 */
@Service
public class ExternalJobService {

    static final int MESSAGE_LIMIT = 200;

    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;
    private final RunStore runs;
    private final RunEvents events;
    private final ReleaseQueries releases;
    private final EngineCommands engine;
    private final PolicyService policy;
    private final IdempotencyService idempotency;
    private final AuditWriter audit;
    private final RunSuspensions suspensions;

    public ExternalJobService(JdbcClient jdbc, Json json, Clock clock, RunStore runs, RunEvents events,
            ReleaseQueries releases, EngineCommands engine, PolicyService policy, IdempotencyService idempotency,
            AuditWriter audit, RunSuspensions suspensions) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.runs = runs;
        this.events = events;
        this.releases = releases;
        this.engine = engine;
        this.policy = policy;
        this.idempotency = idempotency;
        this.audit = audit;
        this.suspensions = suspensions;
    }

    // ------------------------------------------------------------------ publishing (called from the engine)

    /** Publishes the job of a step that just started; a repeated call while the job is open does nothing. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(UUID workspaceId, UUID runId, String nodeId, String topic, JsonNode input, int leaseSeconds,
            int maxAttempts, int retryDelaySeconds) {
        Instant now = clock.instant();
        int inserted = jdbc.sql("""
                INSERT INTO external_job (id, workspace_id, run_id, node_id, topic, input, status, attempt, max_attempts,
                                          lease_seconds, retry_delay_seconds, fence, available_at, created_at)
                VALUES (:id, :ws, :run, :node, :topic, CAST(:input AS jsonb), 'available', 0, :max, :lease, :delay, 0, :now, :now)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.randomUUID()).param("ws", workspaceId).param("run", runId).param("node", nodeId)
                .param("topic", topic).param("input", json.write(input)).param("max", maxAttempts)
                .param("lease", leaseSeconds).param("delay", retryDelaySeconds).param("now", Sql.ts(now)).update();
        if (inserted == 0) {
            return;
        }
        RunStore.RunRow run = runs.find(workspaceId, runId).orElseThrow();
        String label = releases.require(workspaceId, run.releaseId()).nodeLabel(nodeId);
        runs.mutate(workspaceId, runId, m -> {
            ObjectNode step = json.object();
            step.put("nodeId", nodeId).put("label", label == null ? nodeId : label).put("kind", "external")
                    .put("since", now.toString());
            step.putNull("candidateGroup").putNull("assigneeId").putNull("dueAt");
            step.put("topic", topic);
            m.addStep(step);
            m.settle();
        });
        events.append(workspaceId, runId, "EXTERNAL_JOB_PUBLISHED", nodeId, null, Map.of("topic", topic));
    }

    // ------------------------------------------------------------------ worker protocol

    @Transactional
    public ExternalJobDtos.JobBatch fetchAndLock(Caller caller, UUID workspaceId, ExternalJobDtos.FetchRequest request) {
        requireWorker(caller, workspaceId, "fetchExternalJobs");
        Instant now = clock.instant();
        int lease = request.leaseSeconds() == null ? 120 : request.leaseSeconds();
        List<ExternalJobDtos.Job> items = jdbc.sql("""
                WITH picked AS (
                    SELECT j.id FROM external_job j
                    JOIN workflow_run r ON r.workspace_id = j.workspace_id AND r.id = j.run_id
                         AND r.lifecycle IN ('running', 'waiting')
                    WHERE j.workspace_id = :ws AND j.status = 'available' AND j.available_at <= :now AND j.topic IN (:topics)
                      AND NOT EXISTS (SELECT 1 FROM run_suspension s WHERE s.run_id = j.run_id)
                    ORDER BY j.available_at, j.id LIMIT :limit FOR UPDATE OF j SKIP LOCKED)
                UPDATE external_job j SET status = 'locked', worker_id = :worker, fence = j.fence + 1, attempt = j.attempt + 1,
                       lease_expires_at = CAST(:now AS timestamptz) + make_interval(secs => GREATEST(30, LEAST(:lease, j.lease_seconds)))
                FROM picked, workflow_run r
                WHERE j.id = picked.id AND r.workspace_id = j.workspace_id AND r.id = j.run_id
                RETURNING j.id, j.topic, j.input::text AS input, j.fence, j.lease_expires_at, j.attempt, j.max_attempts,
                          j.node_id, r.reference
                """)
                .param("ws", workspaceId).param("now", Sql.ts(now)).param("topics", request.topics())
                .param("limit", request.maxJobs() == null ? 1 : request.maxJobs()).param("worker", request.workerId())
                .param("lease", lease)
                .query((rs, n) -> new ExternalJobDtos.Job(Sql.uuid(rs, "id"), rs.getString("topic"),
                        json.parse(rs.getString("input")), rs.getInt("fence"), Sql.instant(rs, "lease_expires_at"),
                        rs.getInt("attempt"), rs.getInt("max_attempts"), rs.getString("reference"), rs.getString("node_id")))
                .list();
        return new ExternalJobDtos.JobBatch(items);
    }

    @Transactional
    public ExternalJobDtos.ExtendResult extendLock(Caller caller, UUID workspaceId, UUID jobId,
            ExternalJobDtos.ExtendRequest request) {
        requireWorker(caller, workspaceId, "extendExternalJobLock");
        // Serialize with handoff before checking its registration in a fresh statement snapshot.
        lockJob(workspaceId, jobId, request.workerId(), request.fence());
        Instant now = clock.instant();
        Optional<Instant> extended = jdbc.sql("""
                UPDATE external_job SET lease_expires_at = CAST(:now AS timestamptz)
                       + make_interval(secs => GREATEST(30, LEAST(:lease, lease_seconds)))
                WHERE workspace_id = :ws AND id = :id AND status = 'locked' AND worker_id = :worker AND fence = :fence
                  AND lease_expires_at > :now
                  AND NOT EXISTS (SELECT 1 FROM external_job_callback c WHERE c.job_id = external_job.id
                                  AND c.fence = external_job.fence)
                RETURNING lease_expires_at
                """)
                .param("ws", workspaceId).param("id", jobId).param("worker", request.workerId())
                .param("fence", request.fence()).param("now", Sql.ts(now))
                .param("lease", request.leaseSeconds() == null ? 120 : request.leaseSeconds())
                .query((rs, n) -> Sql.instant(rs, "lease_expires_at")).optional();
        if (extended.isEmpty()) {
            throw lost(workspaceId, jobId);
        }
        return new ExternalJobDtos.ExtendResult(extended.get());
    }

    /** Register before submitting to a provider; persist the returned ID in the gateway's dispatch outbox. */
    @Transactional
    public ExternalJobDtos.CallbackRegistration awaitCallback(Caller caller, UUID workspaceId, UUID jobId,
            String idempotencyKey, ExternalJobDtos.AwaitCallbackRequest request) {
        requireWorker(caller, workspaceId, "awaitExternalJobCallback");
        var claim = callbackClaim(caller, workspaceId, jobId, null, "awaitExternalJobCallback", idempotencyKey, request);
        if (claim.isReplay()) {
            Callback prior = callback(caller, workspaceId, jobId, UUID.fromString(claim.replay().resourceId()));
            return new ExternalJobDtos.CallbackRegistration(prior.id(), prior.deadline());
        }
        Job job = lockJob(workspaceId, jobId, request.workerId(), request.fence());
        RunStore.RunRow run = runs.lock(workspaceId, job.runId()).orElseThrow(ApiException::notFound);
        requireRunning(run);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Instant deadline = now.plusSeconds(request.timeoutSeconds());
        jdbc.sql("""
                INSERT INTO external_job_callback (id, workspace_id, job_id, fence, worker_id, subject_id, deadline, created_at)
                VALUES (:id, :ws, :job, :fence, :worker, :subject, :deadline, :now)
                """).param("id", id).param("ws", workspaceId).param("job", jobId).param("fence", job.fence())
                .param("worker", request.workerId()).param("subject", caller.subjectId())
                .param("deadline", Sql.ts(deadline)).param("now", Sql.ts(now)).update();
        jdbc.sql("UPDATE external_job SET lease_expires_at = :deadline WHERE workspace_id = :ws AND id = :job")
                .param("deadline", Sql.ts(deadline)).param("ws", workspaceId).param("job", jobId).update();
        events.append(workspaceId, run.id(), "EXTERNAL_JOB_CALLBACK_REGISTERED", job.nodeId(), caller.subjectId(),
                Map.of("deadline", deadline.toString()));
        audit.write(new AuditWriter.Entry(workspaceId, actor(caller), caller.subjectId(),
                "externaljob.callback-registered", "externalJob", jobId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("nodeId", job.nodeId(), "attempt", job.attempt())));
        idempotency.complete(claim, "externalJobCallback", id.toString(), 200);
        return new ExternalJobDtos.CallbackRegistration(id, deadline);
    }

    @Transactional
    public void completeCallback(Caller caller, UUID workspaceId, UUID jobId, UUID callbackId, String key,
            ExternalJobDtos.CallbackResult request) {
        requireWorker(caller, workspaceId, "completeExternalJobCallback");
        Callback registration = callback(caller, workspaceId, jobId, callbackId);
        complete(caller, workspaceId, jobId, key,
                new ExternalJobDtos.CompleteRequest(registration.workerId(), registration.fence(), request.result()),
                callbackId);
    }

    @Transactional
    public void failCallback(Caller caller, UUID workspaceId, UUID jobId, UUID callbackId, String key,
            ExternalJobDtos.CallbackFailure request) {
        requireWorker(caller, workspaceId, "failExternalJobCallback");
        Callback registration = callback(caller, workspaceId, jobId, callbackId);
        // A remote failure can follow a partial effect. Only an operator may authorize another dispatch.
        fail(caller, workspaceId, jobId, key,
                new ExternalJobDtos.FailRequest(registration.workerId(), registration.fence(), request.message(), false, null),
                callbackId);
    }

    private record Callback(UUID id, String workerId, int fence, Instant deadline) {
    }

    private Callback callback(Caller caller, UUID workspaceId, UUID jobId, UUID callbackId) {
        return jdbc.sql("""
                SELECT id, worker_id, fence, deadline FROM external_job_callback
                WHERE workspace_id = :ws AND job_id = :job AND id = :id AND subject_id = :subject
                """).param("ws", workspaceId).param("job", jobId).param("id", callbackId)
                .param("subject", caller.subjectId())
                .query((rs, n) -> new Callback(Sql.uuid(rs, "id"), rs.getString("worker_id"), rs.getInt("fence"),
                        Sql.instant(rs, "deadline"))).optional().orElseThrow(ApiException::notFound);
    }

    private IdempotencyService.Claim callbackClaim(Caller caller, UUID workspaceId, UUID jobId, UUID callbackId,
            String operation, String key, Object request) {
        return idempotency.claim(workspaceId, caller.subjectId(), operation, IdempotencyService.requireKey(key),
                idempotency.requestHash(operation, Map.of("workspaceId", workspaceId.toString(), "jobId", jobId.toString(),
                        "callbackId", callbackId == null ? "" : callbackId.toString()), request));
    }

    @Transactional
    public void complete(Caller caller, UUID workspaceId, UUID jobId, String idempotencyKey,
            ExternalJobDtos.CompleteRequest request) {
        complete(caller, workspaceId, jobId, idempotencyKey, request, null);
    }

    private void complete(Caller caller, UUID workspaceId, UUID jobId, String idempotencyKey,
            ExternalJobDtos.CompleteRequest request, UUID callbackId) {
        requireWorker(caller, workspaceId, "completeExternalJob");
        String key = IdempotencyService.requireKey(idempotencyKey);
        String hash = idempotency.requestHash("completeExternalJob",
                Map.of("workspaceId", workspaceId.toString(), "jobId", jobId.toString()), request);
        IdempotencyService.Claim claim = callbackId == null
                ? idempotency.claim(workspaceId, caller.subjectId(), "completeExternalJob", key, hash)
                : callbackClaim(caller, workspaceId, jobId, callbackId, "completeExternalJobCallback", key, request);
        if (claim.isReplay()) {
            return;
        }
        Job job = lockJob(workspaceId, jobId, request.workerId(), request.fence(), callbackId);
        RunStore.RunRow run = runs.lock(workspaceId, job.runId()).orElseThrow(ApiException::notFound);
        requireRunning(run);
        ObjectNode result = validateResult(workspaceId, run, job, request.result());
        Instant now = clock.instant();
        endJob(workspaceId, jobId, "completed", null, now);
        runs.mutate(workspaceId, run.id(), m -> {
            m.removeStep(job.nodeId());
            m.settle();
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("topic", job.topic());
        data.put("attempt", job.attempt());
        events.append(workspaceId, run.id(), "EXTERNAL_JOB_COMPLETED", job.nodeId(), null, data);
        try {
            engine.resumeChild(run.engineProcessInstanceId(), job.nodeId(),
                    Map.of(ProcessData.stepVariable(job.nodeId()), json.write(result)));
        } catch (EngineCommands.EngineConflictException e) {
            throw new ApiException(ProblemCode.RUN_BUSY, "The request is busy; try completing the job again shortly.")
                    .withHeader("Retry-After", "2");
        }
        audit.write(new AuditWriter.Entry(workspaceId, actor(caller), caller.subjectId(),
                "externaljob.completed", "externalJob", jobId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("topic", job.topic(), "nodeId", job.nodeId(), "attempt", job.attempt())));
        idempotency.complete(claim, "externalJob", jobId.toString(), 200);
    }

    @Transactional
    public void fail(Caller caller, UUID workspaceId, UUID jobId, String idempotencyKey, ExternalJobDtos.FailRequest request) {
        fail(caller, workspaceId, jobId, idempotencyKey, request, null);
    }

    private void fail(Caller caller, UUID workspaceId, UUID jobId, String idempotencyKey,
            ExternalJobDtos.FailRequest request, UUID callbackId) {
        requireWorker(caller, workspaceId, "failExternalJob");
        String key = IdempotencyService.requireKey(idempotencyKey);
        String hash = idempotency.requestHash("failExternalJob",
                Map.of("workspaceId", workspaceId.toString(), "jobId", jobId.toString()), request);
        IdempotencyService.Claim claim = callbackId == null
                ? idempotency.claim(workspaceId, caller.subjectId(), "failExternalJob", key, hash)
                : callbackClaim(caller, workspaceId, jobId, callbackId, "failExternalJobCallback", key, request);
        if (claim.isReplay()) {
            return;
        }
        Job job = lockJob(workspaceId, jobId, request.workerId(), request.fence(), callbackId);
        RunStore.RunRow run = runs.lock(workspaceId, job.runId()).orElseThrow(ApiException::notFound);
        requireRunning(run);
        String message = truncate(request.message());
        boolean retry = Boolean.TRUE.equals(request.retryable()) && job.attempt() < job.maxAttempts();
        Instant now = clock.instant();
        if (retry) {
            int delay = request.retryAfterSeconds() == null ? job.retryDelaySeconds() : request.retryAfterSeconds();
            jdbc.sql("""
                    UPDATE external_job SET status = 'available', worker_id = NULL, lease_expires_at = NULL,
                           last_error = :error, available_at = :at
                    WHERE workspace_id = :ws AND id = :id
                    """)
                    .param("error", message).param("at", Sql.ts(now.plusSeconds(delay))).param("ws", workspaceId)
                    .param("id", jobId).update();
            events.append(workspaceId, run.id(), "EXTERNAL_JOB_RETRY_SCHEDULED", job.nodeId(), null,
                    Map.of("topic", job.topic(), "attempt", job.attempt(), "message", message));
        } else {
            exhaust(workspaceId, run, job, message, now);
        }
        audit.write(new AuditWriter.Entry(workspaceId, actor(caller), caller.subjectId(),
                "externaljob.failed", "externalJob", jobId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("topic", job.topic(), "nodeId", job.nodeId(), "attempt", job.attempt())));
        idempotency.complete(claim, "externalJob", jobId.toString(), 200);
    }

    // ------------------------------------------------------------------ recovery

    /**
     * Returns the jobs whose lease ran out to the queue, or fails them when attempts are used up. Rows are locked with
     * SKIP LOCKED so several instances can sweep at once. @return the number of jobs recovered
     */
    @Transactional
    public int recoverExpired() {
        Instant now = clock.instant();
        List<Job> expired = jdbc.sql("""
                SELECT id, workspace_id, run_id, node_id, topic, attempt, max_attempts, retry_delay_seconds, fence
                FROM external_job WHERE status = 'locked' AND lease_expires_at < :now
                ORDER BY lease_expires_at LIMIT 50 FOR UPDATE SKIP LOCKED
                """)
                .param("now", Sql.ts(now)).query((rs, n) -> expiredJob(rs)).list();
        for (Job job : expired) {
            RunStore.RunRow run = runs.lock(job.workspaceId(), job.runId()).orElse(null);
            if (run == null || !run.isActive()) {
                endJob(job.workspaceId(), job.id(), "cancelled", "The request ended while the job was leased.", now);
            } else if (hasCallback(job.workspaceId(), job.id(), job.fence())) {
                exhaust(job.workspaceId(), run, job,
                        "The asynchronous provider did not report a result before its deadline. Check the remote outcome before retrying.", now);
            } else if (job.attempt() < job.maxAttempts()) {
                jdbc.sql("""
                        UPDATE external_job SET status = 'available', worker_id = NULL, lease_expires_at = NULL,
                               last_error = 'The worker did not finish before its lease ran out.', available_at = :at
                        WHERE workspace_id = :ws AND id = :id
                        """)
                        .param("at", Sql.ts(now)).param("ws", job.workspaceId()).param("id", job.id()).update();
                events.append(job.workspaceId(), run.id(), "EXTERNAL_JOB_RETRY_SCHEDULED", job.nodeId(), null,
                        Map.of("topic", job.topic(), "attempt", job.attempt(),
                                "message", "The worker did not finish before its lease ran out."));
            } else {
                exhaust(job.workspaceId(), run, job, "The worker stopped responding before the job was done.", now);
            }
        }
        return expired.size();
    }

    /** An operator retries a request whose worker job ran out of attempts; true when there was such a job. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean retryFailed(UUID workspaceId, UUID runId) {
        int reset = jdbc.sql("""
                UPDATE external_job SET status = 'available', attempt = 0, worker_id = NULL, lease_expires_at = NULL,
                       ended_at = NULL, available_at = :now
                WHERE workspace_id = :ws AND run_id = :run AND status = 'failed'
                  AND id = (SELECT id FROM external_job WHERE workspace_id = :ws AND run_id = :run AND status = 'failed'
                            ORDER BY ended_at DESC LIMIT 1)
                """)
                .param("ws", workspaceId).param("run", runId).param("now", Sql.ts(clock.instant())).update();
        return reset > 0;
    }

    /** True when the request's attention was raised by a worker job that ran out of attempts. */
    @Transactional(readOnly = true)
    public boolean hasFailedJob(UUID workspaceId, UUID runId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM external_job WHERE workspace_id = :ws AND run_id = :run AND status = 'failed')
                """).param("ws", workspaceId).param("run", runId).query(Boolean.class).single());
    }

    // ------------------------------------------------------------------ helpers

    private record Job(UUID id, UUID workspaceId, UUID runId, String nodeId, String topic, int attempt, int maxAttempts,
            int retryDelaySeconds, int fence, String status, String workerId) {
    }

    private Job expiredJob(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Job(Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id"), Sql.uuid(rs, "run_id"), rs.getString("node_id"),
                rs.getString("topic"), rs.getInt("attempt"), rs.getInt("max_attempts"), rs.getInt("retry_delay_seconds"),
                rs.getInt("fence"), null, null);
    }

    private static AuditWriter.ActorType actor(Caller caller) {
        return caller.machine() ? AuditWriter.ActorType.SERVICE : AuditWriter.ActorType.USER;
    }

    private void requireWorker(Caller caller, UUID workspaceId, String operation) {
        var access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.JOB_WORK, operation, true);
    }

    private boolean hasCallback(UUID workspaceId, UUID jobId, int fence) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM external_job_callback WHERE workspace_id = :ws AND job_id = :id AND fence = :fence)")
                .param("ws", workspaceId).param("id", jobId).param("fence", fence).query(Boolean.class).single();
    }

    /** Locks the job row and checks that the caller still holds the lease it claims. */
    private Job lockJob(UUID workspaceId, UUID jobId, String workerId, int fence) {
        return lockJob(workspaceId, jobId, workerId, fence, null);
    }

    private Job lockJob(UUID workspaceId, UUID jobId, String workerId, int fence, UUID callbackId) {
        Job job = jdbc.sql("""
                SELECT id, workspace_id, run_id, node_id, topic, attempt, max_attempts, retry_delay_seconds, fence, status,
                       worker_id FROM external_job WHERE workspace_id = :ws AND id = :id FOR UPDATE
                """)
                .param("ws", workspaceId).param("id", jobId)
                .query((rs, n) -> new Job(Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id"), Sql.uuid(rs, "run_id"),
                        rs.getString("node_id"), rs.getString("topic"), rs.getInt("attempt"), rs.getInt("max_attempts"),
                        rs.getInt("retry_delay_seconds"), rs.getInt("fence"), rs.getString("status"), rs.getString("worker_id")))
                .optional().orElseThrow(ApiException::notFound);
        if (!"locked".equals(job.status()) || !workerId.equals(job.workerId()) || fence != job.fence()) {
            throw new ApiException("locked".equals(job.status()) || "available".equals(job.status())
                    ? ProblemCode.JOB_LEASE_LOST : ProblemCode.JOB_NOT_OPEN,
                    "You no longer hold this job. Another worker may have it, or it has ended.");
        }
        Instant expires = jdbc.sql("SELECT lease_expires_at FROM external_job WHERE workspace_id = :ws AND id = :id")
                .param("ws", workspaceId).param("id", jobId).query((rs, n) -> Sql.instant(rs, "lease_expires_at")).single();
        if (!expires.isAfter(clock.instant()) || (callbackId == null && hasCallback(workspaceId, jobId, fence))) {
            throw new ApiException(ProblemCode.JOB_LEASE_LOST,
                    "This lease expired or was handed off to an asynchronous callback.");
        }
        return job;
    }

    private ApiException lost(UUID workspaceId, UUID jobId) {
        boolean exists = Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM external_job WHERE workspace_id = :ws AND id = :id)")
                .param("ws", workspaceId).param("id", jobId).query(Boolean.class).single());
        return exists ? new ApiException(ProblemCode.JOB_LEASE_LOST,
                "You no longer hold this job. Another worker may have it, or it has ended.") : ApiException.notFound();
    }

    private void requireRunning(RunStore.RunRow run) {
        if (!run.isActive()) {
            throw new ApiException(ProblemCode.JOB_NOT_OPEN, "The request has ended.");
        }
        if (suspensions.isSuspended(run.workspaceId(), run.id())) {
            throw new ApiException(ProblemCode.RUN_SUSPENDED, "The request is suspended; try again after it resumes.");
        }
    }

    private void endJob(UUID workspaceId, UUID jobId, String status, String error, Instant now) {
        jdbc.sql("""
                UPDATE external_job SET status = :status, worker_id = NULL, lease_expires_at = NULL, input = '{}'::jsonb,
                       last_error = :error, ended_at = :now
                WHERE workspace_id = :ws AND id = :id
                """)
                .param("status", status).param("error", error).param("now", Sql.ts(now)).param("ws", workspaceId)
                .param("id", jobId).update();
    }

    /** Attempts are used up (or the worker said the failure is final): the request needs an operator. */
    private void exhaust(UUID workspaceId, RunStore.RunRow run, Job job, String message, Instant now) {
        // The input stays until an operator decides, so a retry can run the same job again.
        jdbc.sql("""
                UPDATE external_job SET status = 'failed', worker_id = NULL, lease_expires_at = NULL, last_error = :error,
                       ended_at = :now
                WHERE workspace_id = :ws AND id = :id
                """)
                .param("error", message).param("now", Sql.ts(now)).param("ws", workspaceId).param("id", job.id()).update();
        ObjectNode attention = json.object();
        attention.put("reason", "RETRIES_EXHAUSTED");
        attention.put("nodeId", job.nodeId());
        attention.putNull("effectId");
        attention.put("message", hasCallback(job.workspaceId(), job.id(), job.fence())
                ? "The asynchronous work did not complete. Check the remote outcome before authorizing another dispatch."
                : "The external worker could not complete the work after " + job.attempt()
                        + (job.attempt() == 1 ? " attempt." : " attempts."));
        attention.put("since", now.toString());
        runs.mutate(workspaceId, run.id(), m -> {
            m.lifecycle = RunStore.NEEDS_ATTENTION;
            m.attention = attention;
        });
        events.append(workspaceId, run.id(), "EXTERNAL_JOB_FAILED", job.nodeId(), null,
                Map.of("topic", job.topic(), "attempt", job.attempt(), "message", message));
        events.append(workspaceId, run.id(), "RUN_NEEDS_ATTENTION", job.nodeId(), null, Map.of("reason", "RETRIES_EXHAUSTED"));
    }

    /** The result must be exactly the declared fields with the declared types. */
    private ObjectNode validateResult(UUID workspaceId, RunStore.RunRow run, Job job, Map<String, JsonNode> result) {
        JsonNode node = releases.require(workspaceId, run.releaseId()).node(job.nodeId());
        List<ApiException.FieldError> errors = new ArrayList<>();
        ObjectNode out = json.object();
        java.util.Set<String> declared = new java.util.HashSet<>();
        if (node != null) {
            for (JsonNode field : node.path("config").path("result")) {
                String name = field.path("name").asString();
                declared.add(name);
                JsonNode value = result.get(name);
                if (value == null || value.isNull()) {
                    if (field.path("required").asBoolean(false)) {
                        errors.add(new ApiException.FieldError("/result/" + name, "required", "This result field is required."));
                    }
                    continue;
                }
                if (!matches(field.path("type").asString(), value)) {
                    errors.add(new ApiException.FieldError("/result/" + name, "type",
                            "Expected " + field.path("type").asString() + "."));
                    continue;
                }
                out.set(name, value);
            }
        }
        for (String name : result.keySet()) {
            if (!declared.contains(name)) {
                errors.add(new ApiException.FieldError("/result/" + name, "additionalProperties",
                        "This step does not declare that result field."));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The result does not match what the step declares.")
                    .withFieldErrors(errors);
        }
        return out;
    }

    private static boolean matches(String type, JsonNode value) {
        return switch (type) {
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "date" -> value.isString() && isDate(value.asString());
            default -> value.isString();
        };
    }

    private static boolean isDate(String text) {
        try {
            LocalDate.parse(text);
            return text.length() == 10;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static String truncate(String message) {
        String clean = message.replaceAll("\\p{Cntrl}", " ").trim();
        return clean.length() <= MESSAGE_LIMIT ? clean : clean.substring(0, MESSAGE_LIMIT);
    }
}
