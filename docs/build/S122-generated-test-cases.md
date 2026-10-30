# S122 - Generate test cases for every path

Extends the Test tab of [S96](S96-test-tab-and-integration-mocks.md) and the dry run of [S26](S26-workflow-simulation.md). Before this, the sample request was one fixed set of answers (`sampleValues.ts`: the first choice, No, USD 1,000, 10, today + 14 days, the shortest pattern match, "Sample <key>"). It ignored form rules and route conditions, so a designer reached other paths only by writing saved cases by hand. This is direction item "design-time guarantees" of [docs/26](../26-forms-cases-ai-review.md). Frontend only, with no contract or backend change.

## 1. What the designer sees

The Test tab has a new section, **Cases for every path**, with the button **Generate cases for every path**. It saves the draft (when the person may edit it) and then proposes up to 20 cases. Each proposal shows:

- its name, which lists the answers that were set on purpose ("Estimated cost (USD) is 1999.99", "Manager review rejects (...)"),
- the expected outcome,
- the steps it takes.

A summary line says how many routes and decision rules the cases cover together: "7 cases to review. Together they take 6 of 6 routes." The routes that no case takes are listed under it. These are usually timeouts and failure routes, which still need a hand-written case with step settings.

Proposals are not saved. **Keep** and **Delete** act on one case, **Keep all N** and **Delete all** on all of them. A kept case becomes an ordinary saved case (`savedCases.ts`, version 3) and can be loaded, edited, exported, run with **Run all saved cases** or shared for publication. Names are deterministic, so generating again and keeping replaces the earlier generated cases of the same name. Readers can generate and look; keeping needs `workflow.edit`.

The saved-case list now holds 20 cases instead of 10. That is the limit of `simulateWorkflowCases` and of a shared suite.

## 2. How the answers are derived (`caseGenerator.ts`)

Everything is deterministic and local. No model is involved, and the only requests are dry runs of the draft, which start nothing and call no system or provider.

1. **Conditions.** The generator collects every condition that reads a request-form answer (`/input/<key>`):
   - route conditions on transitions,
   - rows of decision tables that a decision step uses,
   - form rules (`inputUi.visibleWhen`, `requiredWhen`, and group rules through `FieldDef.visibleWhen`).

   Duplicates are dropped. A term that reads anything else (a step result, a decision output) is left to the dry run. It is reached through the answers that feed it, such as the decision row on `/input/amount` that sets `/steps/amountPolicy/route`.
2. **Values, both sides of each comparison** (`answerFor`):

   | Question | Operator | Holds | Fails |
   |---|---|---|---|
   | money, number | `gte 2000` | 2000 | 1999.99 |
   | | `gt 2000` | 2000.01 | 2000 |
   | | `lte 2000` | 2000 | 2000.01 |
   | | `lt 2000` | 1999.99 | 2000 |
   | | `eq 2000` | 2000 | 2000.01 (or 1999.99 at the maximum) |
   | | `ne 2000` | 2000.01 | 2000 |
   | choice | `eq "Other"` | Other | the first other choice |
   | yes/no | `eq true` | Yes | No |
   | text | `eq "IT-12345"` | IT-12345, if it fits the pattern and length | the sample answer |

   The step is one cent for money and for numbers without `multipleOf`, the `multipleOf` when one is set, and 1 for whole numbers. Results are rounded so that no floating-point noise appears. A value outside `minimum`/`maximum`, off `multipleOf`, not a listed choice, not matching the pattern, or of another type gives no candidate.

   "All of" holds when every term is set to hold, and fails one term at a time. "Any of" holds one term at a time, and fails when every term is set to fail.
3. **Candidates** (`candidateInputs`):
   - Start from the sample answers and apply each condition's sides: route conditions first, then decision rules, then form rules. The sample answers alone come last.
   - Before a question is answered on purpose, the rules that show it are made to hold. For example, a route on the asset tag also answers Yes to "Replacing a broken device?".
   - Answers of hidden questions are then cleared until nothing changes, so a case reads like what a requester would send.
   - Each set must pass `validateForm`, which covers visible and required questions, patterns, lengths, minimum and maximum. A set that fails, or that sends the same request as an earlier one, is dropped. At most 40 sets are tried.
4. **Dry runs and selection** (`generateCases`):
   - Each set is dry-run with every approval approved (`simulateWorkflow`, four at a time; results are processed in order).
   - A case is kept when its signature is new. The signature is the routes taken, the decision rules matched, the ending, and the questions shown.
   - Then, for each approval step in document order, the first kept case that reaches it is run again with that step rejecting. Each such run that takes a new path becomes a reject case.
   - When more than 20 cases qualify, cases that take a new path win over cases that only show other questions.
   - Answers that the server refuses (`VALIDATION_FAILED`) are skipped. `WORKFLOW_INVALID` stops generation and shows the blockers, as **Run test** does.
5. **Expectations.** Each case expects its dry run's business outcome, or Needs attention. It also carries a `path` assertion with `match: exact`: the steps in order of first visit. Re-running a kept case therefore fails when a change to the workflow moves it to another path.

## 3. Example: IT equipment request

This is the workflow of `t-forms-02`: manager review, finance sign-off from USD 2,000, and three form rules (a description when the item is Other, a justification from USD 1,000, an asset tag `^IT-\d{5}$` for a replacement). It produces seven cases that take all 6 routes:

| Case | Expect | Path |
|---|---|---|
| Estimated cost (USD) is 2000 | Approved | manager, finance |
| Estimated cost (USD) is 1999.99 | Approved | manager only |
| What do you need? is "Other" | Approved | manager only, description shown |
| Estimated cost (USD) is 999.99 | Approved | manager only, no justification |
| Replacing a broken device? is Yes | Approved | manager only, asset tag IT-00000 |
| Manager review rejects (Estimated cost (USD) is 2000) | Rejected | manager |
| Finance sign-off rejects (Estimated cost (USD) is 2000) | Rejected | manager, finance |

The purchase approval example gives four cases. Amount 5000 and 4999.99 come from the decision rule "at least USD 5,000", and the other two are a reject at each review. Together they take 6 of 6 routes and match 2 of 2 rules.

## 4. Limits

- Only answers to the request form are varied. Step settings other than approve/reject keep their defaults, so timeouts, failing calls and other mocks still need hand-written cases. The coverage line lists those routes.
- Conditions are compared as the simulator compares them: ordering operators work on numbers only, and text, choices and yes/no use `eq`/`ne`. Dates and list questions keep their sample answers.
- The generator relies on the dry run, which is not the engine (S96 section 1): a passing generated case is a check of the design, not a promise about a real request.

## 5. Tests

- `caseGenerator.test.ts`: each operator on both sides, whole-number and `multipleOf` steps, minimum, maximum and cent bounds, choices, yes/no, type mismatches, patterns and lengths, all-of and any-of, the condition sources, hidden questions (shown first, cleared when hidden, also through a hidden controlling question). It also runs the IT equipment and purchase examples end to end through the mock simulator, re-checking every generated case against its own assertions, and checks the 20-case cap with new paths first and refused answers left out.
- `TestPanel.test.tsx`: generate on the purchase workflow, delete one, keep one, keep all, the stored version 3 cases with path assertions, and Run all with 3 of 3 passing.
- `tests/e2e/specs/t-forms-03-generated-cases.spec.ts` (mock mode): builds the IT equipment request, generates the seven cases, keeps them all, loads the replacement case, and runs all with 7 of 7 passing.
