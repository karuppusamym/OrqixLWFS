# S76 - Browser and Docker-stack verification of the batch-5/6 features

Date: 19 October 2026. Branch `build/e2e-batch6`, cut from `723c94c`. Follows the convention of [S62](S62-browser-and-stack-verification.md). No migration was added; logs are in the git-ignored `evidence/s76/`.

## Stack

Private Compose project `orqix-verify-1018` (own volume `orqix-verify-1018_orqix-pgdata`; the existing `orqix_*`, `orqix-verify-1017*` and `orqix-hackathon-verified*` stacks were not touched). Image `orqix/orqix:verify-1018` built from this tree. Ports 28080 (app), 28081 (management), 28180 (Keycloak), 25432 (PostgreSQL), 28095 (procurement stub).

```
. evidence/s76/stack-env.sh            # COMPOSE_PROJECT_NAME, ORQIX_IMAGE, the five ports
docker compose -p orqix-verify-1018 -f deploy/compose/docker-compose.yml --profile app build orqix-migrate
export S76_SECRETS_DIR=<abs path>/evidence/s76/secrets
docker compose -p orqix-verify-1018 -f deploy/compose/docker-compose.yml -f evidence/s76/compose.override.yml --profile app up -d
```

`evidence/s76/compose.override.yml` (private to this stack) mounts an own secrets directory holding two random test signing secrets (`kafka-signing`, `orders-signing`), switches the EX09 consistency scan on (`ORQIX_WATCHDOG_CONSISTENCY_SCAN_ENABLED=true`, interval 10 s, finding recheck 10 s) and shortens the watchdog poll to 15 s. The redirect URI `http://localhost:28080/login/oauth2/code/orqix` (and web origin) was added to the `orqix-web` client through this private Keycloak's admin API; the repository realm file is unchanged.

## Specs added (tests/e2e/specs)

| Spec | Covers | Mock |
| --- | --- | --- |
| `t-pl-01-shared-assets` | S61: share a rule table from the studio, Settings > Shared assets search, where-used, archive (hidden, "Show archived"), restore, studio notice that an archived link blocks publishing | skipped (mock state resets on navigation) |
| `t-ex-06-scope-frames` | S71 (EX06/EX08): section and handler frames on the studio canvas, "When it fails" opt-in, check, publish, stub 400 raises the error, run diagram frame "passed / left because a step raised an error", handler idle | skipped (not modelled) |
| `t-ex-09-waits-and-scan` | S72: message-wait and external-worker rebuild, Consistency scan panel (drift made in the database, listed within the scan interval, hidden for a requester, cleared by the repair) | skipped |
| `t-in-02-events-and-adapter-step` | S73: Send through an adapter step (inspector, check, publish, run), signed inbound delivery (HMAC; bad signature 401, stale timestamp 401, repeat acknowledged), Received events panel, start a request from an event, adapter and trigger edit, disabled trigger refuses senders (404), purge | skipped |
| `t-pl-02-promotion-ui` | S74: promotion screen (check, promote, history), production approval gate (direct promote 409, request, requester sees no Approve, API 403 SELF_APPROVAL_DENIED, second person with the grant-only `workflow.approve-promotion` approves in the browser, history names the approver); gate switched off at the end | skipped |
| `t-ui-03-bpmn-source` | S60: BPMN source view, refusal of a foreign attribute, apply a rename, read-only on a draft that does not round trip (`separateFrom`), viewer read-only | skipped |
| `t-in-04-agent-writes` | S75: declare tool, approve (reason required), unknown write, record outcome, revoke; DB refuses approver = requester | skipped |
| `t-doc-08-quarantine` | S62 not-run item: attachment quarantine and release | skipped |
| `t-pl-02-canary-routing` | S62 not-run item: 50 percent canary, 24 API starts land on both releases, stopping returns to the active release | skipped |

Every spec runs axe WCAG 2.2 AA (serious/critical fail) on the screens and dialogs it opens, plus the existing console/CSP policy. Helpers added to `support/api.ts`: `editDraft`, `apiRead`, `stubFault`, `openTaskOfRun` (pages the approver's task list; a title search on the first page missed new tasks once the shared database held more than one page of open manager tasks).

## Commands

```
cd tests/e2e
. ../../evidence/s76/e2e-env.sh   # E2E_BASE_URL=28080, msedge, realm file, E2E_DB_CONTAINER=orqix-verify-1018-postgres-1, E2E_STUB_URL, E2E_INBOUND_SECRETS_DIR
npx playwright test specs/t-pl-01-shared-assets specs/t-ex-06-scope-frames specs/t-ex-09-waits-and-scan specs/t-in-02-events-and-adapter-step specs/t-pl-02-promotion-ui specs/t-ui-03-bpmn-source specs/t-in-04-agent-writes specs/t-doc-08-quarantine specs/t-pl-02-canary-routing specs/t-ui-14-diagram-insights specs/t-ui-05-accessibility specs/t-ex-09-repair specs/t-in-02-adapters --reporter=list --timeout=200000
```

## Defect found and fixed

Run diagram (`runDiagram.css`): a step the request never reached ("skipped") was drawn at `opacity: 0.5`, which fails the WCAG 2.2 AA text contrast rule (axe `color-contrast`, serious, ratios 2.1 and 2.78) on any run diagram that contains an unreached step, for example the request that left a section by its error route. It is now set apart by a dashed border and grayscale, without opacity. Found by `t-ex-06-scope-frames`; re-verified on a rebuilt image (spec passes). No unit test: the change is CSS only and is covered by the axe step of that journey.

## Observations (not fixed)

- Agent write approve/revoke dialogs show a missing-reason message as plain text, not an alert (not announced to screen readers).
- The real stack has no language model, so the run-time refusal of the approver's own request through a write tool (`WRITE_SELF_APPROVAL`) is not driven from the browser; the database CHECK is (see spec). `AgentWriteToolsTest` covers the runtime rule.
- The unknown agent write row is inserted directly in the verification database, as the drift in EX09 is; an in-flight crash cannot be produced from the browser.
- The shared verification database accumulates open manager tasks, so paged lists can hide new tasks (same effect S75 recorded for `SeparationOfDutiesTest`).

## Results

Real stack (`orqix-verify-1018`, Edge, axe WCAG 2.2 AA). Logs: `evidence/s76/real-final.log` (first full batch), `rerun-1..4.log`, `canary-run.log`, `scope-run.log`, `in02-run.log`, `ex09-run.log`.

- Passed on the real stack: `t-pl-01-shared-assets`, `t-ex-06-scope-frames` (after the CSS fix and image rebuild), `t-ex-09-waits-and-scan`, `t-in-02-events-and-adapter-step`, `t-pl-02-promotion-ui`, `t-ui-03-bpmn-source`, `t-in-04-agent-writes`, `t-doc-08-quarantine`, `t-pl-02-canary-routing`; regression `t-in-02-adapters`, `t-ex-09-repair` (after updating its two labels), `t-ui-05-accessibility` requester/operator/designer/settings tests.
- Several specs needed fixes in the specs themselves before they passed (wrong labels, a Kafka event needs a `value` object, a paged task list); the passing runs above are the final versions. The final complete batch was not run again in one go after the last spec edits: `t-in-04` and `t-ex-09-repair` were rerun on their own, the others passed in the batch (`real-final.log`: 10 passed) or in their own runs.
- Failed and not fixed: `t-ui-05-accessibility` "approver screens" (older spec) looks for its task by title on the first page of the claimable list; the shared verification database now holds more open manager tasks than one page, so the link is not on page 1. Not a product defect (same accumulation effect as S75). Because that file runs in serial mode, its later tests were skipped in that run; they pass with `--grep-invert "approver screens"`.
- Mock (`E2E_MOCK=true`): `t-ui-14-diagram-insights` 6 passed (after the run-diagram CSS change); the new real-only specs are skipped by design; `t-pl-01-shared-assets` failed in mock (state reset on navigation) and is now skipped in mock. The full mock suite was not rerun.
- Not run: the rest of the older real-stack specs; backend `verify` (no backend change); frontend `vitest`/`tsc -b`/eslint (only a CSS change; e2e `tsc --noEmit` is clean); run-time refusal of the approver's own agent write and the in-flight crash path in a browser (needs a language model / crash injection); a live (non-stub) adapter or Kafka source.

The private stack and its volume were removed after the run (`docker compose -p orqix-verify-1018 ... down -v`); the Playwright output directories and logs remain only in the git-ignored `evidence/s76/`.
