# Blue Ocean discovery: lightweight business workflow
Revision 0.1 · 30 October 2026 · **Strategy hypothesis, not research.** Ratings and segment sizes are desk estimates from domain knowledge and this repository's own analysis ([20](20-competitive-analysis.md), [24](24-snapflow-comparison.md), [feature status](feature-status.md)). No customer or non-customer interviews have been run. Treat every score as something to test.

## How to read this document

| Need | Read |
|---|---|
| The one-paragraph answer | [§5 Opportunity summary](#5-blue-ocean-opportunity-summary) |
| How the market competes today | [§1 Strategy canvas](#1-strategy-canvas) |
| Who is not buying, and why | [§2 Non-customer analysis](#2-non-customer-analysis) |
| What Orqix should stop, cut, raise and invent | [§3 ERRC grid](#3-four-actions-framework-errc-grid) |
| Effect on the current roadmap | [§6](#6-implications-for-the-roadmap-and-solution-definition) |

---

## 1. Strategy canvas

### Competitive factors

Scores are 1 (low) to 5 (high) delivery. For **affordability**, **operational lightness** and **time to first live workflow**, high means *better for the buyer* (cheap, small, fast).

1. **Suite breadth** — case management, RPA, decisioning, process mining, portals in one platform
2. **Notation depth** — BPMN/DMN fidelity and advanced constructs
3. **AI generation and agent claims** — "describe it and we build it"
4. **Connector catalogue size**
5. **Developer extensibility** — SDKs, custom workers, code hooks
6. **Partner and integrator ecosystem** — certified SIs, sales coverage
7. **Affordability at entry** — licence plus services to get a first workflow live
8. **Operational lightness** — infrastructure the customer must run or depend on
9. **Time to first governed live workflow**
10. **Business-user authoring without training**
11. **Control evidence an auditor can read** — who approved what, under which version, tested how
12. **Honest migration from an incumbent platform**

### Competitor value curves

| Factor | Pega | Appian | Camunda 8 | ServiceNow workflow | Power Automate–class citizen tools | Industry avg | Orqix today |
|---|---|---|---|---|---|---|---|
| Suite breadth | 5 | 4 | 2 | 4 | 3 | **3.6** | 2 |
| Notation depth | 4 | 3 | 5 | 2 | 1 | **3.0** | 2 |
| AI generation / agents | 5 | 4 | 3 | 4 | 4 | **4.0** | 2 |
| Connector catalogue | 4 | 4 | 3 | 4 | 5 | **4.0** | 2 |
| Developer extensibility | 3 | 3 | 5 | 3 | 2 | **3.2** | 3 |
| Partner / SI ecosystem | 5 | 4 | 3 | 5 | 3 | **4.0** | 1 |
| Affordability at entry | 1 | 2 | 3 | 1 | 4 | **2.2** | 4 |
| Operational lightness | 1 | 2 | 2 | 3 | 4 | **2.4** | 5 |
| Time to first governed live workflow | 1 | 2 | 2 | 2 | 5* | **2.4** | 4 |
| Business authoring without training | 3 | 3 | 1 | 3 | 4 | **2.8** | 4 |
| Auditor-readable control evidence | 4 | 4 | 3 | 4 | 2 | **3.4** | 4 |
| Honest migration from incumbent | 2 | 2 | 4† | 1 | 1 | **2.0** | 4 |

\* Fast to live, but usually *ungoverned*: no publish review, versioned approvals or tested releases.
† Camunda's tooling covers Camunda 7 → 8, not other vendors.

### Observations

- **Where curves converge.** Everyone is raising AI generation, connector counts and partner reach. In late 2026 every vendor says "agentic". This is the red ocean: Orqix cannot win a connector or AI-claims race against Pega, ServiceNow or Microsoft.
- **Where curves diverge.** Camunda bets on developers and notation; citizen tools bet on speed and price; Pega and ServiceNow bet on breadth and ecosystem. Nobody combines *fast and cheap* with *governed and auditable*.
- **Industry assumptions.**
  - Serious workflow needs a platform team, certified developers or an SI.
  - Business users either learn BPMN or are given an ungoverned toy.
  - More breadth, more connectors and more AI autonomy are always better.
  - Migration is a services engagement, sold with a "% converted" headline.
- **Overlooked factors.** Operational lightness, governed speed, evidence a non-technical auditor can follow, and migration that says plainly what did *not* convert. These are exactly where Orqix already scores well.

---

## 2. Non-customer analysis

### Tier 1: soon-to-be non-customers

**Who they are.**
- Business units inside Pega or Appian estates who wait months in a central change queue for small workflow edits. Common in bank and insurer shared-service centres in India, the Philippines and Poland that run work for UK, US and EU parents.
- Camunda teams whose "simple" approvals turned into Java workers, Zeebe clusters and Elasticsearch to operate.
- Owners of Power Automate or similar flows that audit or risk teams have flagged as shadow IT.
- EU financial firms reviewing concentration and exit risk on single SaaS platforms under DORA.

**What they tolerate.** Change queues, licence renewals they cannot justify per workflow, platforms they cannot exit, and audit findings on citizen flows.

**What they would leave for.** A governed place to run *routine* workflows that the business can change itself in days, priced and operated per workflow rather than per enterprise platform.

**Substitutes they consider.** Building on raw Flowable or Temporal in-house, Jira Service Management, Retool-style internal tools, or staying put.

**Estimated size.** Large in count of workflows, not of logos: in a typical large incumbent estate, most deployed processes are simple approvals and reviews that do not need the platform's full suite (hypothesis to verify in interviews).

### Tier 2: refusing non-customers

**Who they are.**
- **Mid-size and regional banks, credit unions and insurers.** The US alone has thousands of banks and credit unions; Europe and Asia add more. Many evaluated Pega or Appian and stopped at price and SI cost.
- **Organisations under data-residency rules** that refuse SaaS workflow tools: Indian regulated entities under RBI localisation expectations, Gulf banks, Indonesian and Vietnamese financial firms, German and French public bodies and *Mittelstand* manufacturers.
- **Risk and compliance teams that banned citizen automation** after an incident.

**Why they refuse.**
- Entry cost: licence plus a six-figure services engagement before the first live workflow.
- Infrastructure: they cannot run Kafka, Elasticsearch and a Kubernetes platform team for an approval process.
- Sovereignty: data cannot leave the country or the bank's own data centre.
- Control: citizen tools cannot show an auditor which version approved what.

**What they use instead.** Shared mailboxes, Excel trackers, SharePoint lists, paper sign-off sheets, and core-banking screens used for things they were not designed for.

**What would convert them.** Self-hosted on one application plus PostgreSQL, live in days, with release review and evidence an auditor accepts, at a price a department can approve.

**Estimated size.** Thousands of organisations globally, each with tens to hundreds of routine workflows currently run on email and spreadsheets.

### Tier 3: unexplored non-customers

**Who they are.**
- **Cooperative banks, microfinance institutions and SACCOs** in Kenya, Nigeria, India, Bangladesh and the Philippines, running loan approvals and exceptions on paper, WhatsApp and phone calls.
- **Municipal and district government offices** handling permits, grants and benefits case files.
- **Hospital and university administration** (procurement, credentialing, research approvals).
- **Internal audit and compliance functions as buyers.** The industry sells to IT and operations; nobody sells workflow to the people who must *prove* control.
- **System integrators and boutique consultancies** who need a re-platform target when a client leaves Pega or Camunda, and today have none they can deploy cheaply.

**Why the industry ignores them.** Assumed too small, too low on IT maturity, or "not a workflow buyer". The industry assumes buyers have platform teams, stable connectivity and English-first users.

**What would make it relevant.**
- Packaged templates for their actual work (loan exception, KYC refresh, permit, grant) rather than a blank modeller.
- Low connectivity tolerance and email or mobile approvals.
- Localised forms and working calendars per region and public holiday set (calendars already exist).
- For auditors: an evidence pack they can open without logging into a platform.
- For SIs: a migration ledger and golden-scenario proof they can put in a statement of work.

**What they use instead.** Paper, messaging apps, spreadsheets, or nothing formal.

**Estimated size.** Very large in count (tens of thousands of institutions and offices), low revenue per account. Viable only with near-zero onboarding cost, templates and partner-led delivery.

### Non-customer commonalities

- **Shared barriers:** platform cost before value, infrastructure they cannot run, specialist skills, and no way to prove control.
- **Shared unmet need:** *governed routine work* — simple, changeable workflows the business owns, with evidence strong enough for an auditor or regulator, running where the data must stay.

---

## 3. Four Actions Framework (ERRC grid)

### Eliminate

| Factor | Why |
|---|---|
| BPMN literacy as an authoring requirement | All three tiers cite specialist skills as a barrier. Orqix already compiles its own workflow format to Flowable; keep BPMN as an engine detail, never a user concept. |
| Required infrastructure beyond one app and PostgreSQL | Tier 2 refuses Kafka, Redis, Elasticsearch and cluster operations. Make the one-app footprint a hard product rule, not a current state. |
| Mandatory certified-developer or SI model to go live | Entry cost is the most common Tier 2 dealbreaker. Partners deliver migrations and templates; they are never required for a first workflow. |
| "% converted" migration headlines | Incumbent converters overclaim. Orqix's every-element disposition is already more honest; drop percentage marketing entirely. |

### Reduce

| Factor | To what level and why |
|---|---|
| Suite breadth | No RPA, process mining, customer decision hub or portal builder. Routine governed work only; integrate with others rather than replace them. |
| Connector catalogue | Reviewed OpenAPI import plus a short curated list (Microsoft, email, HTTP). Not a thousand connectors; Tier 2 needs a few internal APIs done safely. |
| AI autonomy | AI drafts, explains and suggests mappings; it never publishes, executes or converts. Below the industry's "agentic" level by design, which is a selling point to regulated buyers. |
| Advanced notation constructs | Support only the medium patterns real target workflows use (parallel review, rework, timers, child process). Do not chase full Camunda parity. |

### Raise

| Factor | How far |
|---|---|
| Time to first governed live workflow | From months to days, measured and published. The three-day simple-workflow target in [01](01-product-spec.md) becomes the headline promise. |
| Operational lightness and sovereignty | From "small" to "runs in your data centre, in your country, on one VM". Package a single-image deployment with backup and upgrade runbooks. |
| Auditor-readable control evidence | Above Pega and Appian: every release, decision and correction traceable in language an auditor reads, without platform access. |
| Honest migration | Above everyone: dispositions for every source element, preserved originals, golden-scenario equivalence, "converted but changed — verify" severity. |
| Safe operator recovery | Guarded incident correction and honest effect outcomes already exist; make them visible as a reason to trust the product. |

### Create

| Factor | What it serves |
|---|---|
| **Control evidence pack** per release and per run: approvals, versions, tests passed, diff from previous release, corrections made. Exported as PDF/DOCX (generation already exists) | Internal audit and compliance as a new buyer (Tier 3); refusing regulated buyers (Tier 2) |
| **Migration ledger as a product** for SIs: element-by-element disposition, golden scenarios, checksum provenance, packaged for a statement of work | SIs and incumbent-estate teams (Tier 1, Tier 3) |
| **Fixed-scope "live in days" offer**: template + workspace + publish review + evidence pack for a stated price | Tier 2 entry-cost barrier |
| **Regulated template packs** by region (loan exception, KYC refresh, vendor onboarding, permit, grant) with local calendars and languages | Cooperative banks, MFIs, municipalities (Tier 3) |
| **Workflow-level exit guarantee**: definitions, history and evidence exportable in open formats | DORA-style exit-risk reviews (Tier 1) |

**Value-innovation test.** Eliminate/Reduce removes the biggest cost drivers (SI dependency, infrastructure, connector and suite maintenance). Raise/Create invests in what non-customers lack (speed, sovereignty, evidence, honest migration). Both sides move at once, so this passes.

---

## 4. New value curve

| Factor | Industry avg | Orqix today | Orqix blue ocean | Action |
|---|---|---|---|---|
| Suite breadth | 3.6 | 2 | 1 | Reduce |
| Notation depth (exposed to users) | 3.0 | 2 | 0 | Eliminate as a user concept |
| AI generation / autonomy | 4.0 | 2 | 2 | Reduce (bounded by design) |
| Connector catalogue | 4.0 | 2 | 2 | Reduce |
| Partner / SI dependency to go live | 4.0 | 1 | 0 | Eliminate |
| Affordability at entry | 2.2 | 4 | 5 | Raise |
| Operational lightness / sovereignty | 2.4 | 5 | 5 | Raise and make a product rule |
| Time to first governed live workflow | 2.4 | 4 | 5 | Raise |
| Business authoring without training | 2.8 | 4 | 5 | Raise |
| Auditor-readable control evidence | 3.4 | 4 | 5 | Raise |
| Honest migration | 2.0 | 4 | 5 | Raise |
| Control evidence pack | 0 | 1 | 5 | Create |
| Migration ledger for SIs | 0 | 2 | 4 | Create |
| Regional regulated template packs | 0 | 1 | 4 | Create |
| Exit guarantee | 0 | 1 | 4 | Create |

The curve is the inverse of the incumbents' on breadth, AI and ecosystem, and above them on speed, sovereignty and evidence.

---

## 5. Blue Ocean opportunity summary

**The opportunity.** *Governed routine work.* Organisations that must prove control but cannot afford, operate or staff an enterprise BPM platform, and cannot trust citizen tools. Orqix runs their simple and medium workflows on their own infrastructure, live in days, with evidence an auditor accepts and an honest path off incumbent platforms.

**Primary customers.**
1. Mid-size regulated institutions with data-residency constraints (Tier 2) — first target.
2. Business units and SIs moving routine workflows out of Pega, Appian or Camunda estates (Tier 1).
3. Internal audit and compliance teams as buyers or co-sponsors (Tier 3).

**Key differentiator.** Not "a lighter Pega". The only offering that is simultaneously fast, self-hosted and audit-grade, and that says plainly what it does not do.

**Cost structure impact.** No SI on the critical path, one app plus PostgreSQL, a curated connector set and no suite modules cut both Orqix's delivery cost and the customer's total cost.

**Value creation impact.** Days to live, sovereignty by default, evidence packs and honest migration remove the four barriers shared across all non-customer tiers.

---

## 6. Implications for the roadmap and solution definition

**Roadmap tensions to decide.**
- [20 §4](20-competitive-analysis.md#4-where-orqix-stands-today) gap G5 (multi-instance, boundary events, message events, full expressions) chases Camunda parity, which is red ocean. Recommend building only constructs that appear in the target segment's real workflows, ranked by interview and migration-sample evidence.
- Connector and AI-agent expansion should stay bounded; spend that effort on the evidence pack and the single-image sovereign deployment.
- Banking (decision B1 in [20](20-competitive-analysis.md#1-summary-and-decisions-needed)) fits this strategy if it means mid-size regulated institutions and routine operational workflows, not competing with Pega's customer-engagement products.

**For the PRFAQ (solution definition).**
- Headline: "A regulated team put its first audited workflow live in three days, on its own server."
- The FAQ should state what Orqix does *not* do (no RPA, no process mining, AI never publishes) as a strength.

**For the closed-world map.**
- Bound the world to simple and medium workflows, one app plus PostgreSQL, a curated connector set and reviewed OpenAPI imports.

**For the customer persona.**
- Primary: operations lead at a mid-size regulated institution, with the head of internal audit as co-sponsor.
- Secondary: SI migration lead holding an incumbent-exit engagement.

**Next steps.**
1. Interview five to eight Tier 2 non-customers in at least two regions (for example India and the EU) to test the barrier ranking and the evidence-pack concept.
2. Run customer discovery (jobs to be done) for the operations-lead and auditor personas.
3. Prototype the control evidence pack from existing release, approval and PDF/DOCX capabilities, and test it with one auditor.
4. Re-rank the [competitive gap register](22-competitive-gap-delivery.md) against §3.
