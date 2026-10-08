# Migration strategy: PEGA, Camunda and LWFS
Status: proposed adapters. No source exports have been supplied or converted.

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
| Exclusive gateway | Translate only recognized typed conditions | Raw expression blocked |
| Parallel fork/join | Preserve matched pattern | After medium pattern is tested |
| Service task | Map to an approved target connector operation | Never execute source Java delegate/worker automatically |
| Camunda 7 Java/external tasks | Replace with target connector/agent contract | Manual implementation and behavior test |
| Camunda 8 job type/headers | Map to registered worker operation and schema | No automatic semantic equivalence |
| Camunda expressions / FEEL / JUEL | Translate explicit supported subset | Unsupported expressions block |
| Timer | Preserve supported duration and tested semantics | Cron/timezone/calendar details require review |
| Call activity | Resolve and lock child definition | Missing child blocks conversion |
| Forms | Map supported field schema | Rebuild unsupported layout/behavior |
| DMN | Inspect hit policy, expressions and types | Revalidate/recompile supported tables |
| Listeners, scripts, compensation, multi-instance | Preserve source evidence and report | Deferred or manual redesign |
| Running instances/history | Separate migration project | Out of initial scope |

For a three-day import demo, use one real customer/source BPMN file in the tested subset. If none is available, a clearly identified hand-authored compatibility fixture tests the adapter, but is not proof of customer migration.

## PEGA migration

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

Fields: sourceSystem, sourceVersion, adapterVersion, sourceHash, elementCounts, mappings, issues, assumptions, requiredBindings, testCases and publishable.

Each issue: sourceElementId, sourcePath, sourceKind, severity, reasonCode, explanation, targetNodeId if any, suggestedAction, owner and resolutionStatus.

Example issue: element ServiceTask_7, severity blocker, code JAVA_DELEGATE_NOT_PORTABLE, action “Register an HTTP/worker operation and verify equivalent request/result behavior.”

Percentage mapped is an inventory metric only. Publishing requires zero blockers and passing representative behavior tests, not a percentage threshold.

## Equivalence tests and cutover

Compare approved/rejected paths, numeric threshold boundaries, null/missing values, user/group resolution, failure/retry handling, timezone behavior and external-effect count. Test child and parallel branches only when those constructs are in scope.

Business owner signs off the target behavior; technical owner confirms integrations and security. Keep the original definition and report attached to the new workflow. Rollback routes new starts back to the prior system; already-started Orqix instances need an explicit completion/cancellation policy.
