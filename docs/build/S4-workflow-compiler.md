# S4 — Canonical workflow model and compiler
Build specification · revision 1.0 · 7 October 2026

Contracts: [`workflow-v0.schema.json`](../../contracts/workflow-v0.schema.json), [`connection-definition-v0.schema.json`](../../contracts/connection-definition-v0.schema.json). Fixtures: [`compiler-cases.json`](../../examples/fixtures/compiler-cases.json) (25 cases; the schema-layer outcomes were verified during this review) and [`purchase-scenarios.json`](../../examples/fixtures/purchase-scenarios.json).

The compiler is a pure Java module (`com.orqix.compiler`) with no Spring, Flowable, JDBC or network dependencies. Workspace facts (groups, connection definitions and bindings, test flag) arrive as an immutable `CompileContext`. Flowable is called only by `runtime` to deploy what the compiler produced.

## 1. Pipeline

| Stage | Input → output | Stops on |
|---|---|---|
| 1 Parse | bytes → JSON tree; size ≤ 256 KiB, depth ≤ 32, numbers as `BigDecimal`, duplicate keys rejected | Any error |
| 2 Version | `schemaVersion` must be `"0.1"` | `WF_SCHEMA_VERSION_UNSUPPORTED` |
| 3 Schema | JSON Schema 2020-12 validation; one `WF_SCHEMA_INVALID` issue per error (max 50) with its JSON Pointer | Any error (later stages need a well-formed model) |
| 4 Model | Tree → typed records (`WorkflowDefinition`, sealed `Node` hierarchy) | — |
| 5 Graph | Structure rules (§5.1) | Collects all issues |
| 6 Types | Field types, availability, decisions, branches, forms (§5.2) | Collects all issues |
| 7 Bindings | Groups, connections, operations, policy (§5.3) | Collects all issues |
| 8 Generate | BPMN, DMN, source map, manifest, hashes (§6–§9) | Only when no blockers |

`POST /validate` runs stages 1–7. Publish runs 1–8 and deploys. Issues are sorted blockers first, then by `path`, then by `code`, so output is deterministic.

## 2. Node semantics (v0)

| Kind | Business label | Allowed outgoing (`on`) | Outputs at `/steps/<id>/…` | Runtime construct |
|---|---|---|---|---|
| `start` | Request form | exactly one `next`; no incoming | — (input is `/input/…`) | `startEvent` |
| `approval` | Ask for approval | exactly one `approved` and one `rejected` | `decision` (string: approved\|rejected), `reason` (string\|absent), `comment` (string\|absent), `decidedBy` (string), `decidedAt` (string) | `userTask` + generated `exclusiveGateway` |
| `decision` | Make a decision | exactly one `next` | each key of the table's `then`/`default` | `serviceTask` → Flowable DMN, pinned to the release deployment |
| `branch` | Choose a path | ≥ 1 `condition`, exactly one `default` | — | `exclusiveGateway` with `default` flow |
| `http` | Call a system | exactly one `next` | each key of `resultMapping` | async `serviceTask` + effect journal |
| `notification` | Notify someone | exactly one `next` | — | `serviceTask` writing an in-app notification |
| `end` | Finish | none | — | `endEvent` + outcome |

Graphs are acyclic in v0. Any node may have several incoming transitions (simple merge), e.g. both rejection paths in the purchase template reach `notifyRejected`.

## 3. Types

| Source | Type |
|---|---|
| `/input/<k>` | From `inputSchema.properties.k`: `string` (with `enum` → finite string domain), `number`, `integer`, `boolean`, `date` (a `string` with `format: date`: a calendar date `YYYY-MM-DD`; usable by a wait and as a text or date mapping source, not in comparisons) |
| `/steps/<approval>/decision` | string, domain {approved, rejected} |
| `/steps/<approval>/reason`, `comment`, `decidedBy`, `decidedAt` | string (reason/comment may be absent) |
| `/steps/<decision>/<k>` | Type shared by every row and the default for key `k`; for strings the domain is the set of literal values |
| `/steps/<http>/<k>` | Type of the response-schema property at `resultMapping.k` |

Operators: `eq` on any type (number comparison uses `BigDecimal.compareTo`, so `5000` equals `5000.00`); `gte`/`lt` only on `number`/`integer`. The literal must have the field's type.

**Literal safety.** String literals that reach generated artifacts (condition values, decision outputs) must match `^[A-Za-z0-9 _.,:/-]{0,100}$` (`WF_LITERAL_UNSAFE`). Labels, names and messages must not contain `${` or `#{` (`WF_LABEL_EXPRESSION`), because Flowable evaluates expressions in some attributes such as task names. Numbers are emitted with `BigDecimal.toPlainString()`.

## 4. Field availability (dominance)

Let *X dominates N* mean every path from `start` to `N` passes through `X`. Compute dominators once per graph (≤ 50 nodes; the iterative algorithm is sufficient).

1. A reference `/steps/X/k` used **at** node `N` (a branch condition is used at its branch; a decision row at its decision node; an http input mapping at its http node) requires `X ≠ N` and `X` dominates `N` → otherwise `WF_FIELDREF_NOT_GUARANTEED`.
2. `outputMapping` entries with `required: true` require their producer to dominate **every** end node → otherwise `WF_OUTPUT_NOT_GUARANTEED`. Entries with `required: false` only require the producer and key to exist; a missing value at run end is omitted.
3. `/input/*` is always available.

## 5. Validation rule catalog

Severity **B** = blocker (cannot publish), **W** = warning (publish requires `acknowledgeWarnings: true`; kept in the release).

### 5.1 Graph

| Code | Sev | Rule |
|---|---|---|
| `WF_DUPLICATE_ID` | B | Node and transition IDs share one namespace (both become BPMN IDs) |
| `WF_TRANSITION_UNKNOWN_NODE` | B | `from`/`to` must name an existing node |
| `WF_START_COUNT` | B | Exactly one `start` |
| `WF_START_INCOMING` | B | `start` has no incoming transitions |
| `WF_NO_END` | B | At least one `end` |
| `WF_END_OUTGOING` | B | `end` has no outgoing transitions |
| `WF_OUTGOING_INVALID` | B | Outgoing `on` values/counts must match §2 for the node kind |
| `WF_APPROVAL_ROUTES` | B | Approval needs exactly one `approved` and one `rejected` route |
| `WF_BRANCH_DEFAULT` | B | Branch needs exactly one `default` route |
| `WF_BRANCH_NO_CONDITION` | B | Branch needs at least one `condition` route |
| `WF_CYCLE` | B | No cycles (including self-loops) in v0 |
| `WF_UNREACHABLE_NODE` | B | Every node reachable from `start` |
| `WF_DEAD_END` | B | Every node can reach an `end` |
| `WF_BRANCH_SINGLE_TARGET` | W | All routes of a branch lead to the same node |

### 5.2 Types, decisions, forms

| Code | Sev | Rule |
|---|---|---|
| `WF_FIELDREF_UNKNOWN` | B | Referenced input field, step or output key does not exist |
| `WF_FIELDREF_NOT_GUARANTEED` | B | §4 rule 1 |
| `WF_OPERATOR_TYPE` | B | `gte`/`lt` on a non-numeric field; any comparison of a date field |
| `WF_LITERAL_TYPE` | B | Literal type differs from field type |
| `WF_LITERAL_NOT_IN_DOMAIN` | B | `eq` against a value the field can never hold (enum or decision output domain) |
| `WF_LITERAL_UNSAFE` | B | §3 literal safety |
| `WF_LABEL_EXPRESSION` | B | Label/name/message contains `${` or `#{` |
| `WF_DECISION_UNKNOWN` | B | `decisionRef` not in `decisions` |
| `WF_DECISION_DUPLICATE_KEY` | B | Two tables share a key |
| `WF_DECISION_MIXED_FIELDS` | B | All rows of a table must test the same `fieldRef` |
| `WF_DECISION_OVERLAP` | B | Rows must be pairwise disjoint (§5.4) |
| `WF_DECISION_OUTPUT_SHAPE` | B | Every row and the default have identical output keys and per-key types |
| `WF_DECISION_DEFAULT_UNREACHABLE` | W | Rows cover the whole domain, so the default never applies |
| `WF_DECISION_UNUSED` | W | Table not referenced by any decision node |
| `WF_BRANCH_MIXED_FIELDS` | B | All condition routes of a branch test the same `fieldRef` |
| `WF_BRANCH_OVERLAP` | B | Conditions of one branch must be pairwise disjoint |
| `WF_FORM_INVALID` | B | `required` names unknown fields; min > max; keyword not valid for the field type (e.g. `enum` on number, `multipleOf: 0.01` on integer); a date question whose `formatMinimum`/`formatMaximum` is not a real day or whose earliest date is after its latest |
| `WF_WAIT_CHOICE` | B | A wait has exactly one of `duration` and `untilField` |
| `WF_WAIT_FIELD_TYPE` | B | `untilField` must name a date field |
| `WF_WAIT_FIELD_OPTIONAL` | B | A request-form date used by a wait must be required |
| `WF_MESSAGE_CORRELATION_UNKNOWN` | B | The field that identifies the request of a "Wait for a message" step does not exist (S16) |
| `WF_MESSAGE_CORRELATION_TYPE` | B | That field is not text or a whole number |
| `WF_MESSAGE_CORRELATION_OPTIONAL` | B | An optional request field cannot identify a request |
| `WF_MESSAGE_PAYLOAD_DUPLICATE` | B | A message value is declared twice |
| `WF_MESSAGE_TIMEOUT_ROUTE` | B | A message wait with a time limit needs exactly one `timedOut` path |
| `WF_MESSAGE_DUPLICATE_WAIT` | B | Two waits for the same message and field in different parallel paths |
| `WF_UI_UNKNOWN_FIELD` | W | `inputUi` names a field that does not exist |
| `WF_UI_WIDGET_MISMATCH` | W | Widget incompatible with type (`money` needs number with `multipleOf: 0.01`; `select`/`radio` need `enum`; `checkbox` needs boolean) |
| `WF_OUTPUT_MAPPING_UNKNOWN` | B | `outputMapping` key not in `outputSchema`, or incompatible type |
| `WF_OUTPUT_MAPPING_MISSING` | B | A field in `outputSchema.required` lacks a `required: true` mapping |
| `WF_OUTPUT_NOT_GUARANTEED` | B | §4 rule 2 |
| `WF_LAYOUT_UNKNOWN_NODE` | B | `uiLayout.positions` names a missing node (editor must prune on delete) |
| `WF_STEP_NOTE_UNKNOWN_NODE` | B | `metadata.stepNotes` names a missing step; editor moves notes on rename and prunes them on delete |
| `WF_LABEL_STYLE` | W | Label shorter than 3 characters or a placeholder such as "Step 1", "Task", "Node" |

### 5.3 Workspace bindings and policy

| Code | Sev | Rule |
|---|---|---|
| `WF_GROUP_UNBOUND` | B | `candidateGroupRef` is not a group in the workspace |
| `WF_GROUP_EMPTY` | W | Group has no members configured (IdP group membership cannot be counted) |
| `WF_CONNECTION_UNDECLARED` | B | http node uses a connection missing from `dependencies.connections` |
| `WF_DEPENDENCY_UNUSED` | W | Declared connection not used by any node |
| `WF_CONNECTION_UNKNOWN` | B | Connection key/version not registered in the workspace |
| `WF_CONNECTION_UNBOUND` | B | No enabled binding for the connection in this workspace |
| `WF_OPERATION_UNKNOWN` | B | Operation not in the pinned definition version |
| `WF_MAPPING_UNKNOWN_TARGET` | B | `inputMapping` key not in the operation `requestSchema` |
| `WF_MAPPING_MISSING_REQUIRED` | B | A required request field is not mapped |
| `WF_MAPPING_TYPE` | B | Source type incompatible with target (number→integer only from integer; enum source must be a subset of an enum target) |
| `WF_RESULT_POINTER_UNKNOWN` | B | `resultMapping` pointer does not resolve to a scalar in `responseSchema` |
| `WF_RESULT_OPTIONAL` | W | Pointer targets a non-required response property; a missing value at runtime becomes `RESPONSE_INVALID` |
| `WF_TIMEOUT_EXCEEDS_OPERATION` | B | `timeoutSeconds` > operation `maxTimeoutSeconds` |
| `WF_SELF_APPROVAL_NOT_ALLOWED` | B | `security.selfApproval = allow` in a workspace that does not permit it |
| `WF_SELF_APPROVAL_ALLOWED` | W | Self-approval allowed (test workspace); every such decision is flagged and audited |

Parse/schema codes: `WF_PAYLOAD_TOO_LARGE`, `WF_JSON_DEPTH`, `WF_SCHEMA_VERSION_UNSUPPORTED`, `WF_SCHEMA_INVALID`.

### 5.4 Disjointness (decisions and branches)

Every condition is a set over one field's domain:

| Operator | Number set | String / boolean set |
|---|---|---|
| `eq v` | [v, v] | {v} |
| `gte v` | [v, +∞) | not allowed |
| `lt v` | (−∞, v) | not allowed |

Two conditions overlap when their sets intersect (interval intersection with open/closed endpoints, `BigDecimal` endpoints). Example: `gte 5000` and `gte 10000` overlap → `WF_DECISION_OVERLAP`; `gte 5000` and `lt 5000` do not, and together cover the domain → `WF_DECISION_DEFAULT_UNREACHABLE` (warning).

## 6. BPMN generation

Namespaces: `bpmn` = `http://www.omg.org/spec/BPMN/20100524/MODEL`, `flowable` = `http://flowable.org/bpmn`, `orqix` = `https://orqix.example/bpmn/v0` (extension elements, read back by Orqix beans from Flowable's parsed `BpmnModel`).

**Naming:** canonical IDs are used unchanged as BPMN IDs (the schema pattern is a valid XML NCName). Compiler-generated elements use `<canonicalId>__<suffix>`; canonical IDs cannot contain `__`, so collisions are impossible. Process `id` = `workflowKey`; Flowable versions it per tenant, Orqix always starts by the stored `processDefinitionId`.

**Expressions:** generated XML contains exactly these expression forms and nothing else; user text never appears inside `${…}`:

| Expression | Purpose |
|---|---|
| `${orqixRoute.approved(execution, '<id>')}` / `${orqixRoute.rejected(execution, '<id>')}` | Approval outcome routing |
| `${orqixRoute.condition(execution, '<transitionId>')}` | Branch condition (structured condition read from the flow's `orqix:condition`) |
| *(none)* | Message waits (S16) add `message`, `eventBasedGateway` and message/timer catch events with literal names; no expression is involved, the allowlist is unchanged |
| `${orqixWait.until(execution, '<id>')}` | Due moment of a "wait until a date" step (`timeDate` of its timer; S12 section 10) |
| `${orqixDecisionTask}`, `${orqixHttpTask}`, `${orqixNotificationTask}`, `${orqixChildTask}` | Delegate expressions |

The engine is configured with an explicit expression bean map containing only `orqixRoute`, `orqixWait`, `orqixDecisionTask`, `orqixHttpTask`, `orqixNotificationTask` and `orqixChildTask` (Flowable `beans` property), so no other Spring bean is reachable from any expression (verified in spike S-2).

Templates (purchase example; whitespace normative only for golden files):

```xml
<startEvent id="start" name="Request submitted"/>

<userTask id="manager" name="Review request" flowable:candidateGroups="managers">
  <extensionElements>
    <orqix:approval rejectReasonRequired="true" dueAfterHours="24"/>
  </extensionElements>
</userTask>
<sequenceFlow id="manager__out" sourceRef="manager" targetRef="manager__route"/>
<exclusiveGateway id="manager__route" name="Review request: outcome"/>
<sequenceFlow id="t2" sourceRef="manager__route" targetRef="amountPolicy">
  <conditionExpression xsi:type="tFormalExpression"><![CDATA[${orqixRoute.approved(execution, 'manager')}]]></conditionExpression>
</sequenceFlow>
<sequenceFlow id="t3" sourceRef="manager__route" targetRef="notifyRejected">
  <conditionExpression xsi:type="tFormalExpression"><![CDATA[${orqixRoute.rejected(execution, 'manager')}]]></conditionExpression>
</sequenceFlow>

<serviceTask id="amountPolicy" name="Check purchase amount" flowable:delegateExpression="${orqixDecisionTask}">
  <extensionElements>
    <orqix:decision ref="amount-policy" version="1" resource="decisions/amount-policy.dmn"/>
  </extensionElements>
</serviceTask>

<exclusiveGateway id="route" name="Finance review needed?" default="t6"/>
<sequenceFlow id="t5" name="USD 5,000 or more" sourceRef="route" targetRef="finance">
  <extensionElements>
    <orqix:condition fieldRef="/steps/amountPolicy/route" operator="eq" valueType="string" value="financeReview"/>
  </extensionElements>
  <conditionExpression xsi:type="tFormalExpression"><![CDATA[${orqixRoute.condition(execution, 't5')}]]></conditionExpression>
</sequenceFlow>
<sequenceFlow id="t6" name="Under USD 5,000" sourceRef="route" targetRef="procure"/>

<serviceTask id="procure" name="Create purchase order" flowable:async="true" flowable:exclusive="true"
             flowable:delegateExpression="${orqixHttpTask}">
  <extensionElements>
    <flowable:failedJobRetryTimeCycle>R3/PT30S</flowable:failedJobRetryTimeCycle>
    <orqix:http connection="procurement" connectionVersion="1" operation="createOrder" timeoutSeconds="20">
      <orqix:input name="title" fieldRef="/input/title"/>
      <orqix:input name="amount" fieldRef="/input/amount"/>
      <orqix:input name="costCenter" fieldRef="/input/costCenter"/>
      <orqix:result name="procurementId" pointer="/id"/>
    </orqix:http>
  </extensionElements>
</serviceTask>

<serviceTask id="notify" name="Tell requester it is ordered" flowable:delegateExpression="${orqixNotificationTask}">
  <extensionElements>
    <orqix:notification channel="inApp" recipient="initiator" message="Your purchase request was approved and the purchase order has been created."/>
  </extensionElements>
</serviceTask>

<endEvent id="fulfilled" name="Fulfilled">
  <extensionElements><orqix:outcome label="Fulfilled" kind="success"/></extensionElements>
</endEvent>
```

The retry cycle string is produced by one function `retryCycle(maxAttempts, retryDelaySeconds)`; spike S-4 confirmed that Flowable treats `R<n>` as the total number of attempts, so the compiler emits `R{maxAttempts}` (`R3` for 3 attempts, as above; see ADR 0001). User tasks are never retried.

No task or execution listeners are generated. Projections are maintained by one engine-wide event listener (S5 §2), so imported or regenerated BPMN cannot forget them.

Process-level extension: `<orqix:release schemaVersion="0.1" compilerVersion="…" semanticHash="…"/>`. The release UUID is **not** embedded (it would break determinism).

BPMN diagram interchange (`bpmndi`) is generated from `uiLayout` when present (ticket B03.3, priority P2); execution never depends on it.

## 7. DMN generation

One DMN file per referenced table, deployed in the **same** Flowable deployment as the BPMN. The decision delegate evaluates it pinned to that deployment (spike S-3 chooses `parentDeploymentId` on the DMN execute builder or `sameDeployment`). Namespace: the newest DMN namespace Flowable 8 parses (expected DMN 1.3, `https://www.omg.org/spec/DMN/20191111/MODEL/`; confirm in S-3). OMG's current formal DMN is 1.6 (September 2026); that does not imply engine support for 1.6 features.

Canonical `UNIQUE` + `default` compiles to hit policy `FIRST` with the user rows (already proven disjoint, so order is irrelevant) followed by one catch-all row (`-`) carrying the default. This gives exactly "at most one row, else default" without an overlapping `UNIQUE` row.

```xml
<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="dmn_amount-policy_v1"
             name="amount-policy" namespace="https://orqix.example/decisions">
  <decision id="amount-policy" name="amount-policy">
    <decisionTable id="amount-policy__table" hitPolicy="FIRST">
      <input id="amount-policy__in" label="/input/amount">
        <inputExpression id="amount-policy__inexpr" typeRef="number"><text>in1</text></inputExpression>
      </input>
      <output id="amount-policy__out_route" name="route" typeRef="string"/>
      <rule id="amount-policy__r1">
        <inputEntry id="amount-policy__r1_in"><text><![CDATA[>= 5000]]></text></inputEntry>
        <outputEntry id="amount-policy__r1_route"><text><![CDATA["financeReview"]]></text></outputEntry>
      </rule>
      <rule id="amount-policy__default">
        <inputEntry id="amount-policy__default_in"><text>-</text></inputEntry>
        <outputEntry id="amount-policy__default_route"><text><![CDATA["standard"]]></text></outputEntry>
      </rule>
    </decisionTable>
  </decision>
</definitions>
```

Input-entry syntax per operator: `gte` → `>= n`, `lt` → `< n`, `eq` number → `== n`, `eq` string → `== "s"`, `eq` boolean → `== true|false` (S-3 confirms Flowable's accepted forms). The delegate passes `in1` as `BigDecimal`/`String`/`Boolean`.

**Draft testing (`evaluateDecision`)** uses the canonical Java evaluator. A parity test runs every `decisionBoundaries` fixture through both the canonical evaluator and the compiled DMN in Flowable; any difference fails the build.

## 8. Process variables

| Variable | Type | Content |
|---|---|---|
| `orqix_runId`, `orqix_workspaceId`, `orqix_initiator` | string | UUIDs |
| `orqix_input` | string (JSON text) | Validated input |
| `orqix_step_<nodeId>` | string (JSON text) | Outputs of that node (§2) |

Orqix data is stored as JSON **text** and parsed only by Orqix code configured with `BigDecimal` for floats, so Flowable's JSON variable mapper can never change money precision. Generated expressions never read these variables directly.

## 9. Source map, hashes, determinism

*Note (S12 section 10):* waits until a date add the `timeDate` construct and the `orqixWait` expression to the BPMN. They are produced only for definitions that earlier compilers rejected, so no existing artifact changed and `compilerVersion` stays `0.2.0` for now; a release process that wants every new construct to carry a new version bumps it to `0.3.0` together with all golden files.

- **Source map:** `nodes` (canonical id → BPMN ids), `transitions` (canonical id → flow ids), `bpmnToCanonical` (reverse). Generated helpers map back to their owning node, so runtime events on `manager__route` display as "Review request".
- **Hashes:** `sourceHash` = SHA-256 of the RFC 8785 (JCS) form of the full definition; `semanticHash` = same without `uiLayout` and `metadata.stepNotes`; `bpmnHash`, `dmnHashes` = SHA-256 of the exact bytes deployed. Plain-text design notes are limited to 2,000 characters per step and retained in the immutable release definition and export. They do not affect execution, but changing them changes the source hash and invalidates migration approval tied to the exact draft.
- **Determinism:** same definition + context + compiler version → byte-identical artifacts. StAX writer with fixed attribute order, UTF-8, LF, 2-space indent; element order follows canonical array order with each node's helpers immediately after it; no timestamps, random IDs or environment values.
- **Golden files:** `backend/src/test/resources/golden/purchase-approval/{process.bpmn20.xml, decisions/amount-policy.dmn, source-map.json}`. Any intentional output change updates the golden files **and** bumps `compilerVersion` (semver: minor for new constructs, patch for formatting-only).

## 10. Publish transaction

Single database transaction (Flowable deploys inside the Spring transaction; spike S-2 proves rollback removes the deployment):

1. Authorize `workflow.publish`; claim the idempotency key; `SELECT … FOR UPDATE` the workflow row (serializes release numbering).
2. Load the requested draft revision (any existing revision; the release records which).
3. Run stages 1–7 with a fresh `CompileContext`. Blockers → `422 WORKFLOW_INVALID` with `issues`. Warnings without `acknowledgeWarnings` → `422 WARNINGS_NOT_ACKNOWLEDGED`.
4. Generate artifacts and hashes.
5. Deploy: name `<workflowKey> r<n>`, key = workflow UUID, tenant = `workspace.engine_tenant_id`, category `orqix`, resources `process.bpmn20.xml` and `decisions/<key>.dmn`; engine XML schema validation stays **on**.
6. Resolve the `processDefinitionId` for the deployment.
7. Insert `workflow_release` (number = max + 1) and `release_artifact` rows; build the manifest.
8. If `activate` (default true): set `workflow.active_release_id`, increment `workflow.version`.
9. Audit `workflow.published`, complete the idempotency record, commit.

Running instances are untouched by construction: they hold their own `processDefinitionId`.

## 11. Export bundle

Deterministic ZIP (sorted entries, fixed timestamps) named `<workflowKey>-r<n>.zip`:

```
manifest.json          ReleaseManifest
canonical.json         release definition (uiLayout included)
process.bpmn20.xml
decisions/<key>.dmn
source-map.json
connections/<key>-v<version>.json   connection definitions only — never bindings or secrets
BINDINGS.md            required groups and connection keys/versions for the target environment
```

A secret scan over the bundle runs in tests (T-SEC-08).

## 12. Pattern acceptance matrix

A pattern is supported only when engine semantics, canonical/import representation, editor/API exposure and tests all exist. Patterns outside the current subset are rejected even if Flowable could run them.

| Pattern | Representation | Target | Required behavior test |
|---|---|---|---|
| Sequence | Steps connected by `next` | R0 | Next step never starts before its predecessor completes (T-RUN-02) |
| Exclusive choice | Approval routes; branch conditions + default | R0 | Exactly one route taken for each fixture input (T-RUN-03, T-COMP-05) |
| Simple merge | Several transitions into one node (e.g. `notifyRejected`) | R0 | Merge node executes once per run, without waiting for the unused path (T-RUN-04) |
| Parallel split / synchronization | Matched fork/join | R1 (E04) | Both completion orders; one join continuation; restart during parallel work |
| Bounded rework | Loop with occurrence counter | R1 (E04) | New occurrence ID and task per visit; prior decisions immutable |
| Timers, call activity, multi-instance, inclusive, compensation | Future node contracts | R1–R3 | Defined with each epic before exposure |

References: [BPMN 2.0.2](https://www.omg.org/spec/BPMN/2.0.2/About-BPMN) (current OMG formal version), [DMN](https://www.omg.org/spec/DMN) (1.6 formal, September 2026), [RFC 8785 JCS](https://www.rfc-editor.org/rfc/rfc8785), [RFC 6901 JSON Pointer](https://www.rfc-editor.org/rfc/rfc6901). The pinned Flowable release and these tests define the actually supported subset.

## 13. Dry run ("Test this workflow")

`WorkflowSimulator` (`com.orqix.compiler`, implementation `internal/sim`) walks a canonical definition for a sample request and a scenario, with no engine, no I/O and no clock except the date it is given. It exists so a designer can try a workflow before publishing it. It is **not the engine** and it is not a proof (section 13.4).

### 13.1 Inputs and what runs

| Input | Meaning |
|---|---|
| `definitionJson` | A draft revision or a release definition. Stages 1 to 7 of the pipeline run first. Blockers that only say the workspace is not set up yet (`WF_GROUP_UNBOUND`, `WF_CONNECTION_UNKNOWN`, `WF_CONNECTION_UNBOUND`, `WF_CHILD_UNKNOWN`, `WF_CHILD_NOT_PUBLISHED`, `WF_SELF_APPROVAL_NOT_ALLOWED`) become warnings; any other blocker returns `CANNOT_RUN` with the issues (API: 422 `WORKFLOW_INVALID`). |
| `inputJson` | The sample request. Validated by the production `FormValidator` against `inputSchema`; failures return `INVALID_INPUT` with the same field errors as starting a request (API: 422 `VALIDATION_FAILED`, pointers under `/input`). |
| `scenarioJson` | What happens where a person or system decides (below). Everything defaults to the happy path. |
| `SimulationContext` | The `CompileContext` of the workspace (groups, connection definitions and bindings, child workflow facts), a function returning the active release definition of a child workflow, and today's date (UTC). |

Scenario members, all optional, all by step ID: `approvals[id] = {outcome: approve|reject|timeout, reason}`; `calls[id] = {failure: rejected|unavailable|unknownOutcome} | {result: <response body>}`; `children[id] = {outcomes: [<business outcome per occurrence>], results}`; `messages[id] = arrives|timeout` (steps that wait for a message, when the kind exists); `simulateChildren` with `childScenarios[id]`. A malformed value throws `IllegalArgumentException` (API: 422 under `/scenario`); a key naming no matching step is ignored with a `SCENARIO_UNKNOWN_STEP` warning.

### 13.2 Semantics

- **One code for conditions.** Branch conditions use `ConditionMatcher.matches` (the class the runtime route bean uses); decision tables use `DecisionEvaluator.evaluate` (the class behind the studio "Test this rule" endpoint) and the simulator additionally evaluates every row with `ConditionMatcher` to show which rows applied; it throws if the two ever disagree. Numbers compare as `BigDecimal` (4,999.99 < 5,000 = 5,000.00 < 5,000.01). The generated DMN is checked against `DecisionEvaluator` by the existing parity tests, so the chain simulator = evaluator = DMN holds without a second implementation. Compound conditions short-circuit like `orqixRoute.condition`.
- **Walk.** Graphs are acyclic, so the walk is depth first; the first matching transition in canonical order is taken. Approval: `approve` follows `approved`, `reject` follows `rejected` with a reason (default "Sample rejection reason" when the step requires one), `timeout` follows `timedOut` (a step without a time limit is treated as approved, with a note). Values are stored at `/steps/<id>/...` exactly as the engine does, so later conditions, mappings and outputs resolve.
- **Call a system / agent.** Success builds the response from `calls[id].result` or, by default, a sample document built from the operation's `responseSchema` (enum first value, number 1 clamped into its range, text `sample-<field>`, boolean false), then applies `resultMapping`. Failures follow S5 section 6: `rejected` is permanent (`EFFECT_REJECTED`), `unavailable` is retried `maxAttempts` times then `RETRIES_EXHAUSTED`, `unknownOutcome` is `EFFECT_UNKNOWN` (or `RESPONSE_INVALID` for a read-only operation). Every failure ends the walk with status `needsAttention` at that step. A result without a mapped pointer is an unknown outcome.
- **Waits** are skipped with a sentence ("would wait 2 days", "would wait until 14 Oct 2026, 09:00 UTC", or "the date has already passed"). Time limits fire only when the scenario says `timeout`.
- **Parallel branches** run in canonical order up to their join; the join continues once. A branch that reaches a Finish step ends the whole request (the engine terminates the other branches; the remaining ones are marked "not tested"); a branch that needs attention does not stop its siblings, and the request then never joins. Each trace entry carries its branch (`1`, `2.1`).
- **Child workflows** use the child's Finish outcomes: default outcome is the child's first successful one (or the outcome a repeat waits for); `children[id].outcomes` chooses per occurrence. `repeat` stops on `untilOutcome` or at `maxIterations`, `forEach` runs one occurrence per item up to `maxItems`; `iterations`, `limitReached`, `allSucceeded` and `collectionMapping` arrays are produced as in S14. With `simulateChildren` the child's own release is simulated (at most 3 levels, own scenario in `childScenarios`), its trace is nested in `details.occurrences[].trace`, and a child that needs attention holds the parent (`CHILD_NEEDS_ATTENTION`).
- **Bounds.** At most 400 trace entries (`TEST_LIMIT` attention), 64 KiB of sample request plus scenario at the API, 30 runs a minute per person.

### 13.3 Result

`{outcome, outputs, trace[], warnings[], coverage, limits}`. Each trace entry has `seq`, `stepId`, `stepLabel`, `kind`, `branch?`, a plain-language `summary` ("Routed to Finance because Amount (USD 5,000) is at least USD 5,000"), kind-specific `details` (every decision row with the values used and which one matched, branch routes and terms, assigned group, the request and kept result of a call, the notification text), `wrote`, and the `transitionId` taken. Warnings: tolerated blockers, compiler warnings, `RULE_NEVER_MATCHES` (a rule that no allowed request answer can satisfy), `SCENARIO_UNKNOWN_STEP`. Coverage counts steps visited, routes tested (transitions leaving a step with a choice) and decision rules used, and lists what was not tried.

The sentences are part of the contract between implementations: `examples/fixtures/simulation/cases.json` with one `<id>.trace.json` per case (purchase at 1,800 / 4,999.99 / 5,000 / 5,000.01, rejections, the three failure kinds, onboarding with a timeout, a rejection in a branch and a simulated child, the wait-until-date example, bounded repetition and a collection) is replayed by `WorkflowSimulatorGoldenTest` (Java) and by `frontend/src/mocks/simulator.test.ts` (the mock port). Regenerate with `-Dorqix.updateGolden=true` after an intentional change and review the diff.

### 13.4 Why a dry run is not a proof

The simulator answers "if people and systems behave as the scenario says, what does this definition do?". It cannot answer whether they will. It does not contact any system, so it does not test that a connection has a working binding, that a host is reachable or allowed, that credentials work, or that the real response has the shape the schema promises. It does not know who is in a group, so it cannot show that anyone could pick up a task, or that separation of duties would allow the person who did. It skips all waiting, so it shows no timing, no overlap of branches in real time, no concurrency effects and no restart behaviour. It uses the compiler's model, not the generated BPMN, so a defect in the generator is invisible to it (the golden files and the engine tests cover that). A passing test of three scenarios says nothing about the fourth: the coverage summary lists what was not tried. The studio says so in the panel, and the result carries the same text in `limits`.

### 13.5 API and audit

`POST /workspaces/{ws}/workflows/{workflowId}/simulate` (`simulateWorkflow`, permission `workflow.read`, no persistence). Body `{revision?, useActiveRelease?, input, scenario?}`; the draft revision defaults to the current one. 200 returns the result plus `source {revision, releaseNumber}`. 422 `VALIDATION_FAILED` (sample request or scenario), `WORKFLOW_INVALID` (issues), `NO_ACTIVE_RELEASE`; 413 over 64 KiB; 429 `RATE_LIMITED` with `Retry-After` after 30 runs a minute per person (per pod). The audit event `workflow.simulated` carries only `revision` or `releaseNumber`, `steps`, `warnings` and `status`: never the sample request, scenario or trace. The mock API answers with the mock-side port (`frontend/src/mocks/simulator.ts`), which has no rate limit and uses JavaScript numbers (exact for money with two decimals).
