# Product build tracker

Updated 9 October 2026. This is the active tracker for the next build. Existing verified scope is recorded in [feature status](feature-status.md); the broad backlog describes the full product, not a completion claim.

Statuses: **In progress** means implementation is underway; **Implemented** means code exists but final checks remain; **Verified** means the listed checks pass; **Needs external input** identifies acceptance that cannot be established with local fixtures.

| ID | Slice | Status | Completion evidence |
|---|---|---|---|
| WF01 | Bounded child repetition and rework | In progress | Rejected child followed by approval, exhaustion, pinned release, restart, distinct occurrences and immutable history |
| WF02 | Collection iteration | In progress: contract assessment | Bounded sequential/parallel iteration, typed collection input, per-item output and cancellation |
| WF03 | Simple, medium and complex executable examples | In progress | Published examples execute approval/rejection, parallel/timer/child paths and bounded repetition through real Flowable/PostgreSQL |
| MIG01 | Camunda parallel/timer/child import | In progress | Supported BPMN converts, publishes and executes; unsupported semantics remain blockers |
| MIG02 | Pega rule inventory and dependencies | In progress | Bounded XML metadata analysis, provenance, useful reviewed mappings and negative parser tests |
| MIG03 | Native Pega semantic conversion and equivalence | Needs external input | Genuine exports, source versions and expected business scenarios; no universal automatic-conversion claim |
| DOC01 | SharePoint/OneDrive approved document operations | In progress | Stable identifiers, explicit version, bounded content, binding credentials and redirect isolation; scripted Graph tests |
| DOC02 | Real Microsoft tenant acceptance | Needs external input | Tenant/app permissions, approved sites/files and credential provisioning |
| CHAT01 | Conversational request/task execution | In progress | Multi-turn field collection, explicit reviewed submission/decision, existing authorization and idempotency, resume and isolation tests |
| CASE01 | Expanded case/task lifecycle | Planned | Comments, attachments, delegation, reminders, stages and business-calendar deadlines; implement in separate bounded slices |
| OPS01 | Standard image build and browser validation | Planned after integration | Standard build or accurately recorded registry limitation; healthy local stack, real browser checks |
| OPS02 | Production gates | Needs external input | Enterprise identity/integrations, cluster, CI execution, backup/restore and capacity acceptance |

## Execution matrix

| Level | Required behavior | Evidence |
|---|---|---|
| Simple | Request, one approval, rejection and completion | Existing purchase/runtime tests; next slice adds a minimal reusable example |
| Medium | Decisions, matched parallel work, timeout, wait and child results | Existing pattern tests; next slice adds imported and packaged scenarios |
| Complex | Nested pinned children, bounded rework/iteration, integrations and concurrency/restart recovery | New slice must provide execution tests; a complex diagram alone is insufficient |

## Work boundaries

The independent execution, migration, Microsoft document and conversation streams are being built in parallel. Shared API contracts and generated types are integrated centrally before final verification. Customer-specific Pega equivalence, Microsoft access and production acceptance retain explicit external requirements. The user's existing deletion of `design/business-workspace.svg` is preserved.
