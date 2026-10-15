# S35: Conditional request questions and bounded row collections

Updated 15 October 2026. This document describes the supported backend contract and its frontend integration boundary. The combined verification record belongs in the product build tracker; source code and individual test results do not establish production acceptance.

## Conditional request questions

The immutable release's `inputUi` can declare `visibleWhen` and `requiredWhen` maps, keyed by request-field name. Conditions use the existing typed comparisons (`eq`, `ne`, `gt`, `gte`, `lt`, `lte`) and flat `all`/`any` groups. Controllers must be unconditional scalar request fields referenced as `/input/<key>`. Step results, derived collection metrics, file/date comparisons and chains of conditional controllers are rejected during publication validation.

`inputUi.layout.sections` groups questions with a title, one or two columns and an optional `visibleWhen`. A question is visible only if its own visibility condition and its section's condition both hold. A question can belong to only one section. Unknown conditional targets or section questions are blockers.

Example:

```json
{
  "visibleWhen": {
    "reviewDetail": { "fieldRef": "/input/needsReview", "operator": "eq", "value": true }
  },
  "requiredWhen": {
    "reviewDetail": { "fieldRef": "/input/needsReview", "operator": "eq", "value": true }
  },
  "layout": {
    "sections": [{ "id": "review", "title": "Review details", "columns": 2, "fields": ["reviewDetail"] }],
    "helpText": "below"
  }
}
```

The form renderer must omit hidden values from submission. The server rejects a submitted hidden field with the `hidden` field-error code, including a value retained by a modified client. It never silently stores or uses that value. Static `required` and conditional requiredness apply only while the question is visible. Required means the field must be present; use `minLength` or other scalar bounds when empty text is invalid.

`FormValidator.validate(schema, input, inputUi)` applies the pinned policy. The original two-argument method retains unconditional validation. Request start, child-run input validation, incident-input correction and workflow simulation use the pinned UI-aware overload. Comparisons use the shared `ConditionMatcher`, including exact decimal arithmetic. Missing or incorrectly typed controller values do not make a visibility condition true; ordinary schema validation still reports invalid controller types.

## Safe use inside a workflow

A conditionally collected value may be absent. Compiler `FormGuards` rejects its use in a system call or other checked mapping unless every path to that use has a matching guard that proves the question visible and required. A matching atomic guard, matching comparisons in an `all` group, or a proved alternative of an `any` visibility rule can establish availability. An `any` route cannot prove every comparison of an `all` visibility rule.

This is deliberately conservative syntactic implication. It does not infer arithmetic implications, such as treating `amount > 100` as proof of a separately authored `amount > 50` visibility rule. A blocker explains that the field may be missing; use the same condition or make the field unconditional. Some step-specific mappings also require an always-available scalar and can impose stricter rules.

## Bounded row collections

A request collection can hold scalar values or rows with scalar columns. Existing scalar collections keep their format. A row collection is declared as:

```json
{
  "type": "array",
  "title": "Line items",
  "minItems": 1,
  "maxItems": 25,
  "items": {
    "type": "object",
    "additionalProperties": false,
    "required": ["item", "quantity"],
    "properties": {
      "item": { "type": "string", "title": "Item", "minLength": 1, "maxLength": 100 },
      "quantity": { "type": "integer", "title": "Quantity", "minimum": 1, "maximum": 100 },
      "price": { "type": "number", "title": "Price", "minimum": 0, "multipleOf": 0.01 }
    }
  }
}
```

Collections have authored bounds of 1–25 rows. Each row has 1–10 scalar columns and rejects additional columns. Columns retain strict types, requiredness, choices, text/numeric bounds, decimal precision and supported date bounds. Required-column names must exist. Error pointers identify the row and column, for example `/input/lines/0/quantity`.

The object item can have an optional title. Nested rows, collections inside a row, file columns and incident-editable columns are not supported. Rows remain typed JSON in the request projection and Flowable input. This feature does not add object-row `forEach`, row-level workflow editing or calculated columns.

## Collection aggregate references

Studio offers `/input/<list>/count` for scalar lists and object rows, and `/input/<rows>/sum/<numericColumn>` for numeric row columns. Counts and sums of integer columns have integer types; other numeric sums have number types. These references can feed supported decisions, route conditions, mappings and outputs. Renaming a list updates its aggregate consumers.

The shared production input resolver now implements these previously accepted references in runtime execution and simulation. Missing or hidden lists resolve to zero; absent optional numeric cells contribute zero. Malformed lists, rows or present nonnumeric cells are rejected. Production uses exact `BigDecimal` arithmetic. The mock evaluator accumulates decimal coefficients with `BigInt` and converts once to a JSON number, avoiding repeated floating-point addition drift.

Frontend aggregate tests cover count defaults, decimal sums, missing cells, invalid values, supported Studio choices and list renames. Both `InputAggregatesRuntimeTest` actual-engine cases pass: exact decimal totals feed the pinned DMN decision and match simulation, while an omitted optional list produces zero counts/totals and the default decision. The original fixtures incorrectly allowed zero rows when a collection is present; they now use the supported minimum of one. Evidence: `evidence/resume-aggregate-final-verify.log`.

## Verification and acceptance

The compiler tests exercise public entry points:

- `ConditionalFormSafetyTest`: six cases proving guarded versus unguarded consumption, conditional requiredness, sections and `all`/`any` route implications.
- `ConditionalInputValidationTest`: five cases covering hidden-field rejection, visible-only requiredness, typed controllers and preservation of the legacy two-argument validator.
- `RowInputValidationTest`: five cases covering typed rows, exact cents, required cells, unknown columns, scalar types and row-count bounds.
- `RowsAndUiRulesTest`: six cases covering publishable rows, invalid columns/dates/nesting, unknown targets, forbidden controller chains and duplicated section membership.

`ConditionalFormsTest` exercises the HTTP request-start API on PostgreSQL/Flowable: a hidden required question may be omitted, a malicious retained value is rejected, valid typed rows persist, invalid/unknown columns fail, and an approval task is created for the accepted request.

The coordinated 15 October clean compiler run passes all 440 tests and the unchanged coverage gate. Both `ConditionalFormsTest` cases pass in focused and full backend execution. The full application run plus the corrected two-test aggregate fixture rerun yields 651 unique cases, with 647 pass, four intentional skips and zero unresolved failures/errors; final `verify` and packaging pass. The build tracker distinguishes these combined results from another full-suite run.

Frontend evidence is recorded separately: all 716 tests across 84 files pass in `evidence/resume-frontend-final.log`; typecheck, lint and fresh mock/production builds pass. The full mock browser suite passes 62 tests with four real-stack-only skips. A browser journey authored conditions, section layout and row columns, published the workflow, entered and hid an answer, submitted typed rows and verified omission of the hidden answer in stored input. The isolated Docker stack additionally passes 13 real-OIDC/API authoring checks, including strict rows, hidden-value rejection, exact decimal totals through pinned DMN, simulation agreement and zero aggregates for omitted rows (`evidence/resume-real-authoring-final.log`). Customer workflow and deployment acceptance remain separate.
