# UI and product competitiveness review

30 October 2026. Source review, local browser inspection, and a first implemented improvement slice.

## Recommendation

Position Orqix as a focused workflow workspace for teams that need understandable approvals, controlled change and evidence-backed migration. Make the common path exceptionally clear: find work, understand why it is waiting, act, and see the outcome. Competing through an ever-longer feature list would obscure the functionality already present.

Keep the existing design tokens, accessible native controls, permission checks and versioned runtime. Improve information hierarchy and progressive disclosure before changing the brand or replacing the component library. The red/gold theme is consistent; it is not the main usability problem. Do not present a visual refresh as proof of enterprise readiness.

## Evidence and limits

- Inspected the React shell, Home, tasks, workflow catalog, Studio, migration intake and Insights implementations, alongside the feature and competitive delivery records.
- Opened Home, tasks, workflow catalog, migration intake, reusable views, runs, Insights, case types and workflow Studio in local mock mode with manager, designer and operator personas. Inspected screenshots of Home, tasks, migration, Insights and Studio; exercised the changed task journey at 1440px and 390px.
- Browser evidence uses seeded data, not a production deployment. No customer usability study, backend requalification, production load test or customer-source migration was performed in this review.
- Current source and the [delivery register](22-competitive-gap-delivery.md) take precedence over the historical [competitive analysis](20-competitive-analysis.md). That older analysis lists missing capabilities, such as DMN and multi-file import, that now have implementations. Do not restart those projects based on the old gap list.
- Vendor comparisons below describe documented capabilities, not independently benchmarked quality, price or implementation effort. Vendor documentation was consulted on 30 October 2026; rolling documentation can describe a newer release than a customer's installed version.

## Screen-by-screen findings

| Screen | Finding and consequence | Recommended design | Delivery in this change |
|---|---|---|---|
| Navigation | Work, design, operations and governance were one flat list. Users must repeatedly scan unrelated destinations. Long menus need independent scrolling. | Group by user intent, prioritize My tasks, hide unavailable destinations, keep one coherent workspace identity. | Implemented My workspace / Design / Operate / Manage groups and scrolling. Existing permission gates retained. |
| Home | Request catalogs and onboarding appeared ahead of pending decisions. Assigned tasks were concatenated before claimable tasks and immediately truncated. | Put actionable work before the catalog; prioritize the preview across both queues; place learning/setup below daily work. | Implemented; preview deduplicates loaded tasks, orders overdue first, then priority, due date and age, and shows at most five. |
| My tasks | Queue, priority and age exist, but finding a named request/person and isolating due work were missing. A filtered empty list looked like no assigned work. | Search, due-date triage, clear filters, explicit result scope, bookmarkable state, useful no-match recovery. | Implemented for loaded tasks; retains server priority/sort. Search supports title/reference/person/step/queue text. |
| Task detail | Existing split view and mobile detail route are useful foundations. | Keep decision controls visible, summarize the reason for assignment and relevant policy, and progressively reveal history and technical evidence. Verify focus and required fields against every authored approval view. | Existing behavior retained; changed filter state survives opening a task and mobile return navigation. |
| Workflow catalog | Folder management, archived items, import and creation compete in the header. Empty pinned-workflow instructions take space before results. | Keep New workflow primary, import secondary; group library administration into a menu. Make empty pins a compact hint and emphasize name, owner, live version and unpublished changes. | Recommended; not redesigned here. |
| Studio | Canvas, six editing tabs, notation toolbar, palette, inspector and expanded AI dock compete for space. In the observed 1440px screen, much of the workflow is outside the initial canvas view. | Default to a fitted diagram and compact AI dock, reveal advanced metadata on demand, retain Steps as an accessible alternative. Use a focused step inspector with explicit owner, inputs, outputs, failure behavior and test evidence. | Recommended; visual inspection and source review only. Existing auto-layout, undo, validation and tests must be preserved. |
| Migration intake | A large technical paragraph precedes upload and mixes supported conversion, manual review and inventory-only paths. | Show source selection, then source-specific guidance. Present a short Analyze → Resolve → Test → Create draft journey, with full limits expandable. Keep unsupported behavior visible in the assessment. | Recommended; no claims of wider conversion support. |
| Case types | Creating a case immediately asks for a technical immutable key and explains CMMN. | Lead with a business name and a suggested editable key before first save; offer starter lifecycle templates and a readable stage summary. | Recommended; do not change existing identifiers or standard conformance claims. |
| Views / Apps | The library combines request, approval, case, worklist and application-page assets. These concepts require significant prior knowledge. | Add kind filters, previews and “used by” impact summaries; separate creating a reusable screen from assembling an app while preserving exact-version pins. | Recommended; existing reusable views and app composition are not missing features. |
| Runs | Existing attention and overdue filters, references and recovery links are useful. | Make an exception-focused default for operators; preserve a saved all-runs view. Show cause, affected step, owner and safe next action together. | Recommended; retain current recovery authorization and effect-safety checks. |
| Insights | Throughput, cycle time, step waits, attention, old requests and overdue work already exist. Actionable sections are far below the first chart. | Put actionable exceptions and bottlenecks near the top; link consistently to the corresponding filtered work. Add period comparison and business-defined goals only with trustworthy server aggregates. | Recommended; no invented KPI values or claim of process-mining parity. |

These observations support targeted redesign, not a conclusion that every existing workflow is unusable. Validate task completion and comprehension with representative users before a larger redesign.

## Functional improvements delivered

1. Task search matches words across the authorized summary's title, reference, step, requester and queue. It does not fetch hidden request data.
2. Due filters distinguish overdue, due within the next 24 hours, and no due date. Completed work is not labeled overdue. “Next 24 hours” is a rolling duration, not a local-calendar “today” promise.
3. Search and filters live in the URL and survive reload. Opening a task and returning on mobile preserves the list state. Completed-tab transitions drop inapplicable due/priority/queue filters.
4. No-match states offer clearing filters and retain Load more. A failed next-page fetch keeps the loaded results and exposes retry instead of discarding the list.
5. Home promotes waiting work above the request catalog and moves setup guidance below current work. The preview ranks the loaded union of assigned and claimable work rather than giving assigned work unconditional precedence.
6. Navigation groups keep the existing authorization model; hidden modules and existing route/server enforcement are not bypassed.

Scope matters: queue/search/due filtering and Home ranking operate on fetched pages. They cannot establish a workspace-wide overdue total or guarantee the globally most urgent task. Task counters remain the existing API values. This is an immediately useful frontend improvement, not completion of enterprise work management.

## Competitive comparison

| Benchmark | Documented strength | Orqix evidence and competitive action |
|---|---|---|
| Pega | Case management and enterprise low-code application development; Blueprint generates a starting design with case types, lifecycles and personas. | Orqix already has case authoring, reusable views, application pages and reviewed AI assistance. Simplify the business-name-to-working-case journey and prove complete business scenarios. Full case/application acceptance remains explicitly open in the delivery register. |
| Camunda | Tasklist supports assignment and form completion; Operate supports process-instance migration; Optimize provides dashboards and report alerts. | Orqix already has tasks, diagrams, recovery, release controls and analytics. Prioritize searchable work at scale, understandable incident recovery and a measured model-test-publish journey. Verify exact migration/runtime semantics against representative source models. |
| Temporal | Durable code-defined workflow execution, with execution listing and filtering through Visibility. | Use durability and operational diagnosis as engineering benchmarks. Orqix's potential advantage for the target buyer is a business-facing workspace with forms, approvals and governed releases. That advantage must be demonstrated with real workflows, not inferred from feature count. |

Sources: [Pega Platform](https://docs.pega.com/bundle/platform-242), [Pega case management](https://www.pega.com/products/platform/case-management), [Pega Blueprint FAQ](https://community.pega.com/faqs/blueprint); [Camunda Tasklist](https://docs.camunda.io/docs/components/tasklist/userguide/using-tasklist/), [Camunda process-instance migration](https://docs.camunda.io/docs/components/concepts/process-instance-migration/), [Camunda Optimize](https://docs.camunda.io/docs/components/optimize/improve-processes-with-optimize/); [Temporal workflow execution](https://docs.temporal.io/workflow-execution), [Temporal Visibility](https://docs.temporal.io/visibility).

### Where Orqix can differentiate

- **Clear everyday work:** a new approver finds the right item, understands the request and acts without learning workflow-engine vocabulary. The current change begins this direction.
- **Explainable migration:** connect each source finding to the mapped target element, approved behavior change and passing scenario. Existing migration and equivalence tooling should be packaged into one reviewable journey. No universal or lossless conversion claim.
- **Reviewable change:** combine the existing draft diff, dependency pins, test results and promotion approval into a concise release evidence view. Show exactly which version runs new work and which version existing work retains.
- **Focused domain outcomes:** choose one repeatable approval/case scenario with a buyer and measurable success criteria. Measure setup and maintenance effort before claiming lower total cost than Pega or Camunda.

## Sequenced next work

These are proposed increments, not completed features or delivery-date commitments. Reuse existing capabilities and link work to the current delivery register instead of opening duplicate parity epics.

| Priority | Increment | Acceptance evidence |
|---|---|---|
| P0 | Server-side task search, queue and due predicates with stable pagination | Authorization applied before filtering/counting; find an eligible task beyond page one; deny an ineligible task; counts match predicates; deterministic tie-breaks and timezone-boundary tests; contract, mock and real-stack coverage. |
| P0 | Role-based default journeys | Requester starts/tracks; approver finds/decides; designer edits/tests; operator diagnoses/recovers. Measure baseline and changed completion time, errors and assistance needed with representative users. |
| P0 | Release and readiness evidence | Run the changed journeys against the real backend, including claim races and revoked access. Retain the existing external deployment, restore, identity and integration gates; UI polish does not close them. |
| P1 | Studio space and progressive disclosure | At common laptop sizes, key nodes and actions remain reachable with keyboard and pointer; opening/closing AI does not lose edits; initial fit does not override a returning user's saved viewport; Steps remains fully operable. |
| P1 | Migration guided review and equivalence pack | Real sanitized Pega/Camunda exports; a disposition for every relevant source element; owners approve behavior changes; golden scenarios include failure and timeout paths; report links findings, mappings and outcomes. |
| P1 | Integrated exception workbench | Drill from an aggregate to permitted affected runs with equivalent predicates; show cause, effect state and allowed recovery; stale versions and duplicate actions are rejected safely. |
| P1 | Shared saved work views | Named filters scoped to user/workspace, permission-checked sharing, server-side counts and versioned definitions. Browser bookmarks added here are not shared saved views. |
| P2 | Business analytics and operational alerts | Agreed event definitions, matched date ranges, retention controls, p50/p90 and SLA denominators, alert deduplication, and measured query cost. Validate whether existing analytics can be extended before adding a separate system. |
| P2 | Case/application breadth and connector packaging | Finish the declared WM04/UI02 acceptance scope and real-stack app journeys; demonstrate reusable domain scenarios and supported integration failure handling. Avoid treating bounded implementations as standards-wide parity. |

## Validation record

- Final focused frontend suite: 213 tests passed across Shell, Home, TasksPage, taskFilters and the two design-token/contrast suites, including permissions, bookmarked search, clear/reset, task navigation, due boundaries and ranking.
- Typecheck, ESLint, the final production build and `git diff --check` passed.
- Headless Edge mock-mode journey passed desktop search, URL reload, due filtering, clear filters, mobile detail/return navigation and a 390px horizontal-overflow check.
- Visual inspection caught a narrow mobile search field; changed the filters to stack at small widths and repeated the browser check.
- Screenshots and local inspection scripts are under ignored `build/ui-*`; they are local review artifacts, not production evidence. No backend or deployment changes were made.
