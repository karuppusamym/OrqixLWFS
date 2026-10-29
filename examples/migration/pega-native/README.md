# Synthetic native Pega rule archives (MIG11)

These archives are **synthetic test fixtures, not Pega exports and not customer exports**. Each page and the archive comment say so. They hold rule-instance pages in the clipboard XML shape Pega documents for rule and data instances (`<pagedata>`, one element per property, `REPEATINGTYPE` PageList/PropertyList values as numbered `<rowdata>`). They show what the native decoder accepts; they do not show that genuine exports of any Pega release match it. That is AC01/EXT01 customer qualification. See [S112](../../../docs/build/S112-pega-native-conversion.md).

| File | Purpose |
|---|---|
| `purchase-request.zip` | Converts end to end: case type with two stages, decision table, fork with a when rule over a flattened data-page property, three assignments with due times, a two-section harness, a literal `pyDefault`, plus shadowed versions (lower version, not available, withdrawn, closer class, ruleset outside the stack) and unused rules |
| `refusals.zip` | Every referenced construct blocks with its source path: activity, connector, business-day service level, a Blocked decision table, a circumstanced flow action, an unresolvable flow action, a declare expression and a binary entry |
| `expected/*.json` | Inventory and effective-rule resolution computed by the independent Python implementation (`tools/migration-equivalence/pega_native.py`). `PegaNativeTest` requires the backend to produce exactly the same result |

Generate or verify from the repository root:

```powershell
python examples/migration/pega-native/generate.py
python examples/migration/pega-native/generate.py --check
```

Upload an archive with **Import workflow > Native Pega rule archive**, or `POST /migrations` with `sourceFormat: pegaRules` and base64 content. For `purchase-request.zip`, map the work queues Managers, Finance and Procurement to workspace groups and choose the approving flow action of ManagerReview, FinanceReview and ConfirmOrder (`managerApprove`, `financeApprove`, `confirm`). Expected target behavior: amount below 5,000 goes to manager review, 5,000 and above to finance; after manager approval a non-preferred vendor at 1,000 or more also goes to finance; every path then needs order confirmation.
