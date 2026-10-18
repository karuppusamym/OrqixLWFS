# S58 - Browser and Docker-stack verification of the batch-3/4 features

Date: 17 October 2026. Branch `build/verify-e2e`, cut from `46ec063`. Scope: EX07, PL01, EX09, IN02, EX06, DOC08, UI03, PL02 and Pega XPDL import. No migration was added. Logs are under the git-ignored `evidence/` directory of the worktree.

## Isolated stack

Private Compose project `orqix-verify-1017` (own volume, so the existing `orqix_orqix-pgdata` and `orqix-verify-1012_*` volumes are untouched; no other stack was running). Image `orqix/orqix:verify-1017` built from this tree (`evidence/docker-build-1017.log`, rebuilt after the defect fix, `evidence/docker-build-1017b.log`).

```
. evidence/stack-env.sh   # ORQIX_IMAGE=orqix/orqix:verify-1017, app 28080, mgmt 28081, Keycloak 28180, PostgreSQL 25432, stub 28095
docker compose -p orqix-verify-1017 --profile app build
docker compose -p orqix-verify-1017 --profile app up -d
```

The dev realm only registers `localhost:8080` as redirect URI, so the redirect `http://localhost:28080/login/oauth2/code/orqix` was added to the `orqix-web` client in this private Keycloak through its admin API (without it the login page answers 400 "Invalid parameter: redirect_uri"). The repository realm file was not changed.

## Harness

Existing Playwright kit (`tests/e2e`, Edge via `E2E_BROWSER_CHANNEL=msedge`, axe WCAG 2.2 AA, serious/critical fail, console/CSP policy). Added `support/api.ts` (same-origin API helpers) and ten specs:

| Spec | Feature | Mock | Real stack |
| --- | --- | --- | --- |
| `t-pl-01-folders-owners` | PL01 folders, owner filter/reassignment, URL filters, requester denial | pass | pass |
| `t-ui-22-lanes-source-discussion` | UI03 lanes, YAML source (invalid/valid edit), threaded discussion, viewer read-only | pass | pass |
| `t-pl-02-canary` | PL02 start/stop rollout, reader view | pass | pass |
| `t-pl-02-promotion-api` | PL02 environment stage, promotion forward-only, draft not published | skipped (not simulated) | pass |
| `t-doc-08-templates-retention` | DOC08 template versions/archive/placeholder refusal, retention | pass (viewer part skipped: mock state resets on page load) | pass |
| `t-ex-09-repair` | EX09 check consistency; real: projection cleared in the verification database, rebuild with reason, back in sync | in-sync part | pass (full) |
| `t-in-02-adapters` | IN02 adapter, test message to stub, trigger | adapter part | pass (full) |
| `t-ex-06-multi-approver` | EX06 two approvers set in the studio; two different managers must approve; same person not offered second task | skipped (mock does not model it) | pass |
| `t-mig-03-pega-xpdl` | XPDL import to unpublished draft with `pega-xpdl-0.1`; requester denied | skipped (mock rejects by design) | pass |
| `t-mig-04-feel-conditions` | EX07 extended FEEL converts; unsupported source refused | skipped | pass |

Every spec runs axe on the dialogs/screens it opens. Commands (from `tests/e2e`):

```
E2E_MOCK=true E2E_BROWSER_CHANNEL=msedge npx playwright test --reporter=list            # evidence/mock-full-final.log
E2E_BASE_URL=http://localhost:28080 E2E_BROWSER_CHANNEL=msedge E2E_REALM_FILE=../../deploy/compose/keycloak/orqix-dev-realm.json \
  E2E_DB_CONTAINER=orqix-verify-1017-postgres-1 npx playwright test specs/t-pl-01 specs/t-ui-22 specs/t-pl-02 specs/t-doc-08 specs/t-ex-09 specs/t-ex-06 specs/t-in-02 specs/t-mig-03 specs/t-mig-04
```

## Results

- Mock, full suite (`mock-full-final.log`): 73 passed, 10 skipped, 1 failed. The failure was my own IN02 mock spec (the mock integration administrator cannot read workflows, so the trigger target list is empty); fixed by running the trigger part on the real stack only, then `t-in-02` alone passed in mock. The full mock suite was not rerun after that one-spec change.
- Real stack, new specs (`real-new-final.log`): 11 of 12 passed; the 12th (IN02) failed only because a rerun received 409 for an already-existing grant, then passed after tolerating it (`real-in02-rerun.log`).
- Real stack regression (`real-regression.log`): `t-ui-02` demo path, `t-ui-05` accessibility, `t-ui-06` CSP tour: 6 passed, 1 skipped (mock-only), 1 failed - a genuine defect, below. After the fix and image rebuild: `t-ui-05` and `t-in-02` 6/6 pass (`real-after-fix.log`).
- Frontend: `vitest run` 101 files, 837 tests pass (`vitest-full.log`); `tsc -b --noEmit` and eslint on `src/features/settings` clean; e2e `tsc --noEmit` clean.
- Not run: the rest of the older real-stack specs (only the three above); backend `verify` (no backend change); inclusive-gateway runtime and canary request routing in a browser; attachment quarantine UI (needs a file-bearing workflow; covered only by `DocumentLifecycleTest` and unit tests); signed inbound event delivery; owner reassignment on the real stack only ran if another editor existed as owner candidate (the spec skips that step when none).

## Defect found and fixed

`AdaptersPanel` (IN02) requested the workflow list whenever the person could manage connections. An integration administrator without `workflow.read` (the seeded `wade.admin`) got a 403 and a console error on Settings > Connections, failing the existing `t-ui-05` "settings and workspace without access" journey, and the trigger's "Workflow to start" list was empty with no explanation. The request is now made only with `workflow.read`, and the form says why the list is empty. Unit test added (`AdaptersPanel.test.tsx`, 7 pass).

## Observations (not fixed)

- Mock mode keeps document templates, retention and adapters in module memory that a page load resets, so multi-persona journeys for those cannot run in mock.
- `connection.manage` is grant-only, so creating a trigger needs a person who holds both it and `workflow.read`; the spec grants it to the designer via the access API.
