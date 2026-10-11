# S24 - Run diagram and Insights

Updated 11 October 2026. Implementation is complete; coordinated verification is recorded in the product build tracker.

## Run diagram

`GET /workspaces/{workspaceId}/runs/{runId}/path` uses the same permission and relationship checks as run history. It reads the run's pinned release, not the current draft or newest release. The response contains labels, kinds, layout and routes, plus visits and current steps. It does not expose definition conditions, request values, connector configuration or credentials.

The read-only Diagram tab highlights current steps, completed visits, rejection and timeout routes, cancellation and incidents. Repeated visits carry occurrence numbers. Choice and parallel steps without engine events are inferred from neighboring visits; this is a derived history view, not a complete engine execution trace. A keyboard-accessible list and timeline remain available.

History is bounded at 5,000 events. An extra event detects truncation: `historyTruncated=true`, historical visits/routes are withheld, and the UI displays current steps with a warning to use the paginated timeline. A message wait that ends with `timedOut` is shown with that outcome and its timeout route.

## Insights

Workspace-wide Insights require workspace visibility and `run.read`. Summary, throughput and step endpoints aggregate the PostgreSQL projections; they expose references and workflow/step labels, never request titles or payloads. Request links remain subject to run authorization. UTC dates form an inclusive window of at most 365 days, with a 30-day default. Invalid dates, reversed/oversized ranges and foreign workflow identifiers are rejected. Queries use a ten-second statement timeout; timeout returns a retryable 503.

The page offers 7/30/90-day filters, a paginated workflow selector, daily throughput with a table alternative, cycle-time statistics, average/p90 approval and wait durations, current queues, overdue items and incidents. The summary timeout list concerns approvals; the bottleneck table also counts message-wait timeouts. Open/overdue queues are current state, independent of the selected historical window. Step rows, timeout rows and overdue previews are bounded. CSV cells use the migration export's formula protection.

These are operational projections, not process mining, Pega reporting parity or Camunda Optimize parity. Adaptive stages are separate from the graph and their timeline notices; this batch does not introduce arbitrary BI queries.

## Validation

`RunPathBuilderTest`, `RunPathApiTest`, `InsightsServiceTest` and `InsightsApiTest` cover path derivation, permission/privacy, long-history safeguards, ranges, aggregates and timeout/overdue accounting. Frontend diagram/Insights tests cover readable/accessibility alternatives and truncated-history behavior. `t-ui-14-diagram-insights.spec.ts` checks the mock browser flows. Full-tree evidence and any remaining external acceptance are in [the tracker](../19-product-build-tracker.md).
