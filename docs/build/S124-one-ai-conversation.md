# S124: One AI conversation

30 October 2026. Third slice of the forms, cases and AI review ([docs/26](../26-forms-cases-ai-review.md)). It addresses findings A2, A3, A4 (in part) and A6. In the review this slice was called "S123 One AI conversation"; it is S124 here because S121 and S122 were already taken. The safety pipeline from S95 is unchanged:
- closed operations;
- every change applied to a candidate copy and checked by the real compiler;
- review before apply;
- apply as one undo;
- counts only in metering and audit.

## 1. What changed

| Before | After |
|---|---|
| *Describe it* was one-shot and its description was not handed to Ask AI (A2). | When a described draft opens, its conversation already holds the description and the assistant's assumptions and open points. The next Ask AI request continues it. A draft opened from the background-job toast starts with the assumptions only, because the description is not kept in the browser. |
| The assistant always guessed and listed assumptions. | In the Studio it may ask **up to three short clarifying questions** instead of proposing changes. The author answers in the next message or chooses **Just draft it** (`noQuestions: true`). It asks once: if an earlier assistant turn asked (`Questions:` in its text), or the author asked for a draft now, the prompt says `<questions>off</questions>` and any question in the reply is dropped. |
| The AI could not express form rules, step forms, form groups, decisions or calls (A3, A4). | New operations: `setFieldRule`, `setStepFieldAccess` (S123 step forms), `setFormGroups`, `addDecision` (a decision step with its own rule table) and `addCall` (an http step on a registered connection, pinned to its latest version, adding the dependency when it is missing). |
| The model did not see the form's rules or the workspace's connections. | The draft summary adds `formRules` (visibleWhen, requiredWhen, groups) and `connections`: the latest version of each connection, with its operations' request field names, response property names and time limit. |
| The conversation was lost on reload and not shared (A6). | The conversation is **saved with the draft** (`ai_draft_conversation`, V205). It is shared by everyone who can edit the draft, restored when the Studio opens, and cleared for everyone with Clear conversation. A restored proposal is read-only ("From an earlier session"), because it was checked against an older draft. |

Decision recorded (docs/26 section 6): the conversation is kept with the draft and is deletable.
- Its text is never written to audit. Saving is exempt from the audit rule, and clearing is audited as `ai.conversation.cleared` without the text.
- It reaches the model only as the last eight turns of a new request, as before.

## 2. Contract (OpenAPI, additive)

- `AiDraftEditRequest.noQuestions` (boolean, default false).
- `AiDraftEditProposal.questions` (at most 3 strings, at most 200 characters each), included only when there are questions.
- `AiDraftEditOperation`:
  - `op` adds the five new operations;
  - new properties `rule`, `groups`, `table`, `definitionVersion` and `addsDependency`;
  - `before` and `condition` may be null.
- `GET`, `PUT` and `DELETE /workspaces/{ws}/workflows/{id}/ai/conversation`, with `AiConversation {turns, updatedAt, version}` and `AiConversationTurn {role, text, mode?, questions?, proposal?}`:
  - permission `workflow.edit`;
  - at most 40 turns and 200 kB;
  - an empty list clears the conversation;
  - readable whether or not AI is switched on.
- Prompt `ai/prompts/draft-edit-v2.md` (the v1 file is kept for history). The response schema adds `questions`.

## 3. How each new operation is checked

Every operation is applied to the candidate copy, and the whole change must add no compiler blocker.

| Operation | Server rules | Studio applies it only when |
|---|---|---|
| `setFieldRule {key, rule, condition}` | The question exists. The rule is `visibleWhen` or `requiredWhen`. A null condition removes the rule. Setting the same rule again is refused. Form-condition rules (FormGuards) apply. | The rule is still as `before` |
| `setStepFieldAccess {id, fieldAccess?, capture?}` | Approval steps only. Access values are `hidden`, `readOnly`, `editable` or `required`; null sets read-only again. `capture` is normalized to a closed form schema. Normalized to a settings patch with `before`. Publishing rules from S123 apply, including "captured fields only after approval". | The step is still as `before` |
| `setFormGroups {groups}` | Replaces `inputUi.layout.sections`; `[]` removes them. | The groups are still as `before` |
| `addDecision {id, label, table}` | New id. Table key `^[a-z][a-z0-9-]*[a-z0-9]$`, not used yet. `version` 1 and `hitPolicy` UNIQUE are added when missing. | The id and the table key are still free |
| `addCall {id, label, connectionRef, operation, inputMapping, resultMapping, timeoutSeconds}` | The connection is registered and the operation exists. Mappings are objects of field references. The time limit defaults to min(20, the operation's limit). The compiler checks mappings, binding and time limit. | The id is still free; the dependency is added when it is missing |

Parallel sections and child workflows still cannot be added by conversation. The prompt says so, and the assistant points to the step list.

## 4. Files

- **Backend:**
  - `ai/internal/DraftEdits.java`: operations, questions, summary;
  - `ai/internal/PromptLibrary.java`: v2, questions block and schema;
  - `ai/internal/AiService.java`: questions policy, connections, `questionsAsked` count;
  - `ai/api/AiDtos.java`, `ai/api/AiController.java`;
  - new: `ai/internal/ConversationStore.java`, `ai/internal/DraftConversationService.java`, `db/migration/V205__ai_draft_conversation.sql`;
  - `audit/AuditWriter.java`: allowlisted keys;
  - `resources/ai/prompts/draft-edit-v2.md`.
- **Frontend:**
  - `features/ai/api.ts`: conversation calls, `seedConversation`;
  - `features/ai/AuthoringPanel.tsx`: questions, Just draft it, save and restore, clear for everyone, descriptions of the new operations;
  - `features/ai/draftEdits.ts`: apply the new operations;
  - `features/ai/DescribeItDialog.tsx`, `features/ai/AiJobToasts.tsx`: hand over to Ask AI;
  - `features/ai/mocks.ts`: conversation endpoints, questions for vague requests, PO-number and "only when" proposals;
  - `test/setup.ts`: clears the mock conversation between tests.
- **Evaluation:**
  - `AiEvalCli` gains `editMessages` and `editEvaluate`;
  - `tools/ai-eval/edit_eval.py` and `edit-cases.json`: 8 fixed cases.

## 5. Verification (30 October 2026, local)

| Run | Result |
|---|---|
| `DraftEditsTest` (pure) | 16 of 16 pass, 5 of them new: rules and groups, step form with the approved-route guard, decision and call, bounded questions, summary with rules and connections |
| `AiApiTest` (Testcontainers) | 27 of 27 pass. The 2 new tests cover questions then "just draft it" with prompt and audit counts, and saving, reloading, refusing and clearing the conversation with no text in audit. `AiDisabledTest` and `SecurityMatrixTest`: 9 of 9 pass |
| Frontend `tsc -b`, `eslint .` | Clean |
| Frontend `vitest run` for ai, studio, tasks and mocks | 70 files and 459 tests pass, including `OneConversation.test.tsx` (4): apply the new operations, ask then Just draft it with saved turns, restore read-only, clear |
| Eval bridge, offline | `editMessages` builds the v2 prompt with `<questions>off</questions>` and the procurement connection. `editEvaluate` offers a `setFieldRule` change. **`edit_eval.py` was not run against a model:** that needs `OPENROUTER_API_KEY` and network, and it spends money. |

One Studio test (`BpmnSourceView`) failed once in a full run while two other builds were running on the same machine. It passes alone and in the later full run.

## 6. Limits

- **A5:** there is no AI for cases, views or pages yet.
- **A1:** the entry points are not merged. *Answer a few questions* and the governed questionnaire stay separate.
- The conversation is last-write-wins. Two people sending at the same moment can overwrite each other's last turn.
- Questions are asked at most once per conversation, and only in edit mode.
