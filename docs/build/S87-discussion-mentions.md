# S87 - @mentions and notifications in the step discussion (CMP01, gap G1)

Closes gap G1 of the SnapFlow comparison: the threaded step and connection discussion of [S53](S53-lanes-source-discussion.md) gains @mentions, and a mentioned person gets an in-app notification that opens the studio on that step with its discussion. Nothing else about the discussion changes: comments still never change the draft, grant anything or block publishing.

## What exists

**Composer.** Both the "New comment" and "Your reply" boxes suggest people while you type `@` and part of a name (debounced, at most 10 suggestions). Arrow keys move through the list, Enter or Tab picks, Escape closes; mouse picking works too. The text box stays a normal text box for screen readers with `aria-autocomplete="list"`, `aria-controls` and `aria-activedescendant` pointing at a labelled listbox ("People to mention"), a hint "Type @ to mention someone who can read this workflow" and a polite status with the number of matches. Picking inserts `@Display Name `. Only people who can post (workflow.edit or workflow.publish) see a composer, so only they can ask for suggestions.

**Who can be suggested.** `listWorkflowMentionCandidates` (`GET .../workflows/{id}/discussion/mention-candidates?q=`) returns enabled people, other than the caller, whose display name contains `q` (case-insensitive, `%`/`_` are literal) and who can read this workflow in this workspace today: the workspace is visible to them and they hold `workflow.read` there, computed fresh from their role bindings, grants and the IdP groups of their latest sign-in (the same rule as reading the discussion). People of other workspaces, requesters who can only start, workspace administrators without `workflow.read`, disabled people and the caller are never listed. At most 10 among the first 100 name matches are returned; a longer `q` than 100 characters is 400. Callers who may read but not post get 403; foreign principals 404.

**Posting.** `postWorkflowComment` accepts an optional `mentions` list of subject ids (at most 10, otherwise 400). The server keeps a mention only when the person exists, is enabled, is not the author, can read the workflow here at that moment (fresh access) and their `@Display Name` appears in the text (case-insensitive). Anything else (unknown ids, people without access, other workspaces, self, duplicates, a name deleted from the text after picking) is ignored without an error, so the request cannot be used to probe who has access. Kept mentions are stored in `workflow_comment_mention` (ids only) and returned on every comment as `mentions: SubjectRef[]` with current display names.

**Notification.** Each kept person gets exactly one notification of kind `discussionMention`, created in the same transaction as the comment, with the dedup key `discussion-mention:<commentId>:<subjectId>`, so a replay of the notification step never notifies twice (tested by calling it again). It carries `workflowId` and `elementId` (no run) and the message "Dana Designer mentioned you in the discussion on the step "Manager approval" of "Purchase approval"." (or "a connection"); labels are shortened to 80/120 characters. **Decision: the notification never contains any part of the comment text, not even an excerpt**: the text is only shown inside the studio, after the normal `workflow.read` check. The bell shows mention notifications with an @ icon and "Mention in a discussion"; clicking one marks it read and opens `/w/<ws>/workflows/<id>/edit?element=<elementId>&discussion=1`, which selects the step or connection, scrolls to the discussion and moves focus to its heading. If the element has since been deleted from the draft, or the screen is narrower than the studio editor needs, a notice says so.

**Read and read-all.** Marking one read is the existing `markNotificationRead`. There was no read-all before; `markAllNotificationsRead` (`POST .../me/notifications/read-all`, returns `markedRead`) now marks every unread notification of the caller in that workspace read (idempotent, only the caller's own). The bell shows "Mark all as read" when anything is unread. Like markNotificationRead it is not audited (contract-check and SecurityMatrix exemptions).

**Rendering.** Stored mentions are drawn bold, dotted-underlined, on a soft background and always prefixed with "@" (never colour alone), with screen-reader text "mention of". Text typed as "@someone" by hand without a stored mention is shown as plain text.

**Audit.** `workflow.comment.created` metadata adds `mentionCount`, `mentionsIgnored` and `notified` (counts only; no names, no subject ids, never text). No other audit changes.

## Edge cases (decided)

- Self-mention: neither stored nor notified (and the caller is never suggested).
- Access lost before posting: the mention is dropped and nobody is notified (fresh access at post time). Access lost after the notification was created: the notification stays in that person's inbox (it holds no comment text); opening it re-checks access in the studio as usual.
- Resolve and reopen never notify. Replies notify like any comment (a reply is a new comment).
- Edit and delete of comments do not exist, so there is no re-notification or retraction.
- No email or chat delivery; in-app only.
- Display name changes: rendering uses the current name; a comment written with the old name still matches only if the text contains the name the server checked at post time (the stored mention is kept regardless).

## Data and contract

Migration `V130__discussion_mentions.sql`: table `workflow_comment_mention` (composite foreign keys keep it inside its workspace, workflow and comment); `notification` gains `workflow_id`, `element_id`, `comment_id` with composite foreign keys, the kind check adds `discussionMention`, and a check requires a mention notification to point at a workflow, element and comment and never a run. No existing migration was edited. `contracts/openapi.yaml`: new `listWorkflowMentionCandidates` and `markAllNotificationsRead`; `PostWorkflowComment.mentions`, `WorkflowComment.mentions` (required), `Notification.kind` adds `discussionMention`, `Notification.workflowId/elementId`. `frontend/src/api/schema.d.ts` regenerated. The catalog module may now depend on the notification module (Spring Modulith allowed dependency). Mock handlers implement the same rules (simplified double).

## Not included

Notification preferences or muting, email/chat delivery, mentioning groups, mentions in run comments or anywhere outside the workflow discussion, editing or deleting comments, a notification page beyond the bell's latest 20, a real-stack (Docker) or Playwright browser run.

## Verification - 25 October 2026

Backend (JDK 21, real PostgreSQL via Testcontainers, every response validated against `openapi.yaml`): new `WorkflowDiscussionMentionApiTest` (4 tests: suggestion boundaries including other workspaces, requesters, admins, self, literal `%`, viewer 403, foreign 404, long q 400; filtering of mentions, one notification per kept person without any comment text, dedup replay, resolve/reopen without notification, reply mentions, audit counts without text or ids, cap of 10; a person who lost access is neither suggested nor notified; read, read-all and cross-recipient isolation). Targeted run with `WorkflowDiscussionApiTest`, `SecurityMatrixTest` (covers both new operations for foreign principals and snapshots `workflow_comment_mention`), `ArchitectureTest`, `DatabaseTest`: 18 tests, 0 failures. Full `mvnw -f backend/pom.xml clean verify`: BUILD SUCCESS, compiler 524 tests and application 901 tests, 0 failures, 0 errors, 4 skipped (evidence `evidence/cmp01/backend-full.log`, untracked).

Frontend: new `model/mentions.test.ts` (4) and `DiscussionMentions.test.tsx` (3: suggestions exclude author, requester and other workspace; keyboard pick with `aria-activedescendant`; posted mention rendered with screen-reader text; one mock notification without the text; bell link opens the studio on the step with focus on the discussion; mark all read; deleted-element notice). Full suite 137 files, 974 tests pass; `tsc -b`, scoped ESLint, `api:check`, `build:mock` and `tools/contract-check` (88 checks) pass.

Rerun: `backend/mvnw.cmd -f backend/pom.xml -pl app -am -Dtest=WorkflowDiscussionMentionApiTest,WorkflowDiscussionApiTest,SecurityMatrixTest -Dsurefire.failIfNoSpecifiedTests=false test` (JDK 21); from `frontend`: `npx vitest run src/features/studio`.
