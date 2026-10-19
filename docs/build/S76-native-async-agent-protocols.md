# S76 - Native asynchronous agent protocols

19 October 2026. IN03d is delivered and verified locally within the profiles below. The production gateway is a separate Python process using the existing external-worker step and S66 authenticated callback boundary. It is not a new mode of the synchronous S13 agent connection.

## Delivered behavior

| Profile | Native exchange |
|---|---|
| MCP `2025-11-25` experimental tasks | Initialize and verify server/tool capabilities, task-augmented `tools/call`, `tasks/get`, `tasks/result`; `tasks/cancel` when advertised. JSON or bounded SSE responses. Only tools advertising task support and `readOnlyHint: true`. |
| A2A `1.0` JSON-RPC | `SendMessage` with immediate return, durable task/context correlation, `GetTask`, `CancelTask`, one inline JSON artifact or a direct JSON message. No downgrade to 0.3. |
| Responses background API | Explicit model/instructions/output limit, `background: true`, stored response ID, retrieve and cancel, JSON output and no model tools. |

The [SDK README](../../tools/worker-sdk/README.md) contains installation, private-volume requirements, configuration, operation and recovery instructions. Copy the [example configuration](../../tools/worker-sdk/native-agent-gateway.example.json), configure real endpoints and mount separate Orqix/provider bearer-token files. Python 3.10+ and the standard library suffice for production; the real-stack acceptance script additionally uses the existing API test dependencies.

Each topic selects a fixed endpoint/protocol/tool or model and scalar result mapping. Orqix checks the declared result types before resuming Flowable. HTTPS certificate/hostname checks, fresh DNS validation with address-pinned connections, no redirects/proxy routing, response/argument limits and sanitized error codes constrain remote exchanges. Local/private access is an explicit deployment option, disabled by default.

The SQLite outbox commits callback registration and submission intent before remote dispatch. Known remote IDs resume polling after restart. An uncertain submission never automatically resends; it reports attention with a stable dispatch correlation key. Results persist before idempotent callback delivery. Suspension defers delivery; stale fences are abandoned; authentication denial stops the daemon. Provider poll hints are honored within a fixed callback deadline. Cancellation commits intent before sending and checks the remote outcome without repeating an uncertain cancel. A completion racing cancellation raises attention.

Terminal rows clear protected payload/session fields and retain correlation metadata. SQLite deletion does not physically erase freed storage; use a private encrypted durable volume and an appropriate backup/retention policy. One process owns each outbox, enforced by an OS lock; outstanding profiles and workspace/worker identity cannot be silently changed.

## Machine authentication correction

The existing worker API advertised bearer authentication, but the machine security chain denied its routes. S76 admits only the seven exact POST worker lease/callback routes; the service still checks current `job.work` on every operation. Grant/revocation, foreign-workspace refusal, denied GET/admin routes and all seven operation paths have security-matrix coverage. No permission is granted merely by possessing a task/callback ID.

## Executed verification

- Full compiler: **524/524**, Maven `verify` and the unchanged coverage gate pass (`evidence/native-agents-compiler.log`).
- Selected application tests: **67/67**, zero skips; architecture, agent executor, MCP client, approved writes, external workers and security matrix; Maven `verify` and executable packaging pass (`evidence/native-agents-backend-final.log`). This is not a fresh full application run.
- Worker SDK/native wire/outbox suite: **36/36**, including real loopback HTTP, credential rotation, redirect/size refusal, malformed replies, restart/lost acknowledgements, uncertain submission/cancel, grant refusal, deadlines and rejected results (`evidence/native-agent-tests-final.log`). Added to the contracts CI job; hosted CI execution is not claimed.
- Real local API acceptance: **three native journeys pass**, with real Keycloak client credentials, temporary `job.work`, actual workflow publication and Flowable execution. Each gateway is reopened from its durable outbox, polls the native peer, dispatches exactly once and produces typed workflow output `score: 7.5`. The exact temporary grant is revoked in `finally` (`evidence/native-agent-real-api-final.log`). Provider peers are local fixtures; there are no live/billable model calls.
- Contract checker: **88 checks**; Python compilation, example-profile validation and `git diff --check` pass. No frontend changes or new browser qualification are claimed.

The local Docker stack was backed up before migration and app refresh. Backup: `evidence/pre-native-agents-refresh.dump` (38,044,085 bytes). Application image: `orqix/orqix:native-agents-20261019`; source identity at build is recorded in `evidence/native-agents-image-source.json` (HEAD `e66bbb5`, source SHA-256 `387c78e85c340aca3778ea8a3b76d334c2465d4b6b8cb74e66e2ea40d759e3b0`). Database migration is successful through V111. Existing dependencies/volumes were preserved. This image contains the machine-route correction and current application source at build; deploy the SDK gateway separately. Later documentation/CI changes are not part of that image. This is a local rollout, not production qualification.

## Boundaries and remaining acceptance

IN03's selected engineering increment now supports these three native asynchronous profiles. Real provider/account interoperability, credentials, tenant consent and production operations remain deployment acceptance. Do not infer universal protocol conformance or support for every provider/binding.

The gateway does not implement provider webhooks, automatic discovery, file retrieval, interactive input/auth escalation, browser streaming, A2A REST/gRPC, older A2A bindings, or the newer MCP tasks-extension draft. The synchronous S13 loop remains synchronous. Mounted tokens are reread on each call; an external identity service must renew them while preserving the original account/subject.

External-worker deployments are explicitly trusted remote accounts. MCP's read-only hint is checked but the provider must enforce its actual account permissions. This does not extend S75's approved-write tool catalog or authorize remote writes through it. Responses calls offer no tools. Provider costs and response retention are configured at that account; these calls do not use the in-process Orqix model budget ledger.

Protocol references used for implementation: [MCP 2025-11-25 tasks](https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/tasks), [A2A 1.0 specification](https://a2a-protocol.org/latest/specification/), [Responses background mode](https://developers.openai.com/api/docs/guides/background) and [JSON output configuration](https://developers.openai.com/api/docs/guides/structured-outputs).
