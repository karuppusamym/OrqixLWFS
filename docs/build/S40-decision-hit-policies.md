# S40 - Decision hit policy FIRST and OR rows (RL04, bounded)

Extends [S28](S28-rules-authoring.md). A decision table now declares one of two hit policies and its rows may be alternatives.

| Setting | Meaning |
|---|---|
| `hitPolicy: "UNIQUE"` (default) | At most one row can match any case. This is proven at publish time (`WF_DECISION_OVERLAP`); the default applies only when no row matches. Behavior is unchanged. |
| `hitPolicy: "FIRST"` | Rows are tried top to bottom and the first match wins, so rows may overlap. The default applies when no row matches. Row order is part of the meaning of the table and is shown in version comparison. |
| Row `when: { "any": [ ... ] }` | An OR row: 2 to 8 comparisons, true when at least one holds. Fields may repeat (`amount < 100` OR `amount >= 10000`). Allowed under both policies; under UNIQUE every alternative of every row must still be exclusive of the other rows (`WF_DECISION_OVERLAP`). A conjunction (`all`) still allows each field once (`WF_DECISION_DUPLICATE_INPUT`). |

## How it runs

One evaluator and one comparison function serve drafts, the simulator and the pinned production DMN. The generated DMN is always `FIRST` followed by a catch-all default rule. An OR row with k alternatives becomes k consecutive DMN rules, each filling only its own field, so the engine's first matching rule is the first matching alternative. Runtime evidence maps the matched DMN rule back to the canonical row, so the timeline and decision evidence name the row the author wrote (`r1`, `r2`, …, `default`) and list every candidate row with whether it matched; the evidence check confirms the pinned DMN result against the canonical first match and fails loudly on disagreement.

Draft evaluation and the browser mock use the same rules. Studio: the rule table editor has *Which row applies*, the *Any comparison (OR)* group option, and up/down buttons for ordering rows under FIRST.

## Limits (deliberate)

- Only the six existing comparison operators; no lists, ranges, `in`, string functions or arbitrary FEEL.
- No nested groups (an OR of ANDs); express it as several rows under FIRST.
- No COLLECT, RULE ORDER, PRIORITY or ANY hit policies and no aggregation.
- No warning yet for a row that an earlier row always hides under FIRST.
- The Camunda DMN importer is unchanged: FIRST sources are still converted to exclusive UNIQUE rows with a review note, not to native FIRST tables.

## Tests

`FirstAndAnyDecisionTest` (compiler): overlap rejected under UNIQUE and accepted under FIRST, OR rows in the evaluator, simulator and DMN (one rule per alternative), repeated fields in OR rows. `DecisionHitPolicyTest` (PostgreSQL + Flowable): the pinned DMN selects the first matching row, names the canonical row for the second alternative of an OR row, and reports the default. Frontend: `DecisionInspector.test.tsx`, `decisionPolicy.test.ts` (mock validation and evaluation).
