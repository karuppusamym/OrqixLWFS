# Orqix frontend

React + TypeScript + Vite 8 single-page app: business workspace (home, requests, tasks, runs), workflow studio
(React Flow canvas with a keyboard Steps tab), publish review and settings. Specification:
[S7 — Frontend screens and studio](../docs/build/S7-frontend.md). The production build is served same-origin by the
backend (the root `Dockerfile` copies `dist/` into the application jar).

## Scripts

| Command | Purpose |
|---|---|
| `npm ci` | Install (a project `.npmrc` overrides any global `os=`/`cpu=` npm settings so native bindings match this machine) |
| `npm run dev` | Vite on http://localhost:5173, proxying `/api`, `/oauth2`, `/login`, `/logout` to `ORQIX_BACKEND_URL` (default http://localhost:8080) |
| `npm run dev:mock` | Same UI with an in-browser mock API (no backend needed); the "Mock mode" pill switches persona and procurement behaviour |
| `npm run build` | Typecheck and production build to `dist/` |
| `npm run typecheck`, `npm run lint`, `npm test` | TypeScript, ESLint (including jsx-a11y and a ban on `dangerouslySetInnerHTML`), Vitest |
| `npm run api:generate` / `npm run api:check` | Regenerate `src/api/schema.d.ts` from `contracts/openapi.yaml` / fail on drift |
| `npm run e2e`, `npm run e2e:mock` | Playwright suites in `../tests/e2e` |

**Brand palette.** `VITE_ORQIX_BRAND=wf` (default, Wells Fargo–inspired red/gold on warm neutrals) or `orqix` (teal/navy) selects the color token set at build time; it is written into `index.html`, so switching needs a rebuild, not a code change. Tokens and contrast rules: `src/index.css`, `src/styles/contrast.test.ts`, [S7 §8](../docs/build/S7-frontend.md#8-visual-tokens).

## Structure

`src/app` (router, shell, providers), `src/api` (generated types, openapi-fetch client with CSRF header, problem
parsing, idempotency keys), `src/features/*` (screens), `src/components` (UI primitives and shared pieces),
`src/lib` (form renderer, money/date formatting, event texts), `src/stores` (studio draft store with undo/redo),
`src/mocks` (mock mode only; excluded from normal builds by a compile-time flag).

Server state lives in TanStack Query; only the studio draft lives in Zustand. Money is kept as typed text and sent as
a JSON number on submit; the UI never does arithmetic on amounts.
