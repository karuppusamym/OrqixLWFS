# Orqix worker SDK and native asynchronous agents

Python 3.10+; production code uses the standard library only. The existing `orqix_worker.py`
supports synchronous workers and authenticated callback handoff. `orqix_agent_gateway.py`
connects that durable workflow boundary to these native remote protocols:

| Profile | Submission and completion |
|---|---|
| `mcp-2025-11-25` | MCP initialization/capability and tool negotiation; task-augmented `tools/call`, `tasks/get`, `tasks/result`, `tasks/cancel`; JSON or bounded SSE replies |
| `a2a-1.0` | JSON-RPC `SendMessage` with immediate return, `GetTask`, `CancelTask`; inline JSON artifacts or a direct JSON message |
| `responses-v1` | Responses API `background:true`, retrieve by response ID, cancel; completed JSON text; no model tools |

Use a workflow **Work by an external worker** step. Its topic must match a gateway profile.
Map only the necessary scalar inputs and declare the returned values in Studio. For the
example profiles, declare required numeric output `score`. The workflow server checks the
returned field names and types before resuming the engine.

## Start a gateway

Copy [the example configuration](native-agent-gateway.example.json) to a private deployment
location and configure its workspace, exact provider endpoints, tool/model, instructions and
result mappings. Provision a machine identity with **`job.work`** in that workspace. Its
current bearer token and each provider token live in separate mounted files. Token files are
reread for every HTTP call; refresh them through your existing identity/secret service.
Token rotation must retain the same subject/provider account for outstanding jobs.

Create a durable private directory for the outbox. On Linux use directory mode `0700`; on
Windows restrict its ACL to the gateway identity and its operators. The SQLite database
contains protected inputs and pending results until delivery. Never commit it or a token.

```shell
python tools/worker-sdk/orqix_agent_gateway.py --config /private/native-agents.json --database /private/outbox.db
```

`--once` advances due work and fetches once. `--status` prints counts. Stop the daemon before
using `--inspect JOB_ID:FENCE` or `--cancel JOB_ID:FENCE` against its database. Inspection
returns correlation metadata, never inputs, outputs or MCP session credentials. Cancellation
is sent once, then checked by polling; an uncertain cancel is never automatically resent.
A cancellation racing with completion raises attention rather than delivering success.
Resume the daemon to finish checking a pending cancellation.

One process owns one database, enforced by an OS file lock. Run additional independent
gateways with distinct worker IDs and outboxes. Keep each outbox bound to its original
Orqix origin, workspace, worker ID and provider profile. Changing a pending profile fails
closed. A restored outbox may resume only with the registering Orqix subject and matching
provider account; possessing a callback or task ID does not grant access.

## Recovery and effects

The gateway commits callback registration and a submission intent before sending remote
work. A restart with a known task ID resumes polling; it does not submit again. A crash or
lost response during submission becomes `REMOTE_SUBMISSION_UNCERTAIN` and raises workflow
attention. Inspect the provider using the retained dispatch/task correlation IDs before
authorizing an Orqix retry. A new fence must not be used to blindly duplicate the remote job.

Mapped results are durably saved before callback delivery. Lost callback responses retry the
same idempotent delivery. Suspended runs hold delivery; expired/superseded fences or cancelled
runs abandon it. Orqix authentication denial stops the daemon. Callback deadlines are fixed,
between 30 seconds and 24 hours; timeout does not recreate or automatically cancel remote work.
Terminal rows retain correlation metadata and clear private input, result, ticket and session
fields. Ordinary SQLite deletion does not guarantee physical erasure of freed storage; use an
encrypted volume and your deployment's retention/backup policy.

External-worker profiles are a separate, explicitly trusted deployment boundary from the
in-process S13 agent connection. They do not inherit or bypass the IN04 write-tool approval
system. Only delegate work the remote account is authorized to perform. MCP tools must
advertise both task support and a read-only hint; the provider must enforce its account's
actual permissions. Model calls offer no tools. Provider costs and background-response
retention belong to the provider account; the gateway does not use Orqix's in-process model
budget ledger. Select a background-capable model explicitly and configure provider limits.

## Supported boundaries

- Exact configured endpoints; HTTPS and normal certificate/hostname validation. Each request
  validates DNS and connects to that validated address. Private addresses and plain HTTP are
  off by default; both flags must be explicit for local HTTP fixtures.
- No redirects, proxy environment routing, automatic endpoint discovery, file downloads,
  provider webhooks, unsolicited server requests, human elicitation or auth escalation.
- Responses are limited to 1 MiB and a 10-second exchange budget after connection setup;
  provider poll hints are respected. Mapped results are scalar and at most 32 KiB.
- MCP is explicitly pinned to the **experimental 2025-11-25 task revision**; it does not claim
  the later tasks-extension draft. A2A is explicitly 1.0 JSON-RPC; no 0.3 downgrade, REST or gRPC.
- The existing synchronous S13 agent/model/tool loop is unchanged. Use the external-worker
  step and gateway for durable native async work.

## Verify

```shell
python -m unittest discover -s tools/worker-sdk -p "test_*.py" -v
```

The local real-stack acceptance script uses real machine JWTs, the workflow API and engine,
and three loopback provider peers. It makes no live model calls or third-party deliveries:

```shell
python tools/api-e2e/e2e_native_agents.py
```

Configure `ORQIX_BASE_URL`, `ORQIX_KEYCLOAK_URL` and `ORQIX_TEST_POSTGRES_CONTAINER` when the
local stack uses nondefault ports/names. The script grants one temporary machine permission,
revokes that exact grant in `finally`, and retains three uniquely named sandbox workflows/runs.
