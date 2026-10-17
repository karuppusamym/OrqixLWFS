# Workspace functionality and design review

Reviewed 8 October 2026. Scope: implemented React workspace, navigation, list/search behavior, task links, dashboard design, and the existing feature-status documentation.

## Improvements implemented

| Finding | Result |
|---|---|
| My requests was accessible mainly through the account menu and home links | Added a direct navigation item for every workspace member |
| List filters disappeared after leaving the page | Runs, My requests and Workflows now restore filters from URL parameters; reload, shared links and browser Back retain context |
| Empty search results offered little recovery | Added Clear filters, Clear search and actions to restore all results |
| The home page did not summarize the approver's actionable work | Added assigned/claimable counts and shortcuts using the existing task-counter API, plus a request-tracking shortcut |
| Home links to group tasks opened the assigned tab | Group tasks now open with view=claimable; assigned tasks use view=assigned |
| Home workflows and the run workflow selector exposed only the initial page | Added explicit pagination controls for both lists |
| Filter controls lacked visual grouping and could be awkward on narrow screens | Added bordered surface panels, wrapping forms and example search text |
| Run filter changes retained prior results temporarily without feedback | Added an Updating runs status while fetching |

The dashboard retains its existing navy and teal palette. New shortcut cards stack on mobile, match the existing spacing and surfaces, and keep text labels alongside icons. No dependencies were added.

## Validation

- 142 existing frontend unit/component tests passed; seven new regressions passed after final changes (149 total across runs).
- Frontend and browser-test TypeScript checks passed.
- Full frontend lint passed; changed files passed again after final refinements.
- Final production build and mock build passed.
- 14 existing Playwright tests passed in Edge against the mock API, including axe serious/critical checks and console/CSP monitoring.
- Two new Playwright tests passed on the final mock build: search reload/history with an unfinished draft, and layout checks at 390 px and 320 px (16 browser tests across runs).
- Desktop and mobile screenshots were inspected. Captured home and runs pages had no page-level horizontal overflow; wide tables scroll within their containers.
- git diff --check passed for changed source and documentation.

Screenshots: [desktop home](../evidence/ui-review/home-desktop.png), [mobile home](../evidence/ui-review/home-mobile.png), [desktop runs](../evidence/ui-review/runs-desktop.png), [mobile runs](../evidence/ui-review/runs-mobile.png).

The initial UI review used mock data. The follow-up below exercises the local PostgreSQL/Flowable application, Keycloak and procurement stub. Existing user edits were preserved; changes are local and uncommitted.

## Remaining feature priorities

The [feature status](feature-status.md) records completed checks and remaining gates: hard-kill recovery, database outage, in-flight shutdown, cross-instance revocation, performance, database restore, real procurement/PingFederate, CI and cluster deployment. Local tests do not complete those gates.

Parallel/timer/child workflows, Camunda migration, AI drafts, agents, MCP, Teams and PEGA/LWFS adapters remain planned in the [product backlog](10-full-product-backlog.md). They require their own implementation and acceptance work. This review improves the existing R0 user experience and does not alter those feature-status claims.

## Completion follow-up

- Fixed backend compilation blockers in the OpenRouter timeout catch and Camunda analyzer, and supplied deterministic schema-compatible Camunda identifier helpers with collision/length tests. These repairs make the workspace buildable; AI drafting and migration conversion still require their milestone implementations.
- Fixed Runs search to find exact generated references such as ORQ-1003 as well as exact business keys, within the authorized workspace. Updated the contract, generated client types and mock behavior. Added PostgreSQL-backed search and foreign-workspace regressions.
- Added a file-backed credential canary test covering successful and rejected connector calls, API responses, run events, notifications, operator views, audit metadata, decoded exports and captured logs. Confirmed that the test service actually received the credential.
- Added a browser check proving HTML/script strings remain plain text in workflow/form labels, submitted values and decision reasons. Isolated workflow-editing test data so repeated runs do not inherit demo threshold edits.
- Added [local restart acceptance](../tools/resilience/README.md). The built Docker app was stopped and restarted gracefully; existing sessions, waiting state, version pinning, claimed task/version and assigned inbox survived. Subsequent approval produced one successful effect attempt and one completion event. Observed shutdown: 1,158 ms; readiness after start: 18,542 ms.

Final validation: 204 compiler tests and 83 application tests through Maven verify (including architecture and coverage gates); 149 frontend tests; frontend lint/types/API drift and browser-test types; production Docker image and mock build; 16 real-stack browser tests passed with one intentional mock-only skip. Three added browser checks also passed against mocks. Existing RestartTest and TwoExecutorsTest passed against isolated real PostgreSQL databases, covering context restart and 50 concurrent runs across two executors plus retries.

Evidence: [backend verify](../evidence/ui-review/backend-final.log), [real browser suite](../evidence/ui-review/browser-real.log), [local restart](../evidence/ui-review/restart.log), [image build](../evidence/ui-review/compose-build.log). The local Compose application is rebuilt and running at http://localhost:8080. External procurement and production identity/infrastructure were not exercised.
