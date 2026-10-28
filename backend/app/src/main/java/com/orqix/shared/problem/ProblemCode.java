package com.orqix.shared.problem;

import java.util.Locale;

/** Stable problem codes (enum in openapi.yaml {@code Problem.code}) with their HTTP status and default title. */
public enum ProblemCode {
    MALFORMED_REQUEST(400, "The request is malformed"),
    IDEMPOTENCY_KEY_REQUIRED(400, "An Idempotency-Key header is required"),
    INVALID_CURSOR(400, "The cursor is invalid"),
    UNAUTHENTICATED(401, "Sign-in required"),
    FORBIDDEN(403, "You are not allowed to do this"),
    CSRF_INVALID(403, "The request could not be verified"),
    SELF_APPROVAL_DENIED(403, "You cannot approve your own request"),
    SEPARATION_OF_DUTIES(403, "You already decided an earlier step of this request"),
    NOT_ELIGIBLE(403, "You are not eligible for this task"),
    NOT_FOUND(404, "Not found"),
    IDEMPOTENCY_KEY_REUSED(409, "The Idempotency-Key was used for a different request"),
    DUPLICATE_KEY(409, "The key is already in use"),
    WORKFLOW_VERSION_CONFLICT(409, "The workflow changed since you loaded it"),
    PROMOTION_APPROVAL_REQUIRED(409, "This workspace requires approval before a release is promoted into it"),
    RELEASE_NOT_ACTIVE(409, "Another release of this workflow is now active"),
    TASK_VERSION_CONFLICT(409, "The task changed since you loaded it"),
    TASK_ALREADY_CLAIMED(409, "The task is already claimed"),
    TASK_NOT_CLAIMED_BY_CALLER(409, "The task is not claimed by you"),
    TASK_NOT_OPEN(409, "The task is no longer open"),
    RUN_NOT_ACTIVE(409, "The run is not active"),
    RUN_HAS_OPEN_TASKS(409, "The case still has open tasks"),
    RUN_BUSY(409, "The run is busy; try again shortly"),
    RUN_NOT_RECOVERABLE(409, "The run cannot be recovered in its current state"),
    RUN_SUSPENDED(409, "The run is suspended; resume it first"),
    RUN_NOT_SUSPENDED(409, "The run is not suspended"),
    RUN_NOT_SUSPENDABLE(409, "The run cannot be suspended in its current state"),
    RUN_NOT_REPAIRABLE(409, "The run has nothing that can be repaired safely"),
    RUN_NOT_MIGRATABLE(409, "The run cannot move to that release"),
    SIGNAL_NOT_AWAITED(409, "Nothing in the request is waiting for that signal"),
    CALLBACK_ALREADY_RESUMED(409, "The wait was already resumed with another request"),
    CALLBACK_NOT_WAITING(409, "The wait is no longer open for this token"),
    JOB_LEASE_LOST(409, "You no longer hold this job"),
    JOB_NOT_OPEN(409, "The job has ended"),
    EFFECT_NOT_RECONCILED(409, "The outcome of the external call must be reconciled first"),
    EFFECT_STATE_CONFLICT(409, "The external call is not in a state that allows this"),
    GROUP_REVISION_CONFLICT(409, "The group changed since you loaded it"),
    CONFIGURATION_VERSION_CONFLICT(409, "The adapter or trigger changed since you loaded it"),
    RESOURCE_IN_USE(409, "This is still in use"),
    STALE_REVISION(412, "The draft changed since you loaded it"),
    PAYLOAD_TOO_LARGE(413, "The request body is too large"),
    VALIDATION_FAILED(422, "Some values are not valid"),
    WORKFLOW_INVALID(422, "The workflow has blocking issues"),
    WARNINGS_NOT_ACKNOWLEDGED(422, "The workflow has warnings that must be acknowledged"),
    NO_ACTIVE_RELEASE(422, "The workflow has no active release"),
    REASON_REQUIRED(422, "A reason is required"),
    CONNECTION_NOT_BOUND(422, "The connection has no binding in this workspace"),
    EGRESS_NOT_ALLOWED(422, "The endpoint is not allowed by the egress policy"),
    OPERATION_NOT_TESTABLE(422, "This operation cannot be tested"),
    PRECONDITION_REQUIRED(428, "An If-Match header is required"),
    QUOTA_EXCEEDED(429, "Too many active runs for this workflow"),
    RATE_LIMITED(429, "Too many requests"),
    DEPENDENCY_UNAVAILABLE(503, "A required service is unavailable; nothing was saved"),
    AI_NOT_CONFIGURED(422, "AI assistance is not configured"),
    AI_MODEL_NOT_ALLOWED(422, "The requested AI model or profile is not allowed"),
    AI_OUTPUT_INVALID(422, "The AI could not produce a valid workflow"),
    AI_PROVIDER_UNAVAILABLE(503, "The AI provider is unavailable; nothing was created"),
    AI_BUDGET_EXCEEDED(429, "The AI budget for this period is used up"),
    SOURCE_INVALID(422, "The uploaded source file cannot be read"),
    MIGRATION_BLOCKED(422, "Imported elements still need a decision"),
    ATTACHMENT_BLOCKED(409, "The file is blocked and cannot be downloaded"),
    ATTACHMENT_LIMIT_REACHED(409, "The request has reached its file limit"),
    /**
     * Unexpected defect. Not in the contract enum (openapi.yaml has no code for 500); reported as a contract gap.
     * The transaction was rolled back, so nothing was committed.
     */
    INTERNAL_ERROR(500, "Something went wrong; nothing was saved");

    private final int status;
    private final String title;

    ProblemCode(int status, String title) {
        this.status = status;
        this.title = title;
    }

    public int status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** {@code https://orqix.example/problems/{code-in-kebab-case}}. */
    public String type() {
        return "https://orqix.example/problems/" + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
