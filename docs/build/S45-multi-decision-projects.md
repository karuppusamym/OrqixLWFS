# S45 - Explicit multi-decision selection in migration projects

MIG12 closes the multi-decision project selection portion of EX07. Projects can now contain DMN files with several supported decision tables, while retaining the existing safe XML limits, immutable sources, dependency order, mapping review and separate publication.

## Selection and execution

The project inventories every decision ID and resolves BPMN decision dependencies to the source file containing that exact ID. Multi-decision files no longer receive a project blocker solely for containing more than one decision. An empty DMN file, missing/duplicate decision references, unsupported tables and unsafe XML remain blocking. Every decision in a project DMN file must be supported; choosing one does not bypass a different unsupported decision.

When the next file is multi-decision DMN, the designer must explicitly select a decision from the inventoried IDs. There is no first-decision default. The existing server also refuses missing or unknown `decisionId` selections before creating a workflow. The selected decision creates **one draft wrapper workflow for the file**, not one workflow per decision. Existing single-decision behavior remains compatible.

BPMN callers embed the supported table named by their reviewed `decisionMappings`, independently of the decision selected for the wrapper. Two callers can use different decisions from the same uploaded source. The wrapper need not be published. Caller workflows still require ordinary validation and separate publication; conversion never starts a request.

The UI states that converting every file does not create a workflow for every decision. Nonselected decision findings remain in the existing source report with the adapter's `notNeeded` resolution for that conversion. The immutable conversion request retains the selected `decisionId`; changing it after conversion conflicts under existing idempotency rules. No new API field or database migration is needed.

## Evidence and boundaries - 17 October 2026

The synthetic fixture `examples/migration/dmn-multi-review.dmn` contains two supported FIRST decisions. At exposure 5,000, `reviewRoute` returns finance and `alternateRoute` returns manager. This makes an incorrect embedded-table choice observable.

All 15 focused backend tests pass: seven project planner cases and eight public API cases on PostgreSQL/Flowable. The new API scenario refuses missing/unknown selections, retains the analyzed project after refusal, converts the alternate wrapper without publication, converts and separately publishes two callers, executes both at 5,000 with distinct expected outcomes, checks project completion and verifies identical replay versus changed-selection conflict. Existing cases cover project ordering, workspace/permission boundaries, concurrency and legacy records. Log: `evidence/multi-dmn-backend.log`.

All 29 focused frontend cases pass across project review, mapping suggestions and mock migration behavior. Frontend/E2E typechecks, scoped lint and isolated mock build pass. Logs: `evidence/multi-dmn-frontend.log`, `evidence/multi-dmn-types.log`, `evidence/multi-dmn-e2e-types.log`, `evidence/multi-dmn-lint.log`, `evidence/multi-dmn-build.log`.

One Edge mock browser journey passes with axe at selection and completion. It uploads BPMN plus the multi-decision DMN fixture, checks required selection, converts the alternate wrapper, verifies the caller still maps `reviewRoute`, then converts the caller and checks the explicit per-file scope notice. Log: `evidence/multi-dmn-browser.log`.

Backend rerun (JDK 21): `backend/mvnw.cmd -f backend/pom.xml -pl app -am -Dorqix.buildDirectory=target/multi-dmn-1017 -Dtest=ProjectDmnPlanTest,MigrationProjectApiTest -Dsurefire.failIfNoSpecifiedTests=false test`. Frontend rerun from `frontend`: `npx vitest run src/features/migration/ProjectPage.test.tsx src/features/migration/projectDecisionMappings.test.ts src/mocks/migration.test.ts`.

Broader FEEL, new DMN hit policies, decision requirements graph execution and automatic creation of all decision workflows are not included. Previously stored blocked multi-decision projects retain their original plan; reimport the unchanged sources to obtain the new plan. Backend acceptance uses real PostgreSQL/Flowable; browser mock conversion remains a simplified UI double. No customer source-runtime equivalence, full-suite run or Docker rollout is inferred.
