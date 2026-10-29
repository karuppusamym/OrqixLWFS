# S105 - Start one workflow per row of a list of rows (WM07, 28 October 2026)

"Start another workflow" could already run once per item of a list of **single values** (S14: `forEach.itemInput` receives the item). A list of **rows** (several columns per item, S35) was refused. This slice closes the "object-row workflow iteration" part of WM07. No migration, no BPMN change: the for-each settings live in the release definition, as before.

## Model

```json
"forEach": { "itemsRef": "/input/lines", "itemMapping": { "title": "item", "amount": "price" }, "maxItems": 5, "mode": "parallel" }
```

- `itemMapping`: child request field → column of the current row, 1 to 10 entries. Exactly one of `itemInput` (list of single values) and `itemMapping` (list of rows) is set; the schema refuses both or neither.
- Every other S14 rule is unchanged: a required request list of 1 to 25 items, `maxItems` covering the list's maximum, sequential or parallel, `inputMapping` for values shared by every child, `collectionMapping` for ordered results, `iterations` and `allSucceeded`.
- At run time each child gets the shared `inputMapping` values plus the mapped columns of its own row. A column that is empty in a row leaves the child field out; the child's own form validation still applies to the result, exactly as for `itemInput`.

## Compiler

| Code | When |
|---|---|
| `WF_FOREACH_COLUMN` | a mapped column does not exist in the list's rows |
| `WF_FOREACH_ITEM_TYPE` | the column's type does not fit the child field (same rule as `inputMapping`); an optional column fills a required child field; `itemMapping` on a list of single values; `itemInput` on a list of rows (the message points to the item mapping) |
| `WF_FOREACH_INPUT` | the child field does not exist, or is also filled by `inputMapping` |

Child fields filled from the row count as provided for `WF_CHILD_MAPPING_MISSING_REQUIRED`. The simulator gives each simulated child the mapped columns of its row.

## Studio and offline mode

In **Collection iteration**, choosing a list of rows replaces *Child field receiving each item* with **Fill from each row**: one choice of column per field of the started workflow ("Not from the row" by default). A field filled from the row disappears from **Request values** and its request value is removed. Switching between a list of single values and a list of rows starts the item settings again. The offline validator (`WF_FOREACH_ITEM_TYPE`, `WF_FOREACH_COLUMN`) and simulator follow the same rules; the old offline refusal of row lists is gone.

## Tests

- Compiler `BoundedIterationTest` (3 new, 7 in all): a valid row mapping publishes; unknown column, wrong type, optional column into a required field, a field also in `inputMapping`, an unknown child field and a missing required field are refused; rows with `itemInput`, single values with `itemMapping` and both together are refused; the simulator runs two children, each with a valid input from its row.
- Application `WorkflowPatternsTest` (1 new, 15 in all, PostgreSQL and Flowable): sequential and parallel runs over two rows start two children with `title`/`amount` from their own row, in list order, and an unmapped column is not copied.
- Frontend `StudioPage.test.tsx` (1 new): the row controls appear instead of the single item field, offer the row's columns, save `itemMapping` and drop the field from the request values.

Full `mvnw clean verify` (28 October 2026, JDK 21, Testcontainers PostgreSQL, Flowable): compiler 648 pass; application 1,024 run, 4 skipped (opt-in live), none failing. Frontend 161 files, 1,192 tests, lint of changed files and `api:check` pass; `contract_check.py` and `consistency_gates.py` pass.

## Not covered

- Nested lists (a column that is itself a list) and lists of rows inside rows.
- Mutable correction forms between iterations (the rest of WM07).
- The Camunda importer still produces only `itemInput` for multi-instance call activities.
- No Edge/axe journey for the new Studio controls.
