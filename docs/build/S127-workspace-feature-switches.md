# S127 - Workspace feature switches

30 October 2026. Branch `claude/modest-mestorf-3a29e1`, based on `main` at `96b9cf6`. Migration V208 (V204-V206 are used by open PRs #6 and #8, V207 is claimed by the shared-forms session).

The regression run of 30 October found that Orqix had no administrator-level feature switches: modules were switched only by server configuration (Kafka mode, ClamAV) or by whether an AI key is set, and the menu was trimmed only by role permissions. The product owner asked whether administrators should be able to enable or disable features. This slice adds per-workspace switches, enforced on the server and audited.

## Decisions

- **Six modules, per workspace, all on by default.** `ai` (AI assistance), `cases` (CMMN case types and cases), `migration` (Camunda and Pega import), `agents` (agent connections, the MCP and A2A tools behind them, approving agent write tools), `apps` (application pages and UI extensions), `kafka` (Kafka triggers, Kafka adapters and Kafka-sourced inbound triggers). No row in `workspace_feature` means on, so every existing workspace keeps everything it has today.
- **Switching off blocks new authoring and new starts; it never stops running work and never deletes anything.** Reads stay available. A case that is open keeps running and its tasks, stages and steps can be worked; a request already started keeps running, including its agent steps and its sends to a Kafka adapter. Switching back on restores everything as it was.
- **Refusal code: `409 FEATURE_DISABLED`** with the extension member `feature`. 409 (not 403) because the request conflicts with the workspace's current state, which an administrator can change; 403 stays reserved for "you lack a permission", which the frontend explains differently. The UI message: "This part of Orqix is switched off in this workspace. A workspace administrator can switch it on in Settings, Features. Work that is already running is not affected."
- **Order of checks: 404, then 409, then 403.** A caller who cannot see the workspace still gets 404 (no existence disclosure, T-SEC-01); a member gets 409 before the permission check, so the reason shown is the switch rather than a missing permission.
- **Who switches:** `workspace.manage-access` (workspace and organization administrators). Every member may read the switches (`workspace.member`), because the menu needs them. Each change is audited as `workspace.feature.updated` (`feature`, `enabled`) in `audit_event`; setting the current value again writes nothing.
- **No cache.** The gate reads `workspace_feature` on every gated request (one indexed lookup, only for gated operations), so a change applies on every replica immediately.
- **Platform settings stay in server configuration.** Kafka clusters and their allowed topics, Kafka live/stub mode, ClamAV and the AI provider key are unchanged; a switch only gates use within the workspace. AI that is not configured still reports its own reason.

## Contract

- `GET /workspaces/{workspaceId}/features` (`getWorkspaceFeatures`, `workspace.member`): one `WorkspaceFeatureState` per module in fixed order (`feature`, `enabled`, `updatedAt`, `updatedBy`).
- `PUT /workspaces/{workspaceId}/features/{feature}` (`setWorkspaceFeature`, `workspace.manage-access`, audit `workspace.feature.updated`), body `{ "enabled": boolean }`; returns the full list. Unknown feature: 400.
- `WorkspaceMembership.disabledFeatures` (required; on `getMe` and `getWorkspace`): the switched-off modules, so the shell needs no extra request.
- Problem code `FEATURE_DISABLED` (409) and the `Problem.feature` member; response `FeatureDisabled`.
- Every gated operation carries `x-orqix-feature` (one module or a list) and declares 409. Operations gated only for one kind of resource also carry `x-orqix-feature-when`. The info block states the rule.

| Module | Refused while off | Not refused (running work, reads, clean-up) |
|---|---|---|
| ai | `generateAiDraft`, `explainWorkflow`, `suggestFormChanges`, `proposeDraftEdits`, `proposeBlueprintTranscription` (also migration); `getAiStatus` answers `enabled: false`, reason "AI assistance is switched off in this workspace.", no profiles | `getAiGeneration` |
| cases | `createCaseDefinition`, `startCaseInstance`, `convertPegaCaseDefinition` (also migration) | every operation on an existing case instance (tasks, plan items, stages, data, suspend, resume, close, terminate), all reads |
| migration | `uploadMigration`, `convertMigration`, `uploadBlueprintMigration`, `saveBlueprintTranscription`, `proposeBlueprintTranscription`, `convertPegaCaseDefinition`, `uploadMigrationProject`, `convertMigrationProjectNext` | migration review and policy of already imported workflows (`getMigrationReview`, `approveMigrationReview`, migration policy), release migration of running requests, all reads |
| agents | `approveAgentWriteTool`; when the connection is `kind: agent`: `registerConnectionVersion`, `putConnectionBinding`, `testConnection` | `revokeAgentWriteTool`, `reconcileAgentWrite`, agent steps of running requests, HTTP and Microsoft Graph connections |
| apps | `assignAppPage`, `runAppPageChain`, `createUiExtension`, `submitUiExtensionVersion`, `decideUiExtensionVersion` | reading pages, drafts, component data, extension files and frames |
| kafka | `createKafkaTrigger`, `updateKafkaTrigger`, `sendKafkaTestMessage`; when `kind`/`source` is `kafka`: `createAdapter`, `updateAdapter`, `testAdapterSend`, `createInboundTrigger`, `updateInboundTrigger`, `receiveInboundEvent` (after the signature check, so only a signed sender learns why; it can retry later) | `deleteKafkaTrigger`, dead-letter reads, adapter steps of running requests, email, Teams and JDBC adapters |

**Kafka triggers pause.** `KafkaTriggerService.enabled()` leaves out triggers of workspaces where `kafka` is off, so `KafkaConsumerRunner.reconcile` stops their consumers within one reconcile (`orqix.kafka.reconcile-delay`, default 10 s). Messages stay on the topic and the committed offset is kept; reading continues from there when the module is switched on. A batch already being handled when the switch flips finishes normally. Deleting a trigger still requires it to be paused first (unchanged), and pausing it is an update, so changing triggers needs the module on.

## Server

- `V208__workspace_features.sql`: `workspace_feature (workspace_id, feature, enabled, updated_by, updated_at)`, primary key `(workspace_id, feature)`, CHECK on the six names, partial index on switched-off rows, app role `SELECT, INSERT, UPDATE`.
- `identity.WorkspaceFeatures`: the state, the audited change (row lock on the workspace to serialize concurrent switches), `require(workspace, feature)`, and the gate table (`GATED_OPERATIONS`: operationId, method, contract path, modules; `CONDITIONAL_OPERATIONS`).
- `identity.security.FeatureGateInterceptor`: a Spring MVC interceptor on `/api/v1/workspaces/**`. It matches the request's method and best-matching route pattern (path variable names ignored) against the gate table, takes the workspace from the first path variable, and throws `FEATURE_DISABLED` after `requireVisible`. Matching on the route, not the controller method name, because several controller methods are not named after their operationId.
- Kind-dependent checks sit in the services once the kind is known: `ConnectionAdminService` (agent definitions and connections), `AdapterService` (Kafka adapters), `InboundService` (Kafka-sourced inbound triggers).
- `AiService.status` reports the switch; `IdentityController` adds `disabledFeatures` with one query for all of the caller's workspaces.
- `AuditWriter` whitelists `workspace.feature.updated` with `feature` and `enabled`.

## Frontend

- **Settings → Features** (`FeaturesPanel`, shown with `workspace.manage-access`): one card per module with its state, who switched it and when, what the module covers, "While it is off" and "Work already running" in plain language (`lib/features.ts`). Switching off asks for confirmation that repeats both texts; switching on is one click. After a change every query is invalidated, so the menu, Home and AI status follow at once.
- `useWorkspace().isOn(feature)` reads `disabledFeatures` (missing field = all on, for older servers and hand-built fixtures).
- Menu (`Shell`): Cases, Apps and Agent writes are hidden when their module is off (and from the Ctrl+K palette, which uses the same list). Home: the import card is hidden, and the "Build a workflow", "Look at an example" and "Connect other systems" texts drop AI, cases and Kafka. Workflows: the Import button is hidden. AI entry points already hide themselves when `getAiStatus` says disabled.
- A page reached through an old link (Cases, Apps, Extensions, Import, Agent writes) and the Kafka settings tab show a notice: the module is off, what that means for running work, and who can switch it on.
- Mock backend (`mocks/features.ts`): switches kept in the mock database (persisted with it), the two operations, the AI status, and a gate for the unconditional operations. The kind-dependent checks are server-only.

## Tests and evidence

- `WorkspaceFeaturesApiTest` (real stack): defaults and permissions (member reads, outsider 404, designer 403, unknown feature 400); cases off refuses `createCaseDefinition` and `startCaseInstance` with 409 and `feature`, outsiders still get 404, the running case's plan and the definition list stay readable, other workspaces are untouched, a repeated switch writes no second audit record, on again works; AI off in `getAiStatus` and on `generateAiDraft`; agents off refuses an agent connection but not an HTTP one; Kafka off removes the trigger from the consumer runner's list, refuses trigger create, test message and Kafka adapters but not a Teams adapter, and on again restores both; the server gate table equals `x-orqix-feature`/`x-orqix-feature-when` in the contract, every gated operation declares 409, and every gated route exists among the controller mappings.
- `SecurityMatrixTest`: both new operations return 404 to foreign principals and change nothing (`workspace_feature` added to the row-count snapshot).
- Frontend: `FeaturesPanel.test.tsx` (switch off with confirmation, menu follows, switch on; Home and menu for another user; 409 from the mock gate and the page notice; tab hidden from operators), `KafkaTriggersPanel.test.tsx` (notice when off).
- Playwright `t-ws-01-feature-switches.spec.ts` (mock mode and real stack): the administrator switches Apps and extensions off, the requester loses Apps from the menu, sees the notice and gets 409 from the API; switched on again it is back for both. The spec always switches the module back on in `finally`.

## Not done

- No platform-level (all workspaces) switch and no per-role switch; the switch is per workspace only.
- Switching off does not pause timers, waits or agent steps of running requests, by design.
- The mock does not reproduce the kind-dependent checks.
