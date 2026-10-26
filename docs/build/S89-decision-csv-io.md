# S89 - Decision table CSV import and export (CMP03, gap G3)

Business users can keep the rules of a decision table in a spreadsheet. A table of a workflow draft is downloaded as CSV, edited, and imported back. Import is a normal draft change (never a publish) and runs the same decision validation as publish, so an imported table cannot be invalid. Extends [S40](S40-decision-hit-policies.md) and [S61](S61-shared-assets.md).

## File format (strict, version of this note)

UTF-8 (a byte order mark is accepted, exports have none), comma separated, RFC 4180 quoting, LF or CRLF. Every deviation is an error with a line and a column; nothing is coerced.

```
hitPolicy:FIRST,effectiveFrom,effectiveTo,in:number:/input/amount,in:text:/input/costCenter,out:text:route
rule,,,gte 50000,,executiveReview
rule,,,gte 5000,eq IT,itFinanceReview
rule,,,gte 5000,,financeReview
default,,,,,standard
```

| Part | Rule |
|---|---|
| Header, cell 1 | `hitPolicy:UNIQUE` or `hitPolicy:FIRST` |
| Header, cells 2-3 | `effectiveFrom`, `effectiveTo` (S41 date window) |
| Input columns | `in:<type>:<fieldRef>` (1 to 30), type `text`, `number` or `boolean`, fieldRef as in the workflow (`/input/amount`, `/steps/<id>/<name>`) |
| Output columns | `out:<type>:<name>` (1 to 10), after all inputs |
| Rule line | first cell `rule`; up to 50; **top to bottom is the order of the table** (priority under FIRST) |
| Default line | exactly one, last, first cell `default`, no conditions, no dates, every result filled |
| Input cell | empty (field not tested) or `<operator> <value>`: operator `eq ne gte gt lt lte`, exactly one space. A rule with one filled cell is a single comparison; with several it is an All (AND) row, in column order. A rule needs at least one |
| Values | `number`: plain `-?digits[.digits]`, at most 18 digits, no separator, exponent, sign `+` or currency. `boolean`: `true` or `false`. `text`: as is, no leading or trailing space (the compiler further limits text to 100 letters, digits and ` _.,:/-`) |
| Dates | `YYYY-MM-DD` or empty; `effectiveTo` not before `effectiveFrom` |
| Not in CSV | OR (`any`) rows, one field with different value kinds, an empty text result, values with edge spaces or over 120 characters. Export refuses these with the reasons (422) rather than writing something approximate; split an OR row into one row per alternative under FIRST |

Table key and version are not in the file: the import keeps the key and version of the table it replaces. The file names the workflow fields it tests, so it belongs to the workflow it was exported from (or one with the same fields).

### Limits of the reader

128 KiB, 60 lines, 120 characters per cell, 100 reported problems. Refused: invalid UTF-8, control characters (including tabs), line breaks inside cells, lone carriage returns, empty lines, unbalanced or misplaced quotes, a wrong number of cells, unknown columns, duplicate columns. Cells are only ever text: no formula is evaluated, no external reference followed.

### Spreadsheet formula injection (OWASP CSV injection)

On **export**, every cell whose first character is `=`, `+`, `-`, `@`, tab, carriage return or `'` gets a leading apostrophe (so a negative result `-1.5` is written `'-1.5`). On **import**, one leading apostrophe is removed when it is followed by one of those characters; any other leading apostrophe is an error (`apostrophe`). Because input cells start with an operator word (`gte 5`), only results and text values are affected. A hand-typed `=SUM(...)` is read as plain text and then refused by the compiler's literal safety (`WF_LITERAL_UNSAFE`). Export proves its own round trip before answering: the file is parsed again and compared with the table, otherwise the export is refused.

## API (contracts/openapi.yaml, tag Workflows)

| Operation | Permission | Notes |
|---|---|---|
| `exportDecisionCsv` GET `/workflows/{id}/decisions/{key}/csv?revision=` | `workflow.read` | `text/csv`, `Content-Disposition: attachment`, `Cache-Control: no-store`; 422 with reasons when not representable |
| `getDecisionCsvTemplate` GET `.../csv-template` | `workflow.read` | The table's columns, one example rule, the default line; imports as is |
| `importDecisionCsv` PUT `.../csv` | `workflow.edit` | Body is the CSV (`Content-Type: text/csv`, else 400), `If-Match` required (428) and checked (412), 413 above 128 KiB; saves the next draft revision (audit `workflow.draft.saved`, source `decisionCsv`); returns the draft with its ETag |

No new permission. Foreign principals get 404 on all three (SecurityMatrixTest); a reader importing gets 403.

Import errors are 422 `VALIDATION_FAILED` with one field error per problem: pointer `/csv/lines/<line>/columns/<column>`, `code` (the parse kind, or the compiler code such as `WF_DECISION_OVERLAP`, `WF_FIELDREF_UNKNOWN`, `WF_LITERAL_UNSAFE`), message. Compiler findings are mapped back to the cell: rule line, and the column of the condition or result concerned.

### Same validation as publish and DMN import

After parsing, the table replaces the old one in the latest draft and the **compiler's `validate`** runs on the whole draft. Any blocker located inside that table refuses the import (hit-policy overlap under UNIQUE, mixed or duplicate fields, unknown field, operator and value type, result shape against the default, literal safety, dates). The DMN importer's own self-check is a separate migration path; the shared rules are the compiler's, which is also what Check workflow and Publish use. If the draft has structural (schema) errors elsewhere, the compiler cannot reach the table rules; the import is then refused with a message to run Check workflow first. Warnings (for example an unreachable default) do not block.

## Studio

Decision step > **Spreadsheet (CSV)**: **Export CSV**, **Download template**, **Import CSV** (hidden for read-only users; export and template stay available). Import flushes the autosave, sends the file bytes, and loads the result as the current saved revision. Problems appear in an accessible list (`role="alert"`, "Line 3, column 4: ...", count in the heading, announced) and nothing changes. Browser mock handlers implement a simplified double of the same format.

## Shared decisions

Not offered directly: a shared decision's content has no workflow context (field types come from the workflow), so it cannot get the full validation. Use the draft: import into the table, then **Save as shared version N+1** ([S61](S61-shared-assets.md)); the usual validation of the linked copy applies.

## Tests

- `DecisionCsvTest` (12, no database): parsing, BOM/CRLF/quotes, every error with line and column, no coercion, limits, export, exact round trip, formula neutralisation for each risky first character, refused tables, template, error locator.
- `DecisionCsvApiTest` (4, PostgreSQL): import as a draft change only (no release, audit source), export -> import -> identical decision (byte-stable file), evaluator uses row order, 403/404/412/413/428/400, overlap/unknown field/unsafe literal with line and column and nothing saved, OR rows refused on export.
- `SecurityMatrixTest`: the three operations in the generated foreign-principal matrix plus an explicit declaration check.
- Frontend: `DecisionCsvSection.test.tsx` (5), `decisionCsv.test.ts` (3, mock double), `DecisionInspector.test.tsx` unchanged and passing. `api:check` green.

## Not included

OR rows and mixed-kind fields in CSV; Excel (`.xlsx`) files; semicolon or tab delimiters and other encodings; creating a new decision table from a file (create the table, then import); import or export of shared decision versions directly; dry-run (check without saving) of a file; merging instead of replacing; a browser run against the real stack.

Example: `examples/decision-csv/purchase-routing.csv` (imports into the purchase approval template's `amount-policy`).
