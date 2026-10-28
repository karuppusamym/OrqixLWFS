# What you can do with Orqix

A plain-language map of the tool, by what you want to get done. Each line says what is possible today and where the limits are. For step-by-step help see the [user guide](user-guide.md); for setup see the [admin guide](admin-guide.md) and the [demo guide](demo-guide.md). On a development stack, `tools/demo/seed_samples.py` and `tools/demo/seed_gallery.py` add ready-made examples named "Sample - ...".

## 1. Ask for something, decide something (everyone)

| You want to | How | Notes |
|---|---|---|
| Start a request | **Home > Start**, or **Assistant** ("start Purchase approval"), or **Ctrl+K** | Forms show required fields and help text; files can be attached |
| Follow a request | **My requests**, or the run page (timeline and diagram) | Plain-language progress, decisions and outcome |
| Approve or reject | **My tasks** (claim, decide, give a reason) | Amount-based routing, several approvers and groups are supported |
| Do it by talking to the Assistant | **Assistant** page | Words that appear in a workflow name ("I need a purchase") match instantly with no AI; other wording asks the AI when a key is set, and otherwise lists what you can start |

## 2. Build a workflow (designers)

- **Start from**: a template, **a few questions** (no AI), a blank workflow, the **Describe it to AI** draft, an **example** (Home > Get started > Look at an example), or an **import** from Camunda or Pega.
- **Steps you can add**: approval, decision (rules), choose a path, call a system, generate a document, notify, run in parallel and wait for all, wait (time or date), wait for a message or a signal, send a signal, hand work to an external worker, send through an adapter (email, Teams, Kafka, database read), start another workflow, undo completed calls, raise an error, finish.
- **Canvas** in BPMN shapes or cards, **Steps**, **Lanes** (who does what) and **Source** views; **View** shows or hides the step list, **Ask AI** and the details cards.
- **Ask AI** changes the draft in plain words ("add a finance approval over 5,000"); you review each proposed change before it is applied; **Talk** dictates. It needs an AI key.
- **Check workflow** lists what must be fixed before publishing; the **Test** tab runs the whole workflow with mock answers for integrations and checks the outcome, path and rules used.
- **Export** the draft or any release as BPMN with its diagram. **Publish** makes a numbered version; older versions keep running; roll back or release a share of new requests first (canary).

## 3. Rules, forms and reusable parts

- **Decision tables** inside a workflow, or **shared** across workflows (Settings > Shared assets; samples "Purchase routing" and "Expense limit"); import and export them as **CSV**; a test grid checks rows.
- **Forms** are generated from the workflow's questions: types, required, patterns, conditional fields, file upload, document links.
- **Views** (request, approval, case and worklist layouts) are reusable and pinned by version.
- **Documents**: generate PDF or Word from request fields; read SharePoint/OneDrive files; templates and retention are in Settings.

## 4. Connect other systems

| Need | What exists | Limit |
|---|---|---|
| Call an HTTP service | Approved **connections** (secret by reference, allow-listed hosts, retries, unknown-outcome handling) | Development uses a stub service |
| Wait for an outside system | **Callback token**: poll and resume without a session; or a named message | One resume per wait |
| Receive events | **Signed inbound webhooks** (record, start with a person's rights, or resume a wait); **Kafka triggers** (start or resume, dead letters) | Kafka is off by default; plain-text broker tested; TLS/SASL not yet run |
| Tell others | **Outbound webhooks** (signed, retried, replayable); email, Teams, Kafka and database **adapters** | Adapters deliver only to local test transports in this build |
| AI agents | Agent connections with read-only tools; approved write tools; MCP and A2A | Write tools go to stubs |
| External workers | Hand work to a program and wait for its result (worker SDK) | - |

## 5. Run and operate

Run list with filters and search; retry or fix a request that needs attention; suspend and resume; reconcile uncertain calls; **Insights** (throughput, cycle times, bottleneck steps, overdue, timeouts; figures can be up to 30 seconds old); notifications and @mentions; audit trail; health, metrics and graceful shutdown; several replicas with no sticky sessions.

## 6. Cases (work without a fixed path)

Human tasks, nested stages, entry and exit rules, milestones, manual and required work, typed case data and user events, with a visual case designer and CMMN export. **Limit**: this is a bounded part of CMMN 1.1; full CMMN import and discretionary planning are not built.

## 7. Move from Camunda or Pega

Camunda 7 and 8 files (BPMN, DMN, forms) and Pega **XPDL 2.0**, archives and a **Blueprint PDF** reader go through a review screen that says what was understood and what needs a decision; equivalence tests compare behaviour. **Limit**: native Pega rules are not read, and real Blueprint PDFs are unproven until a sample is supplied; treat each migration as a reviewed conversion with side-by-side testing.

## 8. Run it safely (administrators)

Roles and per-workflow rights, groups, workspaces and test workspaces, promotion between stages with an approval gate, secrets by reference, egress allow-lists, signed releases, audit, backup and migration rules, Compose and OpenShift deployment. **Limits**: development identity provider only; production identity, high availability and capacity figures are still your decisions ([S92](build/S92-scalability-qualification.md), [S101](build/S101-integration-and-bulk-verification.md), [S102](build/S102-insights-and-inbox-at-volume.md)).

## What is not done

Full CMMN case management; a general application designer; real Pega Blueprint PDFs and native Pega rules; production identity, high availability and bank-scale capacity (one workspace starts requests one after another at roughly 14 per second per workflow).
