# S53 - Swimlanes, YAML source editing and threaded element discussion (UI03)

UI03 delivers three bounded additions to the workflow studio. None of them changes the compiler, the canonical workflow document or the runtime.

## What exists

**Lanes tab (read-only, derived).** The steps are laid out by who or what is responsible: Requester (the request form), one lane per approval group (named from the workspace group list, or the key when unknown), "No group chosen" for an approval without a group, External workers, and Automation (everything else). Steps run left to right in flow order; a draft with a loop still lists every step. Handoffs (connections that cross lanes) are listed under the grid. Choosing a step selects it for the inspector. Lanes are computed from the definition and stored nowhere, so they cannot disagree with the steps.

**Source tab (YAML).** The whole canonical document as YAML (JSON-compatible schema, so `yes`, `null`, dates and `0123` stay text). Workflow: edit, **Check source**, then **Apply to draft**. Apply is blocked unless the text:

- is at most 400,000 characters and nested at most 40 levels;
- parses (syntax errors give a line number) with no duplicate keys, anchors, aliases, merge keys, `__proto__`/`constructor`/`prototype` keys or non-finite numbers;
- has `schemaVersion: "0.1"`, text `workflowKey` equal to the open workflow's key (the identity cannot be changed here), lists for `nodes`/`transitions`/`decisions`, unique step ids and unique connection ids, each step with text id/type/label and each connection with text id/from/to/on;
- survives a **round trip**: the parsed document is serialized and parsed again and must be identical in meaning (key order ignored).

The check also lists what applying would add, remove or change (steps, connections, other sections, canvas layout; at most 30 lines). A banner shows whether the current draft itself converts to YAML and back without difference. Apply replaces the draft in one undoable step; autosave and the normal **Check workflow** (server validation, compile, issues) then run as for any edit, so YAML never bypasses compiler validation. People without `workflow.edit` see the YAML read-only.

**Threaded discussion.** In the studio inspector column, for the selected step or connection:

- one level of replies; threads can be resolved and reopened; the Lanes tab shows "N open threads" on a step;
- read needs `workflow.read`; start, reply, resolve and reopen need `workflow.edit` or `workflow.publish` (so a reviewer/publisher can comment without being able to edit). Viewers see threads and a "read but not add" note;
- a thread targets a step or connection id of the **current saved draft** (the studio saves first; an unknown id is 422). Comments stay readable after the element is later deleted;
- text is 1 to 2000 characters, at most 500 threads per workflow and 100 replies per thread; replies must be on the same element as the thread and cannot nest; replies cannot be resolved separately;
- comments are review notes: they never change the draft or revision, grant anything or block publishing. Audit events `workflow.comment.created|resolved|reopened` record the comment id only, never its text. Non-members of the workspace get 404, start-only requesters 403/404, and a comment ID is only addressable inside its own workflow.

## Data and API

Migration `V60__element_discussion.sql` adds `workflow_element_comment` (composite foreign keys keep a comment inside its workspace and workflow, a check keeps resolution on root comments only). New operations in `contracts/openapi.yaml`: `listWorkflowDiscussion` (`?elementId=`), `postWorkflowComment`, `resolveWorkflowThread`. The SecurityMatrix test generated from the contract covers them for foreign principals and snapshots the new table. Frontend: `js-yaml` (already in the lockfile as a transitive dev dependency) is now a direct dependency with a small local type declaration.

## Not included

- **BPMN source editing and BPMN round-trip checks** (delivered later in [S56](S60-bpmn-source.md)). There is no BPMN view, export of the canonical document as editable BPMN, or import from edited BPMN inside the studio. The existing Camunda/BPMN migration importer is unchanged and remains one-way (import). The "round trip" delivered is canonical document -> YAML -> canonical document.
- Stored or editable lanes (assigning a step to a lane other than by its approval group), drawing lanes on the canvas, pools, drag between lanes, nested or group-per-person lanes.
- Mentions, notifications, email, edit or delete of a comment, reactions, attachments, rich text, comment history across published releases, discussion on decisions, form fields or the workflow as a whole.
- Merge/diff UI between YAML and the draft beyond the change list; line-level YAML validation against the JSON schema (the server check remains the authority); YAML comments are not preserved (the text is regenerated from the document); concurrent-edit conflicts follow the existing draft revision conflict flow.
- A Playwright browser journey and a real-stack (Docker) run.

## Verification - 17 October 2026

Backend (JDK 21, real PostgreSQL via Testcontainers, every response validated against `openapi.yaml`): `WorkflowDiscussionApiTest` (2), `SecurityMatrixTest` (3), `ArchitectureTest` (5), `DatabaseTest` (2), `CatalogApiTest` (7), `WorkflowOrganizationApiTest` (6): 25 tests, 0 failures (`evidence/ui03-backend.log`). The discussion tests cover designer and publisher posting, viewer denial for post and resolve, one-level replies and element matching, unknown element/blank/oversized text, unknown parent, resolve/reopen and idempotent replay, rejecting resolution of a reply, filtering and open counts, audit metadata without comment text, no change to the draft revision, foreign principals and cross-workflow comment IDs. A first run of the discussion test had one wrong expectation (a start-only requester gets 404, not 403, on an unpublished workflow); the test now accepts either denial. The full backend suite was not run.

Frontend: 3 new unit files/component tests (`source.test.ts` 12, `lanes.test.ts` 3, `SourceLanesDiscussion.test.tsx` 6) and the full suite (97 files, 818 tests) pass; `tsc -b`, scoped ESLint, `api:check` and `build:mock` pass. The mock handlers are a simplified double of the server (evidence `ui03-frontend.log`, `ui03-types.log`, `ui03-lint.log`, `ui03-build.log`, untracked under `evidence/`).

Rerun: `backend/mvnw.cmd -f backend/pom.xml -pl app -am -Dtest=WorkflowDiscussionApiTest,SecurityMatrixTest,ArchitectureTest,DatabaseTest -Dsurefire.failIfNoSpecifiedTests=false test` (JDK 21); from `frontend`: `npx vitest run src/features/studio`.
