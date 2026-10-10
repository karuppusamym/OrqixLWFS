# Migration compatibility fixtures

These files are hand-authored test fixtures, not customer exports. Camunda 7 and 8 BPMN fixtures model manager approval, a 5,000 amount boundary, optional finance approval, a service task and success/rejection endings. The service implementation is deliberately nonportable; map it to the approved procurement operation before conversion.

`unsupported-features.bpmn` contains one of each construct the Camunda adapter blocks or drops knowingly (boundary and intermediate timers, script/send/receive/manual tasks, a business rule task, parallel and inclusive gateways, a sub-process, a call activity, a task listener and an unsupported condition expression). `xxe-attack.bpmn` carries an external-entity payload that must be rejected without being resolved. The expected dispositions are asserted in `backend/app/src/test/java/com/orqix/migration/FixtureDispositionTest.java` rather than in separate JSON files.

The synthetic Pega ZIP contains one placeholder `rules/process.xml`. It demonstrates archive inventory and the checksum-linked manual mapping workflow only. It does not reproduce a native Pega rules archive or demonstrate automatic Pega conversion. The browser/integration tests deliberately label their manual mapping as synthetic.

See [migration strategy](../../docs/05-migration.md) and [migration workbench](../../docs/17-migration-workbench.md) for the supported subset and limitations.

## Expansion fixtures (all synthetic, hand-authored; none is a customer export)

| File | Purpose |
|---|---|
| `expression-operators.bpmn`, `expression-operators-feel.bpmn` | The same three gateways in JUEL and FEEL: `>`, `<=`, `!=`, an `and` group on one field and an `or` group across two fields, each with a default flow |
| `service-task-groups.bpmn` | Six service tasks in three implementation groups (topic `send-mail` x3, class `AuditDelegate` x2, class `ArchiveDelegate` x1) |
| `multi-instance-call.bpmn` | Call activity run once per item of the variable `items` (parallel); converts to a bounded for-each once the mapping sets the bound |
| `multi-instance-unsupported.bpmn` | Multi-instance constructs that stay blocked: user task, fixed cardinality, completion condition, Camunda 8 output collection, expression collection, standard loop |
| `business-rule-task.bpmn` | Business rule task calling the DMN decision `reviewRoute` (`dmn-first-hit.dmn`) and a gateway on its result |
| `dmn-approval-level.dmn` | UNIQUE table, numeric input, two outputs, bounded middle range `[1000..5000)` carried as the default; boundary values 999.99 / 1000 / 4999.99 / 5000 |
| `dmn-first-hit.dmn` | FIRST table with overlapping rules folded into exclusive rows |
| `dmn-customer-tier.dmn` | Text lists, a number output, an unused yes/no column and a catch-all rule |
| `dmn-integer-ranges.dmn` | Integer input with an inclusive range |
| `dmn-unsupported.dmn` | One decision per blocked construct; expected reason codes are asserted in `DmnImportTest` |

The expected dispositions are asserted in `FixtureDispositionTest`, `ConverterExpansionTest` and `DmnImportTest`; `MigrationApiTest` converts, publishes and runs them against PostgreSQL and Flowable.
