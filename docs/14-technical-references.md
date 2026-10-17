# Technical references
Revision 0.5 · reviewed 7 October 2026 · replaces the research reading guide (revision 0.4)

## Reference policy

A reference stays in this package only if it is one of the following:

1. **A normative standard Orqix implements**, cited at its current edition. The edition year does not matter when no newer edition exists (for example BPMN 2.0.2 is still OMG's current formal BPMN).
2. **Maintained documentation or release notes of a pinned dependency**, rechecked at kickoff and at every milestone.
3. **Recent research or engineering guidance (2025–2026) tied to a concrete evaluation** that a named ticket will run. None of these is needed for R0.

Older research papers were removed in this revision. They had no build impact: the engineering rules they once motivated are stated directly in the specifications and are justified by our own tests, not by citation (see "Removed" below).

## A. Standards (current editions)

| Standard | Current edition (checked 2026-10-07) | Used for | Milestone |
|---|---|---|---|
| [BPMN](https://www.omg.org/spec/BPMN/2.0.2/About-BPMN) | 2.0.2, OMG formal | Compiler output, pattern semantics (S4) | R0 |
| [DMN](https://www.omg.org/spec/DMN) | 1.6, OMG formal (September 2026); 1.7 in beta | Decision tables. The deployed namespace is the newest one Flowable 8 parses (expected 1.3); engine support decides which features are usable | R0 |
| [CMMN](https://www.omg.org/spec/CMMN/1.1/About-CMMN) | 1.1, OMG formal | Adaptive cases (E05) | R3 |
| [JSON Schema](https://json-schema.org/draft/2020-12) | 2020-12 in use; a v1/2026 release is published but validator support is unconfirmed | Workflow, connection and form schemas; OpenAPI 3.1/3.2 dialect | R0 |
| [OpenAPI](https://spec.openapis.org/oas/v3.1.1.html) | 3.1.1 used; [3.2.0](https://spec.openapis.org/oas/v3.2.0.html) published September 2025 and monitored for tooling support | `contracts/openapi.yaml` | R0 |
| [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) | 2023, obsoletes RFC 7807 | Error model (S3 §4) | R0 |
| [Idempotency-Key header](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/) | IETF HTTPAPI Internet-Draft — **not an RFC** | Header name and replay semantics (S3 §5) | R0 |
| [RFC 8785 JSON Canonicalization](https://www.rfc-editor.org/rfc/rfc8785) | Current | `sourceHash`, `semanticHash`, request hashes | R0 |
| [RFC 6901 JSON Pointer](https://www.rfc-editor.org/rfc/rfc6901) | Current | Field references, result mapping, error pointers | R0 |
| [RFC 9700 OAuth 2.0 Security BCP](https://www.rfc-editor.org/rfc/rfc9700) | BCP 240, 2025 | Login flow rules (S6 §1) | R0 |
| [OpenID Connect Core 1.0](https://openid.net/specs/openid-connect-core-1_0.html) | Current, with errata | PingFederate/Keycloak login | R0 |
| [WCAG 2.2](https://www.w3.org/TR/WCAG22/) | W3C Recommendation, current | Accessibility acceptance (S7 §7) | R0 |
| [OWASP ASVS](https://owasp.org/www-project-application-security-verification-standard/) | 5.0.0 (May 2025) | Security verification checklist at the GA gate (S6 §9) | GA |
| [Model Context Protocol](https://blog.modelcontextprotocol.io/posts/2026-07-28/) and its [security best practices](https://modelcontextprotocol.io/docs/2026-07-28/tutorials/security/security_best_practices) | Specification 2026-07-28 | MCP registry and tool execution (E11) | R2 |

## B. Dependency documentation (recheck at kickoff and each milestone)

| Source | State at review | Used for |
|---|---|---|
| [Flowable 8.0.0 release](https://github.com/flowable/flowable-engine/releases/tag/flowable-8.0.0) | Released 27 Feb 2026; Spring Boot 4 / Framework 7; Jackson 3 default with Jackson 2 compatibility layer; no later 8.x release at review | Engine pin, spike S-1…S-6 |
| [Spring Boot release notes](https://spring.io/blog/2026/08/20/spring-boot-4-0-8-available-now/) and [system requirements](https://docs.spring.io/spring-boot/system-requirements.html) | 4.0.8 and 4.1.1 released 20 Aug 2026 | Boot pin (4.0.x until 4.1 is proven with Flowable) |
| [Spring Modulith releases](https://spring.io/blog/2026/08/26/spring-modulith-2-2-m1-2-1-1-2-0-8-and-1-4-13-released/) | 2.0.8 / 2.1.1 (Aug 2026) | Module verification |
| [Spring Security reference](https://docs.spring.io/spring-security/reference/) | Current | OAuth2 login, resource server, CSRF for single-page apps |
| [React Flow accessibility](https://reactflow.dev/learn/advanced-use/accessibility) | Current | Canvas keyboard and labelling |
| [Vite 8 announcement](https://vite.dev/blog/announcing-vite8) | Stable since 12 Mar 2026 | Frontend build |
| [PostgreSQL versioning policy](https://www.postgresql.org/support/versioning/) | 18.6 current (13 Aug 2026); 14 reaches end of life 12 Nov 2026 | Database major selection |
| [Node.js release schedule](https://github.com/nodejs/Release) | 24 Active LTS until 20 Oct 2026; 26 LTS from 28 Oct 2026 | Build toolchain |
| [Keycloak documentation](https://www.keycloak.org/documentation) | 26.7 (Jul 2026); no LTS line | Local development identity only |
| [MCP Java SDK releases](https://github.com/modelcontextprotocol/java-sdk/releases) | 2.0.1 (Aug 2026); 2.0 notes track protocol 2025-11-25, not 2026-07-28 | E11 SDK/protocol selection (R2) |
| [Camunda 7 end-of-life notice](https://camunda.com/blog/2025/02/camunda-7-enterprise-end-of-life-extension/) | Community 7 security patches ended with 7.24 (October 2025) | Migration context (doc 07, E16) |

## C. Recent research and guidance — later milestones only

| Reference | Concrete use | Owner / milestone |
|---|---|---|
| [Assessing the Business Process Modeling Competences of LLMs (BEF4LLM), 2026](https://arxiv.org/abs/2601.21787) | Structure E09 evaluation by validity, syntactic, pragmatic and semantic quality; keep a versioned fixture set and per-dimension results for each model/prompt/compiler combination | AI/QA · R2 |
| [Demystifying evals for AI agents, Anthropic engineering, January 2026](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents) | Judge agent/tool tasks by persisted state and observable external results; include denial, timeout, cancellation, duplicate-completion and invalid-output cases. Vendor guidance, not a protocol or a reason to choose that vendor | AI/integration · R2 |
| [Vanilla-Converter, 2025](https://arxiv.org/abs/2508.04352) | Catalog of Camunda 7-specific constructs and a model for reporting automatic transformations vs. remaining manual work in the Camunda adapter. Targets Camunda 7 → 8, not Flowable; no code or equivalence claim is adopted | Migration · R1 |

## Removed in this revision

| Removed reference | Year | Where the engineering rule now lives |
|---|---|---|
| Workflow Patterns (van der Aalst et al.) | 2003 | Pattern acceptance matrix, defined by our tests against BPMN 2.0.2 and the pinned engine (S4 §12) |
| Seven Process Modeling Guidelines | 2010 | Label and readability rules (doc 02, S7), validated in our own business-user trial |
| Sagas (Garcia-Molina, Salem) | 1987 | Effect classification, unknown outcomes and operator reconciliation (S5 §6–7) |
| Case Handling (van der Aalst et al.) | 2005 | Workflow-versus-case classification questions (doc 01); CMMN 1.1 for R3 |
| ReAct (Yao et al.) | 2022 | Bounded agent-task rules (doc 10 E10) with the current MCP security guidance |
| Process-model representation comparison for LLMs | 2025 | Canonical JSON decision stands; revisit only through our own E09 experiment |

## Refresh rule

The technical lead rechecks sections A and B at kickoff and at each milestone, records changes in ADR 0001, and updates this page. A research reference is added only together with the ticket that uses it. A newer publication date alone never justifies an architecture change.
