# Browser end-to-end tests (Playwright)

S9 tests T-UI-02…06: the demo path, task conflicts, keyboard-only studio editing, axe accessibility scans on every
route (serious/critical findings fail), and a tour checking for CSP violations and unexpected console errors.

## Against the real stack (default)

```bash
cd deploy/compose && docker compose --profile app down -v && docker compose --profile app up -d --build   # clean database
cd tests/e2e && npm ci
E2E_REALM_FILE=../../deploy/compose/keycloak/orqix-dev-realm.json npx playwright test
```

Last run (29 October 2026, fresh stack, [S106](../../docs/build/S106-shapes-promotion-kafka-e2e.md)): 70 passed, 65 skipped by design (mock-only specs, presenter rehearsals), about 21 minutes. CI runs the same suite after the image job.
The demo path expects a fresh database; run on a clean stack.

## Against mock mode

```bash
cd tests/e2e && npm run test:mock     # builds the frontend with --mode mock and serves it with the production CSP
```

## Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `E2E_BASE_URL` | `http://localhost:8080` (mock: `http://localhost:4173`) | Application under test |
| `E2E_MOCK` | unset | `true` runs against mock mode |
| `E2E_BROWSER_CHANNEL` | unset (bundled Chromium) | Use an installed browser, e.g. `msedge` or `chrome`, when the bundled one is not downloaded |
| `E2E_PASSWORD_<USERNAME>` | — | Password for one dev user, e.g. `E2E_PASSWORD_RHEA_REQUESTER` |
| `E2E_PASSWORD` | — | One password for every user |
| `E2E_REALM_FILE` | — | Read the dev-only passwords from the Keycloak realm export instead |
| `E2E_AI_LIVE` | — | `true` lets `t-ai-02` call the configured AI provider on the real stack; otherwise it checks the agent connection without testing it |
| `E2E_STUB_URL` | unset | Procurement stub admin URL for fault injection, e.g. `http://localhost:8095` on the compose stack; `t-ex-06-scope-frames` needs it on the real stack |
| `E2E_REHEARSAL` | — | `true` runs the `t-demo-*` presenter rehearsals, which need a seeded demo stack ([S86](../../docs/build/S86-demo-rehearsal-1025.md)); the general suite and CI skip them |

Console-error policy: a 401 logged while a page is still signing in is the SPA's designed unauthenticated probe and is
ignored; specs that deliberately provoke an error response (for example a stale claim answered with 409) call
`sessions.allowHttpErrors()` for that step. A spec that expects one specific browser message names it exactly with
`sessions.allowConsoleError(text, url?)` (`t-ui-02g-extensions` does this for the blocked reload of an extension that
leaves its document). CSP violations recorded on the page and uncaught exceptions always fail.
