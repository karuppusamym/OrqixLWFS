# S50 - Automatic conversion of bounded Pega XPDL flow exports

This adds an automatic flow-export path alongside the existing Pega archive assessment and reviewed reconstruction. It does **not** implement native RAP rule-page conversion or close MIG11/customer source qualification.

## Source format and supported behavior

Pega's published help describes exporting a flow rule as XPDL 2.0 from its Design tab ([XPDL definition](https://community.pega.com/sites/pdn.pega.com/files/help_v718/definitions/x/xpdl.htm), [Flow Design tab](https://community.pega.com/sites/pdn.pega.com/files/help_v718/rule-/rule-obj-/rule-obj-flow/design.htm)). These are historical Pega 7.1.8 documents, not proof of export availability or compatibility in every current release. The XPDL namespace/shape was also checked against the [published XPDL 2.0 sample artifact](https://github.com/gitpan/BPM-XPDL/blob/master/examples/xpdl-2.0-sample/sample.xpdl).

Upload `sourceFormat: pegaXpdl` with XML text, or select **Pega flow export (XPDL 2.0)** in Import workflow. The adapter requires the exact `http://www.wfmc.org/2004/XPDL2.0alpha` namespace, an explicit `XPDLVersion` of `2.0`, and one WorkflowProcess. It automatically translates supported source structure through the existing BPMN adapter into canonical workflow nodes, inferred simple inputs and routes; the user supplies no canonical definition or checksum-linked mapping package.

Supported: untriggered starts, plain ends, explicit XOR routes, bounded comparisons accepted by the existing condition parser, OTHERWISE routes, and `Implementation/No` tasks with one literal Performer, explicit automatic StartMode and manual FinishMode. `No` alone is not treated as proof of a human task. Human tasks must have the approval/rejection shape the existing compiler accepts. Assignment targets and ambiguous approval routes require explicit workspace mappings; source users/permissions are never imported. Optional ROLE declarations describe assignment references, not authorization grants. XPDL imports are standalone; multi-file migration projects still accept BPMN/DMN only.

Known textual documentation/export metadata and selected diagram graphics/schema-location hints are retained in the immutable source and reported as exclusions requiring acknowledgement. Remote schemas are never fetched. The normal compiler validates the inferred or explicitly supplied request schema and graph before any draft is created. Publication remains a separate authorized action.

## Conservative refusal and provenance

Every parsed child and attribute is checked against a context-specific allowlist. Unknown or foreign extensions, scripts/tools, subflows, arbitrary rule pages, computed performers, deadlines, data declarations, triggered events, unsupported routing and ambiguous IDs/singletons/defaults block conversion with source paths and reasons. No source code or general Pega expression is executed. Unsupported condition expressions are refused by the existing bounded parser.

Source bytes and SHA-256 remain those of the original XPDL, not the intermediate BPMN. Source IDs and paths link report findings and target source maps. Adapter identity is `pega-xpdl-0.1`; source version is explicitly `XPDL 2.0`, not a claimed Pega runtime version. Draft creation audit records `pega-xpdl-import`, distinct from manual mapping imports. Workspace permissions, exact-request replay/conflict behavior, compiler gate and migration publication review remain in force. V48 adds the new stored source-format value without changing existing jobs.

## Acceptance boundaries

Fixtures under `examples/migration/pega-xpdl` are synthetic, standard-shaped flow exports. They establish target behavior for the supported grammar, not native Pega rule resolution or source-runtime equivalence. Rule inheritance, ruleset resolution, circumstances, activities, declarative rules, native decision-rule pages and arbitrary RAP contents remain MIG11/AC01. Existing archive inventory/manual reconstruction remains available and native archive conversion remains blocked.

The mock browser backend explicitly rejects Pega conversion and directs users to the real backend. Frontend integration tests use explicit API doubles to verify upload/review wiring; runtime acceptance uses PostgreSQL/Flowable through the actual migration, publication, run and task APIs. No fresh Docker rollout or customer-source certification is inferred.

## Verification - 17 October 2026

All 30 focused backend cases pass: seven XPDL parser/converter cases, three real PostgreSQL/Flowable API scenarios, fourteen existing migration API regressions and six migration safety cases. New execution covers start/finish, 4,999.99/5,000/5,000.01 routing, mapped human approval and rejection, separate publication, metadata acknowledgement, source hash/maps, replay/conflict, unsupported behavior refusing any draft creation, requester denial and outsider concealment. Existing archive/manual reconstruction checks remain green. These are synthetic target scenarios, not observed Pega source outcomes.

All 21 frontend migration/review cases pass, including the new format upload, ordinary mapping form and absence of a submitted canonical definition or mapping package. The Pega UI test uses explicit API responses; no new browser or real-stack browser run is claimed. Typecheck, scoped lint, production Vite build, generated API types and all 88 contract/consistency checks pass.

Logs: `evidence/pega-xpdl-backend-final.log`, `evidence/pega-xpdl-frontend-final.log`, `evidence/pega-xpdl-types-final.log`, `evidence/pega-xpdl-lint-final.log`, `evidence/pega-xpdl-build.log`, `evidence/pega-xpdl-contracts.log`. Backend rerun with JDK 21: `backend/mvnw.cmd -f backend/pom.xml -pl app -am -Dorqix.buildDirectory=target/pega-xpdl-1017 -Dtest=PegaXpdlTest,PegaXpdlApiTest,MigrationSafetyTest,MigrationApiTest -Dsurefire.failIfNoSpecifiedTests=false test`.
