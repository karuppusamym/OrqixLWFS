# S117 - Native Pega conversion, round 3: connectors, transforms, validation and case types as case definitions (29 October 2026)

Third MIG11 round after [S112](S112-pega-native-conversion.md) and [S115](S115-pega-native-completion.md). The user asked to convert Connect-REST connectors, simple data transforms, validate rules and case types (as CMMN case definitions), and to refuse data pages with a clear reason unless they are read-only calls on a connection. **AC01/EXT01 stay open**: the grammar is still synthetic and no genuine export or Pega system was available.

Migration **V202**. No other number used. The branch first merged `claude/big-open-features-f6ffdd` (WM04 cases S110/S113, UI02 pages), so the case designer's API and subset validator are available; their code was not changed.

## What now converts

| Source construct | Target | Conditions |
|---|---|---|
| **Connect-REST connector** on an Integrator shape (`pyConnectorName`) | A **call step** (`http` node) on a workspace connection. The connection, operation and version are chosen explicitly per call (`connectionMappings` on the convert request; the workbench lists each call with its source method and endpoint). Request fields come from `pyRequestMappings` or a request data transform; result fields from `pyResponseMappings` (JSON pointers into the response) | Conversion is refused (422) until every call has a connection mapping. Request values must be properties (the call step takes field references, not literals). Reported for review (`PEGA_NATIVE_CONNECTOR_AS_CALL`); an authentication profile is reported as not migrated (`PEGA_NATIVE_CONNECTOR_CREDENTIALS`): the connection's own approved binding applies. Results read by a condition, or a result property that is also a request field, are refused (`PEGA_NATIVE_CONNECTOR_OUTPUT_READ`) |
| **Data page** on an Integrator shape (`pyDataPageName`) | The same call step, through the data page's connector | Only a read-only, Thread-scoped data page sourced from one connector, without refresh strategy (`PEGA_NATIVE_DATA_PAGE_AS_CALL`, review). Any other data page, and any `.D_*` property reference, is refused (`PEGA_NATIVE_DATA_PAGE_UNSUPPORTED`): the target has no cached, shared or reloaded pages |
| **Data transform** used as a connector request, a connector response or a subflow input (`pyInputTransform`) | Step input or result mappings | Only Set actions whose source is a property (or, for a subflow input, a literal is refused too). Functions, loops, conditions, Append, Remove and other actions are refused (`PEGA_NATIVE_TRANSFORM_UNSUPPORTED`) |
| **Validate rule on case creation** (`pyCreateValidate` on the case type) | Request form validation: Required → required, Minimum/Maximum → `minimum`/`maximum`, MinLength/MaxLength → `minLength`/`maxLength`, a simple anchored Pattern → `pattern` with `patternMessage` | Other checks are refused with the check named. A validate rule on a flow action is refused with a specific message: converted approvals collect only a decision and a reason |
| **Case type as a case definition** (new endpoint `POST /migrations/{id}/case-definition`) | A CMMN case definition in the case designer's admitted subset, created through the existing case definition API (`CaseService.createDefinition`), which deploys it and pins the workflow releases: each stage a CMMN stage (required, chained in order); each process a workflow task (`processTask` with `orqixwf:<key>` and a `workflowTasks` mapping) running the published workflow converted from that flow; optional processes discretionary items planned by a mapped group; alternate stages stages entered by a user event that a mapped group raises, leaving the primary stage; elapsed service levels a timer that reaches a "late" milestone; request fields typed case data fields | Every process flow must be converted and published first (`workflowMappings`, flow name → workflow key); every optional process and alternate stage needs a group (`roleMappings`). With `dryRun` the proposal is returned and checked by `CmmnSubsetValidator` without creating anything. The same request again replays the recorded definition; a different one is a conflict (409). Reported: manual stage completion and case close (the subset requires `autoComplete="false"` where work is planned), timers in place of service levels |

**Process starting points.** A case type's processes (primary and alternate stages, in order, de-duplicated) are now starting points of kind `process`, so each can be converted and published on its own before the case definition pins it. The Python oracle and `RuleResolution` add them identically; the S112/S115 expectations were regenerated for this.

The case-type-to-workflow conversion is unchanged: alternate stages and optional processes are still refused there, and the message now points to the case definition target.

## Still refused, with reasons

| Construct | Why |
|---|---|
| **Declare expressions** and other declarative rules | The target has no computed fields; the message says so |
| Data pages other than read-only Thread-scoped connector pages; `.D_*` references | No cached, shared or reloaded pages in the target |
| Transforms with functions, loops, conditions or non-Set actions; literals in connector requests | Call steps and child steps take field references only |
| Connector results read by conditions | Call results are not request fields the branch conditions can read |
| Validate checks other than Required/Minimum/Maximum/MinLength/MaxLength/simple Pattern; validate rules on flow actions | No equivalent form check, or no form at the approval |
| Case data fields with a pattern or minimum length | Case data fields support required, maxLength, minimum, maximum and options only (`PEGA_NATIVE_CASE_FIELD_CHECK_UNSUPPORTED`) |
| Service levels on business days or with escalations in a case definition | Case timers are elapsed durations and only reach a milestone (`PEGA_NATIVE_SLA_TIMER_UNSUPPORTED`) |
| Spin-offs, Any/Some joins, multivariate circumstances and the rest of S115's list | Unchanged |

## Fixture

`examples/migration/pega-native/purchase-connected.zip` (synthetic, `generate.py`): case type ConnectedPurchase with stage Intake (process Order, optional process Escalate, service level IntakeSLA 2 days with an 8-hour goal), stage Close (process Close), alternate stage Withdrawn (process Withdraw), case service level CaseSLA 5 days and validate rule RequestChecks (Title required, max 120; Amount required, 1 to 1,000,000). Flow Order: manager review, then Place the order through connector CreateOrder (POST, timeout 10 s; request transform OrderRequest from Title, Amount, CostCenter; response OrderId from `/id`). A read-only data page D_OrderPlacement on the same connector. Expected resolutions in `expected/purchase-connected.json`.

## Workbench

`PegaNativeReview` lists each call step with its source connector, method and endpoint and asks for a workspace connection and operation (`useConnections`). For a case type it shows a **Convert to a case definition** panel: case key and name, the published workflow for each process flow, a group for each optional process and alternate stage, an acknowledgement, **Preview case definition** (dry run: fields, workflow tasks, generated CMMN with its hash, open findings on refusal) and **Create case definition**, enabled only after a successful preview. A recorded conversion is shown instead of the panel.

## Verification - 29 October 2026

| Check | Result |
|---|---|
| Python tool tests (`python -m unittest discover -s tools/migration-equivalence -p "test_*.py"`), `generate.py --check` | 43 pass (process starting points on all fixtures; purchase-connected in stage order); 8 fixture files match |
| `PegaNativeRound3Test` (oracle parity on four fixtures with process starts; the Order flow converts with an `http` call step and compiles; data page as call and its refusals; transform and connector refusals; validate rule as form validation and the declare message; the case definition passes `CmmnSubsetValidator`, plus mapping, field-check and timer refusals) | 6/6 pass |
| `PegaNativeTest` (S112/S115 behaviour; the refusals fixture's connector without a method is now a grammar refusal on its page, since connectors convert) | 7/7 pass |
| `ArchitectureTest` (module boundaries, with `migration` now allowed to use `cases`) | 6/6 pass |
| Compiler module (`mvnw -pl compiler clean verify`) | 641/641 pass |
| Frontend: full Vitest suite, typecheck, ESLint, `api:check` | 174 files, 1,246/1,246 tests (2 new: call-step connection mapping and case-definition preview with refusal; recorded case conversion); typecheck, full ESLint and `api:check` pass |
| Contract checks (`tools/contract-check/contract_check.py`) | 88/88 pass |
| E2E specs (`tests/e2e`, `npx tsc --noEmit`) | Type-check |
| `PegaNativeRound3ApiTest`, `PegaNativeApiTest`, `SecurityMatrixTest` and the full application suite on PostgreSQL/Flowable | **Not run**: Docker Desktop was restarting and its engine did not answer during this session (Testcontainers needs it). `PegaNativeRound3ApiTest` covers the call step against the procurement stub, publishing the four process workflows, case definition preview/403/create/replay/409, and a case running its first workflow task |

No Docker stack was started, stopped or rebuilt.

## Real-stack E2E specs

`tests/e2e/specs/t-mig-07-pega-native-round3.spec.ts` (purchase-connected archive: the Order flow's call step mapped to `procurement`/`createOrder`, converted, published and run to completion against the procurement stub after manager approval; Escalate, Close and Withdraw converted and published; the case type previewed and created as a case definition in the workbench; a case started whose first workflow task runs the Order workflow to completion). `t-mig-05` uses an exact cell locator for the shadowed flow (the strict-mode failure in the live run: the path also appears in a finding line). `t-mig-06` selects its starting points by rule path, so the new process starting points do not change it. The specs type-check (`npx tsc --noEmit`) and are skipped in mock mode; they were not run here because the live stack was down.

## Not covered

- AC01/EXT01: real exports, source-runtime equivalence, source-owner sign-off.
- Connectors other than Connect-REST (SOAP, SQL, file), authentication profiles, response error handling and retries beyond the connection's own policy.
- Data pages with parameters, refresh strategies, requestor or node scope, or lists; declare expressions and other declarative rules.
- Case types: case-wide data propagation into workflow tasks beyond the converted fields, stage validation, case-level escalations, child cases, business-day timers.
