# Migration strategy: PEGA, Camunda and LWFS
Status: a supported Camunda 7/8 subset (including comparison operators and and/or groups, grouped service mappings, bounded multi-instance calls and DMN decision tables with hit policies UNIQUE and FIRST) and Pega archive assessment with reviewed manual mappings are implemented. Synthetic fixtures are tested; customer source exports have not been supplied. See the [migration workbench](17-migration-workbench.md) for current behavior and limits. The broader adapters below remain a strategy.

## Migration product contract

Convert supported definitions into a reviewable draft, with evidence of every mapping and every gap. Definition migration does not automatically migrate live instances, history, users, attachments, credentials or external integration code.

Every import preserves original bytes, a source checksum and adapter version. Every source element receives a disposition: preserved, mapped, manual remediation, unsupported or intentionally excluded with explicit acknowledgement. Silent omission is prohibited.

Pipeline: upload → safe parse → identify source/version → inventory → compatibility report → mapping → canonical draft → validate → compare test outcomes → publish by an authorized user.

The UI may show a preliminary diagram before the draft is publishable. Blockers cannot be removed merely by dismissing a warning.

## Camunda 7 and Camunda 8 are separate adapters

BPMN syntax alone does not guarantee runtime equivalence. Record whether the source uses Camunda 7 extensions or Camunda 8/Zeebe extensions.

| Source construct | Proposed treatment | First-release boundary |
|---|---|---|
| None start / end, sequence flow | Map to canonical start/end/transitions | Conditional stretch |
| User task | Map assignment and form only when supported | Group/form remediation often required |
| Exclusive gateway | Translate only recognized typed conditions: `== != > >= < <=` and flat and/or groups; each is a review item with the source text | Raw expression, `not` over a comparison, mixed and/or blocked |
| Parallel fork/join | Preserve matched pattern | After medium pattern is tested |
| Service task | Map to an approved target connector operation | Never execute source Java delegate/worker automatically |
| Camunda 7 Java/external tasks | Replace with target connector/agent contract | Manual implementation and behavior test |
| Camunda 8 job type/headers | Map to registered worker operation and schema; tasks with the same job type, topic or delegate class share one mapping | No automatic semantic equivalence |
| Camunda expressions / FEEL / JUEL | Translate explicit supported subset | Unsupported expressions block |
| Timer | Preserve supported duration and tested semantics | Cron/timezone/calendar details require review |
| Call activity | Resolve and lock child definition | Missing child blocks conversion |
| Forms | Map supported field schema | Rebuild unsupported layout/behavior |
| DMN | Inspect hit policy, expressions and types; convert UNIQUE and FIRST tables with literal tests into a decision table hosted in a draft or attached to a business rule task, with generated boundary cases | Other hit policies, expressions, several tested inputs and gaps blocked; revalidate on real samples |
| Listeners, scripts, compensation | Preserve source evidence and report | Deferred or manual redesign |
| Multi-instance | Call activity over a plain collection becomes a bounded for-each (designer sets the bound, 1 to 25); everything else stays blocked | Other multi-instance forms manual |
| Running instances/history | Separate migration project | Out of initial scope |

For a three-day import demo, use one real customer/source BPMN file in the tested subset. If none is available, a clearly identified hand-authored compatibility fixture tests the adapter, but is not proof of customer migration.

## PEGA migration

The current release delivers archive assessment and reviewed reconstruction. Its engineering delivery and synthetic target acceptance are closed as MIG10 under [ADR 0002](adr/0002-pega-migration-delivery-scope.md), supported by 105/105 Docker checks across small, medium and complex scenarios. Native export conversion is future MIG11; customer source qualification remains AC01/EXT01. No native Pega export version is certified by this evidence.

Pega's own documentation describes exporting rules and data via application/product/ruleset packages for movement between Pega environments. It does not provide evidence that these are portable executable BPMN. [Pega application migration documentation](https://docs.pega.com/bundle/platform-242/page/platform/system-administration/migration-applications-systems-moving.html)

Our engineering conclusion: inspect a representative, sanitized export before promising a parser or equivalence. Do not market a custom JSON sample as a native PEGA export.

Required discovery bundle:
- Pega product/version, export mechanism and archive inventory.
- One simple process with assignments, decision and integration references.
- One medium process containing a stage change, alternate path or SLA.
- Relevant rule dependencies, data definitions and integration contracts.
- Expected input/output scenarios and an explanation of routing/rule resolution.
- A source owner who can verify observed behavior.

| Business concept | Target representation | Known limitation to assess |
|---|---|---|
| Stages / steps | Stage metadata and sequential BPMN where equivalent | Not all case-stage behavior is sequential |
| Assignment | User task + approved group mapping | Work queues, skills and routing rules need explicit mapping |
| Simple decision | Decision table or typed gateway | Rule resolution, inheritance and specialized logic may not translate |
| Integration step | Registered connector operation | Endpoint/auth/payload mappings rebuilt |
| Data transform | Typed field mapping | Custom procedural logic requires a worker or redesign |
| SLA | Durable timer and escalation policy | Business calendars and goal/deadline semantics require tests |
| UI section | Schema form when representable | Custom controls and client behavior may need rebuilding |
| Dynamic case/ad hoc work | Later CMMN discovery | No invented equivalent in the simple graph |
| Declarative rules / activities | Inventory and remediation | Never silently drop or execute as arbitrary code |

Recommended cutover: new low-risk requests enter Orqix; existing cases finish in PEGA. Reconcile outcomes before routing more traffic. This reduces scope without requiring live-state migration.

## LWFS adapter contract

LWFS is not identified. Do not assume a specific vendor, an acronym expansion or an existing JSON structure.

Adapter interface: detect(source) → sourceKind/version/confidence; parse(source) → source inventory; analyze(inventory) → issues; convert(approvedMappings) → canonical draft + source map + unresolved issues.

Minimum information needed from the source:
- Stable workflow, node and transition identifiers.
- Node kinds and semantics, including branches/default paths and joins.
- Variable types, input/output mappings and initial values.
- Assignment/routing rules and forms.
- Connector operations, timeout/retry policy and error transitions.
- Timer units/timezone, child references and versioning rules.
- At least two representative definitions with expected results.

A normalized intermediate format may be designed after inspection. The Orqix canonical JSON is an import format for Orqix; it must not be mislabeled LWFS-native.

## Compatibility report contract

Fields: sourceSystem, sourceVersion, adapterVersion, sourceHash, elementCounts (supported, info, review, action, blocker), mappings, issues, assumptions, requiredBindings, testCases (generated and run for DMN tables) and publishable. Severity `review` means converted but verify: it never blocks and is acknowledged separately from `info` losses.

Each issue: sourceElementId, sourcePath, sourceKind, severity, reasonCode, explanation, targetNodeId if any, suggestedAction, owner and resolutionStatus.

Example issue: element ServiceTask_7, severity blocker, code JAVA_DELEGATE_NOT_PORTABLE, action “Register an HTTP/worker operation and verify equivalent request/result behavior.”

Percentage mapped is an inventory metric only. Publishing requires zero blockers and passing representative behavior tests, not a percentage threshold.

## Equivalence tests and cutover

Compare approved/rejected paths, numeric threshold boundaries, null/missing values, user/group resolution, failure/retry handling, timezone behavior and external-effect count. Test child and parallel branches only when those constructs are in scope.

Business owner signs off the target behavior; technical owner confirms integrations and security. Keep the original definition and report attached to the new workflow. Rollback routes new starts back to the prior system; already-started Orqix instances need an explicit completion/cancellation policy.

## Transparent conversion and case classification

Each transformation record identifies its source element, mapping rule/version, target element, assumption, severity and related behavior test. [Vanilla-Converter (2025)](https://arxiv.org/abs/2508.04352) is a useful recent catalog of Camunda 7-specific constructs and of reporting automatic versus manual transformation work.

Maintain a fixture bundle containing original source, expected converted representation, compatibility report and expected outcomes. A syntactically valid result is insufficient when assignments, gateway behavior or integration effects differ.

That converter targets Camunda 7 → Camunda 8. It is not adopted as a Flowable converter and provides no PEGA/LWFS compatibility guarantee. No external converter code is selected.

Use the case-classification questions in [the product specification](01-product-spec.md) before flattening PEGA stages into a process. Owner: migration lead with source-system/business owners. Existing E16 milestones and source-sample dependencies remain.

## Synthetic Pega acceptance boundary

The local small/medium/complex acceptance bundle tests the full authorized Orqix path: synthetic archive inventory → exact checksum-linked, manually reviewed mapping package → normal compiler → draft → separate publication → actual target execution. Medium and complex callers resolve the imported small child through explicit dependency mappings and published release pins. Targets derive from the existing `simple-review`, `medium-bounded-rework` and `complex-batch-review` examples.

The XML is descriptive Pega-like test data, not genuine exported Pega grammar. An archive checksum proves which source bytes accompany the reviewed reconstruction; it does not prove that the reconstruction preserves native Pega behavior. The fixture `mappings.json` holds per-file dispositions; the runner assembles the full upload package with the actual archive job ID, checksum, source version, definition and file mappings. The evidence pack retains that package, source, targets, dependency/release provenance and individual expected-versus-observed results.

The real Docker run passed **105/105 checks across 10 scenarios**, with all three workflows imported and separately published. Curated synthetic expectations matched actual target execution for approval/rejection, repetition exhaustion, joins, real one-minute timers without clock acceleration, ordered child results and cancellation. Acceptance tooling tests passed **14/14**, and fixture schema, provenance and reproducibility checks passed. Synthetic reviewed-target acceptance is verified; native Pega parsing and source equivalence remain unverified. See [S39](build/S39-pega-synthetic-acceptance.md) for limits, the [execution log](../evidence/pega-synthetic-live-final-1016.log) and [evidence pack](../evidence/pega-synthetic-live-final-1016.zip).

**AC01 is not closed by synthetic acceptance.** Genuine source definitions and source-runtime observations, Pega inheritance/rule-resolution/circumstance review, business calendars and SLA semantics, connector/failure behavior and source/business owner signoff remain external acceptance requirements.
