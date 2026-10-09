# Migration compatibility fixtures

These files are hand-authored test fixtures, not customer exports. Camunda 7 and 8 BPMN fixtures model manager approval, a 5,000 amount boundary, optional finance approval, a service task and success/rejection endings. The service implementation is deliberately nonportable; map it to the approved procurement operation before conversion.

`unsupported-features.bpmn` contains one of each construct the Camunda adapter blocks or drops knowingly (boundary and intermediate timers, script/send/receive/manual tasks, a business rule task, parallel and inclusive gateways, a sub-process, a call activity, a task listener and an unsupported condition expression). `xxe-attack.bpmn` carries an external-entity payload that must be rejected without being resolved. The expected dispositions are asserted in `backend/app/src/test/java/com/orqix/migration/FixtureDispositionTest.java` rather than in separate JSON files.

The synthetic Pega ZIP contains one placeholder `rules/process.xml`. It demonstrates archive inventory and the checksum-linked manual mapping workflow only. It does not reproduce a native Pega rules archive or demonstrate automatic Pega conversion. The browser/integration tests deliberately label their manual mapping as synthetic.

See [migration strategy](../../docs/05-migration.md) and [migration workbench](../../docs/17-migration-workbench.md) for the supported subset and limitations.
