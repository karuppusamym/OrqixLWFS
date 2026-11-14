# S152 - AI reviewer on approval steps

An approval step can name an AI skill that reviews each of its tasks and approves or rejects it instead of a person,
when the answer is confident and the step's autonomy allows it. Otherwise the task stays with people and shows the
suggestion. Builds on the AI task step of [S134](S134-ai-decisions.md) (skills, guardrail pipeline, calibrated
confidence, autonomy policy, durable job queue, decision records) and doc 29 section 7.3 (`recommend`: "suggested
outcome and rationale shown to the approver") and 7.6 (autonomy levels). Closes the S134 gap "an approval that follows
an AI task does not record the human outcome by itself" for approvals with an AI reviewer.

Flyway: V460 only (V461-V469 unused). OpenAPI: `TaskDetail.aiReview` (new schema `TaskAiReview`),
`TaskDecision.decidedBy` and `TaskDecision.aiReview`; additive only. Compiler version not bumped: the BPMN of an approval
changes only when it has an AI reviewer, so no golden file moves.

## 1. Design decisions

1. **Where the reviewer lives.** `approval.config.aiReviewer = { skill: {key, version}, autonomy (suggest |
   autoWithReview | auto, default suggest), minConfidence (default 0.85), reviewSamplePercent (default 10, autoWithReview
   only), inputs (skill input name to field reference, the aiTask mapping shape), redaction }`. The version is required:
   the designer pins it (the Studio picker fills in the approved version). There is no `shadow` level: shadow evidence is
   collected with an AI task step (S134); a reviewer that never acts would only cost money.
2. **Which skills.** An approved `classify` skill whose labels include `approve` and `reject`, or a `recommend` skill
   whose outcomes include them. Other labels or outcomes (for example `escalate`) are allowed and always go to a person
   (`heldFor: notADecision`).
3. **Refused in v1.** Several approvers (`multiInstance`), one approval per list item (`perItem`) and steps with fields
   the decider must fill in (`fieldAccess: required` or required `capture` fields): one AI answer cannot stand for
   several people, items or typed values. Optional captured fields stay empty when AI decides.
4. **Same queue as AI tasks.** A review is an `ai_task_job` with `purpose = 'review'`, the task id and the task version
   it saw. Lease, fence, sealed input, turn state, recovery, deadlines, metering and the provider SPI are reused
   unchanged. The guardrail pipeline (`AiTaskEngine`) gets `TaskRequest.review`: a classify label or a recommend outcome
   of `approve` or `reject` is routed like a classify label (`label:approve` / `label:reject`); admission, minimization,
   calibration, effective autonomy (promotion ceiling, drift fallback, `allowAutoWithReview`) and the review sample are
   the S134 rules.
5. **When AI decides.** Route `label:approve|reject` only when: calibrated confidence >= threshold; effective autonomy is
   `autoWithReview` or `auto`; not an adverse answer in a regulated workspace (classify: the label's `adverse` flag;
   recommend: `reject` counts as adverse); for `auto`, the answer is listed as `skillKey:approve` or `skillKey:reject` in
   the policy's low-risk labels. Anything else is a suggestion (`heldFor`: lowConfidence, suggest, notADecision,
   adverseRegulated, notLowRisk, notPromoted, drift, policy).
6. **The person always wins.** Completion of a review job locks the request first (like a person's decision), then the
   job (fence), then the task. The agent decides only when the task is open, not suspended, not sent back to the
   requester, and still at the version the job saw. A person who claimed, corrected, was reassigned or changed the
   priority since then keeps the task; the answer is attached as a suggestion (`heldFor: personActed`). A task dispatched
   to a person when it was created is still reviewed (the assignment happened before the job saw the version). A person
   who decides first cancels the job in the decision transaction; the worker's completion then finds the job ended and
   changes nothing (no record, no event). `task_decision` stays unique per task, so nobody decides twice.
7. **Who the AI reviewer is.** Not a subject. `task_decision.actor_subject_id` is null, `actor_kind = 'aiReviewer'`, with
   the skill key, version and AI decision record. The audit actor type is the new `agent` (no subject), action
   `task.decided-by-ai`. The API keeps `TaskDecision.actor` required: AI decisions carry the fixed reference
   `{id: 00000000-0000-4000-8000-0000000a1001, displayName: "AI reviewer"}` and `decidedBy: "aiReviewer"`, so clients
   that read `actor` keep working (making `actor` nullable would be a breaking change under docs/api-compatibility.md).
8. **Never self-approval, never separation of duties.** The AI reviewer has no subject, so it never matches the
   initiator (no `selfApprovalAllowed` flag) and never appears in `separatedFrom` checks: a later step separated from
   an AI-decided step can be decided by any eligible person, and an AI decision never lets a person bypass a separation
   (it does not decide on anyone's behalf). AI-decided tasks never appear in anyone's completed list.
9. **Reason and outputs.** The reason is the model's rationale (recommend) or its reasons joined with "; " (classify),
   cut to 1,000 characters, never blank (a rejection needs a reason). Step outputs: `decision`, `reason`,
   `decidedBy: "ai-reviewer"`, `decidedAt`, `aiReview {skill, version, confidence, threshold, decisionId}`.
10. **Human outcome.** When a person decides a task whose AI review answered, the decision record gets
    `accepted` (same action) or `overridden` (value = the person's action, reason = the person's reason) in the same
    transaction; an override becomes a pending evaluation candidate exactly like an explicit S134 override.
11. **Visibility.** `TaskDetail.aiReview` is returned to everyone who can see the task; the recommendation, rationale
    and reasons only to callers who can see the request data (the same rule as `request.input`). Run events carry the
    answer and confidence but never the rationale (it may quote request data).

## 2. Runtime

- `RuntimeProjectionListener.onTaskCreated` (not for list items) resolves the reviewer's inputs from the task's
  variables and calls `AiReviews.queue` -> `AiTaskJobs.publishReview` in the task-creation transaction (sealed input,
  event `AI_REVIEW_QUEUED`). The approval stays the current step.
- `AiTaskWorker` runs review jobs like AI tasks. `AiTaskJobs.complete` branches to `completeReview`: decision record
  (now with `task_id`), job end, then `AiReviews.settle`.
- `AiReviews.settle`: a deciding route calls `TaskService.decideAsAgent` (version bump, `task_decision`, step outputs,
  run step removal, `TASK_DECIDED_BY_AI`, `engine.complete`, AGENT audit, metrics); a busy engine rolls the completion
  back and the worker retries (S134). Otherwise `AI_REVIEW_SUGGESTED` (answer, confidence, `heldFor`) or
  `AI_REVIEW_UNAVAILABLE` (reason). The kill switch (retiring the version), a missing evaluation run, budget, residency
  or provider failures end on unavailable, so a person decides.
- `TaskService.decide` calls `AiReviews.personDecided` after inserting the person's decision: cancel the open review job
  and record the human outcome.
- `autoWithReview` decisions are sampled into the existing after-the-fact review queue (`GET /ai-review-queue`).
- The run evidence pack names the decider of an AI-decided step as `AI reviewer (<skill> v<n>)`.

## 3. API

- `TaskDetail.aiReview` (`TaskAiReview`): `status` (pending, suggested, decided, unavailable, cancelled), `skillKey`,
  `skillVersion`, `autonomy`, `effectiveAutonomy`, `recommendation`, `confidence`, `threshold`, `rationale`, `reasons[]`,
  `decidedByAi`, `heldFor`, `unavailableReason`, `decisionId`, `humanOutcome`; null when the step has no reviewer.
- `TaskDecision.decidedBy` (person | aiReviewer) and `TaskDecision.aiReview {skillKey, skillVersion, decisionId}`.
- Run detail: the run's AI decisions (`GET /runs/{id}/ai-decisions`) include review records; the timeline shows the
  new events.

## 4. Compiler rules (`AiReviewerRules`)

| Code | Severity | When |
|---|---|---|
| `WF_AI_REVIEWER_SKILL` | blocker | Unknown skill; not a classify or recommend skill; no `approve` or `reject` label/outcome |
| `WF_AI_SKILL_NOT_APPROVED` | blocker | The pinned version is not approved |
| `WF_AI_EVALUATION_REQUIRED` | blocker | No passing evaluation run on the version's profile within 30 days |
| `WF_AI_AUTONOMY_NOT_PROMOTED` | blocker | Autonomy above the version's promotion ceiling |
| `WF_AI_REVIEWER_UNSUPPORTED` | blocker | Several approvers, a list of items, or required fields at the step |
| `WF_AI_INPUT_MISSING`, `_UNKNOWN`, `_TYPE`, `_OPTIONAL`, `WF_AI_REDACTION_UNKNOWN` | blocker | As for AI tasks (S134), on `aiReviewer/inputs` |
| `WF_AI_AUTO_NO_LOW_RISK`, `WF_AI_REVIEW_SAMPLE_UNUSED` | warning | As for AI tasks |

The release manifest `aiSkills` lists the reviewer's pin with the approval's node id, so the release evidence pack names
it. The BPMN keeps the reviewer configuration as the `aiReviewer` attribute of `orqix:approval` (round trip exact).

## 5. Studio and pages

- Approval inspector, section **AI reviewer**: skill picker (only eligible skills), autonomy (the compiler's ceiling
  message), threshold, review sample, input mapping with redaction (as `AiTaskInspector`); disabled with the reason on
  steps the compiler refuses.
- Task page: **AI reviewer suggests: Approve (0.91)** with rationale, reasons and why AI did not decide; **Use the
  suggestion as my comment** prefills the comment; the person still chooses Approve or Reject. Pending, unavailable and
  cancelled reviews say so. Decision history marks **Decided by AI reviewer**.
- Run page: timeline texts for `TASK_DECIDED_BY_AI`, `AI_REVIEW_QUEUED`, `AI_REVIEW_SUGGESTED`,
  `AI_REVIEW_UNAVAILABLE`; the AI decisions panel shows the rationale and reasons.
- Mock mode: skill `purchase-review` (approve/reject, approved, promoted to autoWithReview); tasks of steps with a
  reviewer show a suggestion (mock runs never call a model and the AI reviewer never decides there).

## 6. Settings

No new settings. `orqix.ai.tasks.*` (worker, threads, lease, attempts, deadline) and the workspace AI policy
(`allowAutoWithReview`, `lowRiskLabels`, `regulated`, residency, retention) apply to reviews as to AI tasks.

## Verification

- Compiler `AiReviewerCompilerTest` (5): compile and manifest pin, BPMN round trip and unchanged BPMN without a
  reviewer; skill checks (unknown, not approved, extract mode, missing reject outcome, recommend with extra outcomes,
  evaluation gate, promotion ceiling); input contract on `aiReviewer/inputs`; refusals (several approvers, required
  field access, required capture; optional capture allowed); review sample warning. Full compiler suite 1232 tests pass.
- Application `AiReviewerFlowTest` (3, real PostgreSQL and Flowable, answers from `AiTaskStub`): confident approve and
  confident reject decided by the AI reviewer (null subject, `aiReviewer` kind, rationale as reason, decision record
  linked to the task, `agent` audit without subject, step outputs, task detail history and `aiReview`, the run
  evidence pack naming the AI reviewer, absent from the person's completed list, no rationale in events); low confidence stays with a person, whose reject is recorded as
  `overridden reject <reason>`; autonomy suggest stays with a person, whose approve is recorded as `accepted`; a person
  who decides while AI is working cancels the job (one decision, no record, no AI event); a person who claims while AI
  is working keeps the task (`personActed`).
- Existing classes re-run: `AiTaskFlowTest`, `DocumentExtractionFlowTest`, `AiApiTest`, `RunsAndTasksTest`,
  `SeparationOfDutiesTest`, `ListDrivenApprovalTest`, `InclusiveAndMultiInstanceTest`, `StepFormRuntimeTest`,
  `ActionableTasksTest`, `TaskDispatchTest`, `ApproverCorrectionTest`, `WorkManagementTest`, `PurchaseScenariosTest`,
  `EvidencePackTest`, `AuditChainTest`, `SecurityMatrixTest`, `ArchitectureTest`: 110 tests pass.
- Full backend `mvnw -o verify` (JDK 21, compiler and application, coverage gate, packaging): BUILD SUCCESS - compiler
  1232 tests, application 1447 (5 skipped). The evidence-pack naming was added after that run and re-run with
  `AiReviewerFlowTest` and `EvidencePackTest` (5 pass).
- Contracts: `contract_check.py` 144 checks pass; Redocly lint valid (76 warnings, unchanged); `sdks/codegen/generate.py
  --check` current; `frontend/src/api/schema.d.ts` regenerated.
- Frontend: `tsc -b`, ESLint, Vitest (new `AiReviewerFields.test.tsx`, `AiReviewPanel.test.tsx`, additions to
  `AiDecisionsPanel.test.tsx` and `events.test.ts`).

## Not covered

- Several approvers, list-driven approvals and steps with required fields (refused by the compiler).
- The simulator and Test tab treat an approval with a reviewer like any approval: scenarios cannot mock the AI
  reviewer's answer yet.
- `/steps/<id>/aiReview` is not declared to the field picker or the compiler's types, so later steps cannot route on
  the AI confidence; `decidedBy` can be read as before.
- A reviewer on a step reached again in a loop reviews each visit; a review job of an earlier visit whose task ended by
  a timer is ended when the next visit queues its job (not when the timer fires).
- A person who opens the task while AI is still working sees "reviewing"; the page does not refresh by itself.
- The workspace exit export and the run retention archive list AI reviewer decisions with an empty actor (their
  file formats were not changed to carry `actor_kind`).
- Live provider accounts, browser end-to-end journeys and the Docker stack were not exercised for this feature.
- The release change record PDF (`docs/release/orqix-change-record-2026-11-10.pdf`) has no generator source in the
  repository and was not regenerated.

## Merge notes

- Flyway V460 (V461-V469 free). **V460 rebuilds the `audit_event.actor_type` check** (`audit_event_actor_type_check`)
  as `user, service, system, agent`: a sibling that adds an actor type must list all four. It also drops `NOT NULL` on
  `task_decision.actor_subject_id` and adds `actor_kind` (check `person | aiReviewer`), `ai_decision_id`,
  `ai_skill_key`, `ai_skill_version` with `task_decision_actor_shape`; `ai_task_job.purpose` (check `aiTask | review`),
  `task_id`, `task_version` with `ai_task_job_review_has_task`; `ai_decision_record.task_id`. No foreign keys (run
  purges delete in table order); no new table, so nothing for `orqix_purge_run`.
- `AuditWriter.ActorType.AGENT`; whitelist +1 `task.decided-by-ai`.
- `TaskQueries.DecisionRow` gains `actorKind`, `aiDecisionId`, `aiSkillKey`, `aiSkillVersion` (only `mapDecision`
  constructs it); `actor` may be null: use `me.equals(d.actor())`, `RuntimeViews.actor(...)` and skip nulls when
  collecting names. `RuntimeDtos.TaskDecision` gains `decidedBy`, `aiReview`; `RuntimeDtos.TaskDetail` gains
  `aiReview` (both constructed in one place each).
- `AiTaskPort.TaskRequest` gains `review` (old 17-argument constructor kept). `AiTaskJobs` and `TaskService` get
  `AiReviews` through setters (constructors unchanged); `RuntimeProjectionListener` gets an `ObjectProvider<AiReviews>`
  constructor argument.
- Compiler: `Node.Approval` gains a 19th component `aiReviewer` (7-argument constructor kept); new `Node.AiReviewer`;
  `AiTaskRules.inputs(...)` is now shared (package-private overload); new rule class `AiReviewerRules` called from
  `Rules` after `AiTaskRules`; schema `approvalNode.config.aiReviewer`.
- Test stub `AiTaskStub`: a classify skill with the labels approve and reject answers `reject` for "decline", else
  `approve`; a recommend answer follows the same rule (other skills unchanged).
- OpenAPI: `TaskAiReview` after `TaskDetail`; regenerate `frontend/src/api/schema.d.ts` and the SDK models after
  merging; `docs/api-changelog.md` regenerated.
