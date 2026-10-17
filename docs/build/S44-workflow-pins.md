# S44 - Personal workflow pins

PL03 delivers a personal shortlist of up to ten workflows per account and workspace, saved in the current browser. Shared folders, owners, asset libraries and server-synchronized preferences remain PL01.

## Behavior

People with `workflow.read` can Pin or Unpin a workflow from the catalog or its overview. The Pinned workflows section stays available independently of catalog search and pagination. Pinned links open the existing workflow overview. Requesters without `workflow.read` see no pin controls or pinned-workflow fetches.

Pins survive reloads in this browser. Same-tab controls update together, and other tabs respond to storage events, including clearing site storage. Pins are not shared across accounts, workspaces, browsers or devices. Clearing browser site data removes them.

## Storage and authorization

- Versioned storage keys include the signed-in subject ID and workspace ID; values contain only `{version: 1, ids: [...]}`. Workflow labels, descriptions, definitions, credentials and permissions are not stored.
- IDs must have UUID syntax. Malformed JSON, unsupported versions, oversized data and oversized lists are ignored; duplicate IDs are deduplicated. Ten pins is a hard write limit; unpinning remains available at that limit.
- Stored IDs grant no access. Each pinned item is resolved through the ordinary workspace workflow GET API independently of catalog search/pagination. The separate pin query cache includes subject and workspace, refetches on mount, and is removed when unused. Labels are hidden during refresh or after a failed fetch; unavailable items show a generic message, Retry and Unpin.
- Blocked/full storage leaves stored pins unchanged and displays an error. The UI does not claim success or silently use temporary pins.

Pin changes create no server mutation or audit event. Existing server read authorization remains authoritative.

## Verification - 17 October 2026

All 25 focused frontend cases pass across pin storage, pin page integration and existing list-filter regressions. Coverage includes account/workspace key separation, a same-browser account switch, ID-only persistence, search/reload/overview removal, another tab clearing storage, unavailable pins after refresh, requester denial, corrupt storage, the cap and storage write failure.

One mock Edge browser journey passes under the production CSP: pin, inspect stored IDs, search for a nonmatching name, reload, open the pinned workflow and unpin from the overview. It also checks requester denial, mobile width and desktop/mobile axe. Frontend/E2E typechecks, scoped lint and the isolated mock build pass.

Evidence: `evidence/workflow-pins-tests-final.log`, `evidence/workflow-pins-browser.log`, `evidence/workflow-pins-types-final.log`, `evidence/workflow-pins-e2e-types.log`, `evidence/workflow-pins-lint-final.log`, `evidence/workflow-pins-build.log`.

No fresh backend suite, real-stack browser test or Docker refresh is claimed. The separate external-worker commit is not certified by this feature's checks.
