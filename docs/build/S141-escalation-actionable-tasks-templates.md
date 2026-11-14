# S141 - Escalation policies, actionable tasks and workspace step templates (CG-T04, CG-T01, CG-S03)

6 November 2026. Branch `claude/wave-c-tasks-a1ca8b`, based on `claude/camunda-features-gap-a441b7` at `a55eaa31`
(Waves A and B). Plan: [doc 29 §9.5 and §9.8](../29-camunda-gap-implementation-plan.md). Flyway V340-V342 (range
V340-V349 reserved for this slice). Builds on S27 (calendars, overdue notices), S63 (dispatch), S47/S73 (stub
transports) and S133 (package step templates).

## CG-T04 Escalation policies

Approval config `escalationPolicy.levels` (1-5): `afterHours` (1-2160), `action` (`notify`, `reassign`,
`alertOperators`), `groupRef` (a workspace group; required for notify and reassign, refused for alertOperators).
Separate from S27's `escalation` (recurring overdue notices), which is unchanged.

Decisions:

- **Counted from task creation**, in working hours of the step's `calendar` when it has one (the S27
  `BusinessCalendar`), otherwise elapsed hours. Every level is planned durably when the task is created
  (`task_escalation`, one row per level with its due instant), read from the pinned release, so later draft edits do
  not change a running task.
- **The SLA keeps running.** No level changes `due_at`, the time limit timer or the overdue notices.
- **Dispatch rechecks.** The worker (`TaskEscalations`, every 60 s, `orqix.escalations.poll-delay`) takes due rows
  `FOR UPDATE SKIP LOCKED`, then the run and the task without waiting (the decision lock order). A closed task skips
  this and every later level (`taskClosed`); a suspended request postpones the level by an hour.
  - `notify`: in-app notice (kind `taskEscalated`) to the group's members who can currently see the workspace (stored
    groups of their last sign-in, at most 50).
  - `reassign`: the task moves to the group's queue and returns from its assignee, only if at least one person in the
    group is eligible for it *after* the move (candidate group, required skills, `task.act`, self-approval and
    separation of duties, as in S63). Otherwise the level is skipped with `noEligiblePerson` and the task stays.
    Eligible members are told; actionable messages (CG-T01) are queued for them with reason `escalated-<level>`.
  - `alertOperators`: in-app notice to everyone holding `run.operate` (at most 50).
- **Once each.** A level is settled once (`pending` -> `done`/`skipped`, with outcome and recipient count); notices
  are deduplicated per task, level and person. Each settled level writes one `TASK_ESCALATED` run event (level,
  action, group, afterHours, workingTime, outcome, recipients) and one `task.escalated` audit record (system actor).
  Messages and events carry the task label and request reference, never request values.
- **Compiler** (`EscalationPolicyRules`, no version bump, golden files unchanged): `WF_ESCALATION_POLICY_ORDER`
  (strictly later levels), `WF_ESCALATION_POLICY_GROUP` (group missing or given for alertOperators),
  `WF_ESCALATION_POLICY_GROUP_UNBOUND`, `WF_ESCALATION_POLICY_STEP` (list-driven or multi-approver steps; a level acts
  on one task), warning `WF_ESCALATION_POLICY_SAME_QUEUE`. BPMN carries the policy as the `escalationPolicy`
  attribute of `orqix:approval` (like `calendar`), so the BPMN source round trip keeps it.
- **Studio**: approval inspector, Work queues and deadlines → *Escalate when nobody decides* (levels with hours,
  action and group, add/remove, order warning, read-only when published). Timeline text for `TASK_ESCALATED`.

## CG-T01 Actionable tasks in Teams, Slack and email

Settings → Actionable tasks (`workspace.manage-access`): off by default per workspace (no row = off), link validity
1-168 hours (default 48), channels: email (sender, 1-20 exact recipient domains), Teams and Slack (reference of the
secret that signs their interactions; never a value). Chat identities map a Teams AAD object id or Slack member id to
an Orqix person (one each way).

Decisions:

- **Who gets a message.** When a task is created (or moved by an escalation level), one message per person and
  enabled channel is queued: to the assignee when dispatch or a proposal assigned one, otherwise to the eligible people
  of the queue (at most 25). The worker (`TaskActionOutbox`, every 15 s) sends only after rechecking at that moment:
  task open, the person still eligible, nobody else holds it, channel on, email domain approved or chat identity
  mapped; otherwise the message is skipped with a code (`TASK_NOT_OPEN`, `TASK_ALREADY_CLAIMED`, `NOT_ELIGIBLE`,
  `CHANNEL_OFF`, `RECIPIENT_NOT_ALLOWED`, `NO_CHAT_IDENTITY`). Content: task label, workflow, request reference, due
  date and the actions; never request values.
- **Authenticated as the person, two ways.**
  - *Signed, expiring links* (email; also the Open button of cards): `base64url(id, expiry).base64url(HMAC-SHA256(key,
    head | workspace | task | person))`, key derived from the deployment cursor secret with its own label. The
    `task_action_link` row binds the id to one task and one person and allows revocation; the token is never stored.
    Any unknown, tampered, expired or foreign link is 404.
  - *Chat platform identity*: Slack `X-Slack-Signature` (`v0=` HMAC of `v0:timestamp:body`, timestamp within 5 minutes)
    and Teams outgoing-webhook `Authorization: HMAC <base64>`; the platform user must be mapped to a person.
- **Checked exactly like the task API.** The person is rebuilt from the groups of their latest sign-in (as delegation
  and dispatch see them) and the action calls `TaskService.claim/decide` with that caller: eligibility, required
  skills, self-approval, separation of duties, versions, suspension, idempotency and the service's own audit
  (`task.claimed`, `task.decided`, `auth.denied`) are the API's. Deciding a task in the person's queue claims it first
  in the same transaction, like the task page and MCP.
- **Confirmation.** Opening a link changes nothing; the link page posts `confirm: true`. A link decides at most once;
  posting the same decision again replays it (`outcome: replayed`). A chat Approve/Reject stores a 5-minute
  confirmation bound to the person (`task_action_confirmation`; reason text only while pending); the person's Confirm
  executes it once (idempotency key `task-chat:<id>`), again answers "Already done", another person's press answers like
  an unknown one. Claim acts at once. Reject needs the reason box when the step requires a reason.
- **Audit** `task.action-used` in its own transaction whatever the outcome (taskId, channel, action, outcome, linkId,
  confirmationId, errorCode; never reasons or values), `task.action-sent` (system), `task.action-settings-updated`,
  `task.chat-identity-linked`/`-unlinked`.
- **Transport**: `TaskActionTransport` with only `LocalTaskActionStub` (bounded in-memory journal of rendered messages:
  email subject/text/link, Teams Adaptive Card, Slack blocks). No SMTP, Graph/Bot Framework or Slack Web API client is
  linked; live delivery and tenant registration are external, as in S47/S73.
- **Frontend**: Settings tab *Actionable tasks* (switch, link hours, channels, linked accounts, recent messages with
  skip reasons); public page `/act/:token` (task facts, Claim/Approve/Reject, confirmation step, link to the task).

## CG-S03 Workspace step templates

`step_template` + immutable `step_template_version` (step JSON, SHA-256, note, who, when). `workflow.edit` creates,
versions (same step type, `expectedVersion`), renames, shares and archives; `workflow.read` lists (own templates plus
active templates other workspaces of the organization share, read-only there) and reads one with its history. The
stored step keeps type, label, description and config, never id, position or routes, and is checked against the
workflow schema inside a generated one-step workflow (field errors point into `/step`); references are checked where
the template is used, by that workflow's validation. Search (`q`) matches name, description, step type and
*operation* (connection and operation, topic, adapter, decision, skill, workflow, template or group of the step). Audit
`workflow.step-template-created`/`-versioned`/`-updated`.

Studio: *Save as template* in the step panel (name, key, share); palette section *From workspace templates* next to
the S133 package templates, searched with the same text; adding one creates a step of that type with a copy of the
configuration.

## Contract and data

- OpenAPI: one block tagged **TaskActions** (paths before `/workspaces/{workspaceId}/mcp-server`, schemas before
  `McpServer`): `getTaskActionSettings`, `updateTaskActionSettings`, `listChatIdentities`, `linkChatIdentity`,
  `unlinkChatIdentity`, `listTaskActionMessages`, `receiveSlackInteraction`, `receiveTeamsInteraction`,
  `getTaskActionLink`, `useTaskActionLink`, `listStepTemplates`, `createStepTemplate`, `getStepTemplate`,
  `updateStepTemplate`, `addStepTemplateVersion`. New permission qualifiers `chatSignature` and `taskActionLink`; the
  two link operations are matrix-exempt (no session; covered by `ActionableTasksTest`) and the matrix expects 404 for
  any made-up link. Notification kind enum gains `taskEscalated`.
- Workflow schema: approval `escalationPolicy`.
- Flyway: `V340__task_escalation_policies.sql` (notification kind CHECK + `task_escalation`),
  `V341__actionable_tasks.sql` (`task_action_setting`, `task_action_channel`, `chat_identity`, `task_action_message`,
  `task_action_link`, `task_action_confirmation`), `V342__workspace_step_templates.sql`. App-role grants only; no
  permission enum or grantable-permission change.
- Security chain: the Slack/Teams interaction routes and `/api/v1/task-action-links/*` join the stateless inbound
  chain (no session, CSRF off, no ambient credential).

## Tests

- Compiler `EscalationPolicyRulesTest` (4): valid policy with calendar compiles and is in the BPMN; order, missing,
  unknown and forbidden groups; same-queue warning; schema bounds (no levels, unknown action, six levels).
- `TaskEscalationTest` (3, PostgreSQL/Flowable): durable plan at creation; nothing early; notify once despite repeated
  runs; reassign out of a held task to finance with the due date unchanged and the old holder refused; operator alert;
  events and audit per level without values; finance claims and decides; closed task skips every level; reassignment
  with nobody eligible (required skill) stays; working-hour due instants from the calendar.
- `ActionableTasksTest` (7): deliveries to eligible people only, chat only for mapped identities, no request values,
  skip codes, admin-only listing; link view, tampered link 404, unconfirmed 422, stale version 409, decide once as the
  person with the API's own decision and audit, replay, another person's link refused afterwards; a link issued for
  the requester's own request refused with `SELF_APPROVAL_DENIED`; Slack missing/wrong/stale signature 401, unmapped
  user, confirmation then single execution and "Already done"; reject needs a reason, another person's confirmation is
  unknown; Teams claim, stale Slack card refused, confirm; forged Teams HMAC 401; settings and identity permissions,
  422 field errors, 409 version conflict, unlink audit.
- `StepTemplatesApiTest` (2): create without id/position/routes, search by operation, viewer reads but cannot
  create, outsider 404, duplicate key 409, version conflict 409, type change 422, version history, organization
  sharing read-only, archive; schema errors under `/step`, start refused, references checked later.
- `SecurityMatrixTest`: the 15 operations for foreign principals (404, nothing changes; the nine new tables in the
  row-count snapshot). `ArchitectureTest` passes.
- Frontend: `EscalationPolicyFields.test.tsx` (4), `TaskActionsPanel.test.tsx` (3), `TaskActionLinkPage.test.tsx`
  (2), `workspaceTemplates.test.ts` (3), `events.test.ts` (+3 escalation lines); mocks `mocks/taskActions.ts`.

## Results

See "Evidence" below (filled from the runs in this worktree).

## Merge notes

- **Notification kind CHECK**: V340 recreates `notification_kind_check` with every kind on this base plus
  `taskEscalated`. Any sibling migration that adds a kind (reminders, guest forms, schedules...) must list all kinds
  in the last migration that recreates the CHECK; `NotificationService.Kind` gains `TASK_ESCALATED`; the OpenAPI
  `Notification.kind` enum gains `taskEscalated` (regenerate `frontend/src/api/schema.d.ts`).
- No change to the Permission enum, grantable permissions, problem codes or adapter kinds.
- Shared files touched (additive): `AuditWriter` (9 actions), `NotificationService.Kind`, `SecurityConfiguration`
  (two patterns in the inbound chain), `TaskService` (package methods `storedCaller`, `groupMembers`, `operators`),
  `TaskStore.moveToGroup`, `RuntimeProjectionListener` (constructor gains two `ObjectProvider`s; `onTaskCreated` plans
  escalations and queues messages after dispatch), compiler `Node.Approval` (new last component `escalationPolicy`;
  the seven-argument constructor kept), `ModelReader`, `BpmnGenerator`/`BpmnReader` (one attribute each), `Pipeline`
  (one rule call), `workflow-v0.schema.json` (approval `escalationPolicy`), `consistency_gates.py` (two qualifiers,
  two matrix exemptions), `SecurityMatrixTest` (bodies, expected 404 for links, snapshot tables), frontend
  `SettingsPage`, `routes.tsx` (`/act/:token`), `Palette.tsx`, `StudioPage.tsx`, `Inspector.tsx`,
  `WorkManagementFields.tsx`, `lib/events.ts`, `mocks/browser.ts`, `test/server.ts`, `test/setup.ts`,
  `api/endpoints.ts`, `api/types.ts`.
- `contract_check.py` here fails one check that predates this branch: `listRunAiDecisions` and
  `recordAiDecisionOutcome` (Wave B, S134) use the permission token `participant`, which is not in
  `PERMISSION_QUALIFIERS`.

## Not done

- Live SMTP, Teams (Bot Framework / Graph) and Slack Web API delivery, tenant and app registration; the stub keeps
  rendered messages in memory only. Teams outgoing webhooks carry no timestamp, so a replayed Teams request is
  answered idempotently (claim refused, confirm replays) rather than refused by age.
- Self-service chat account linking (administrators link accounts) and a person picker (the person's id is typed).
- Updating or deleting already-sent chat cards when the task ends; links stop working because the task closes.
- Escalation levels relative to the due date, per-level notification through actionable channels other than the
  reassignment case, and escalation of list-driven or multi-approver steps.
- Where-used for step templates and update prompts when a template gets a new version (steps are copies).
