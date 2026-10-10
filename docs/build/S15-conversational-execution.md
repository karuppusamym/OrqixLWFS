# S15 — Guided conversational execution

Status: verified slice; verification is recorded in the release tracker. This is a deterministic, resumable command interface over the existing workflow and task services. It does not require an AI provider and does not claim unrestricted natural-language intent recognition.

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
| `my tasks` | Lists assigned or claimable tasks with review commands. |
| `claim taskId` / `approve taskId` | Prepares a task action; execution requires a separate confirmation. |
| `reject taskId: reason` | Prepares a rejection with its reason; execution requires confirmation. |

A simple purchase conversation is `start Purchase approval`, followed by a title, business purpose, cost center, amount, and `confirm`. The service follows the published form's field order and displays a review before creating a run. Numbers accept valid comma-grouped thousands; booleans accept `true`, `false`, `yes`, and `no`. Arrays must be supplied as JSON, rather than inferred from prose.

## 2. State, authorization, and replay

Conversations are scoped to a workspace and their author. The GET endpoint resumes only that author's unexpired conversation. Each subsequent message supplies `conversationId` and `expectedVersion`; a stale version returns a conflict rather than overwriting the draft. The UI can resume a saved conversation pointer without retaining a raw transcript.

Every message uses the normal idempotency header. Responses are retained as conversation turns so replay of a submitted confirmation returns the same run or task result. Request creation uses the existing run-start service with the selected published release and validated input. Task actions use the existing task visibility, ownership, version, eligibility, self-approval, and decision services. Confirmation rechecks current task state and authorization; an earlier suggestion is not authorization to execute later.

The service stores collected field values, the review state, and replayable responses. It does not store raw conversational messages or include them in audit metadata. Audit entries record conversation stage and version; run starts, claims, and decisions keep their existing domain audit entries. Drafts expire using the configured idempotency retention period, with replay records protected until their keys expire. The first slice limits an author to 50 active conversations per workspace and 200 messages per conversation. New-session admission is serialized per author and workspace.

## 3. API

- `POST /workspaces/{workspaceId}/conversations/messages`: send `{ message, conversationId?, expectedVersion? }` with an idempotency key.
- `GET /workspaces/{workspaceId}/conversations/{conversationId}`: resume the current author-scoped state.

Replies identify the conversation and its version and provide the stage, assistant message, choices, current field, reviewed input, or resulting run/task identifier. Stages include `idle`, `collecting`, `review`, `confirmingTask`, and `completed`.

The Conversation page is an additional way to execute the same authorized actions available through forms and the task inbox. Workflow editing, operator effect reconciliation, arbitrary integration execution, and unattended approval are outside its command vocabulary.

## 4. Verification and further work

`ConversationApiTest` covers typed collection and invalid values, reviewed edits, confirmation and idempotent replay, draft resume and version conflict, author/workspace isolation and expiry, explicit task confirmation, permission revocation, and competing task ownership. It exercises the real public API, form validator, engine, and task services with stub integrations.

Further work includes richer language understanding with a reviewed intent plan, natural-language document selection, attachment and citation views, conversational explanations of parallel and repeated work, broader browser acceptance, accessible field-specific controls, paging beyond the current recent-item lists, and explicit administrative retention settings. Adding an AI interpreter must preserve the existing confirmation and permission boundaries; model-generated commands should become proposals before execution.
