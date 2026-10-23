# S80 - Reusable case-data views

21 October 2026. UI02d extends the versioned view designer to case-data presentation. It covers authoring, explicit version selection on case definition creation, immutable snapshots, case start and authorized data corrections. Full UI02 remains open for worklist pages, composed actions, localization, extension sandboxing and the other general-designer acceptance in S56.

## Delivered behavior

The view library now offers a Case kind, case-scoped bindings (`case.fieldName`), a yes/no control and a Save data action. The visual editor supports the existing sections, one/two-column layout, field ordering, labels, preview, undo and publication review. Case controls cover the six existing scalar case-data types; whole-number fields use a number control while retaining integer validation. File and row controls are not offered for case views. Existing request/approval vocabulary remains unchanged; yes/no controls and Save data are admitted only on Case views.

When creating a case definition, a designer may select a published Case view and exact version. Every declared case field must appear once, with the same type and required flag. Choice values and labels must match the case choices in order. The view must have one Save data action and may have one constrained same-app navigation action. No expressions, scripts, external actions, arbitrary task bindings or case lifecycle actions are admitted through this presentation format.

The client reports compatibility problems before submission; the server independently reloads and validates the exact immutable workspace-local version. Foreign/missing versions and incompatible bindings/types/actions block definition creation. The request supplies only the pin, never a trusted copied presentation. V112 stores a snapshot beside the immutable case definition, with workspace and version foreign keys. The application role can read/insert snapshots and cannot update/delete them. A later library edit or new publication cannot change an existing case definition or instance. A new case definition version must explicitly select its own view version.

Case start and the case data panel use the pinned labels, ordering and responsive sections. The actual controls retain their original field types, numeric/text constraints, choice values and required flags. Initial submission still calls the existing case-start API. The custom Save data label calls the existing case-data update API with the current case version, idempotency key and required reason. Data access, worker eligibility, suspension, closed-case refusal, concurrency and protected history remain authoritative. A read-only requester sees the layout without receiving edit authority or a Save action. The renderer does not fetch the view library at runtime.

## Verification

- Selected backend Maven `verify`: **34/34**, zero skips; packaging and compiler coverage gate pass (`evidence/case-views-backend.log`). New `CaseViewApiTest` covers two-definition reuse, later publication/old-instance stability, explicit new-version selection, boolean preservation, foreign/missing versions, compatibility refusal, current data-read/edit permissions, numeric bounds and identical update replay. Existing case lifecycle/data, request/approval reuse, view library, architecture and security matrix checks also pass.
- Focused frontend: **46/46** across ten files (`evidence/case-views-frontend-focused.log`). New cases cover case-view compatibility/refusal, exact version selection during case creation, rendered constraints, managed reason/version/idempotency, read-only action refusal and visual editor case controls/actions. Existing case and request/approval tests remain green.

The first backend attempt could not start because the local Docker engine was stopped. Docker was started and the tests above then executed successfully against real PostgreSQL/Flowable. This was an environment startup failure, not a passing test run.

- Database migration/role checks: **2/2**, empty-database migration and application-role append-only restrictions pass, including UPDATE/DELETE refusal for `case_definition_view` (`evidence/case-views-database.log`). These supplement the 34 selected application cases above; they are separate Maven runs, not a fresh full application suite.
- Full frontend: **946/946** across 129 files (`evidence/case-views-frontend-full.log`).
- Frontend/E2E types, full lint, generated API drift and **88 contract checks** pass (`evidence/case-views-{types,lint,api,e2e-types,contracts}.log`). Production frontend and embedded authoring/tasks bundles build in the Docker image.

The compiler implementation is unchanged. The focused Maven runs pass the existing accumulated compiler coverage gate; no fresh full compiler or full application run is claimed.

- Real Edge against PostgreSQL/Flowable/Keycloak: **1/1** passes (`evidence/case-views-real-browser.log`). The fixture publishes a case view through the authorized API, selects it while authoring a case definition in the UI, starts as a separate requester using the pinned layout and boolean control, confirms read-only access, corrects as an eligible worker through the custom Save action with a required reason, reloads and verifies retained values. Serious/critical axe checks cover authoring, start and mobile correction. The existing editor tests plus the new Case editor test cover visual view authoring; the browser creates the source view through the API.

## Local rollout

The local dependencies were started without deleting volumes. Before application refresh, a custom-format database backup was saved as `evidence/pre-case-views-refresh.dump` (38,095,370 bytes). Checksum and prior image are in `evidence/case-views-previous-image.json`. Migration succeeds through V112; 82 application tables are recorded by the migration runner. The source-identified image `orqix/orqix:case-views-20261021` is deployed locally on port 18080 with healthy readiness/container health. Build identity: HEAD `6667e70`, source SHA-256 `1c504253877206f92ebe2550e6b2182a30d8d00dd7f133c1d9301ece783497eb` (`evidence/case-views-image-source.json`). Existing dependencies/volumes were retained. Build, migration and refresh logs are `evidence/case-views-image-build.log`, `case-views-demo-migration.log`, `case-views-demo-refresh.log` and `case-views-rollout.log`.

UI02d is closed within this recorded case-data presentation scope. It does not add a graphical CMMN model designer, a whole-case page composer, case task/lifecycle action composition, worklist views, localization or custom component sandboxing. Those remain under S56/UI02 and WM04 as applicable. No new full CMMN/general-designer conformance or production/customer acceptance is inferred.
