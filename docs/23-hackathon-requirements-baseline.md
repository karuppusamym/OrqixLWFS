# Lightweight Workflow Management Engine: hackathon requirements baseline

Revision 1.0 | 17 October 2026 | Revised specification; implementation acceptance pending

Source: the user-supplied “Lightweight Workflow Management Engine — Hackathon Problem Statement, Business Value, Scope & Deliverables” text. The five referenced images were not supplied for this review; fidelity to those images is unverified. This revision resolves specification gaps using explicit engineering defaults. It does not certify implementation, enterprise compliance, dependency approval or business savings.

## 1. Purpose and authority

Provide an application-embedded engine for simple, deterministic workflows and a utility to reconstruct eligible simplified PEGA blueprints. Keep complex case management in PEGA. Target faster configuration changes, reusable controls and lower total operating cost across an indicative portfolio of approximately 40 workflows.

This document defines the **hackathon acceptance profile**, distinct from the historical R0 delivery slice and the broader R1–R3 product roadmap. An existing feature is neither removed nor accepted by this document. For hackathon acceptance, the requirements below take precedence over historical deferrals of calendar-aware SLAs or email notifications. Existing machine-readable contracts continue to describe the implemented interface; a discrepancy requires a tracked implementation change, not an assumption that the requirement already works.

The primary integration user is an application developer. Requesters, approvers, workflow designers, reviewers and operators use the resulting application. Workflow business roles and platform permissions are different concepts.

## 2. Eligibility and scope

### Tier-1 eligibility

Every candidate must have a recorded assessment. All conditions must pass; an unknown answer requires review before migration.

| Criterion | Hackathon default |
|---|---|
| State count | At most 10 distinct executable states in a published definition, including start and terminal states; visual groups and transition edges do not count. A human-task state counts once, irrespective of the number of assignees. |
| Business roles | At most 5 distinct role/group references used for workflow task routing. Platform administrator/designer permissions do not count unless also used as routing roles. |
| Routing | Sequential tasks and mutually exclusive deterministic branches; one default branch; no unresolved/ambiguous routing. |
| Rules | Versioned comparisons of typed fields with literals and bounded AND/OR conditions; no arbitrary code, external rule resolution or adaptive planning. |
| Structure | Acyclic, with one start and at least one terminal state; one active business task per case. Parallel work, child/multi-case hierarchies and unbounded rework are outside this profile. |
| Rule changes | Each policy change creates a reviewed version. Changes requiring runtime rule mutation or unresolved inherited rules are ineligible. |
| Duration | Expected completion within 30 calendar days; longer cases require explicit business/technical review. This triage default never silently expires or deletes a case. |
| Integrations | At most 2 approved business-system operations per path, excluding identity, audit and notification infrastructure. Operations must have defined timeout, failure and replay behavior. |
| Volume | Expected pilot demand must fit the measured capacity envelope in section 8. Higher demand requires a new test and review. |
| Controls | Documented inputs, outcomes, ownership, closure policy, evidence needs and golden scenarios. |
| Regulatory classification | Source/business owner confirms suitability. Regulated lifecycles or special retention/control obligations remain in PEGA until explicitly qualified; no automatic classification. |

These numeric triage defaults are design choices for this profile, not facts recovered from the reference images. Cases failing the profile remain on PEGA. No full BPM, advanced BPMN/CMMN or PEGA parity is promised.

### Delivery priorities

**P0 is mandatory for full hackathon acceptance.** A reduced demonstration must list its omissions and cannot be called complete against this baseline.

| Priority | Required scope |
|---|---|
| P0 | Conversational, human-reviewed workflow creation; versioned definitions; configurable tasks and routing; assignment/reassignment; open-task validation; per-task business-calendar SLAs; stage-specific email templates/recipients; closure policy; append-only audit and export; eligible sample blueprint reconstruction; repository schema/seed data; independently buildable frontend components and host integration; local demonstration and the checks in sections 8–9. |
| P0 control | Host-provided authentication and permissions; deny unauthorized decisions and requester self-approval; detect overdue/stale tasks and notify configured recipients. |
| P1 | User-initiated delegation, recurring/multi-level escalation, dashboards for cycle time/volume/SLA/aging/load and expanded migration formats. Existing implementations may be demonstrated with their own evidence. |
| Production gate | Approved Orchesstra checklist/technology/dependencies, enterprise identity/mail bindings, retention/backup/support decisions, target-environment and workload qualification. These cannot be inferred from a local demonstration. |
| Excluded | Role/entitlement administration UI; complex rules/case planning; active PEGA case/history transfer; universal native PEGA conversion; OCP deployment code as a hackathon deliverable. |

Reassignment is mandatory; delegation is an optional additional action. A staged task queue and export support operational inspection without requiring an analytics dashboard for P0.

## 3. Identity and authorization

The host supplies authenticated identity, workspace context, trusted role/group mappings and action permissions. The engine enforces these on every API, task action and evidence export. Request payloads cannot assert identity or grant membership. A local identity provider and seeded users are acceptable only for the labelled local demo.

Logical permissions are: define/edit, review/publish, start/read-own, claim/decide, reassign, close and audit/export. Bind them to the host's existing permission model rather than adding an entitlement-management product. Missing mappings deny access. Changes to membership take effect on the next protected action; stale assignments do not retain decision authority.

Requesters cannot approve their own cases. Reassignment targets must be eligible in the same workspace and must pass separation-of-duties checks. Denials leave business state unchanged and produce a security event. Production publishers must be distinct from the last editor; local demo personas exercise this rule. Emergency exceptions require an explicit, separately reviewed policy and are not a default bypass.

## 4. Definition, authoring and Git lifecycle

Conversational creation gathers purpose, typed inputs, states, outcomes, role mappings, branch conditions, per-task SLA/calendar, notifications, closure policy and sample cases. It asks for missing required values and produces an editable draft plus validation findings. A deterministic guided question flow is sufficient; a paid model/provider is not required. AI output, when used, is untrusted draft data and cannot publish or execute automatically.

The canonical runtime format is the versioned JSON definition validated by [workflow-v0.schema.json](../contracts/workflow-v0.schema.json), plus explicitly versioned referenced configuration. A schema mismatch or unsupported construct blocks publication. No second undocumented workflow language is introduced by this baseline.

Each release records definition/schema version, content hash, author, reviewer, source Git commit, role bindings, calendar revision, notification templates and integration bindings. No secrets enter Git or exports. Calendar/template configuration not represented in the current schema requires a documented versioned reference contract before acceptance.

Changes pass schema/compiler validation and golden scenarios in a pull request. A reviewer approves before the release artifact is published through CI/CD or an equivalently recorded local review step for the demo. The published artifact hash must match the reviewed Git content. Automatic Git push/promotion is not required.

Published revisions are immutable. Existing cases retain their original definition and referenced configuration; new cases can select the new release. Rollback changes the release used for new starts. In-place active-case migration is excluded. Urgent calendar/policy changes affecting active cases require an explicitly designed audited remediation process, not silent replacement.

## 5. Routing, task lifecycle and closure

Each case and task has a durable ID, version and status. Before a transition, validate the acting user's current authority, expected case/task version, required typed data, predecessor outcome and open-task state. Resolve exactly one eligible next task or a terminal outcome. Zero/multiple eligible routes or an empty eligible group puts the case into visible operator attention; never auto-approve.

Only one decision wins under concurrent submission. Repeating a command with the same idempotency key and payload returns its recorded result; reusing the key with a different payload conflicts. Reassignment records old/new ownership, actor, reason and time without resetting the SLA. Terminal tasks reject later decisions. External calls use bounded retries and stable effect keys; unknown outcomes require reconciliation before retry where the target cannot deduplicate.

The versioned closure policy has two supported modes:

- `REQUIRE_NO_OPEN_TASKS`: reject manual closure while any task remains open; list blockers. This is the default.
- `CANCEL_OPEN_TASKS`: an authorized operator supplies a reason; remaining tasks become cancelled, pending task timers/notifications are invalidated, and the case becomes closed in one consistent transition. Completed decisions remain unchanged.

Normal completion resolves the final task before closing the case. Closure and a competing decision serialize so that one wins and the other receives a conflict. Dispatchers recheck task/case state before sending queued task reminders. Mail already handed to a server cannot be recalled. Already completed external effects are not reversed by closure. Reopening is outside this profile.

## 6. Calendar-aware SLA and overdue behavior

Every task declares a positive SLA in business minutes and an immutable calendar revision. The SLA starts when the task becomes actionable. A calendar has an IANA time zone, working weekdays, non-overlapping local working intervals and full-day holiday dates. Invalid zones, intervals, durations or calendars without reachable working time block publication. The sample uses `America/New_York`, Monday–Friday, 09:00–17:00; its holiday list is explicitly synthetic, not an official jurisdiction calendar.

Accumulate elapsed working minutes within calendar intervals. Outside working time, advance to the next interval; holidays exclude the whole local date. Intervals are start-inclusive/end-exclusive. A due instant exactly at closing time is valid; completion at or before that instant is on time. Store UTC timestamps, the calendar revision and calculated due instant; display local time with its zone. Weekend/holiday skips and daylight-saving boundaries require tests. Reject calendars whose working boundaries are ambiguous or nonexistent in their zone for the supported scheduling horizon, rather than guessing a clock offset.

No SLA pause/resume is included in P0. Time continues through reassignment, blocked integration and operator attention; the UI must state this. Future pause support requires audited intervals and a revised due-time contract. A wait timer or execution timeout retains its separately configured wall-clock semantics.

Once a still-open task passes its due instant, persist one breach event and queue one overdue notification per task/SLA revision. The background detector operates at least once every 60 seconds under the tested healthy load. Stale approval means overdue and unresolved; it does not auto-approve, reject or reassign. The escalation recipient is an explicitly configured supervisor group or operator fallback. Recheck eligibility and closure state at dispatch. Recovery after restart catches overdue tasks without duplicate logical breach/notification records. Recurring escalation is P1.

Example: with Friday 16 October 2026 at 16:00 as the start, an eight-business-hour SLA and no intervening holiday is due Monday 19 October at 16:00 local. Marking 19 October as a synthetic holiday moves the due time to Tuesday 20 October at 16:00 local.

## 7. Notifications and audit evidence

Each task stage configures templates for assignment, completion/rejection and overdue events; the case configures its terminal notification. Recipients are selected from the requester, eligible assignee/group and explicitly mapped supervisor/operator groups. Required empty mappings block publication; unexpectedly empty runtime recipients create visible operator attention. Templates permit only allowlisted escaped fields and authorized application links. They cannot evaluate arbitrary code or disclose restricted fields to broader recipient groups.

Use a transactional outbox so notification intent survives committed state changes and restarts. Retry transient failures with bounded backoff; after 3 delivery attempts, retain a failed item for operator review. Record queued, provider-accepted, failed and suppressed states separately. SMTP acceptance is not evidence that a person received/read mail. Stable message IDs reduce duplicate risk; exactly-once external email delivery is not promised. Notification failure never reverses an approval or implies delivery success.

The local demo uses an SMTP capture service and verifies the generated email; enterprise mail delivery needs an approved binding. In-app-only notifications do not satisfy the stage email requirement.

The application audit stream records event ID, UTC time, actor/service, workspace, case/task IDs, definition/configuration revision, action/outcome, prior/new state or ownership, reason, correlation/idempotency references and source-migration hash where applicable. Append-only means the runtime role cannot update/delete audit entries; it does not mean storage administrators are technically unable to alter the database. Corrections append a new event. Audit and business state commit consistently; notification delivery results append later events.

Authorized users can export ordered JSON/CSV evidence with a manifest identifying release, filters, time range and event count. Secrets and unapproved payloads are excluded. Proposed demo retention is 365 days for audit metadata and 90 days for completed business payload, subject to legal-hold and owner approval before production; these are planning defaults, not a compliance determination. Refer to [security and lifecycle policy](11-security-operations.md) for controlled purge/backup handling. No destructive retention job is required for the hackathon.

## 8. Architecture, compliance, dependencies and quality targets

Keep the engine inside the application boundary with application-owned APIs and PostgreSQL durability. Use the repository's Java/Spring Boot/Flowable and React stack as the existing implementation candidate; [ADR 0001](adr/0001-dependency-baseline.md) records repository version decisions, not enterprise approval. Database migrations and sample master data belong in the repository. Runtime identities cannot execute DDL.

The authoring/task components must have documented host inputs, authentication/context integration, route/event contracts and compatible API versions. Demonstrate separate frontend build artifacts and mounting them in a minimal host. A separate backend or separately administered identity system is not implied by independent frontend deployment. Host-specific packaging requirements remain in the compliance gate.

Interpret “no third-party licensed products” for development as **no additional purchased/proprietary product or subscription dependency**. Open-source dependencies are candidates only when their licenses, notices and vulnerabilities pass the organization's review. This interpretation requires organizer confirmation. Produce locked dependencies and a dependency/SBOM report; omit optional paid AI/services. If all third-party software is literally prohibited, the current stack is unqualified and requires an explicit architecture decision; it cannot be declared compliant by wording alone.

The Orchesstra checklist must identify approved stack/versions, host/component packaging, identity/authorization, data classification/encryption/retention, audit, observability, dependency licensing/security, accessibility and operational ownership. The platform owner supplies the authoritative checklist and evidence expectations. Mark each item Pass/Fail/Not assessed with an evidence reference and reviewer. Until supplied and reviewed, report **Orchesstra compliance: pending**. Do not invent certification criteria.

The following are proposed local acceptance targets, not measured results or production guarantees:

| Area | Target and measurement |
|---|---|
| Load/performance | 50 concurrent authenticated users, 10,000 stored cases, 15-minute steady test after 5-minute warm-up; mixed workload 40% list/detail reads, 20% starts, 30% decisions, 10% reassignment/closure. Enough seeded eligible tasks prevent workload starvation. |
| Latency/errors | p95 under 500 ms for reads and under 1 second for accepted local mutations, measured at API boundary; external execution is asynchronous. Unexpected errors under 1%; deliberate authorization/conflict tests reported separately. Record hardware, dataset, release and response distributions. |
| Correctness | No lost acknowledged state, duplicate accepted decision or duplicate deduplicated external effect in concurrency/restart scenarios. |
| Recovery | Resume durable jobs within 120 seconds of a replacement application instance becoming ready, with the database available. No database-disaster recovery claim. |
| SLA/notification | Breach detection within 60 seconds of due time under healthy tested load; outbox first attempt within 60 seconds of queueing with the capture server available. Test eventual catch-up after outage. |
| Security | All defined cross-workspace, revoked-membership, self-approval, unauthorized export and injection scenarios deny without business-state mutation. No secrets in logs, repository or evidence. |
| Accessibility | Core authoring, inbox, decision and review journeys work by keyboard, have accessible names and pass automated serious/critical issue checks; broader WCAG conformance requires separate review. |
| Availability | No production SLA established by a local demo. Platform owner must set and qualify availability, capacity, backup RPO/RTO and support commitments before production. |

## 9. Traceable acceptance scenarios

Record each outcome as Pass/Fail/Not run, with source commit, artifact digest, configuration, command/script, observed output and evidence link. Existing historical test counts do not qualify the current candidate automatically.

| ID | Scenario | Required result |
|---|---|---|
| H01 | Guided conversation with a missing role, SLA or branch | Requests the missing information; produces an editable draft; cannot publish invalid data or execute directly. |
| H02 | Publish 10-state/5-role definition, then 11-state or 6-role definition | Boundary case succeeds; excess is refused by the hackathon profile before deployment. |
| H03 | Test every exclusive route and an ambiguous/missing route | Expected path matches golden results; unsafe configuration blocks publication or enters attention if runtime data cannot route. |
| H04 | Authorized claim, decision and reassignment; self/foreign/revoked-user attempts | Eligible actions persist with history; denied actions do not change business state; reassignment preserves due time. |
| H05 | Concurrent task decisions and duplicate start/decision commands | One accepted transition; exact retry returns recorded result; changed-payload key reuse conflicts. |
| H06 | Friday/holiday example in section 6; interval end, outside-hours, DST and invalid calendar fixtures | Exact expected UTC/local due instants; invalid/ambiguous calendar boundaries refused. |
| H07 | Advance clock beyond deadline, restart detector and close competing task | One logical breach/overdue notice; detection/catch-up meets section 8; terminal tasks cannot create new task reminders. |
| H08 | Each stage notification plus mail outage/recovery and unsafe template fields | Correct rendered recipients/content in SMTP capture; durable retries/status; forbidden interpolation refused; no false delivery claim. |
| H09 | Close with each closure policy while work is open, including a competing decision | Default refuses and identifies tasks; cancellation policy leaves no actionable open task; losing concurrent action conflicts; full history retained. |
| H10 | Publish a changed definition/calendar/template while an old case is open | Old case retains pinned behavior; new case uses new reviewed release; Git/artifact hashes reconcile. |
| H11 | Runtime audit update/delete attempt and authorized/unauthorized evidence export | Audit mutations denied; authorized export has complete ordered events/manifest; unauthorized export denied. |
| H12 | Reconstruct eligible simplified source and execute approve/reject/boundary/failure cases | Traceable reviewed mapping, valid draft, separate publish action and expected target behavior; unsupported source prevents executable partial output. |
| H13 | Kill application during a task transition/integration and restart | Acknowledged state survives; work recovers within the tested bound; remote effect deduplicates or is explicitly held for reconciliation. |
| H14 | Build frontend components separately and mount in sample host | Authentication/context, navigation and supported API contract work; incompatible version fails visibly. |
| H15 | Fresh local setup from repository and run declared load/security/accessibility checks | Migrations/seeds produce runnable demo; section 8 results and limitations recorded. |
| H16 | Inspect dependency/compliance package and unresolved decisions | SBOM/notices/scan and checklist are present with honest status; unresolved organizational gates are not labelled passed. |

## 10. Blueprint migration contract

Migrate **definitions only**. Exclude running cases, historic decisions, identities, attachments, inherited native rule resolution and automated retirement/deletion in PEGA.

For supplied screenshots/PDF, record checksum, source page/diagram references and a reviewed structured transcription of states, transitions, forms, roles, SLAs and outcomes. Automated image/OCR interpretation is optional; human review of the transcription is mandatory. Missing or unreadable semantics produce findings rather than invented behavior. A structured blueprint can be mapped into a validated canonical draft. Manual transcription/review must be disclosed in the demo.

Where available, the delivered bounded XPDL path and archive-assessment/reconstruction path follow [ADR 0003](adr/0003-minimum-demo-pega-scope.md). Supported items are start/end, sequential human tasks, simple typed inputs and deterministic exclusive routes. Role bindings, integrations, calendars and templates require explicit target mappings and review. Every source element receives a disposition: mapped, manually reconstructed, unsupported or informational. An unsupported executable element blocks publishable conversion until removed by an approved scope change or implemented with equivalence evidence.

Store source hash, importer/mapping revision, findings, source-to-target IDs, review identity and target hash. Validate before creating an executable release; import never auto-publishes. Golden scenarios include approval, rejection, routing threshold, empty owner, deadline and integration failure. Synthetic examples demonstrate target reconstruction only. Genuine exports, source-system results and source/business owner sign-off are required to claim customer-source equivalence or compatibility. No universal native RAP converter is promised.

## 11. Deliverables and business measurement

Check into GitHub: application/compiler/frontend source; dependency locks and reports; schema/migrations/seed master data; canonical workflow/configuration examples; synthetic holiday calendar; email templates; sample source/transcription/mappings/findings; executable acceptance scripts; API/host component contracts; setup/user/operator guides; demo walkthrough; acceptance evidence manifest; decision/compliance register. Keep credentials and sensitive source data out of Git. Supply local run instructions; no new OCP deployment code is required.

Business targets are hypotheses until measured. For each candidate, capture current case volume, eligible share, license metric/contract, actual avoidable platform cost, change lead time and support effort. Measure the same values after adoption.

Annual net benefit = contractually avoidable PEGA license/platform expense + evidenced change/support savings − incremental engine hosting, maintenance, security/support and migration operating costs. Track one-time build/migration cost separately; payback is one-time cost divided by positive annual net benefit. If net benefit is nonpositive, do not report a positive payback. Allocated license cost is not automatically avoidable expense; contracted savings need procurement confirmation.

Report migrated/eligible workflow counts, volume, median/p95 change lead time, cycle time, overdue-task rate and support effort. “Approximately 40 workflows” is a planning portfolio estimate, not an accepted inventory. “Days rather than weeks” is a target measured from approved change request to usable release, not a guarantee. Record baseline and observation window before claiming improvement.

## 12. Remaining external decisions and sign-off

The specification gaps have proposed resolutions; organizational facts below cannot be manufactured. They block only the corresponding qualification claim. Independent development and a clearly labelled synthetic local demo can continue.

| ID | Input / owner | Default while pending | Gate / required evidence |
|---|---|---|---|
| HC01 | Authoritative Orchesstra checklist and approved versions / platform owner | Existing repository stack is a candidate only | Compliance claim: checklist, reviewer and evidence per item. |
| HC02 | Interpretation of third-party licensing / organizer + dependency reviewer | No new paid/proprietary dependency; OSS candidate subject to review | Dependency compliance: written interpretation and reviewed SBOM/licenses. |
| HC03 | Host permission/group and frontend integration contracts / application + identity owners | Local seeded identities and sample host | Enterprise integration: mapped claims/permissions, packaging and denial tests. |
| HC04 | Representative blueprint and business policy / organizer + source/business owners | Synthetic source, explicit transcription and sample policy | Customer migration: genuine source, golden results and semantic sign-off. |
| HC05 | Official calendars, SLA exceptions and mail recipients/transport / business + messaging owners | Synthetic calendar, no pause, one overdue notice, local SMTP capture | Operational adoption: approved revisions/bindings and delivery evidence. |
| HC06 | Retention/legal hold, capacity, availability, backups and support / data + platform owners | Planning defaults in sections 7–8; no production claim | Production: approved policy, restore/load evidence and named support owner. |
| HC07 | Contract terms, inventory and savings baseline / sponsor + procurement | Indicative portfolio and benefit model only | Savings claim: confirmed avoidable costs and measured before/after results. |

Engineering completion requires H01–H16 results, a review of failures/omissions and the exact candidate commit. A reduced demo may be accepted only with an explicit excluded-scope record. Full production and compliance approval additionally require the applicable HC gates. No sign-off is implied by this specification revision.
