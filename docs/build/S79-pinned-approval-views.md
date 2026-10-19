# S79 - Pinned reusable approval views

19 October 2026. UI02c completes reusable approval presentation from the view library through Studio, authoritative publication checks and the task runtime. The broader general application designer remains UI02; this increment does not close case/worklist pages, composed actions, localization or component extensions.

## Author and apply

Create an Approval view in the workspace view library. Bind every request field exactly once using `request.fieldName`, matching its type and required flag. Sections have one or two columns; field/section ordering and labels come from the view. The existing visual editor, preview, undo and publication review apply. Supported fields are the library's text, number, date, choice, file and object-row controls. Choice values must match the workflow enumeration in order and use the same value/label. Boolean fields are not yet supported by the view format.

Include exactly one Approve action and one Reject action, optionally one same-app navigation action. Action labels may be customized. Task comment bindings and the library's request-correction action are not admitted for workflow reuse: the task's managed comment and authorized input-correction controls remain responsible for those operations. No script or external action is executed.

In Studio, select an approval step and choose a published approval view and exact version. Applying copies the presentation into `config.viewBinding`, including its workspace-local view UUID and immutable version. Input schema, request form, routing, candidate group, skill/separation controls, deadlines and correction rights are untouched. Removing the approval view restores the standard task presentation; Studio undo can restore the pin. Read-only Studio does not offer selection or fetch the view library.

## Publication and runtime

Validation and publication independently load the pinned version within the workflow workspace. Missing/foreign versions are blockers; copied presentation changes, incompatible types/bindings/required flags and unsupported actions are blockers. A newer library version produces the existing stale-asset warning; it never silently replaces the pin. Request-view and approval-view checks share field-type compatibility rules.

The task API returns the approval presentation copied from its workflow release. It does not fetch the view library or require its read permission from an approver. Later edits/publications of the library cannot change existing release/task presentation. Request values still come exclusively from the authorized runtime projection. The renderer shows read-only context with responsive columns and uses existing field formatting; files, comments, history, correction and operational controls remain on the task page.

Custom Approve/Reject labels call the existing task decision API with fixed `approve`/`reject` actions. Fresh eligibility, claim ownership, self-approval/separation rules, rejection reason, task version, idempotency, suspension and completion checks remain authoritative. A presentation cannot grant an action or supply a decision payload. Local navigation uses the view format's existing constrained same-app routes and normal route/API authorization.

## Verification

- Backend selected Maven `verify`: **18/18**, zero skips, packaging and compiler coverage gate pass. Includes four new `ApprovalViewBindingTest` cases plus request-view reuse, view-library API, architecture and security matrix. The new cases cover two-workflow reuse, unclaimed/unauthorized decision refusal, rejection reasons, old-release retention after version publication, stale warning, foreign/missing pins, incompatible fields/actions and authoritative publication tamper rejection. Log: `evidence/approval-views-backend.log`.
- Full compiler: **524/524**, unchanged coverage gate and Maven `verify` pass. Log: `evidence/approval-views-compiler.log`.
- Focused frontend: **31/31** across seven files. Includes compatibility/refusal, copied-source immutability, explicit version selection, failed library reads, read-only Studio, removal/undo, rendered context and custom decision controls, required rejection reason and self-approval refusal. Log: `evidence/approval-views-frontend-focused.log`.
- Frontend and E2E TypeScript, full lint, API generation drift and **88 contract checks** pass. Logs: `evidence/approval-views-{types,lint,api,e2e-types,contracts}.log`.

- Full frontend: **939/939** across 127 files, exit 0 (`evidence/approval-views-frontend-full.log`).
- Real Edge against PostgreSQL/Flowable/Keycloak: **1/1** authoring-to-runtime journey passes (`evidence/approval-views-real-browser.log`). It publishes the reusable view, applies it in Studio, publishes the workflow, starts a request as a separate requester, checks copied context, claims as a manager, validates the required rejection reason, decides with the custom label, reloads the completed task, and runs serious/critical axe checks on authoring and mobile active/completed task pages. The fixture creates the source view through the authorized API; the existing view-editor frontend tests cover its visual editing.

No fresh full application suite, full CMMN/general-designer completion, production deployment or customer acceptance is implied.

## Local rollout

The application was rebuilt as `orqix/orqix:approval-views-20261019` after the source checks. `evidence/approval-views-image-source.json` records HEAD `e71dbef` and source SHA-256 `a4f13e144c0dae599fba17e596fea48c3ddf7da575d8066c8f1ddc63377f472c`; embedded authoring/tasks bundles and the production frontend build pass in the image build. The database was backed up first to `evidence/pre-approval-views-refresh.dump` (38,084,460 bytes; checksum and prior image in `evidence/approval-views-previous-image.json`). Migration validation succeeds through existing V111; S79 introduces no new migration. Existing dependencies and volumes were retained. Readiness and container health are healthy, and the real browser journey above ran against that image on localhost:18080. Logs: `evidence/approval-views-image-build.log`, `approval-views-demo-migration.log`, `approval-views-demo-refresh.log` and `approval-views-rollout.log`.

UI02c is closed within the exact delivery above. Full UI02 remains open for case/worklist runtime pages, composed actions, localization, extension sandboxing and the other S56 general-designer acceptance. No arbitrary task-data bindings, writable custom controls or action composition are delivered by this increment.
