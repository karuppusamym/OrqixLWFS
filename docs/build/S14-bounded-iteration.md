# S14 — Bounded iteration and rework

Status: verified slice; `BoundedIterationTest` and `WorkflowPatternsTest` pass in the combined clean build. Source contract is `contracts/workflow-v0.schema.json`.

## Execution contract

The outer workflow graph remains acyclic. A `startWorkflow` step may contain exactly one iteration policy. Every occurrence creates a separate child run pinned to the parent's published child release. Tasks, decisions, events and external effects retain the occurrence's run identity. Parent cancellation stops active descendants and creates no further occurrences.

```json
"repeat": { "maxIterations": 3, "untilOutcome": "Accepted" }
```

Repeat reuses the initial mapped inputs. It stops on the named published child outcome, cancellation, or the configured bound (1–25). Human review/rework therefore creates new tasks while retaining earlier rejection reasons. Updating request values inside the repeat construct is a separate capability.

```json
"forEach": { "itemsRef": "/input/items", "itemInput": "title", "maxItems": 5 }
```

For each runs one child at a time by default. Set `mode` to `parallel` to start all bounded children together and wait for all of them. Common values come from `inputMapping`; `itemInput` receives that occurrence's item and must not also appear in `inputMapping`. The referenced parent input field must be a required typed scalar array. Its `minItems` is at least 1 and `maxItems` at most 25; the iteration policy must cover that maximum. String, integer, number and boolean items are supported with their scalar validation constraints. Nested objects, nested arrays and empty collections are not supported in this slice.

```json
"items": {
  "type": "array", "title": "Purchase titles",
  "items": { "type": "string", "title": "Purchase title", "minLength": 1 },
  "minItems": 1, "maxItems": 5
}
```

## Results and routing

Every child step exposes its final `outcome`, `outcomeKind`, `reference`, mapped result fields, and:

| Field | Type | Meaning |
| --- | --- | --- |
| `iterations` | integer | Number of completed child occurrences |
| `limitReached` | boolean | Repeat bound reached without matching the desired outcome; false for collection iteration |
| `allSucceeded` | boolean | Every occurrence ended with outcome kind `success` |

Use a branch condition on `limitReached` to route repeat exhaustion. Use `allSucceeded` for a collection-wide success decision; the last child outcome alone cannot describe earlier rejected items. Use `collectionMapping` to collect scalar values into arrays in input order, regardless of completion order. For example `{"outcomeItems":"outcome","referenceItems":"reference"}`. Child `outcome`, `outcomeKind`, and `reference` are always collectable; a custom child output must be a scalar guaranteed on every completion path. Custom collected results can be absent when an operator cancels a child before producing its output, so those output mappings must be optional. Individual results also remain on their child runs.

## Runnable complexity examples

Publish `examples/simple-review.json` first. The medium and complex examples reference its `review-request` key; change that reference if importing it under another key. All use the `managers` group.

| Example | Execution |
| --- | --- |
| `simple-review.json` | Submit → one approval → accepted/rejected |
| `medium-bounded-rework.json` | Repeated child review, up to three occurrences, explicit exhaustion route |
| `complex-batch-review.json` | Parallel approvals → durable timer → parallel item child reviews with ordered result arrays → aggregate success/rejection |

`WorkflowPatternsTest.simpleMediumAndComplexExamplesPublishAndExecuteThroughThePublicApi` publishes and executes these source files against PostgreSQL and Flowable. The complex scenario rejects the first item and accepts the last to verify that aggregate rejection is preserved.

## Remaining work

Arbitrary graph cycles; mutable correction forms between iterations; nested/object collections; dynamic repeat conditions beyond a child outcome; business-calendar deadlines; native imported BPMN loop characteristics. These are not implied by bounded child repetition.
