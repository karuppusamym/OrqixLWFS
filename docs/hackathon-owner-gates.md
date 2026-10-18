# Hackathon decision and compliance register

Reviewed 18 October 2026 against the [requirements baseline](23-hackathon-requirements-baseline.md). All seven gates remain **open**. Roles below identify who must decide; they are not named assignments or recorded approvals. Engineering tests cannot substitute for these decisions.

| Gate | Decision owner role | Evidence needed to close | Current evidence and remaining work |
|---|---|---|---|
| HC01 | Platform / Orchesstra owner | Authoritative checklist, approved stack versions and reviewer decision for each item | Repository stack and architecture checks are available. Obtain the checklist and assess each requirement. |
| HC02 | Organizer, dependency reviewer and security owner | Written licensing interpretation, reviewed SBOM/notices, security remediation or explicit dispositions | No new paid subscription is required. Dependency inventories and 36 open base-image advisory findings are [recorded](hackathon-evidence/README.md). License compatibility and risk acceptance are unapproved. |
| HC03 | Host application and identity owners | Approved claims/group/permission mapping, component deployment contract and identity lifecycle tests | Local seeded identities, scoped authorization, pinned fixture IDs and independent component tests pass. Test real enterprise provisioning, disabling and group changes with the host. |
| HC04 | Source-system and business owners | Representative genuine blueprint, approved business policy, expected golden outcomes and signed semantic comparison | Synthetic import/transcription and deterministic route tests exist. Obtain genuine source and owner expectations. Original reference screenshots were not supplied, so visual fidelity is unverified. |
| HC05 | Business and messaging owners | Approved calendar revisions, SLA exceptions, recipient bindings, transport configuration and real delivery evidence | Synthetic business time, durable breach detection and SMTP capture pass. Approve actual bindings and verify enterprise mailbox delivery. |
| HC06 | Data, platform and support owners | Retention/legal hold policy, capacity/availability targets, backup/restore proof and named support owner | Local 50-session/10,000-case load and application restart evidence pass. Production restore, availability and policy approval remain outstanding. |
| HC07 | Sponsor and procurement | Contract terms, workflow inventory, avoidable license costs and measured savings baseline | Benefit model is indicative. Confirm contractual costs and measure before/after outcomes. |

For each approval, record the named reviewer, UTC decision date, candidate source/image, evidence references, decision and any expiry or follow-up. An advisory marked `not fixed` by a scanner remains open until the security owner approves an explicit disposition or a later scan proves remediation. No waiver is recorded here.

The [acceptance record](hackathon-acceptance.md) distinguishes completed local qualification from these gates. No new OpenShift deployment is required by the hackathon baseline.
