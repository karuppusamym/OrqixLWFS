# S103 - Start throughput, archive and delete, loose ends (28 October 2026)

## 1. Start throughput (S92 F1, and the per-workflow lock from S101)

Two locks made starts queue:

| Lock | What it protected | Held for | Change |
|---|---|---|---|
| Workspace counter row (`run_seq`) | Gap-free request numbers `ORQ-n` | The whole start transaction, engine start included | New setting `orqix.runs.reference-allocation`: **`gapless`** (default, unchanged) or **`fast`**, which takes the number in its own short transaction (`RunReferences`) |
| Workflow row, `FOR NO KEY UPDATE` (S101) | The exact open-request limit | The whole start transaction | Back to a shared lock. Only when a workflow is within 64 requests of its limit do starts queue behind a per-workflow advisory lock and count again, so the limit stays exact |

**Why not take the number at the end of the transaction instead?** Many places read the request reference inside the start transaction: the outbound webhook "request started" row, task and notification text, and the engine's own projections. A number assigned late would be missing for them. Fast mode keeps the number available from the start and only gives up "no gaps".

**What fast mode means.** References stay unique and increasing. A start that fails after taking its number (rare: input is checked before) leaves a gap. Every replica must use the same setting. Use `fast` when many requests start in one workspace at once; keep `gapless` if a regulator or an auditor needs a gap-free sequence.

**Why 64.** Starts that are far from the limit run without waiting, so near the limit at most as many as are in flight can be uncounted. The margin must stay larger than the connection pool size times the number of replicas (15 x 4 = 60 by default).

**Tests.** `RunReferencesFastModeTest` (2): concurrent starts of two workflows get unique references; a number taken in a transaction that is rolled back stays used (a gap) and the counter is free for the next taker at once, which proves the counter is not held by the start. `RunsAndTasksTest.tRun08b` still holds the limit exactly (limit 3, eight concurrent starts: exactly 3 accepted). `tRun09` still proves gap-free references in the default mode.

**Measured** (`tools/api-e2e/e2e_bulk.py`, integration stack, one laptop, one replica, 200 concurrent starts of one workflow by 8 sessions, then 200 concurrent messages, then 300 Kafka records):

| Run | Starts per second | Messages per second | 300 Kafka records |
|---|---|---|---|
| Before (S101) | 16.9 | 9.6 | 19.5 s |
| Default `gapless`, new workflow lock | **53.6** | 29.7 | 14.8 s |
| `fast` numbering | **82.4** | 22.2 | 19.3 s |

One clean run per mode; message and Kafka figures move between runs by about a third, so only the start figures are a clear result. Further runs on this laptop failed at the client side with connection resets and 30-second read timeouts on the app's and Keycloak's mapped ports, while the app answered its health check in 4 ms and the database had nothing waiting: Docker Desktop's port proxy under repeated bursts, as already seen in S92. A capacity figure needs production-class hardware.

## 2. Archive and delete

- **Archive a workflow** (`POST .../workflows/{id}/archive`, `workflow.edit`, reason required, the workflow version must match): it leaves the catalog, start lists and child choices; refused with `409 RESOURCE_IN_USE` while requests are open. UI: **Organize > Archive this workflow**, with a second confirmation. `WorkflowArchiveApiTest`.
- **Delete a Kafka trigger or a webhook subscription** once it is switched off (`DELETE`, `connection.manage`); a switched-on one is refused with `409 RESOURCE_IN_USE`. Status, de-duplication, dead-letter and delivery rows go with it; the audit trail keeps the deletion (key and counts only). Migration **V173** grants the delete. UI: **Delete** appears on paused rows in Settings > Kafka and Settings > Webhooks, with a second confirmation. Tests in `KafkaTriggerApiTest` and `OutboundWebhookApiTest`, the security matrix and both panels.

## 3. Loose ends closed

- Accessibility (axe, WCAG 2.2 AA) at 1280 px now also covers Home and Cases: no serious or critical finding.
- Integration (43/43) and bulk (10/10) re-run on the current image before the throughput change.
- `PinnedWorkflows.test.tsx`: the test clicked the catalog's Unpin button while the pinned link was navigating to the overview; it now waits for the overview. Three full frontend runs in a row passed.
- The demo's stale "Sample - Document review" draft is archived through the new endpoint.
- The other session's branch (CI fixes, code-review fixes, simpler New workflow flow, whole-word Assistant matching) is merged into `main`; one conflict (the same flaky test fixed in both) was resolved by keeping both waits.

## 4. Review fixes (29 October 2026)

A deep review of everything since r0-2026-10-29 found twelve issues; all are fixed.

| # | Issue | Fix |
|---|---|---|
| 1 | `fast` numbering took the number in a `REQUIRES_NEW` transaction, so each start needed a second connection from the same 15-connection pool; 15+ simultaneous starts would starve it | The number comes from one auto-committed statement on a small pool of its own (`orqix.runs.reference-pool-size`, default 4). `RunReferencesFastModeTest` now runs 24 starts at once |
| 2 | A parent workflow could still start an archived child | Archive is refused while another workflow's active or canary release starts it as a step; a child start of an archived workflow fails |
| 3 | Archive ignored switched-on Kafka and inbound triggers that start the workflow | Refused until they are switched off (`RESOURCE_IN_USE`, naming them) |
| 4 | The exact open-request limit depended on "pool size x replicas < 64", unchecked | `RunQuota`: `orqix.runs.quota-margin` (64) must exceed the pool size times `orqix.runs.expected-replicas` (4), checked at startup |
| 5 | Child starts checked the limit without the per-workflow lock | Child starts use the same `RunQuota` check |
| 6 | Organize > Archive fetched the latest version just before archiving, so the version check always passed | The version is taken when the dialog opens |
| 7 | "Answer a few questions" created the workflow, then saved its draft; a failed save left an empty workflow and a retry said the name was taken | A retry saves only the draft of the workflow already created; Back is locked meanwhile |
| 8 | Deleting a Kafka trigger right after pausing it could race a consumer that had not stopped yet | Refused while a replica reported its consumer running in the last minute; the delete notice says that Kafka keeps the consumer group's position |
| 9 | Assistant name matching took any shared 4-letter start ("form" found "Formal") | Only plural and simple word endings ("purchases", "approve"/"approval", "policies") |
| 10 | Removing a question silently made a conditional approval run always | The dialog says which approval now runs for every request |
| 11 | A missing number came back as 0 (ORQ-0) | It fails with an error |
| 12 | A reason of only spaces passed archive validation | Refused (422) |

