# S15 — Guided conversational execution

Status: verified slice with a product pass (section 5). This is a deterministic, resumable guided interface over the existing workflow and task services. The commands below work without an AI provider. On the first turn only, a free-text request such as "I need a laptop" may be matched to a workflow the caller can already start by the optional intent service (`ConversationIntentService`, AI profile and budget controls apply); that only proposes a workflow and pre-fills validated fields, and the person still reviews and confirms. The page discloses this. Unrestricted language understanding is not claimed.

## 1. Supported commands

| Command | Result |
|---|---|
| `workflows` | Lists published workflows the caller can start, with selectable start commands. |
| `start workflow-name-or-key` | Resolves an available workflow and begins collecting its published input fields. Ambiguous matches show choices. |
| A field answer | Validates a string, number, integer, boolean, or JSON array against the published form schema. Invalid answers leave the current field open. |
| `fieldName=value` | Supplies or edits a named field before submission. |
| `skip` | Skips the current optional field; required fields still need valid values. |
| `confirm` or `submit` | Executes the reviewed request or task action. |
| `cancel` or `reset` | Discards the conversational draft; this does not cancel an existing workflow run. |
| `my requests` | Lists the caller's recent requests with status commands. |
| `status ORQ-reference-or-run-uuid` | Reads the run through the normal visibility policy and explains its current lifecycle, outcome, and attention message. |
| `my tasks` | Lists assigned or claimable tasks; each choice is `review taskId` (no action is prepared). |
| `review taskId` | Shows the request details (labelled values) of a task the caller can act on, with no action prepared. Confirming at this point decides nothing. |
| `claim taskId` / `approve taskId` | Prepares a task action (and shows the request details); execution requires a separate confirmation. |
| `reject taskId: reason` | Prepares a rejection with its reason; execution requires confirmation. |

A simple purchase conversation is `start Purchase approval`, followed by a title, business purpose, cost center, amount, and `confirm`. The service follows the published form's field order and displays a review before creating a run. Numbers accept valid comma-grouped thousands; booleans accept `true`, `false`, `yes`, and `no`; choice (enum) answers match ignoring case. Lists of text, number, whole-number or Yes/No items are entered as comma-separated items (`Laptop, Monitor`); a JSON array is still accepted. Lists of structured items are not collected in conversation. A `format: date` question is asked as "Enter the date as year-month-day, for example 2026-10-14", validated by the form validator (including formatMinimum/formatMaximum), and shown in review as a readable date (`review.fields[].display`, for example 14 October 2026) while the stored value stays YYYY-MM-DD. Request confirmations and lists name a request by its reference and request title when the caller may see the title. Prompts and error messages use the question's own title and plain wording.

## 2. State, authorization, and replay

Conversations are scoped to a workspace and their author. The GET endpoint resumes only that author's unexpired conversation. Each subsequent message supplies `conversationId` and `expectedVersion`; a stale version returns a conflict rather than overwriting the draft. The UI can resume a saved conversation pointer without retaining a raw transcript.

Every message uses the normal idempotency header. Responses are retained as conversation turns so replay of a submitted confirmation returns the same run or task result. Request creation uses the existing run-start service with the selected published release and validated input. Task actions use the existing task visibility, ownership, version, eligibility, self-approval, and decision services. Confirmation rechecks current task state and authorization; an earlier suggestion is not authorization to execute later.

The service stores collected field values, the review state, and replayable responses. It does not store raw conversational messages or include them in audit metadata. Audit entries record conversation stage and version; run starts, claims, and decisions keep their existing domain audit entries. Drafts expire using the configured idempotency retention period, with replay records protected until their keys expire. The first slice limits an author to 50 active conversations per workspace and 200 messages per conversation. New-session admission is serialized per author and workspace.

## 3. API

- `POST /workspaces/{workspaceId}/conversations/messages`: send `{ message, conversationId?, expectedVersion? }` with an idempotency key.
- `GET /workspaces/{workspaceId}/conversations/{conversationId}`: resume the current author-scoped state.

Replies identify the conversation and its version and provide the stage, assistant message, choices, current field, reviewed input, or resulting run/task identifier. Stages include `idle`, `collecting`, `review`, `confirmingTask`, and `completed`. `review.fields` lists the values in form order with their business labels (name, label, value); `review.input` keeps the raw map. For a task, `review` carries the request details and `taskAction` (`claim`, `approve` or `reject`) is present only once an action is prepared and waiting for confirmation; a `confirmingTask` reply without `taskAction` means the person has only been shown the request, and `confirm` then asks them to choose first.

The Conversation page is an additional way to execute the same authorized actions available through forms and the task inbox. Workflow editing, operator effect reconciliation, arbitrary integration execution, and unattended approval are outside its command vocabulary.

## 4. Verification and further work

`ConversationApiTest` covers typed collection and invalid values, reviewed edits, confirmation and idempotent replay, draft resume and version conflict, author/workspace isolation and expiry, plain-language prompts and comma-separated lists, labelled review fields, task review without a prepared action, explicit rejection reason and separate confirmation, permission revocation, and competing task ownership. It exercises the real public API, form validator, engine, and task services with stub integrations.

Further work includes richer language understanding with a reviewed intent plan, natural-language document selection, attachment and citation views, conversational explanations of parallel and repeated work, paging beyond the current recent-item lists, a persisted transcript (only the latest turn is resumable after a reload), field-specific controls for structured lists, and explicit administrative retention settings. Adding a stronger AI interpreter must preserve the existing confirmation and permission boundaries; model-generated commands should become proposals before execution.

## 5. Product pass (UI and mock mode)

- Language: the page speaks in business terms (Start a request, My tasks, My requests, Submit request, Approve, Reject, Change, Skip this question). Commands, `fieldName=value` and JSON are not shown. The help under the message box explains one question at a time and comma-separated lists and discloses the optional AI matching.
- Flow: choices become buttons (request types, choice fields, Yes/No, Skip). The review shows labelled values with a **Change** control per value. Task review shows the request first; Approve, or Reject with a required reason, prepares the action; **Confirm approval/rejection** is a separate step; Go back and Leave this task alone record nothing.
- Safety: submission and decisions require an explicit confirm; replay of the same idempotency key returns the same result; the task decision rechecks eligibility, version and self-approval at confirmation; other people's conversations and other workspaces answer 404; a task the caller cannot act on answers 403/404. A task's request values are stored in the conversation response only for the caller who was already authorised to read them and expire with the draft.
- Accessibility: the transcript is a polite live region (`role="log"`); focus moves to the answer box after each question and to the current step otherwise; Enter sends and Shift+Enter inserts a line; the answer box stays focusable (read-only) while a message is sending; errors for a stale or expired conversation offer Reload or Start again. The layout holds at 375 px. Only theme tokens are used.
- Mock mode: `frontend/src/mocks/conversation.ts` implements `sendConversationMessage` and `getConversation` over the mock database with the same commands, validation, review, idempotency and task decision rules, so the Assistant works in `npm run dev:mock`. Conversations persist in the browser's local storage in mock mode. `tests/e2e/specs/t-chat-01-assistant.spec.ts` runs the purchase and a manager decision.
