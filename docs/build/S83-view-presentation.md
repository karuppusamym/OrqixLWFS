# S83 - Responsive and localized reusable view presentation

23 October 2026. This delivery continues the full original non-infrastructure scope in [S81](S81-full-non-infrastructure-completion.md). It extends the existing versioned view library and four runtime bindings; it does not close general page composition, action composition, extensions, migration or full CMMN.

## Product behavior

The view editor authors presentation settings alongside the existing section/field/action model:

- A base language and up to eight partial translation dictionaries, using language codes with an optional region. Targets are stable section, field and action IDs, plus explicit choice-option indices. Missing text falls back to the base label. Blank entries remove a translation. Removing a component prunes its retired translation targets; undo retains the earlier draft.
- A two-column/table breakpoint at 640, 768 or 1024 pixels. Forms remain one column below the selected breakpoint; worklists switch between cards and their table. The surrounding application navigation/panel breakpoint remains an application concern.
- Comfortable/compact spacing and brand/neutral theme tokens. Tokens map to fixed application styles; no custom CSS, URLs, scripts or remote fonts are accepted.

Preview supports language switching and narrow layout, including fallback labels. Publication review includes the configured tokens and translated languages. Runtime language selection is a per-viewer convenience retained in local storage; unavailable storage does not block forms. The setting cannot grant data or action access.

Request, approval, case and worklist views render translated authored text. Choice labels can change while their stored enum values remain canonical. Input types, required flags, bounds, conditions, decimals, data values and server operation grants remain authoritative. Application chrome, validation messages, dates/numbers and business data are not automatically translated. This is authored view text, not a translation of the entire application or arbitrary external page content.

## Immutable publication and validation

The server validates presentation object shape, language keys, token enums, current translation targets and trimmed plain-text labels. The existing 32 KiB definition bound includes translations. Unknown targets, unsupported style fields, malformed dictionaries and invalid tokens are rejected.

Request forms with configured presentation carry the exact published view snapshot in `inputUi.viewDefinition` alongside their existing exact version reference and copied form layout. Publication checks equality against the workspace-local published version. A copied snapshot without its pin is rejected. Detaching removes the shared snapshot and preserves the base form labels/layout. Approval releases, case-definition bindings and workspace worklist bindings already pin full immutable definitions and retain that boundary. Old views and request pins without presentation remain supported.

The workflow contract recognizes this data-only annotation. Compiler execution semantics remain unchanged; publication owns workspace and snapshot checks. Renderers apply only fixed presentation tokens and escaped text. Locale selection does not mutate a snapshot or rewrite submitted values.

## Acceptance evidence

Executed acceptance is recorded below. Full original UI02 and WM04 acceptance remains open; this delivery is reusable-view presentation, not a general page or extension engine.


## Executed verification

- Selected backend `verify`: 28/28 pass across the view registry, request/approval compatibility, case/worklist snapshots, architecture and generated security matrix, including coverage/packaging. A final 3/3 request-pin rerun verifies the corrected accented-text fixture and exact snapshot publication.
- Full compiler `verify`: 524/524 with the unchanged coverage gate. The new presentation annotation does not enter execution semantics.
- Full frontend: 964/964 across 134 files on the final language-persistence behavior. Focused view checks also pass 20/20. Frontend/E2E types, full lint, API drift and 88 contract checks pass. An import-style lint failure and a damaged accented test expectation were corrected before acceptance.
- Real Edge: 3/3 journeys pass against PostgreSQL/Flowable/Keycloak. They cover visual authoring and preview, request/approval pinning/publication, language switching while retaining inputs, canonical choice values after request submission, current claim/decision permissions, case start/read-only access/corrections, and worklist table/card switching. Mobile serious/critical accessibility checks and actual grid column counts at 900/1200 pixels pass. The common browser fixture reports no console errors, uncaught exceptions or CSP violations. The worklist test restores the previous workspace binding.

The local application runs `orqix/orqix:view-presentation-20261023`, source SHA-256 `0aed6ad1371620340ad504d89c7322149f4231dd2238641b1c932276cf002058`. Current source, manifest and image label match. A 38,137,024-byte database backup precedes the refresh; migration 114 remains current, readiness is healthy, and dependencies/volumes/topology are retained. This delivery adds no database migration or new operation grant. Production/customer qualification and a fresh full application-backend test run are not claimed.

Evidence: `evidence/view-presentation-backend.log`, `view-presentation-request-pin-final.log`, `view-presentation-compiler.log`, `view-presentation-frontend-final.log`, `view-presentation-final-types.log`, `view-presentation-final-lint.log`, `view-presentation-e2e-types.log`, `view-presentation-api.log`, `view-presentation-contracts.log`, `view-presentation-real-browser.log`, and the image build/rollout/source-attestation records.

UI02f is complete within this responsive/localized/token-based presentation boundary across the four implemented view kinds. General page/component/list composition, conditional view composition, composed actions, migration and sandboxed extensions remain required under UI02. Full CMMN remains WM04. The full original non-infrastructure objective is still open.
