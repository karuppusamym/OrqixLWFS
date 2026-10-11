# S20: bounded DMN files in migration projects

Projects accept BPMN and DMN sources under the existing limits: 1–10 files, 1 MiB per XML file, 4 MiB total. When `sourceFormat` is omitted, the safe XML parser identifies DMN by its namespace and otherwise routes the source through the BPMN analyzer. A `.xml` filename does not force BPMN. Explicit formats remain authoritative and malformed, incorrectly labelled or unsafe XML fails atomically without storing a partial project.

The supported project DMN slice is **exactly one convertible decision per file**. It reuses the standalone DMN analyzer, generated boundary checks, converter, compiler validation, immutable source storage and migration governance. Multi-decision files receive `PROJECT_DMN_SELECTION_REQUIRED`, with advice to split the source or use a standalone reviewed import. Unsupported DMN semantics remain visible in each stored file report and also receive `PROJECT_DMN_NOT_CONVERTIBLE`. This does not add general FEEL, decision requirements graph or native source-engine execution support.

BPMN Camunda 7 `decisionRef` and Camunda 8 `calledDecision/decisionId` references recognized by the existing analyzer become cross-file decision dependencies. Missing references receive `PROJECT_MISSING_DECISION`; duplicate decision IDs across files receive `PROJECT_DUPLICATE_DECISION`. Process and decision identities occupy separate planning namespaces, so a DMN definitions ID cannot overwrite a BPMN process ID. Existing child-call missing/duplicate/cycle diagnostics remain enforced. Any project issue removes the conversion order and prevents ordered conversion.

The dependency order converts a DMN file before its BPMN caller. The DMN step creates an ordinary standalone decision workflow draft through the existing converter. The caller then embeds its decision table from the uploaded DMN source through explicit `decisionMappings`; it does **not** call the standalone decision workflow and needs no published decision workflow release. Child workflow calls continue to require a published release. Neither source conversion automatically publishes or starts anything.

## Reviewed mapping and contract

Project file responses add optional `sourceFormat` (`bpmn`, `dmn` or null), `decisionIds` and `decisionDependencies` (BPMN task ID to source decision ID). The GET service normalizes absent lists/maps in historical stored reports to empty containers. Existing historical conversion orders continue to contain migration job IDs and remain executable.

The project page lists decision IDs and task references, accepts `.dmn` alongside `.bpmn`/`.xml`, and seeds the next caller's editable decision mapping JSON only when a decision has one matching uploaded project file. The designer reviews the suggested migration/decision IDs and optional `inputRef`, can override them, and explicitly submits conversion. Changing filters or refreshing project metadata does not replace edits to the open file. Missing, duplicate, unsupported or legacy-without-metadata candidates are not guessed.

All authorization, source hashing, report evidence export and exact-draft approval rules remain those of the existing workspace migration APIs. References are verified in the workspace by the existing decision mapping resolver. Real customer exports, source-engine equivalence and infrastructure/tenant acceptance remain external validation; these fixtures are synthetic.

## Validation

Added pure Java planner coverage for namespace-based `.xml` detection, both Camunda decision reference styles, missing/duplicate decisions, multi-decision diagnostics, unsupported DMN semantics, wrong explicit formats and unsafe XML. Added API coverage that converts an XML-labelled DMN before its XML-labelled BPMN caller and executes the published caller at three decision boundaries without publishing the standalone DMN workflow; a second API test reads and converts a legacy stored project without new fields.

Local frontend checks: typecheck passed and **13 focused tests passed** (three mapping suggestion cases and ten existing project UI cases). Backend Maven execution is reserved for the root agent's coordinated build; added Java tests are not claimed as passed here.

```powershell
npm --prefix frontend run test -- --run src/features/migration/projectDecisionMappings.test.ts src/features/migration/ProjectPage.test.tsx
npm --prefix frontend run typecheck
```
