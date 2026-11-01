# Regional regulated template packs

Status: built-in templates with compiler, simulator and Docker-stack tests (results below). **Illustrative templates, not regulatory-compliant products.** Thresholds, review intervals, holiday lists, document names and fees are placeholders chosen to show the platform. An institution's compliance function must confirm the rules for its regulator, state or county before real use. Second-language labels are drafts for native-speaker review.

Strategy context: [27 Blue Ocean discovery](27-blue-ocean-discovery.md), section 2 tier 3 (cooperative banks, MFIs, SACCOs, municipalities) and section 3 *Create: regulated template packs by region*.

## What a pack is

A pack is a set of ordinary workflow templates (`examples/pack-<region>-<template>.json`) for one industry, with a variant per region. A variant sets:

| Regional setting | How the template does it |
|---|---|
| Working calendar | Every approval with a due date uses a calendar: time zone, working weekdays, opening hours (with a lunch break where offices have one) and the public holidays for 2026 and 2027. Due dates count working hours only |
| Currency | Amount fields are titled with the currency (INR, EUR, KES, NGN) and use a plain number field with two decimals. Thresholds in rule tables are in that currency |
| Language | Field titles, section titles, step names, the submit button and notifications are bilingual: "English / Hindi", "English / German", "English / Swahili". Nigeria is English only |
| Local rules | Tax or registration IDs are format-checked on the form (GSTIN, EU VAT number, KRA PIN, CoA number, NGO Darpan ID); thresholds, document lists and review intervals follow local practice |

Templates use only constructs the compiler already supports: rule tables, branches, approvals with a working calendar, parallel and inclusive splits, several approvers on one step, lists of rows (count and sum), time limits with escalation, and in-app notifications. They need **no connection and no child workflow**, so a workspace can publish any of them as soon as the task groups exist.

## The packs

| Pack | Template | India (Hindi) | EU (German) | Kenya (Swahili) | Nigeria (English) |
|---|---|---|---|---|---|
| Banking and microfinance | Loan exception approval | `pack-in-loan-exception` | `pack-eu-loan-exception` | `pack-ke-loan-exception` | `pack-ng-loan-exception` |
| | KYC refresh | `pack-in-kyc-refresh` | `pack-eu-kyc-refresh` | `pack-ke-kyc-refresh` | `pack-ng-kyc-refresh` |
| | Cooperative / MFI group loan | `pack-in-group-loan` | not offered (see gaps) | `pack-ke-group-loan` | `pack-ng-group-loan` |
| Shared services | Vendor onboarding | `pack-in-vendor-onboarding` | `pack-eu-vendor-onboarding` | `pack-ke-vendor-onboarding` | not offered |
| Public sector | Building or business permit | `pack-in-permit` (building plan) | `pack-eu-permit` (building permit) | `pack-ke-permit` (single business permit) | not offered |
| | Grant application | `pack-in-grant` | `pack-eu-grant` | `pack-ke-grant` | not offered |

Twenty templates. The template ID is the file name without `.json`.

### Loan exception approval

A loan that breaks credit policy (rate concession, tenor, FOIR/DSTI/LTV, collateral, bureau score, ...). The branch manager reviews; a rule table on the amount then sends it to the credit head (with overdue reminders) or to a credit committee where two different members must approve. Loans to insiders and related parties also need compliance. A rejection always needs a reason; the requester never decides their own request.

| Region | Credit head from | Committee from | Regional note |
|---|---|---|---|
| India | INR 25 lakh | INR 1 crore | Bank week Monday to Saturday with the second and fourth Saturdays closed (RBI) |
| EU | EUR 100,000 | EUR 500,000 | Related-party step reflects CRD Article 88; TARGET2 closing days |
| Kenya | KES 1,000,000 | KES 5,000,000 | SACCO / microfinance bank wording (deposit multiple, guarantors, CRB listing) |
| Nigeria | NGN 10 million | NGN 50 million | Microfinance / cooperative bank wording |

### KYC refresh (extends the existing periodic review)

`banking-kyc-periodic-review.json` already covers a fixed 90-day re-review as a child of KYC onboarding. The pack does not duplicate it: the regional KYC refresh keeps its contract (inputs `customerName` and `customerType`, group `kyc-analysts`, output `reviewedBy`) and adds two required answers, `riskRating` and `detailsChanged`, that drive a risk-based path. It stands alone; to use it as the onboarding child instead, change `workflowRef` and also map those two answers (rules cannot read an answer that was left out):

* low risk and nothing changed: accepted on self-declaration (no human step);
* medium risk or changed details: analyst re-review;
* high risk: re-review and enhanced due diligence by compliance **in parallel**; due diligence escalates to the head of compliance after five days.

A rule table sets `refreshIntervalYears`: India 2 / 8 / 10 years (RBI KYC Master Direction), EU, Kenya and Nigeria 1 / 3 / 5 years (common practice, placeholders). The document list is regional (Aadhaar / passport / voter ID ...; national ID card; National ID / Maisha card; NIN / passport / CAC certificate). Outcomes: `Refreshed`, `Restricted`.

### Cooperative / MFI group loan

A group with a list of members and amounts. A rule table checks registration and group size using the member count (India JLG 4 to 10, SHG 10 to 20 after compulsory group training; Kenya chama 5 to 20, SACCO members group 3 to 20; Nigeria cooperative, esusu/ajo or trade association 5 to 20). Ineligible groups end as `Ineligible` without a human step. A field officer verifies the group, then a rule table on the **sum** of member amounts sends it to the branch manager or to a two-member credit committee (India INR 10 lakh, Kenya KES 2,000,000, Nigeria NGN 20 million). Outputs: member count, total amount, verifier.

### Vendor onboarding (shared services)

Procurement due diligence and bank-detail verification run in parallel; vendors that process personal data get a privacy review (DPDP Act 2023, GDPR Article 28, Kenya Data Protection Act 2019); a declared conflict of interest or high spend (INR 50 lakh, EUR 250,000, KES 10,000,000) needs compliance. The tax ID is format-checked (GSTIN, VAT number, KRA PIN). India flags MSME (Udyam) vendors, Kenya flags AGPO certificate holders. Unlike `vendor-onboarding-check`, it needs no AI connection.

### Building or business permit (public sector)

* **India, building plan approval** and **EU, building permit**: a rule table first decides which specialist reviews apply (so the result is known on every path), a clerk checks completeness, planning scrutinises the plans, then an *inclusive* split runs only the reviews the rules ask for: fire safety from 15 m in India (NBC high-rise) or 22 m in the EU variant (German state building codes), or for assembly use; heritage review inside a heritage zone or for a listed building. The permit authority decides within the statutory period (30 days India, three months EU, as a wall-clock time limit) or the case escalates to the commissioner / head of department.
* **Kenya, county single business permit**: rule tables decide the inspections and set the fee band from the number of employees (KES 5,000 / 15,000 / 30,000 placeholders for the county Finance Act), then a clerk checks completeness, public health inspection for food, drink and lodging, fire inspection for large premises or hazardous goods, then the decision within 14 days or the chief officer.

### Grant application (public sector)

A rule table rejects requests above the programme cap or with too little own contribution (EU also when the de minimis ceiling of EUR 300,000 over three years would be exceeded). Two reviewers on the technical panel must both approve, finance checks the budget (a list of budget lines; the total is an output), and the grants committee decides from a threshold. Registration IDs: NGO Darpan ID (India), register number (EU), Social Development registration (Kenya).

## Working calendars

Generated by `tools/template-packs/generate_packs.py`; revision `<REGION>-<bank|office>-2026-illustrative`, valid from 2026-01-01.

| Region | Zone | Bank calendar | Office calendar | Holidays |
|---|---|---|---|---|
| India | Asia/Kolkata | Mon to Sat 10:00 to 17:00; 2nd and 4th Saturdays listed as holidays | Mon to Fri 09:30 to 13:30 and 14:00 to 18:00 | Republic Day, Independence Day, Gandhi Jayanti, Good Friday, Christmas (2026, 2027); 2026 gazetted festival holidays (Holi, Id-ul-Fitr, Ram Navami, Mahavir Jayanti, Buddha Purnima, Id-ul-Zuha, Muharram, Milad-un-Nabi, Janmashtami, Dussehra, Diwali, Guru Nanak Jayanti) |
| EU | Europe/Berlin | Mon to Fri 09:00 to 17:00; TARGET2 closing days | Mon to Fri 08:00 to 12:00 and 13:00 to 16:00; German nationwide holidays | Easter dates computed for 2026 and 2027; state holidays not included |
| Kenya | Africa/Nairobi | Mon to Fri 08:30 to 16:30 | Mon to Fri 08:00 to 13:00 and 14:00 to 17:00 | New Year, Good Friday, Easter Monday, Labour, Madaraka, Mazingira, Mashujaa, Jamhuri, Christmas, Boxing Day; a Sunday holiday moves to Monday; Idd-ul-Fitr and Idd-ul-Adha 2026 |
| Nigeria | Africa/Lagos | Mon to Fri 08:00 to 16:00 | (no office templates) | New Year, Good Friday, Easter Monday, Workers', Democracy, Independence, Christmas, Boxing Day; a Sunday holiday moves to Monday; Eid-el-Fitr, Eid-el-Kabir and Maulud 2026 |

Lunar-calendar holidays are given for 2026 only, as the official notification fixes them each year. Add the next year's dates as a new calendar revision when they are gazetted.

## Using a pack

1. **New workflow → Start from a template.** The picker now filters by **Region** (All regions, No specific region, European Union, India, Kenya, Nigeria) and **Industry** (All industries, General, Banking and microfinance, Public sector, Shared services) and shows "N of M templates". Each pack template shows its region, industry and second language.
2. Create the task groups the template lists ("Needs approver groups") in Settings → Access and add members. The template card lists them; the [table below](#task-groups) has all of them.
3. Adjust thresholds, intervals and holidays to your rules, then publish. Nothing runs until it is published.

`GET /workspaces/{id}/templates` returns the new optional fields `pack`, `industry`, `region` and `languages` for pack templates (absent for general templates). The backend discovers `templates/pack-*.json` on the classpath; the Maven build packages them from `examples/` (never copied by hand). Template files describe themselves with metadata tags `pack:`, `region:`, `owner:`, `lang:` and `template:`.

### Task groups

| Group key | Used by |
|---|---|
| `branch-managers`, `credit-heads`, `credit-committee`, `compliance-officers` | Loan exception |
| `kyc-analysts`, `compliance-officers`, `head-of-compliance` | KYC refresh |
| `field-officers`, `branch-managers`, `credit-committee` | Group loan |
| `procurement-officers`, `accounts-payable`, `privacy-officers`, `compliance-officers` | Vendor onboarding |
| `permit-clerks`, `planning-officers`, `fire-officers`, `heritage-officers`, `health-inspectors`, `permit-authority`, `permit-heads` | Permits |
| `grant-reviewers`, `grants-finance`, `grants-committee` | Grants |

Committee and panel steps need at least two eligible members besides the requester.

## Changing the packs

Edit `tools/template-packs/generate_packs.py`, then run it. It rewrites `examples/pack-*.json` and the sample requests in `examples/fixtures/pack-scenarios.json`. `--check` exits 1 when a file is out of date. A missing translation or a label over the field limit stops the generator.

## Verification

| Check | Command | Result |
|---|---|---|
| Schema (all 20 files) | `python tools/contract-check/contract_check.py` | PASS, 108 checks; every `examples/pack-*.json` is a valid workflow |
| Manifests | `python tools/validate-manifests/validate_manifests.py` | PASS, 132 checks across 3 overlays (no manifest change) |
| Compile and dry-run | `RegionalTemplatePacksTest` (compiler module) | 108 tests pass: 20 validate and compile, 87 samples reach the expected outcome and path; whole compiler module 774/774 |
| Catalog | `TemplatePacksCatalogTest` (app module) | 2/2 pass (packs listed after the general templates with pack, industry, region, languages, owner) |
| Frontend | `npm test`, `npm run typecheck`, `npm run lint` | Typecheck and lint clean; `NewWorkflowDialog.test.tsx` 9/9 including the new filter test; full suite 1339 of 1340, the one failure is `RunDiagram.test.tsx` (timeout handle), which does not touch templates and fails on this loaded machine with the default 1 s wait (its sibling test needs 8 s for the same canvas) |
| Docker stack | `tools/template-packs/e2e_packs.py --all` | 402/402 checks: all 20 templates created, published and run; 80 samples completed with the expected outcome |

`RegionalTemplatePacksTest` validates every pack file in a workspace that has its groups (no blockers, publishable), compiles it to BPMN, checks that every calendar parses with a non-empty holiday list in the region's time zone, and runs every sample of `pack-scenarios.json` through the simulator: each must finish with the expected outcome, pass the expected steps and skip the others (including timeouts, escalation, ineligible and rejection paths).

`e2e_packs.py` works on any stack (`ORQIX_BASE_URL`). It creates the task groups (backed by the dev realm's managers and finance groups), checks the template list, creates a workflow from each template, publishes it, starts the sample requests, decides each human step with real users and checks the outcome. It also checks that every calendar due date falls on a working day inside opening hours. Samples that need a time limit to pass are left to the simulator test.

Stack used on 31 October 2026: private Compose project `orqix-packs`, image `orqix/orqix:packs` built from this branch, app http://localhost:38480 (management 38481), Keycloak 38180, PostgreSQL 35432, stub 38095, its own volume `orqix-packs_orqix-pgdata`. The redirect URI for port 38480 was added to that Keycloak's `orqix-web` client through its admin API; the repository realm file is unchanged. No other stack or volume was touched.

The run created workflows from every template (at least one per region: India, EU, Kenya, Nigeria), published each, and ran every sample without a time limit to completion through real OIDC login, the API, PostgreSQL and Flowable: approvals, rejections with reasons, two-member committees and panels, parallel and inclusive splits, ineligible groups and applications that end without a human step. Every calendar due date fell on a working day inside opening hours, for example a Nairobi committee task due Friday 13 November 17:00 and Indian office tasks due 18:00 local time. The seven samples that need a time limit to pass (escalation after due diligence, statutory decision periods) were left to the simulator.

## Gaps found

Noted, not built (no engine features were added for the packs):

| Gap | Effect on the packs | What would close it |
|---|---|---|
| **Currency is USD only** (`inputUi.currency` is `const: USD`; the money widget says "US dollars") | Amounts use a plain number field titled with the currency; no currency symbol, grouping (lakh/crore) or currency-aware formatting | A currency code on the form (ISO 4217) and locale-aware money widget |
| **No form translations** | Labels are "English / local" in one string, limited to 100 characters for field titles; users cannot pick a language | Per-language label maps for fields, options, sections and steps, chosen by user locale |
| **Option values are shown as stored** | Options stay English (they are data used by rules); no translated option labels | Option labels separate from option values |
| **Outcome labels are ASCII only** (`^[A-Z][A-Za-z ]{0,39}$`) | `Approved`, `Refreshed`, `Granted` ... stay English | Unicode outcome labels or translated display names |
| **One calendar per approval step, no calendar library** | The calendar is copied into every step; a holiday change means editing each step (the generator does it for the packs) | Shared, versioned calendars referenced by key, like shared decision tables |
| **No state, county or Länder holiday sets** | India banking holidays differ by state (NI Act holidays), German holidays by Land, and Nigerian states declare extra days; the packs carry national lists only | Calendar library with regional overlays |
| **Weekday pattern only** | "Second and fourth Saturday" is approximated by listing those Saturdays as holidays; Saturday half days (common in Kenyan SACCOs and Indian branches) cannot be expressed | Per-weekday opening hours |
| **Calendars only on due dates** | Time limits (`timeoutAfter`, escalation after N days) are wall-clock, so a statutory "30 working days" period cannot be modelled exactly | Working-time durations for time limits |
| **Several approvers cannot have a due date** | Committee and panel steps have no calendar SLA or escalation | Due dates on multi-approver steps |
| **Rules see list count and sum only** | Per-member rules (for example the RBI household income cap or a per-member maximum) cannot be checked on a group loan; budget total cannot be compared with the requested amount | Row-level conditions and field-to-field comparisons |
| **Rules cannot read an unanswered optional field** (the request stops with needs attention) | Checkboxes and answers that rules or branches read are required, which is why the KYC refresh needs two more inputs than the periodic review | A "not answered" value that rules can test, or a default per field |
| **Rule-table text values allow only letters, digits, spaces and `. , : / - _`** | Options used by rules avoid brackets and apostrophes ("Joint liability group - JLG") | Wider literal set, or option keys separate from labels |
| **No arithmetic across fields** | The EU de minimis check is a yes/no question instead of "aid received + requested <= EUR 300,000" | Computed values in rule tables |
| **Lunar holidays need yearly update** | Festival dates are given for 2026 only | Calendar library with yearly revisions |
| **No EU group-loan variant, no Nigeria public-sector or vendor variants** | Not needed for the first tier-3 buyers (EU cooperatives do not usually lend to groups); Nigeria variants are English only | Add variants with native-speaker labels (Hausa, Yoruba, Igbo) when a buyer asks |

Translations (Hindi, German, Swahili) were written for this release and have not been reviewed by native speakers.
