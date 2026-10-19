# S74 - Promotion screen and optional approval gate (PL02b)

Builds on [S54](S54-promotion-canary-environments.md). It adds the browser screens for environment promotion, an optional approval gate before promotion into a production-stage workspace, and a stronger rollout UI test. Not a Git integration and not cross-organization promotion (a target in another organization is still `404`).

## Behavior

- **Promotion screen.** Each release row on the workflow overview has "Promote" (needs `workflow.export`). The page `/w/:ws/workflows/:wf/releases/:release/promote` lists the other workspaces of the same organization where the caller can edit workflows, with their stage (earlier or equal stages are disabled). "Run compatibility check" calls `checkReleasePromotion` and shows per-connection status, per-group existence and member count, called workflows, and the compiler findings (blockers vs warnings). Promote is offered only when the report is compatible; it sends the `expectedDraftRevision` the check reported when the target already has the workflow. A stale revision (`412`) tells the person to check again. Promotion history (`listWorkflowPromotions`) is shown below, including who approved.
- **Approval gate.** `workspace_environment.promotion_approval` (V99), set through `setWorkspaceEnvironment` (`requireApproval`, optional; omitted keeps it) by `workspace.manage-access`. It can only be on for stage `production` (`422` otherwise) and moving a workspace out of production switches it off. When on, `promoteRelease` into that workspace is refused with `409 PROMOTION_APPROVAL_REQUIRED`; the check report carries `approvalRequired`. The caller (same rights as promoting: export at source, edit at target, compatible report) uses `requestReleasePromotion` instead, which stores a `pending` request and creates nothing in the target. One pending request per workflow per target (`409 DUPLICATE_KEY`).
- **Who approves.** A new grant-only, workspace-wide permission `workflow.approve-promotion` in the **target** workspace (no role carries it; granted through the access API like `audit.read`). `approvePromotionRequest` / `rejectPromotionRequest` need it. The requester can never approve or reject their own request (`403 SELF_APPROVAL_DENIED`, audited as `auth.denied`); a database CHECK repeats this. The requester may only `cancelPromotionRequest`. Rejection needs a reason (`422 REASON_REQUIRED`). There is no auto-approval, timer, default approver or bulk approve.
- **At approval.** The target is checked again (later stage, compatible report). If incompatible (`422`) or the target draft moved (`412 STALE_REVISION`) the request stays pending (cancel and re-request). On success the draft is saved exactly as a direct promotion would (never publishes, copies no binding/secret/group). The new workflow owner and draft author are the requester; provenance records `promoted_by` = requester and `approved_by` = approver; the approver is the audit actor.
- **Audit.** `release.promotion.requested` (source), `release.promotion.approval-requested`, `.approved`, `.rejected`, `.cancelled` (target), plus the existing `release.promoted` / `release.promotion.received` (now with `requestId`, `approvedBy`). Metadata is ids and numbers only; notes are not audited.
- **Promotions page.** `/w/:ws/promotions` (nav "Promotions" for editors, approvers, access managers): the workspace stage and approval requirement (editable by `workspace.manage-access`), and the requests with Approve / Reject (reason dialog) for approvers other than the requester, Withdraw for the requester.

## API additions

`requestReleasePromotion`, `listPromotionRequests` (read/edit/approve; pending by default), `approvePromotionRequest`, `rejectPromotionRequest`, `cancelPromotionRequest`; `requireApproval` on the environment; `approvalRequired` on the report; `approvedBy` on history; problem code `PROMOTION_APPROVAL_REQUIRED`; permission `workflow.approve-promotion`. Migration `V99__promotion_approval.sql` only.

## Not included / decisions

- No quorum or multiple approvers, delegation, expiry of requests, or notifications to approvers (they find requests on the Promotions page).
- The requester own rights are not re-evaluated at approval time; the approver judgement and the re-check of the target are the controls.
- The gate covers promotion into production only; other stages, publishing and activation keep their own gates. Approval does not publish.
- Git, signed bundles, cross-organization/cross-instance promotion remain excluded. Approval cannot copy bindings, secrets or groups.
- The mock API (`frontend/src/mocks/promotion.ts`) is a simplified double: its report comes from the release connection/group references, not the compiler.
- No Playwright/browser journey was run for these screens.
- `tools/contract-check` gained the qualifier `requester` (for `cancelPromotionRequest`).
- Merge note: V99 recreates the `permission_grant` CHECK constraints listing every grantable permission; a concurrent migration that also changes that list must be reconciled.

## Verification - 18 October 2026

- New backend `PromotionApprovalTest` (3, real PostgreSQL, responses validated against the contract): gated direct promotion is `409`; request rights; one pending per workflow; visibility; approval needs the grant; requester refused for approve and reject (even holding the grant) and audited; the DB constraint refuses a self-decision; reason required; approval saves an unpublished draft with promoter and approver in history and audit; stale draft leaves the request pending (`412`); only the requester cancels, once; rejection; gate is production-only and cleared when leaving production; without the gate requests are `422` and direct promotion works.
- `SecurityMatrixTest` covers the five new operations (foreign principals `404`, no state change).
- Full `backend verify`: 796 tests, 0 failures, 4 skipped, BUILD SUCCESS (`evidence/s69/backend-verify.log`; evidence is untracked).
- `contract_check.py`: 88 checks pass; `api:check` passes.
- Frontend: `PromotePage.test.tsx` (6), `PromotionRequestsPage.test.tsx` (5), `ReleaseRollout.test.tsx` (4). Full suite (889 tests): 884 passed, 5 failed with 15 s timeouts on heavy studio/AI/guided-dialog tests while the machine was loaded (`evidence/s69/frontend-full.log`); those four files (35 tests) pass with `--testTimeout=90000`. One real failure from this change (Shell navigation test now lists Promotions for access administrators) was fixed. `tsc -b`, ESLint (whole project) and `build:mock` pass.
- The weak S54 canary test is replaced: a requester who cannot read workflows must see the explicit permission message and no rollout section, and no request to the rollout/analytics/canary endpoints is observed; a new test checks a designer sees status only (no controls, no figures table).

Rerun: `backend/mvnw.cmd -f backend/pom.xml -pl app -am -Dtest=PromotionApprovalTest,ReleasePromotionTest,SecurityMatrixTest -Dsurefire.failIfNoSpecifiedTests=false test`; from `frontend`: `npx vitest run src/features/promotion src/features/workflows/ReleaseRollout.test.tsx`.
