# Orqix demo guide

For presenters. Everything here runs locally on the Docker stack; each demo says what it proves and what to say if it is asked about limits. Status of every capability: [feature status](feature-status.md).

## Before the demo (10 minutes, once)

```bash
cd deploy/compose
docker compose --profile app down -v            # clean database
docker compose --profile app up -d --build      # app, Keycloak, PostgreSQL, procurement stub
cd ../.. && python tools/demo/seed_demo.py      # nine requests in different states
```

Open <http://localhost:8080>. Sign-in passwords for the test users are in `deploy/compose/keycloak/orqix-dev-realm.json` (development only).

| Person | Role in the story | Sees |
|---|---|---|
| `rhea.requester` | Requests purchases | Home, My requests, the request form |
| `mani.manager` | Manager approver | My tasks (manager step) |
| `fin.finance` | Finance and compliance approver | My tasks (finance step, vendor compliance review) |
| `olga.operator` | Operates running requests | Runs, recovery actions |
| `dana.designer` | Designs workflows | Workflows, studio, Describe it |
| `paul.publisher` | Publishes versions | Publish review, rollback |
| `wade.admin` | Workspace administrator | Settings: access, groups, connections, AI |

Optional AI: put `OPENROUTER_API_KEY` in `.env` before `docker compose up`. Without it every demo works except the AI parts: the **Describe it** button and the AI section in Settings are simply absent, with no message saying why, so tell the audience AI drafting is switched off in this environment. If `deploy/compose/.env` holds an `OPENROUTER_API_KEY` the stack picks it up and **Describe it** appears, so check which case you are in before the demo. For a stack on other ports, run the seed with `ORQIX_BASE_URL` and `ORQIX_STUB_URL` set. Each AI call costs a fraction of a cent (economy profile: DeepSeek; costly models are excluded and a monthly budget applies).

## A repeatable, isolated demo stack

`tools/demo/demo-stack.sh` stands up a separate stack from the current checkout so a demo never depends on whatever else runs on the machine (its own Compose project, volume, image tags and ports; it refuses to start if a port is taken, and never touches another project):

```bash
tools/demo/demo-stack.sh up   demo-1026 38000   # builds the images if missing (REBUILD=1 forces), starts, waits, registers the sign-in redirect
tools/demo/demo-stack.sh seed demo-1026 38000   # adds the demo requests (safe to repeat)
tools/demo/demo-stack.sh stop demo-1026         # stop, keeping the data
tools/demo/demo-stack.sh start demo-1026 38000  # start again
tools/demo/demo-stack.sh down demo-1026         # remove the stack AND its database volume
```

With port base 38000 the app is http://localhost:38080 and Keycloak http://localhost:38180. Use a new tag for a clean database. Development credentials only; the demo users are in `deploy/compose/keycloak/orqix-dev-realm.json`.

Reset the procurement stub between runs of demo 1 if you inject faults: `curl -X POST localhost:8095/__reset`.

## Sample data (four ready-made workflows)

After the standard seed, `python tools/demo/seed_samples.py` adds four clearly named sample workflows and requests in finished and open states, all through the public API. Set `ORQIX_BASE_URL` (and `ORQIX_STUB_URL`) for a stack on other ports. It is safe to re-run: a workflow whose key exists is not imported again and a request whose reference text already exists is not started again, so a re-run only fills gaps. It never prints passwords and resets nothing.

| Workflow (key) | Source | Finished requests | Open item |
|---|---|---|---|
| Sample - Pega XPDL: amount routing (`sample-pega-xpdl-routing`) | `medium.xpdl` | 4,999.99 ends Standard; 5,000 and 5,000.01 end Finance | none (see note) |
| Sample - Pega XPDL: manager review (`sample-pega-xpdl-review`) | `review-branch.xpdl`, Managers mapped to `managers` | approved by `mani.manager` ends Finance; rejected ends Rejected | "27-inch monitors": sign in as `mani.manager` |
| Sample - Pega Blueprint (synthetic PDF) (`sample-pega-blueprint-purchase`) | `synthetic-blueprint.pdf`, reviewed edits applied | 6,000 manager then finance, Approved; 4,999.99 manager only, Approved; one rejected by the manager | "Forklift battery" (cost centre Logistics): sign in as `mani.manager` |
| Sample - Camunda 7 purchase (`sample-camunda-purchase`) | `camunda7-purchase.bpmn` | 4,999.99 and 5,000 (via finance) both Fulfilled with a purchase order | none |

Notes:

- The XPDL "amount routing" flow has no human step: "Finance" there is an ending, not a task, so no request can wait at Finance. For a request waiting at Finance use the seeded Purchase approval requests (ORQ-1003, ORQ-1004, `fin.finance`).
- The Blueprint sample is built from a synthetic PDF written for Orqix, not a Pega export. The reader is generic; do not present it as compatibility with Pega's own Blueprint layout. Its transcription was edited and marked reviewed by the script exactly as the browser rehearsal does (Finance SLA unit set to hours, the purchase order integration excluded with a reason).
- The Blueprint start form has no title field, so its list rows read as the cost centre (Lab, Warehouse, Facilities, Logistics); the script uses a different one for each request.
- The Camunda requests show a purchase order reference (for example PO-3) in the run's history.
- Run numbers (ORQ-...) depend on how many requests the stack already holds.

## Demo 1 — Business approval (the core, 12 minutes)

Proves: forms, human tasks, rules, a real API call with honest outcomes, versioning, security, recovery.

1. **Designer** `dana.designer`: Workflows → *Purchase approval* → open the studio. Show the request form, the manager approval, the *amount ≥ 5,000* rule (decision table) and the procurement call. Click **Check workflow**.
2. **Publisher** `paul.publisher`: **Review and publish** → publish version 1 (skip if the seed already did).
3. **Requester** `rhea.requester`: Start a request, USD 1,800. Note the reference (ORQ-n) and "Waiting for Managers".
4. Restart the application while it waits: `docker compose restart orqix` (takes about 20 s). Sign in again; the request is still waiting.
5. **Manager** `mani.manager`: My tasks → claim → approve. **Requester**: the request is *Completed · Fulfilled* with the procurement reference in the timeline; no duplicate order (`curl localhost:8095/__orders`).
6. Request USD 5,000 → *Waiting for Finance approvers* (4,999.99 does not). Reject at finance with a reason: *Completed · Rejected*, no purchase order.
7. **Versioning:** publish version 2 while a version 1 request waits; the waiting request keeps version 1.
8. **Security:** sign in as `fred.facilities` (another workspace) and open a run URL: *not found*, never "forbidden" (nothing is disclosed).
9. **Failure and recovery:** `curl -X POST localhost:8095/__faults -d '{"mode":"status400","times":1}'`, approve a request. It ends as **Needs attention** with a business message; as `olga.operator` open the run, follow the recovery guidance, **Retry**. Then a *commit-then-timeout* fault (`commitThenTimeout`): the order exists but the answer was lost, so Orqix retries with the same key and still creates one order.

## Demo 2 — AI agent calls a tool, a rule decides

Proves: agents as governed connections (read-only, allow-listed tools, budgets), tool results feeding rules. Full script and limits: [S13 §9](build/S13-agents-mcp.md).

1. As `wade.admin`: Settings → Connections → *Vendor risk assistant* (**AI agent** badge): read-only instructions, one allowed tool. **Test the agent** with Acme Ltd, UK, 12,000: risk 72 (high) and the timeline sentence.
2. As `dana.designer` open *Vendor onboarding check* (create it from the template list) and show the agent step and the two rule tables. Publish (acknowledge the warning if AI is off).
3. As `rhea.requester` start Acme Ltd: "Vendor risk assistant checked 'Acme Ltd'… risk 72 (high)"; waits at Compliance review. `fin.finance` approves. Globex is approved automatically; Umbrella Trading (sanctions hit) goes to compliance.
4. Fault: `curl -X POST localhost:8095/__mcp/faults -d '{"mode":"status500","times":2}'`, start a run: it retries and completes.

Say if asked: the agent can only read; it cannot change the workflow or call a tool that is not on its list; high-stakes outcomes go to a person.

## Demo 3 — Secured inbox (Teams approval is deferred)

The plan defers Teams approval; the secured in-app inbox is the baseline. Show My tasks as `mani.manager`: claim, approve, "someone else completed this" when two managers race, and a requester who cannot approve their own request. Teams/email notification and callback remain planned (R2, needs a Teams tenant).

## Demo 4 — Parallel steps, timers and a child workflow

Full script: [S12 §10](build/S12-workflow-patterns.md).

1. As `dana.designer`: New workflow → **New hire onboarding**. Show the IT and Facilities branches, the escalation, the join, the *Waits 1 minute* step and the **Order equipment** step that starts *Purchase approval*.
2. `paul.publisher` publishes. `rhea.requester` starts it. `mani.manager` approves IT, `fin.finance` approves Facilities.
3. After the one-minute wait the run shows **Other requests started by this one**: a Purchase approval ORQ-n. Approve it as `mani.manager`; the parent timeline says it finished and the parent completes with the purchase order number.
4. Variants: reject Facilities (the whole request ends and the IT task is cancelled); cancel the parent as `olga.operator` while the child waits (the child is cancelled); republish *Purchase approval* mid-run (the running parent keeps its pinned version).

### Bonus: wait until a date, and the Assistant

- **Wait until a date:** `examples/start-date-onboarding.json` waits until a start date the requester enters (a date question). The wait card reads "Until Start date, 09:00 UTC". Details in [S12 §10](build/S12-workflow-patterns.md).
- **Assistant:** as `rhea.requester` open **Assistant** → *Start a request* → answer the questions (one at a time, Change a value on the review, Submit). As `mani.manager` open **Assistant** → *My tasks* → pick the request, read it, Approve, then Confirm. Nothing is recorded before the confirm step. Guide: [user guide](user-guide.md).

## Demo 5 — Camunda import

1. As `dana.designer`: Workflows → **Import workflow** → upload `examples/migration/camunda7-purchase.bpmn`.
2. Show the compatibility report: every element has a result (supported, needs a decision, blocked) and the reason. Map the groups and the service task to the Procurement connection, **Convert to draft**, open the studio ("Imported from Camunda — review and test before publishing"), publish, run it. 4,999.99 versus 5,000 routes like the original.
3. Upload `examples/migration/unsupported-features.bpmn`: blockers (timers, scripts, parallel gateways…) are listed and conversion is refused. Upload `xxe-attack.bpmn`: rejected without being read.

Say if asked: this is a supported subset with an honest report, not "all Camunda".

## Demo 6 — Pega

The minimum Pega demo is not blocked on customer exports. Follow [the Pega migration walkthrough](demo-pega-migration.md): automatically convert supported synthetic XPDL 2.0 flows, publish separately, execute completion/threshold/approval/rejection cases, and show an unsupported-trigger refusal. Archive assessment and checksum-linked reviewed reconstruction remain available. [ADR 0003](adr/0003-minimum-demo-pega-scope.md) defers direct native RAP rule conversion outside this demo; it is not advertised as implemented. Build and rehearse the latest merged candidate on the real local stack before presenting.

## Demo 7 — Prompt → draft → edit → publish → execute

Proves: AI drafts a workflow that still passes the same validation and human publishing as any other. Needs `OPENROUTER_API_KEY`. Details: [S10](build/S10-ai-assistance.md).

1. As `dana.designer`: Workflows → New workflow → **Describe it**. Use an example (for example *Employees claim expenses; managers approve; above USD 1,000 finance approves too*), profile **Economy**. Generation takes 10–60 seconds; close the dialog to see that it continues and a notice opens the draft when ready.
2. The studio opens with **AI-drafted — review every step before publishing**: assumptions and missing configuration with *Go to step*. Fix the group, **Check workflow**, **Explain** (the explanation is checked against the validation result, so it never calls a missing group ready).
3. `paul.publisher` publishes; `rhea.requester` starts a request; approvals complete it exactly like any other workflow.
4. Settings → AI (as `wade.admin`): profiles, excluded costly models, price caps, month-to-date spend.

## Closing: honest status

The current product closeout adds cross-file migration findings exports, audited task priority and age sorting, historical document-version selection in connection administration, published-version comparison and saved step notes. To demonstrate priority, claim a task, choose **Change priority**, enter a bounded number and reason, then confirm; the queue can sort/filter the result. To demonstrate review, edit **Step notes**, check and publish, then open **Compare versions** on the workflow overview. Load more releases to compare older versions.

See [product closeout](21-product-closeout.md) for remaining engineering features and customer acceptance separate from excluded infrastructure. The examples execute simple, medium and complex bounded workflows; they do not establish native Pega equivalence or customer Microsoft access.

Open [feature status](feature-status.md): what is verified, what is implemented but not applied to a real cluster, and what needs customer input (real procurement endpoint, PingFederate, OpenShift, CI host, a native Pega export). The production gates in [the delivery plan](06-delivery-plan.md#production-release-gates) have not been passed, and the demo does not claim they have.
