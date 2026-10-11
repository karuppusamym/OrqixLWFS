# Competitive analysis, banking direction and the Pega/Camunda conversion execution plan
Revision 0.1 · 9 October 2026 · **Research and proposed roadmap.** The user subsequently authorized continued implementation. Current build status is tracked in [doc 19](19-product-build-tracker.md); sequencing is in [§9](#9-delivery-sequence-and-dependencies).

## How to read this document

| Need | Read |
|---|---|
| The conclusions and what to decide | [§1 Summary](#1-summary-and-decisions-needed) |
| What was actually read, and what could not be read | [§2 Source log](#2-source-log-and-evidence-labels) |
| What Camunda, Pega Blueprint, Pega Decision Hub and Pega banking products do | [§3](#3-what-the-competitors-do) |
| Where Orqix stands today | [§4](#4-where-orqix-stands-today) |
| Banking: what Pega did and how Orqix can be better | [§5](#5-banking-what-pega-and-camunda-do-and-how-orqix-can-be-better) |
| **How Pega and Camunda definitions will be converted into Orqix, step by step** | [§6](#6-conversion-execution-plan) |
| How a converted workflow executes at runtime | [§7](#7-how-a-converted-banking-workflow-executes) |
| Recommendations mapped to the backlog | [§8](#8-recommendations-mapped-to-the-backlog) |
| Order of work, inputs and risks | [§9](#9-delivery-sequence-and-dependencies), [§10](#10-risks-and-open-inputs) |

This document extends [05 Migration strategy](05-migration.md) and [17 Migration workbench](17-migration-workbench.md); it does not replace them. Where they conflict with this document, they describe what is built and this document describes proposals.

**Important scope note.** None of the existing planning documents mention banking (the R0 scenario is purchase approval). Banking is therefore treated here as a **new domain requirement** that needs an owner decision (§1, decision B1), not as something already specified.

---

## 1. Summary and decisions needed

### Conclusions

1. **Orqix's conversion foundation is sound and unusually honest.** Every source element gets a disposition, blockers stop publishing, originals are preserved with a checksum, and AI never converts anything. Keep this stance; it is a differentiator against vendor converters that report "% converted".
2. **Camunda conversion is a breadth and ergonomics problem.** The adapter works for a declared subset. The gaps are a missing "converted but changed, please verify" severity, no DMN or form import, one-file-at-a-time imports, no cross-file migration project or XLSX report (per-process grouped findings and JSON/CSV exports are now built), and the unsupported constructs that real models use most (multi-instance, boundary events, message events).
3. **Pega conversion is an intake problem before it is a mapping problem.** Pega's own documentation sources disagree on whether an exported archive contains readable rule XML or opaque binaries (§6.4). Until a real export from the bank's Pega version is inspected, no automatic rule conversion should be promised. The strongest approach is **behavior-first**: capture the source system's observed outcomes as golden scenarios, then map and prove equivalence against them.
4. **AI should propose, humans and the compiler should decide.** Pega Blueprint's useful ideas are an editable design reviewed before build, and a review branch before merge. Orqix should apply both: AI proposes mappings and a design brief; a person accepts each; the normal compiler validates; nothing publishes without separate approval.
5. **For banking, do not copy Pega's breadth. Beat it on provable control.** Pega's strengths are packaged domain solutions (KYC lifecycle, disputes, lending) and a marketing-oriented decision engine. Orqix's opportunity is independently reviewable decisions, maker-checker on every consequential change including conversion, effect safety (no duplicate payment or letter), pinned versions, and an evidence pack a regulator or auditor can follow.

### Decisions needed before development resumes

| ID | Decision | Recommendation | Blocks |
|---|---|---|---|
| B1 | Which banking domains first? | Start with **KYC/onboarding review** and **credit/loan approval**; add **disputes** second (parent/child structure already supported). Defer marketing next-best-action. | All banking templates, §5 |
| B2 | Who is the source-system owner for the first Pega process and the first Camunda process, and can they supply a sanitized export plus 20+ observed scenarios? | Name one process per source, each with a business owner | All of §6 |
| B3 | Which Pega version and export mechanism does the bank use? | Run the discovery spike in §6.4 before any Pega adapter work | Pega adapter |
| B4 | Where may AI-assisted conversion run? (external provider, bank-approved private model, or disabled) | Default **disabled** for bank sources until the bank approves a model; definitions contain no customer data but may reveal internal logic | §6.6 |
| B5 | Is maker-checker on mapping approval and publish mandatory? | Yes for banking workspaces: mapper, approver and publisher are three different people | §6.2, §5 |
| B6 | Jurisdiction and regulator list | Needed to fix retention, residency, notice and deadline rules; not assumed here | §5.3 |

---

## 2. Source log and evidence labels

Every factual claim below carries one of these labels. Vendor marketing is not treated as evidence of behavior.

| Label | Meaning |
|---|---|
| **[Doc]** | Vendor technical documentation |
| **[Vendor]** | Vendor marketing, customer quote or case study; treat as a claim |
| **[Partner]** | Partner, integrator or third-party write-up |
| **[Repo]** | Fact about this repository, checked on 9 October 2026 |
| **[Knowledge]** | General product knowledge not taken from a cited page; must be validated in the discovery spike before reliance |

### Pages the user supplied

| Source | What it gave |
|---|---|
| [Camunda docs home](https://docs.camunda.io/) | Landing/navigation only: agentic orchestration, IDP, FEEL, Camunda 7 migration guide, MCP docs server. Little technical detail |
| [Camunda platform](https://camunda.com/platform/) | **[Vendor]** capability list: Web Modeler, Zeebe engine, agents, 500+ connectors, Operate, Tasklist, Optimize, governance, versioning, in-flight migration, canary rollouts |
| [Camunda Marketplace](https://marketplace.camunda.com/en-US/home) | **Could not be read.** The page is script-rendered and returned only a title and footer. No claim is made about marketplace content |
| [Pega Blueprint](https://www.pega.com/blueprint) | **[Vendor]** mostly marketing; legacy-analysis agents, document/sketch inputs, customer claims |
| [Lucidchart BPMN software](https://lucid.co/lucidchart/landing/bpmn-software) | **[Vendor]** authoring UX: shape libraries, templates, comments, revision history, AI text-to-diagram, integrations |
| [Lucidchart process maps](https://lucid.co/lucidchart/use-cases/process-maps) | **[Vendor]** swimlanes, assisted layout, data linking, diagram-as-code (Mermaid), embedding |
| Two further Lucidchart use-case links (visual organizations, technical diagrams) | Same marketing site and tracking-parameter URLs; not fetched separately because the two pages above covered the BPMN and process-map content |
| [Pega Customer Decision Hub](https://www.pega.com/products/decision-hub) | **[Vendor]** next-best-action, arbitration, decision records, bias/policy checks, 20+ channels |

### Additional sources found during research

| Source | Used for |
|---|---|
| [Camunda Diagram Converter](https://docs.camunda.io/docs/guides/migrating-from-camunda-7/migration-tooling/diagram-converter/) | **[Doc]** INFO/REVIEW/WARNING/TASK severities, BPMN+DMN+form analysis, XLSX/CSV/JSON reports, Java SPI for custom rules |
| [Camunda 7→8 technical details](https://docs.camunda.io/docs/guides/migrating-from-camunda-7/technical-details) | **[Doc]** delegates vs job workers, JUEL vs FEEL, listeners, variables, CMMN not planned |
| [Camunda BPMN coverage](https://docs.camunda.io/docs/components/modeler/bpmn/bpmn-coverage/) | **[Doc]** supported element families (support highlighting was not machine-readable; lists are a proxy) |
| [Camunda incidents](https://docs.camunda.io/docs/components/concepts/incidents/) | **[Doc]** incident causes, resolve flow, "resolve does not recheck until a worker activates" |
| [Camunda connectors introduction](https://docs.camunda.io/docs/components/connectors/introduction-to-connectors/) | **[Doc]** overview only; element templates, result/error expressions not covered on this page |
| [Web Modeler collaboration](https://docs.camunda.io/docs/components/modeler/web-modeler/collaboration/) | **[Doc]** element comments, @mentions, password-protected read-only links, iframe embed, canvas lock with take-over |
| [Pega Blueprint FAQ](https://community.pega.com/blueprint-faqs) | **[Doc/community]** inputs (form, PDFs/Word, screenshots, code, video), outputs, anonymization, residency, "early-stage" |
| [Pega Academy: Blueprint](https://academy.pega.com/node/104046) | **[Doc]** generate → edit → export PDF/Blueprint file → import into Infinity (23.2+/24.1+) → Branch rule for review |
| [Pega CLM and KYC overview](https://community.pega.com/products/client-lifecycle-management-kyc) | **[Vendor]** onboarding, maintenance, periodic review, offboarding; AML/product/tax question sets |
| [Pega CLM capability sheet Nov 2025](https://www.pega.com/system/files/resources/pdf/pega-client-lifecycle-management-nov25.pdf) | **Could not be extracted** (compressed PDF); only the search-result summary was used |
| Pega Smart Dispute for Issuers ([overview](https://docs-previous.pega.com/node/2603071), [Coforge case study](https://coforge.com/what-we-do/success-stories/revolutionizing-financial-services-with-pegas-smarter-dispute-management)) | **[Doc]/[Partner]** Claim parent + Dispute case types, fraud routing, queues with SLA, audit trail, card-network updates |
| [Capgemini: lending transformation with Pega](https://www.capgemini.com/insights/research-library/lending-transformation-pega/) | **[Partner]** loan origination on case management plus Credit Decision Hub; thin |
| [Pega NBA Designer: engagement policies](https://docs-previous.pega.com/node/1830441), [Academy](https://academy.pega.com/node/79281) | **[Doc]** eligibility / applicability / suitability, taxonomy, constraints, arbitration; older docs path, screens may differ |
| Pega application migration ([Distributing applications](https://docs-previous.pega.com/system-administration/84/distributing-applications-between-systems), [product rule](https://academy.pega.com/node/4376), [community answer](https://support.pega.com/comment/1003956)) | **[Doc]/community** product rule → ZIP; **sources conflict on archive contents** |
| [Camunda banking: agentic AI use-case matrix](https://page.camunda.com/hubfs/Banking%20and%20Financial%20Services%20Q3%202026/Camunda_AgenticAIUseCaseMatrix.pdf), [industries](https://camunda.com/outcomes/industries/) | **[Vendor]** KYC split across agents, payment exceptions (ISO 20022), loan origination |
| [IBA Group: Camunda KYC](https://ibagroupit.com/insights/camunda-in-banks-know-your-customer-kyc-automation/), [HCLTech onboarding](https://hcltech.com/case-study/revolutionizing-customer-onboarding-for-a-swedish-retail-banking-organization), [BANCOS](https://bancos.com/en/?p=2308) | **[Partner]** banking implementations on Camunda; outcome figures are self-reported |
| [Vanilla-Converter (2025)](https://arxiv.org/abs/2508.04352) | Already cited in [05](05-migration.md): catalog of Camunda 7 constructs and automatic-versus-manual reporting |
| A page the user supplied that failed: `https://www.pega.com/industries/financial-services/banking` | Returned 404; banking findings come from the sources above instead |

---

## 3. What the competitors do

### 3.1 Camunda 8

| Area | What it does | Label |
|---|---|---|
| Engine | Zeebe, distributed, durable state, retries, timeouts, escalations, compensation | [Vendor] |
| Modeling | Web Modeler for BPMN, DMN and forms; Copilot; connectors and templates | [Vendor] |
| Connectors | 500+ prebuilt (REST, Kafka, SAP, LLM providers); element templates configure them in the modeler | [Vendor]/[Doc] |
| Agents | Deterministic BPMN control around agent steps; tool allow/deny lists; human-in-the-loop; MCP/A2A/REST | [Vendor] |
| Human work | Tasklist: assignment, escalation, forms | [Vendor] |
| Operations | Operate: live instances, execution trace, incident resolution. Incident flow: fix variables → raise retries → resolve; resolving does not re-check until a worker next activates the job | [Doc] |
| Analytics | Optimize: throughput, cycle time, SLA, token cost | [Vendor] |
| Governance | RBAC, versioning, in-flight instance migration, canary rollouts, SSO, SCIM | [Vendor] |
| Collaboration | Element comments with @mentions, password-protected read-only links, iframe embed, canvas lock with "take over" | [Doc] |
| Coverage | Supports inclusive/exclusive/parallel/event gateways, multi-instance, boundary and event subprocesses, error/message/signal/timer/escalation/compensation events. Data objects are modeling-only; pools/lanes, complex gateway and transaction have no linked execution page (likely modeling-only, not confirmed); CMMN is not planned | [Doc] |

**Camunda 7→8 conversion tooling (the closest prior art for Orqix's importer).** The Diagram Converter analyzes BPMN, DMN and form files against a chosen target version, groups similar findings (for example "JavaDelegate → job worker" ×37), and reports as web view, CSV, XLSX or JSON for automation. Severities: **INFO** no action; **REVIEW** converted but verify behavior is unchanged; **WARNING** no direct equivalent; **TASK** manual change required. It auto-converts namespaces, property renames and simple expressions, and exposes a Java SPI for custom rules. [Doc]

Hard differences Camunda itself documents: JavaDelegates become external job workers; JUEL is not supported in Camunda 8 (FEEL instead); sequence-flow execution listeners are not supported; variables are JSON; CMMN is not planned. [Doc]

### 3.2 Pega Blueprint (AI-assisted design)

Inputs: industry/purpose/description form; PDFs, Word and legacy documentation (added March 2025); screenshots, source-code tools and video (added July 2025). Outputs: case types, case life cycles, data objects, personas. Every element is editable; the design exports as a PDF for sign-off and as a Blueprint file imported into Pega Infinity (23.2+/24.1+) to generate an application, producing a **Branch** to review rules before merging. Only anonymized process descriptions go to the generative models; storage region is selectable (US-East, UK or EU for new blueprints). Pega describes it as early-stage with data-model capture still improving. [Doc/community]; legacy-analysis agents for COBOL/Notes/SAP are a [Vendor] claim without detail.

**What to take:** design-before-build with business sign-off; a review branch; an anonymization step before any provider call; document/screenshot grounding.
**What not to take:** the claim that AI "builds 50–70% of the solution" ([Vendor] customer quote). Orqix should not publish unverified percentage claims.

### 3.3 Pega Customer Decision Hub (CDH)

A next-best-action engine: one decision per customer per moment across 20+ channels, using adaptive models, arbitration among eligible actions, and constraints on contact volume. Every decision records what was chosen, what was rejected and why; bias and policy checks run before a strategy goes live; monitoring covers drift. [Vendor] Configuration uses **engagement policies** in Next-Best-Action Designer with three categories: **eligibility**, **applicability** and **suitability**, plus taxonomy, constraints, arbitration and channels. A worked bank example limits cards to existing customers aged 18+ (eligibility) and ties card tiers to credit-score thresholds (suitability, 500 and 750). [Doc]

**Relevance to Orqix.** CDH is a marketing-decisioning product with self-learning models. Orqix is a workflow and approval product. Copying CDH wholesale is out of scope. Two ideas transfer directly and fit existing capabilities:

1. **Decision records with reasons** (chosen, rejected, why) for every automated decision. Orqix decision tables are deterministic, so this is achievable and stronger (exact rule row, input snapshot, table version).
2. **Eligibility / applicability / suitability as a reviewable layering of rules** for product and credit decisions, expressed as decision tables with tests. Do not build adaptive or self-learning models; model risk management for them is a separate program.

### 3.4 Pega and Camunda in banking

| Domain | Pega | Camunda ecosystem |
|---|---|---|
| Onboarding / KYC | Client Lifecycle Management and KYC: onboarding, maintenance, periodic review, offboarding; rules-driven due diligence by country, booking entity and product; AML/product/tax question sets; GenAI and agentic features in the Nov 2025 capability sheet. [Vendor] | Orchestration of ID verification and screening around DMN rules; agentic matrix splits KYC into collect → check against policy → follow up, human confirms exceptions. [Vendor]/[Partner] |
| Lending | Case management plus Credit Decision Hub (Capgemini write-up). [Partner] | HCLTech credit platform: referred/declined applications reviewed and overridden by operations; real-time monitoring of each application. [Partner] |
| Disputes | Smart Dispute for Issuers: **Claim** parent with several transaction **Dispute** children; fraud answers route to a fraud queue; queues with priority and SLA; automatic audit trail; periodic card-network rule updates. [Doc]/[Partner] | Payment-exception agents parse ISO 20022, enrich, match duplicates; human confirms. [Vendor] |
| Governance | Decision records, bias/policy checks, audit history. [Vendor] | Governance and audit trail "each domain demands"; tool allow/deny lists. [Vendor] |

Outcome figures in these sources (for example "70% faster") are vendor or partner claims and are not used as targets here.

---

## 4. Where Orqix stands today

Verified by reading the repository on 9 October 2026 ([feature status](feature-status.md), [17](17-migration-workbench.md), `backend/app/src/main/java/com/orqix/migration`).

### Strengths to preserve

| Capability | Evidence |
|---|---|
| Disposition for every source element; blockers stop publishing; no silent omission | `Report.Issue.blocksPublishing()` treats open `blocker` and `action` as blocking |
| Original bytes, SHA-256, adapter version, approved mappings and source-to-target IDs persisted | [17](17-migration-workbench.md) |
| Idempotent conversion (same request returns the original workflow; changed request rejected) | [17](17-migration-workbench.md) |
| Safe parsing (XXE rejected, size/depth/element limits, archive path safety) | [17](17-migration-workbench.md) |
| Camunda 7/8 subset: none events, approvals, typed exclusive conditions, mapped HTTP service tasks, matched parallel regions, constant duration timers, mapped child calls | [17](17-migration-workbench.md) |
| Simple **JUEL and FEEL** comparisons converted; everything else reported, never approximated | `Expressions.java`: operators `eq`, `gte`, `lt` only |
| Pega archive inventory, manual mapping package with checksum linkage and provenance per target node | [17](17-migration-workbench.md) |
| Pinned versions and pinned child releases; durable timers; honest effect outcomes with reconcile | [feature-status](feature-status.md) |
| Bounded AI with budget, excluded models, access re-check; bounded agents with read-only MCP | [S10](build/S10-ai-assistance.md), [S13](build/S13-agents-mcp.md) |

### Gaps relative to the competitors

| # | Gap | Evidence | Competitor reference |
|---|---|---|---|
| G1 | No "converted but changed — verify" severity. Current: supported, info, action, blocker | `Report.java` constants | Camunda REVIEW |
| G2 | Per-process findings are grouped by source kind with kind/severity/unresolved filters; full JSON/CSV exports exist. Cross-file projects/grouping and XLSX remain open | `MigrationPage`, `reportExport`; filters retain complete-report exports and conversion | Camunda grouped view, CSV/XLSX/JSON |
| G3 | One BPMN file per import; no project of many files; no dependency ordering for call activities | `MigrationService`, `ConversionRequest` | Converter takes BPMN+DMN+forms together |
| G4 | DMN and form files are not imported (`DMN_IMPORT_NOT_SUPPORTED`, `FORM_NOT_IMPORTED`) | Reason codes in `Analyzer` | Converter handles all three |
| G5 | Unsupported: multi-instance, inclusive/event gateways, boundary timers/errors, message events, subprocesses, expressions beyond `eq/gte/lt` (no `>`, `<=`, `!=`, and/or) | Reason codes `MULTI_INSTANCE_NOT_SUPPORTED`, `GATEWAY_NOT_SUPPORTED_IN_V0`, `EVENT_NOT_SUPPORTED_IN_V0`, `SUBPROCESS_NOT_SUPPORTED`, `EXPRESSION_NOT_SUPPORTED` | Camunda 8 supports these |
| G6 | Service-task conversion requires hand mapping of every task; Camunda 8 connector element templates are not recognized | `GROUP_MAPPING_REQUIRED`, `WORKER_NOT_PORTABLE`, `JAVA_DELEGATE_NOT_PORTABLE` | Camunda connectors |
| G7 | Pega: mapping package remains hand-authored. Bounded XML identities, dependency evidence and rule-type mapping suggestions are built; effective rule resolution, native conversion and source behavior capture remain open | [17](17-migration-workbench.md); target golden harness described in G8 | Blueprint-style assisted analysis |
| G8 | A six-case target golden-scenario runner and deterministic evidence ZIP are built and verified on synthetic BPMN with local stubbed effects. Direct source-runtime replay and genuine-source equivalence remain open | [Acceptance harness](../tools/migration-equivalence/README.md), [spec](build/migration-equivalence-acceptance.md) | Source-owner baselines and signed differences still required by [05](05-migration.md) |
| G9 | No maker-checker on mapping approval or publish; no segregation between mapper and approver | `workflow.edit` converts, separate publish permission only | Banking control, §5 |
| G10 | Bounded child rework, sequential/parallel scalar collections and ordered collected child output arrays are implemented; arbitrary graph cycles, mutable correction forms and nested/object collections remain open | [S14](build/S14-bounded-iteration.md), [tracker](19-product-build-tracker.md) | Banking referrals need domain-specific correction and acceptance |
| G11 | No incident-style variable correction with audit; no "waiting on nothing" detection | Operator retry exists; variable edit does not | Camunda incident workflow |
| G12 | Request comments, templates and publish-review changes exist; current closeout adds step annotations and general published-version comparison; threaded element discussions and swimlanes remain open | Studio auto-layout, template catalog, publish review, run comments | Web Modeler, Lucidchart |

---

## 5. Banking: what Pega and Camunda do and how Orqix can be better

### 5.1 Where to compete and where not to

| Pega/Camunda banking capability | Recommendation |
|---|---|
| Packaged KYC/CLM lifecycle with jurisdiction rules | **Compete selectively.** Ship KYC onboarding review and periodic review as *templates* built from decision tables and approvals, not as a monolithic product. Jurisdiction rules become versioned **rule packs** (§5.2) |
| Disputes with Claim/Dispute parent-child | **Compete.** Pinned child workflows exist ([S12](build/S12-workflow-patterns.md)). Add parent-aggregates-children status, and per-child SLA |
| Lending with credit decision engine | **Compete on explainability.** Decision tables with test cases and a stored decision record per run. Do not build adaptive scoring models |
| Customer Decision Hub (marketing next-best-action) | **Do not build.** Different product, different risk (model governance, 20+ channels) |
| Agentic AI around KYC and payment exceptions | **Compete with bounded agents.** Orqix agents are already read-only, bounded and budgeted. Add output validation and human escalation (§5.2 item 7) |
| Conversion off Pega/Camunda | **Differentiate.** Behavior-first, evidence-pack conversion with maker-checker (§6) |

### 5.2 How Orqix can be better, concretely

Each item is a proposal. Milestones are in [§8](#8-recommendations-mapped-to-the-backlog).

1. **Decision record per automated decision.** Persist: input snapshot (redacted per policy), table or rule version, matched row, outcome, who or what decided, timestamp. Show chosen versus not-chosen where a table has several candidate rows. This copies CDH's best idea at deterministic scale and answers "why was this declined" without re-running anything.
2. **Maker-checker everywhere that matters.** Draft author ≠ approver ≠ publisher for banking workspaces. Also applies to connection definitions, rule packs, mapping approvals and conversion acknowledgements. Self-approval is already denied inside workflows; extend the principle to administration.
3. **Rule packs with effective dates.** Pega's card-network updates ship as periodic releases. Orqix equivalent: a decision table or policy set with `effectiveFrom`/`effectiveTo`, an approved change record and test fixtures, so a regulatory change is a reviewed, dated, testable release rather than an edit. A run records which pack version applied.
4. **Jurisdiction matrices.** Pega drives due diligence by country, booking entity and product. Model as a decision table whose output is a required-checks list; the workflow instantiates the required tasks (parallel regions already supported).
5. **Parent/child cases with aggregation.** Claim → Dispute, Customer review → Document requests. Parent status derived from children, with a stated rule, and child SLAs shown on the parent.
6. **Queues, delegation and reminders.** Pega shows prioritized queues with SLA. Orqix has group tasks, eligible-user delegation and durable one-shot in-app assignee reminders; durable queue priority and age display are added in the current closeout; repeated breach escalation and business calendars remain open (E04 and E06 already describe durable deadlines). Business-calendar handling must be explicit and tested.
7. **Agents with validation gates.** For KYC (collect → check → follow up) and payment exceptions: each agent step has a schema-validated output; failure or low confidence routes to a human task; every tool call, token cost and decision recorded on the run; write tools stay out of scope until separately approved ([S13](build/S13-agents-mcp.md)).
8. **Money correctness as a first-class rule.** Decimal arithmetic only, currency and scale declared per field, explicit rounding mode, no floating point, boundary tests (the existing 4,999.99 / 5,000 / 5,000.01 pattern generalizes).
9. **Effect safety for payments and letters.** Idempotency keys, unknown-outcome reconciliation (already implemented) and an "external effects" count asserted in tests. A converted workflow must not be able to duplicate an irreversible action.
10. **Evidence pack per release and per conversion.** One exportable bundle: definition, version, test results, approvals with identities and times, source report, and equivalence results. This is what an auditor asks for.
11. **Four-eyes on sensitive data views.** Field-level redaction in run detail, explicit reveal with audit, and no sensitive values in logs or AI prompts.

### 5.3 Banking controls checklist (to be confirmed against the bank's regulator)

The jurisdiction is not known (decision B6). The list below is a design checklist, not legal advice. Each line needs mapping to the bank's actual obligations by its compliance function.

| Control | Design response |
|---|---|
| Segregation of duties | §5.2 item 2; permission matrix with mutually exclusive roles |
| Immutable audit | Append-only audit with hash chaining or WORM storage option; audit export; time source stated |
| Data minimization and residency | Per-workspace region and retention policy; redaction before any AI call; Pega Blueprint offers region choice for blueprints, so customers will ask |
| Model and AI governance | Model registry entry per AI use, version and budget; human decision remains authoritative; AI output labeled and recorded |
| Change control | Reviewed, dated releases; rollback by pinned version; no in-place edit of a published release |
| Resilience and recovery | Existing kill/restart and database-outage evidence; restore exercise still open (T-RES-06) |
| Third-party risk | Connection definitions list endpoints, data classes and owners; egress allow-list exists |
| Retention and legal hold | Defined per record class; hold overrides deletion; open item in [11](11-security-operations.md) |
| Sanctions/PEP, fraud, credit bureau | Integrated through connections to the bank's existing services; Orqix orchestrates and records, it does not screen |

---

## 6. Conversion execution plan

This is the main deliverable: how Pega and Camunda definitions will be converted into Orqix, who does what, what each step produces and what must be true before moving on.

### 6.1 Contract (unchanged from [05](05-migration.md))

Convert supported definitions into a **reviewable draft** with evidence of every mapping and every gap. Live instances, history, users, attachments, credentials and integration code are separate projects. Silent omission is prohibited. Percent-mapped is an inventory metric, never a publish criterion. Publishing requires zero open blockers and passing representative behavior tests.

### 6.2 End-to-end pipeline

Each phase has an owner, an output and an exit gate. A phase cannot start until the previous gate passes. "Tool" means Orqix performs it deterministically; "AI-assisted" means it proposes and a person decides.

| # | Phase | Performed by | Output | Exit gate |
|---|---|---|---|---|
| 0 | **Scope and intake** | Business owner + migration lead | Migration project record: source system, version, process list, owners, data classification, AI permission (B4) | Named source owner (B2); classification agreed |
| 1 | **Capture behavior** | Source owner with analyst | **Golden scenarios**: inputs and observed outcomes from the live source for each path (approve, reject, rework, timeout, error, thresholds, null values, multiple approvers) | At least one scenario per path; owner signs the scenario list |
| 2 | **Upload and safe parse** | Tool | Stored original bytes, SHA-256, detected source kind and version | Parse limits pass; adapter selected |
| 3 | **Inventory and analyze** | Tool | Compatibility report: every element with severity, reason code, explanation and action; grouped summary; downloadable | Report persisted; every element has a disposition |
| 4 | **Propose mappings** | AI-assisted (optional) + analyst | Proposed group, service-task, child-workflow, expression and rule mappings, each marked `proposed` with rationale | Analyst accepts, edits or rejects each proposal (never bulk-accept) |
| 5 | **Approve mappings** | Second person (maker-checker) | Approved mapping set with identities and times | Approver differs from mapper |
| 6 | **Convert to canonical draft** | Tool | Draft workflow, source map (source ID → target ID), assumptions list | Compiler validation passes; invalid mappings create no draft |
| 7 | **Compile and static review** | Tool + designer | Compiler findings; decision-table checks; connection bindings resolved | No blockers; assumptions acknowledged |
| 8 | **Equivalence test** | Tool | Each golden scenario replayed on the target with stubbed effects; results compared with observed source outcomes | 100% of golden scenarios match, or each difference has a signed business explanation |
| 9 | **Business sign-off** | Business owner | Signed behavior approval; technical owner signs integrations and security | Both signatures recorded |
| 10 | **Publish** | Third person (publisher) | Immutable release pinned with dependencies | Separate permission; evidence pack attached |
| 11 | **Pilot / parallel run** | Operations | New starts for a low-risk slice enter Orqix; optional shadow comparison | Agreed reconciliation window passes with no unexplained difference |
| 12 | **Cutover and rollback plan** | Operations + owner | Traffic routed by product/segment; documented rollback that routes new starts back | Rollback rehearsed |
| 13 | **Decommission source** | Owner | Source retired only after in-flight work finishes and records are retained | Written retention decision |

In-flight cases finish in the source system unless a separate migration project is approved ([05](05-migration.md)).

### 6.3 Camunda: execution detail

**Variants.** Camunda 7 and Camunda 8 are separate adapters (`camunda-bpmn-0.2` today). Detection reads the namespaces (`camunda:` versus `zeebe:`). [Repo]

**Intake set.** BPMN, DMN and `.form` files together as one **migration project**, plus a list of external-task topics or job types and the connector element templates used. Files are processed in dependency order: child processes before parents (call activities pin a published child release).

**Construct treatment** (today versus proposed):

| Source construct | Today | Proposed | Notes |
|---|---|---|---|
| None start/end, sequence flows | Converted | — | |
| User task / candidate groups | Group mapping required | Propose mapping from group names; analyst confirms | Camunda 8 candidate groups and 7 `candidateGroups` |
| Exclusive gateway, simple `eq/gte/lt` | Converted | Add `>`, `<=`, `!=`, and/or as **REVIEW** items once the compiler supports them | Do not add to the importer before the compiler (S4) supports them |
| Complex FEEL/JUEL (functions, property access, lists) | Blocked | Stay blocked; show a suggested decision-table rewrite | No approximation |
| Service task: Java delegate / external task / Zeebe job type | Hand-map each | Group by job type or topic; propose a connection operation per group; one mapping covers all tasks of that type | Never execute source code |
| Camunda 8 connector element template (for example HTTP JSON) | Not recognized | Recognize known templates; pre-fill the REST operation from the template's URL, method and headers; secrets (`{{secrets.X}}`) become **references to be bound**, never values | Verify against real templates in the spike |
| Parallel fork/join | Converted when matched | — | |
| Timer (constant duration) | Converted | Add bounded date/cycle only after S12 supports them | |
| Call activity | Mapped child required | Resolve by name inside the project; auto-propose input/result mapping; REVIEW | |
| Multi-instance (approve by each of N) | Blocked | **Priority 1 construct** (see §8); needs compiler support first | Most common unsupported item in banking approvals |
| Boundary timer / error events | Blocked | **Priority 2**; pairs with approval time limits already in S12 | |
| Message / signal events, event subprocess | Blocked | Later; needs correlation design (E13) | |
| Listeners, scripts | Dropped with warning (`LISTENER_DROPPED`) | Keep as WARNING with the source text preserved | Execution listeners on flows have no Camunda 8 equivalent either |
| DMN | Not imported | **Import supported hit policies into Orqix decision tables**, same severity report, boundary tests generated | Highest value after §6.3 grouping |
| Forms (`.form` JSON / embedded) | Not imported | Map supported field types to the Orqix form schema; unsupported layout = TASK | |
| Pools, lanes | Likely modeling-only in Camunda 8 (not confirmed) | Lanes can propose group mappings | |
| CMMN (Camunda 7) | Not applicable | Blocked; Camunda 8 does not support it either | |

**Severity model.** Add `review` between `supported/info` and `action`. Proposed mapping to Camunda terms: INFO → `info`, REVIEW → `review` (converted, verify), WARNING → `action` (no direct equivalent; decision needed), TASK → `blocker`. `review` does not block publishing but must be acknowledged and appear in the evidence pack. The OpenAPI and frontend report views must be updated together.

**Report outputs.** Add grouped summary by `reasonCode` across the project; CSV and JSON export (JSON is intended for automation and for the AI-assisted step); XLSX optional.

### 6.4 Pega: execution detail

**Honest starting point.** Pega's own migration mechanism is the product rule: an archive of rulesets and data instances moved between environments. Older Pega documentation describes the archive as XML; a community answer for Pega 8 and later says the archive contains `rules.jar` (binary) and `schema.jar` (readable table XML), and that logic is not exportable as text. That answer is itself flagged as AI-assisted and must be validated. A third-party accelerator claims to generate XML for rules and is unverified. [Doc/community] **Therefore automatic Pega semantic conversion scope cannot be established until a real export is inspected.**

**Discovery spike (do first, before any Pega adapter work).** In a bank sandbox on the bank's Pega version: build or choose one case type with one flow, one decision table, one data page, one REST connector and one SLA; export it by every available mechanism; record what each contains. Mechanisms to test:

| Channel | What to capture | Likely usable for |
|---|---|---|
| A. Product rule ZIP | Inventory of files; whether rule bodies are readable | Inventory and dependency list ([Repo] today) |
| B. Per-rule XML ("View XML" in Dev Studio) [Knowledge] | Readable rule definitions one by one | Decision tables, flows, connector definitions |
| C. Documentation exports from Pega (application document, case type and flow diagrams, decision-table spreadsheets) [Knowledge] | Human-readable structure | AI-assisted reading, human verification |
| D. Behavior capture (golden scenarios, §6.2 phase 1) | Observed outcomes from the running system | **Equivalence proof independent of format** |
| E. Process documents, screens, recordings (Blueprint-style input) | Intent, roles, stages | AI-assisted design brief |

The spike ends with a written finding per channel: *readable / partially readable / opaque*, and an adapter decision. If rules are opaque, conversion becomes **design-assisted rebuild** (channels C, D, E) and the report says so plainly.

**Concept map** ([Knowledge]; validate in the spike). Treatments follow the "known limitation" column already in [05](05-migration.md).

| Pega concept | Orqix target | Automatic? | Main risk |
|---|---|---|---|
| Case type | Workflow (+ parent/child workflows) | Draft by mapping | Dynamic/ad hoc case work has no simple-graph equivalent |
| Stage | Stage metadata plus sequence; not behavior | Mapping | Stages are not always sequential |
| Flow / process shape | Sequential, branch, parallel nodes | Mapping | Utility, split-for-each, subprocess shapes |
| Assignment, work queue, worklist | Approval task + group mapping | Mapping | Skills, routing and work-queue rules |
| Decision table / tree / map | Decision table | **Propose, then verify by tests** | Rule resolution, circumstancing, inheritance (effective rule differs by access group and date) |
| When rule | Typed condition | Propose | Context-dependent evaluation |
| Data transform / activity | Typed field mapping or worker | **Never executed**; reported | Procedural logic |
| Data page | Field source via connection operation | Mapping | Scope and refresh semantics |
| REST/SOAP connector | Registered connection operation | Mapping | Auth, payload, error handling rebuilt |
| Declarative rule (expression, constraint, trigger) | Inventory and remediation | No | Fire on change; hidden dependencies |
| SLA (goal, deadline, passed deadline) | Duration timer plus escalation | Mapping | Business calendar and urgency semantics |
| Correspondence | Notification effect | Mapping | Template and channel behavior |
| Section / harness (UI) | Form schema when representable | Partial | Client behavior and custom controls |
| Access group, role, privilege | Workspace group and permission mapping | Mapping | Different permission model |
| Ruleset version and rule resolution | Single resolved snapshot per target release | **Required input** | The most common source of unexpected differences |

**Adapter plan.** `pega` adapter keeps the existing inventory and mapping-package contract (`orqix-pega-mapping-0.1`), and adds only what the spike proves readable:

1. A **resolved snapshot** input: the effective rules for one access group and date, supplied by the source owner, so inheritance and circumstancing are not guessed.
2. Rule-type readers added one at a time (decision table first, then flow, then connector), each with its own fixtures and a blocker for anything unrecognized.
3. Assisted mapping proposal (§6.6) over the inventory and readable rules.
4. Behavior-first validation (§6.5) as the real gate.

### 6.5 Equivalence and validation for banking

The target-side golden-scenario harness in G8 is now delivered: six synthetic purchase scenarios execute through normal import/publish/start/task APIs and compare admission, routing, outcomes, successful effects and matching stub orders. Four standalone tests verify comparison failures and reproducible evidence ZIPs. This is local target acceptance, not a source-semantic-equivalence claim or an enforced customer publishing gate. Source-runtime capture/replay, owner review and signed deviations remain required for banking cutover.

| Test class | What it asserts | Example |
|---|---|---|
| Path coverage | Each golden scenario reaches the same terminal outcome and the same sequence of human tasks | Approve, reject with reason, rework, timeout |
| Numeric boundaries | Same result below, at and above each threshold, using decimals | 4,999.99 / 5,000.00 / 5,000.01 |
| Null and missing | Same behavior for missing or empty values | No credit score present |
| Rounding and currency | Same rounding mode and scale | Fee calculation to 2 dp, half-up versus half-even |
| Assignment | Same group or user receives the task; no self-approval | Maker cannot approve own request |
| Time | Timers fire at the same duration, same time zone; calendar behavior stated | 3 business days versus 72 hours |
| Failure and retry | Same retry count and same terminal state on error | Downstream 503, then success |
| External effects | **Effect count equals the source's**, with stubbed endpoints, never the real downstream twice | One payment instruction, one letter |
| Idempotency | Replaying a start does not create a second case | Same idempotency key |

Process: golden scenarios (phase 1) are stored with the project; the harness replays each against the converted draft with every external effect stubbed; differences are listed; each difference is fixed or signed off by the business owner as intentional. Results go into the evidence pack. Optional **shadow mode** in the pilot (phase 11) runs the same new input through both systems and compares outcomes, with the target's external effects disabled.

### 6.6 AI-assisted conversion: guardrails

AI is useful for reading unfamiliar rules and proposing mappings; it is dangerous as a decider. The design keeps it in the proposing role.

| Rule | Reason |
|---|---|
| AI output is stored as `proposed` with model, prompt version, cost and the source evidence it used | Reproducible and reviewable |
| Each proposal is accepted, edited or rejected individually; no "accept all" for banking workspaces | Prevents rubber-stamping |
| The compiler and equivalence harness validate regardless of who proposed | Existing guarantee |
| AI cannot publish, acknowledge assumptions, or mark blockers resolved | Separation of duties |
| Inputs are definitions only: no customer data, no secrets, no credentials; redaction pass before any provider call | Pega Blueprint sends only anonymized descriptions; same principle |
| Source documents are treated as **data**, never as instructions (prompt-injection hardening) | Uploaded files can contain adversarial text |
| Disabled by default for bank sources until B4 is decided; supports a bank-approved private model | Internal logic is sensitive |
| Budget, model exclusions and concurrency limits from [S10](build/S10-ai-assistance.md) apply | Existing controls |

Useful AI tasks, in order of value: (1) propose group, connection and child mappings from names and structure; (2) summarize each Pega rule's intent in plain language for the analyst; (3) propose a decision-table rewrite of a blocked expression, shown as REVIEW and verified by tests; (4) draft golden scenarios from process documents for the owner to correct; (5) a Blueprint-style **design brief** for new workflows.

### 6.7 Cutover patterns

| Pattern | When | Control |
|---|---|---|
| New starts only | Default | In-flight stays in source; reconcile outcomes before routing more traffic |
| Strangler by product or segment | Multi-product processes | Route one product first; per-product rollback |
| Shadow comparison | High-risk decisions (credit, KYC risk rating) | Both systems decide; only the source acts; differences reviewed daily |
| Parallel run with reconciliation report | Payment-adjacent processes | Daily reconciliation of counts, outcomes and effects |
| In-flight migration | Only by separate approved project | Requires state mapping per wait point; Camunda 7 documents that multi-instance runtime data is not migratable and that wait states may be needed |

### 6.8 Implementation architecture (proposed, not scheduled)

Extends `com.orqix.migration`; no change to existing contracts until development resumes.

| Component | Proposal |
|---|---|
| Adapter SPI | One interface per source kind: `detect`, `parse`, `analyze`, `convert` as in [05](05-migration.md); adapters for Camunda 7, Camunda 8, Pega (versioned) |
| Migration project | New entity grouping many sources, a dependency order, golden scenarios and an approval history |
| Findings | Severity enum extended with `review`; grouped query by `reasonCode`; CSV/JSON export |
| Mapping catalog | `proposed / accepted / rejected / approved` states with actor and time; second-person approval |
| Assisted mapping job | Reuses the AI job pattern (202 plus polling, budget, access re-check, audit) |
| Equivalence runner | Replays scenarios on the draft using the existing stub/test-run mode with effects stubbed; stores per-scenario results |
| Evidence pack | Deterministic ZIP: original source and hash, report, mapping history with identities, test results, approvals, release identifier |
| Permissions | New permissions for mapping approval and evidence export; banking workspaces enforce mutually exclusive mapper, approver and publisher |
| Data model | New tables only (project, source, finding, mapping, scenario, result); existing tables unchanged |
| API | New operations under `/api/v1/workspaces/{workspaceId}/migration-projects`; existing `/migrations` stays |

### 6.9 Roles

| Role | Responsibility |
|---|---|
| Source owner | Supplies exports and observed scenarios; confirms behavior |
| Business owner | Signs target behavior |
| Migration analyst | Reviews findings, accepts or edits mappings |
| Mapping approver | Second person approving mappings |
| Designer | Fixes the draft in the studio |
| Publisher | Separate person; publishes the release |
| Technical owner | Signs integrations, security and cutover |
| Compliance reviewer | Reviews evidence pack for banking workspaces |

---

## 7. How a converted banking workflow executes

Conversion produces a canonical definition that compiles to the same executable form as any other Orqix workflow. Runtime behavior is the existing, verified design ([S5](build/S5-runtime.md)); the points that matter for banking are stated here.

| Step | Behavior |
|---|---|
| Start | Idempotent by key; request creates a run pinned to a release and its dependency releases |
| Human task | Group task; claim; one winner; decision recorded with identity, time and form version |
| Decision | Decision table evaluated deterministically; decision record stored (§5.2 item 1) |
| Parallel and child | Matched fork/join; child workflow pinned; parent resumes when children complete |
| Timer and SLA | Durable; survives restart; time-limit routes handled; business calendar behavior documented per workflow |
| External call | Connection operation with allow-listed egress; honest outcome states: applied, not applied, **unknown**; unknown outcomes must be reconciled by an authorized person before retry |
| Failure | Bounded retry; failed job recorded; operator actions permission-scoped and audited |
| Agent step | Bounded model and tool calls; validated output; escalation to human task on failure; usage recorded |
| Recovery | Run resumes after process kill and database outage (existing resilience evidence) |
| Audit | Sensitive actions audited; export is secret-free |
| Versioning | New release does not alter running instances; rollback re-points new starts only |

Implemented platform slices include bounded child rework, sequential/parallel scalar iteration, ordered collected child output arrays, delegation and durable one-shot assignee reminders ([S14](build/S14-bounded-iteration.md)). Still needed for many banking processes: mutable correction forms, nested/object collection policies and native BPMN multi-instance/loop import (G5), message correlation for waiting on customer documents (E13), and automatic aging/escalation (§5.2 item 6). Current closeout adds durable priority, age display and oldest/newest ordering. These are called out in [§8](#8-recommendations-mapped-to-the-backlog).

---

## 8. Recommendations mapped to the backlog

Relative size: **S** a few days, **M** one to three weeks, **L** more than three weeks for an experienced pair. These are planning sizes, not commitments; the existing docs warn that estimates need owner confirmation ([S8](build/S8-r0-tickets.md)).

### Wave 1 — conversion ergonomics and honesty (no new engine features)

| # | Item | Epic | Size | Gate |
|---|---|---|---|---|
| 1.1 | Add `review` severity; update OpenAPI, frontend and report tests together | E16 | S | Contract check passes |
| 1.2 | Per-process and cross-file project grouping with complete CSV/JSON findings export delivered | E16 | S | Focused UI/export checks pass; exports preserve full report counts |
| 1.3 | Migration project (many files, dependency order, child-before-parent) | E16 | M | Multi-file fixture converts in order |
| 1.4 | Group service tasks by job type/topic; one mapping per group | E16 | S | Fixture with 5 identical tasks needs one mapping |
| 1.5 | Maker-checker on mapping approval and publish (banking workspace setting) | E14, E16 | M | Same person cannot hold two of mapper/approver/publisher |

### Wave 2 — equivalence and Pega intake

| # | Item | Epic | Size | Gate |
|---|---|---|---|---|
| 2.1 | **Pega discovery spike** (§6.4) with real bank export | E16 | S–M | Written finding per channel (B2, B3) |
| 2.2 | Target golden-scenario runner and deterministic evidence pack delivered; source-runtime replay remains open | E16, E17 | L | Six synthetic live cases pass with successful-effect and matching stub-order checks; genuine source baselines pending |
| 2.3 | Evidence pack export | E16, E18 | M | Deterministic ZIP; contains approvals and results |
| 2.4 | DMN import to decision tables with generated boundary tests | E07, E16 | M | Hit-policy fixtures; unsupported policies blocked |
| 2.5 | Form import (supported field types) | E06, E16 | M | Form validator parity fixtures |
| 2.6 | Assisted mapping proposals (AI, optional) | E09, E16 | M | Proposals recorded; none auto-accepted |

### Wave 3 — constructs banks actually use

| # | Item | Epic | Size | Gate |
|---|---|---|---|---|
| 3.1 | Multi-instance approvals (compiler, runtime, importer) | E04, E05 | L | Cancellation during multi-instance tested |
| 3.2 | Bounded rework / send-back | E04 | L | Prior decisions immutable; bound enforced |
| 3.3 | Boundary timer and error events | E04, E05 | M | Race between approval and timer has one winner |
| 3.4 | Expression operators `>`, `<=`, `!=`, and/or in compiler first, then importer | E03 | M | Compiler fixtures before importer change |
| 3.5 | Connector element-template recognition (Camunda 8) | E08, E16 | M | Real template fixtures from the spike |
| 3.6 | Message correlation for customer responses | E13 | L | Late and duplicate message tests |

### Wave 4 — banking product layer

| # | Item | Epic | Size |
|---|---|---|---|
| 4.1 | Decision record per automated decision | E07, E17 | M |
| 4.2 | Rule packs with effective dates | E07, E15 | M |
| 4.3 | Parent/child aggregation and per-child SLA | E04 | M |
| 4.4 | Queue priority and ageing | E06 | M |
| 4.5 | KYC and credit-approval templates (after B1) | E02 | M each |
| 4.6 | Agent output validation and escalation | E10, E11 | M |
| 4.7 | Incident-style variable correction with audit; detect runs waiting on nothing | E17 | M |
| 4.8 | Authoring: element comments, version compare, swimlanes by group, templates, Mermaid/BPMN export for review | E02 | M–L total |
| 4.9 | Later: in-flight run migration, canary releases | E15, E18 | L |

---

## 9. Delivery sequence and dependencies

The user authorized implementation after this research was written. Platform work proceeds with the existing scope; banking-specific decisions B1–B6 and source exports remain open. The proposed sequence is:

1. **Before coding:** resolve B1–B6 in the [decision register](13-decision-register.md); obtain one real export and 20+ observed scenarios per source (B2, B3).
2. **Wave 1** can start immediately after the parallel session merges: it touches the importer, report and permissions and needs no new engine capability. Do it on a separate branch and confirm the other session has not modified `com.orqix.migration` or `contracts/openapi.yaml` first.
3. **Run the Pega spike (2.1) in parallel with Wave 1.** Its result decides the Pega adapter scope and whether "design-assisted rebuild" replaces automatic conversion.
4. **Wave 2** target-side runner and deterministic packs are delivered. Genuine-source intake, observed baselines and owner-signed differences are the next acceptance dependencies; passing a synthetic target suite does not prove source equivalence.
5. **Wave 3** constructs are ordered by observed blocker frequency in real imports, not by this document's guess. Record every `reasonCode` from real projects and re-rank.
6. **Wave 4** after B1 selects domains.

Acceptance for the whole conversion program: for each source process, every golden scenario matches or has a signed explanation; zero open blockers; an evidence pack exists; a second person approved mappings and a third published; rollback was rehearsed.

---

## 10. Risks and open inputs

| Risk | Mitigation |
|---|---|
| Pega archives are opaque on the bank's version | Spike first; fall back to design-assisted rebuild with behavior capture; never claim automatic conversion |
| Rule resolution differences (circumstancing, inheritance, ruleset versions) cause silent behavior change | Require a resolved snapshot input; equivalence tests as the gate |
| Over-trust in AI proposals | Individual acceptance, second-person approval, equivalence harness |
| Vendor outcome claims create unrealistic expectations | Do not quote percentages as targets; measure internally |
| Scope creep into a marketing decision engine | Decision B1 excludes next-best-action |
| Regulatory assumptions made without a regulator named | B6; §5.3 is a checklist for the bank's compliance team, not legal advice |
| Duplicate effects during parallel run or shadow mode | Effects stubbed or disabled on the target; effect count asserted |
| Conflicting edits with the parallel development session | Doc-only now; Wave 1 waits for merge (§9) |
| Camunda 7 specifics (delegates, listeners, transactions) carry hidden behavior | Reported as WARNING/TASK; behavior tests required; no source code executed |

**Inputs still needed:** named source owners and processes (B2); Pega version and sandbox access (B3); AI permission decision (B4); banking domain choice (B1); jurisdiction (B6); a Camunda 8 model that uses connector templates; at least one Camunda DMN and one form from the bank's real models.

**What this document did not verify:** Pega's rule archive contents; Camunda Marketplace contents (page unreadable); the Nov 2025 Pega CLM capability sheet (PDF not extractable); Pega's banking industry page (404). Items labeled [Knowledge] are untested assumptions.

## Current integration follow-up

Repository capabilities in this research are a snapshot, not a completion tracker. The active build includes guided conversational execution, Microsoft Graph document reads, immutable comments, bounded child rework, sequential/parallel scalar collections, ordered collected child output arrays, eligible-user delegation and durable one-shot in-app assignee reminders. JSON/CSV report exports and per-process finding groups/filters are built. A six-case target acceptance runner passes on synthetic imported workflows with local stubbed effects and exports deterministic provenance/result ZIPs. PDF/OOXML snapshots and text extraction pass local integration and executable-jar/container parser checks; customer Graph tenant acceptance remains external. The current checks and limitations are recorded in [doc 19](19-product-build-tracker.md). The research's vendor claims have not been independently revalidated in this integration pass. Banking templates, genuine-source equivalence, multi-file migration projects, mapping maker-checker, business-calendar deadlines and the remaining proposed waves retain their own acceptance criteria and are not claimed complete.

### Wave status at 10 October 2026

| Item | Status |
|---|---|
| 1.1 `review` severity | Built (non-blocking, counted, persisted; UI filter and acknowledgement) |
| 1.2 Grouped findings and export | Per-file and cross-file findings export built, with reason/source-kind/file grouping, complete report hashes and partial-load protection; grouped service mappings built (`SERVICE_TASK_GROUP`) |
| 1.3 Migration project | Built (up to 10 mixed BPMN/DMN files, dependency order, convert-next). One convertible decision per DMN file; missing/duplicate decisions and unsupported sources block conversion; reviewed task mappings remain explicit. S20 |
| 1.4 Group service tasks | Built |
| 1.5 Maker-checker | Built for migrations (policy, independent review bound to exact draft, source and mappings hashes; mapper, reviewer and publisher differ). Not yet applied to connection definitions, rule packs or ordinary workflows |
| 2.2, 2.3 Equivalence runner, evidence pack | Built earlier on synthetic fixtures; no real source yet |
| 2.4 DMN import | Built for the safe subset (UNIQUE and FIRST, one tested input, literal tests); generated boundary tests |
| 2.5 Form import | Not built |
| 2.6 Assisted mapping proposals | Not built |
| 3.1 Multi-instance | Built for call activities mapped to bounded for-each; user-task multi-instance is not supported by the compiler |
| 3.3 Boundary timer and error events | Not built |
| 3.4 Operators | Built: `eq ne gte gt lt lte`, flat all/any groups of 2 to 8 terms |
| 3.5 Connector element templates | Not built |
| 4.1 Decision record | Built for canonical single-input tables: pinned rule version/result, input values, every candidate's match/selection, default selection and permission-filtered history. S21; no values in global audit |
| 4.x Banking product layer | Templates built and executed; durable task priority, age display and oldest/newest sorting added. Effective-dated rule packs, automatic aging/escalation, four-eyes across stages, multi-currency and business calendars remain gaps in the [capability matrix](banking-capability-matrix.md) |

See [doc 19](19-product-build-tracker.md) for what is verified on the merged tree and what is not.

Current engineering, customer acceptance and excluded infrastructure are tracked separately in [product closeout](21-product-closeout.md). A completed wave slice does not close all later roadmap items.
