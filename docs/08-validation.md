# Package validation
Revision 0.5 · 9 October 2026

Counts and checks establish that the package is internally consistent and that the contracts are well formed. They are not application tests and do not establish runtime behavior.

## Implementation verification — revision 0.5

The application now exists; its test evidence is maintained in [feature status](feature-status.md#evidence-at-this-revision). Summary of the runs behind this revision:

| Suite | Environment | Result |
|---|---|---|
| Compiler tests | JDK 21, Maven | 204 pass; 96 % instruction coverage |
| Application integration tests | Testcontainers PostgreSQL 18, WireMock | 74 pass; responses validated against the OpenAPI contract |
| Frontend unit tests, typecheck, lint, build | Node 24 | 142 pass; all checks clean |
| Browser E2E (Playwright, Edge) | Docker stack, clean database | 13 pass, 1 skipped (mock-only) |
| API E2E with failure injection | Docker stack | 26 / 26 |
| Contract check, stub tests, manifest validation | Python, Maven, kubeconform | 47 checks, 58 tests, 132 checks pass |

Defects found during end-to-end verification and fixed: logout did not end the IdP session; the logout form failed CSRF; API 401s became the post-login redirect target; run timelines showed node IDs; `/not-found` and `/forbidden` returned JSON 404 on direct load; a link on the publish page relied on colour alone (WCAG 1.4.1); the dev profile could not start from the IDE (database password defaults and a relative secrets path); the `Principal` and `RunEvent.data` schemas were mis-specified in the contract.

Not yet verified: the resilience suite (T-RES), performance smoke, OpenShift deployment, CI execution, the real procurement endpoint and PingFederate (see feature status).

## Checks performed in revision 0.4

| Area | Check | Result |
|---|---|---|
| Files | Repository files excluding `.git` | 35 |
| Links | Local Markdown links and `#anchor` targets across 25 Markdown files | 117 checked, 0 broken |
| References | Search for removed research citations and the old reading-guide path | None remaining |
| JSON | All JSON in `contracts/` and `examples/` parses | 6 files, pass |
| Workflow schema | Meta-validates as JSON Schema 2020-12; `purchase-approval.json` validates | Pass |
| Connection schema | Meta-validates; `procurement.connection.json` validates; testable non-read-only, missing idempotency header and GET-with-body variants are rejected | Pass |
| Compiler fixtures | All 25 RFC 6902 patches apply to the base example; schema-layer cases fail schema validation and semantic-layer cases pass it, as expected | 25 / 25 consistent |
| OpenAPI | Parses; 43 paths, 48 operations; all 508 `$ref`s resolve (including external schema files); unique operation IDs; path parameters match templates; every operation has `x-orqix-permission`; every mutating operation (except validate, evaluate and mark-read) has `x-orqix-audit`; 65 component schemas are valid 2020-12 | Pass |
| Reference DDL | Applied to PostgreSQL 16.13 (throwaway container) with `ON_ERROR_STOP`; 21 application tables created | Pass |
| DDL constraints | Cross-workspace draft insert rejected by composite FK; self-approval flag rejected on a non-test workspace; duplicate platform binding rejected (`NULLS NOT DISTINCT`); app role denied UPDATE on `audit_event` | Pass |
| Ticket register | 52 tickets parsed; P0 100.5 h, P1 9.5 h, P2 3.5 h; P0 per lane BE 37, IQ 35.5, FE 28 | Matches S8 §1 |
| External versions | Flowable, Spring Boot, Spring Modulith, MCP specification and Java SDK, OpenAPI, JSON Schema, PostgreSQL, Node.js, Vite, Keycloak, OWASP ASVS, OMG DMN and RFC 9700 checked against vendor or standards pages on 7 October 2026 | Recorded in S1 §1 and doc 14 |

## Earlier checks still applicable

- Decision boundaries: 4,999.99 routes standard; 5,000 and 5,000.01 route finance review (now also fixtures in `purchase-scenarios.json`).
- SVG tag structure checked in revision 0.1; the design board was not changed since.

## Limitations

- No application exists. Runtime, integration, security, performance, recovery and production checks have not run.
- Semantic compiler expectations (graph, types, bindings) are specified with codes but cannot execute until the compiler exists (ticket B03.2).
- Flowable-specific mechanisms (DMN pinning API, retry-cycle count, failed-job hook, engine events, schema separation, property names) are designed but unverified until spike B01.2.
- The OpenAPI file was checked with a custom structural script, not with Redocly or a full OpenAPI validator; CI stage 1 adds that lint.
- Rendered visual QA of the design board and user research remain outstanding.
- External-dependent feature details and R1–R3 estimates still require the decisions in document 13.

## History

- 0.4 — Build specification S1–S9, OpenAPI, DDL, connection schema, fixtures; 15 planning inconsistencies resolved; references limited to current standards and maintained documentation; capacity finding added.
- 0.3 — Research references applied to plan documents (since removed in 0.4).
- 0.2 — Master plan, backlog, coverage and decision register added.
- 0.1 — Initial planning package.
