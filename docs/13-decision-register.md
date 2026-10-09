# Decisions, prerequisites and unresolved inputs
Revision 0.3 · D01 now needs an answer before kickoff (see the build specification capacity finding).
These items become blocking only when their dependent build/release work starts. No credentials should be pasted into this document.

## Decision register

| ID | Decision/input | Proposed default | Who resolves it | Needed by | If unresolved |
|---|---|---|---|---|---|
| D01 | R0 team and schedule | 3 experienced engineers for 5 days ([S8 §1](build/S8-r0-tickets.md#1-capacity-finding-read-first) option A; ticket-level P0 estimate ≈ 100 h exceeds the 72 h three-day capacity); business owner available | Sponsor/technical lead | **Before kickoff** | Keep 3 days only as a declared reduced-scope R0 (option C); no full-platform date commitment |
| D02 | Priority business workflow and policy | USD purchase request; finance review at 5,000; no self-approval | Business process owner | R0 form/rule freeze | Treat example as sample, not approved business logic |
| D03 | Meaning/version of LWFS | Unknown source; pluggable adapter | LWFS owner | E16 source discovery | Native LWFS conversion stays Needs input |
| D04 | PEGA/Camunda source versions and sanitized exports | One simple and one medium definition plus expected results | Source-system owners | Migration adapter tickets | Only compatibility fixtures; no native migration claim |
| D05 | PingFederate client/group claims and role mapping | OIDC; issuer+subject; explicitly mapped memberships | Identity/security owner | Enterprise login validation | Use real local OIDC only for development; PingFederate gate remains open |
| D06 | OpenShift namespace, registry, HA DB and network access | Target manifests; existing enterprise services | Platform/DB owner | Deployment validation | Local integration is not target-topology proof |
| D07 | Procurement API/operation and safe replay contract | Approved test operation with deduplication or reconciliation | Integration owner | R0 real API demo | Cannot claim end-to-end external success |
| D08 | AI provider, data policy and budget | AI disabled until explicitly configured | AI/security/workspace owners | E09 | Templates/manual designer work |
| D09 | Real MCP server/approved tools and agent endpoints | Remote allowlisted operations; locked schemas | Integration/security owner | E10/E11 | Registry design only, no functional integration claim |
| D10 | Teams/mail application permissions | Notification + secure inbox link first | Messaging/identity owner | E12 | In-app notification stays baseline |
| D11 | Pilot workload and availability target | 50 concurrent users, 10,000 stored runs; targets from doc 01 | Sponsor/platform owner | Load-test design/GA gate | No verified capacity or availability claim |
| D12 | Retention, sensitive fields, disaster RPO/RTO | Proposed policies in doc 11 | Data/security/DB owners | Production use | Production readiness remains incomplete |
| D13 | Separation of publisher/editor and approval policies | Deny requester self-approval; review production publishing policy | Business/security owner | R1 / production | Conservative restricted roles and explicit exception tracking |
| D14 | Need for attachments/business calendars | Not in R0; R1 only when required | Business owner | E06/E04 | Use basic forms and elapsed-time SLA |
| D15 | Git hosting, promotion permissions, secret manager | Interfaces only; bind to approved enterprise services | Platform owner | E15 | File export works; no automatic Git promotion |
| D16 | Exact dependency/SDK/protocol versions | Candidate pins in [S1 §1](build/S1-engineering-baseline.md#1-version-pins) (checked 7 Oct 2026); lock after spike B01.2 in ADR 0001 | Technical lead | First implementation session | Do not state versions are tested |
| D17 | Production support/on-call and incident contacts | Named owners and verified alert route | Sponsor/platform owner | GA gate | No unattended production handoff |
| D18 | Advanced PEGA case semantics needed | Discover representative adaptive case | Business/source owner | E05 | Support bounded workflows only |

## Already chosen for the plan

Java core; React UI; Flowable OSS as only core engine; PostgreSQL durability; modular monolith; OpenShift target; no mandatory Redis/Kafka/Python/Temporal/pgvector; canonical subset with loss-aware import; immutable releases; optional AI; business-first navigation; no silent conversion loss.

These decisions are proposed architecture choices drawn from the supplied prompt. Revisiting them is possible through a recorded design change, not an accidental dependency substitution.

## Minimum kickoff handoff

Technical lead needs D01–D02, available development identity/DB, a usable D07 integration contract and a repository/build environment. Enterprise identity/OCP production credentials can arrive later only if the corresponding validation is clearly outstanding. Native migration work requires D03/D04. AI/MCP/Teams work requires their actual endpoint and permissions decisions.

The plan can be reviewed now without supplying any secret. Access is configured through approved environment secret mechanisms during implementation.

## Gate decision record

At a checkpoint, record: gate ID; tested release/image; environment; evidence link; responsible reviewer; Pass/Fail/Not run; unresolved defects; impact on declared scope; next owner/action. “Conditional pass” must list the exact excluded capabilities and must not be presented as full production approval.
