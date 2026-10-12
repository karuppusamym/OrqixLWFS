# S31 - Requests with no pending work

The runtime watchdog inspects at most 50 stale requests per pass. Requests must have an empty current-step projection, be running or waiting, and have no update for five minutes. Rows are locked with `SKIP LOCKED`, allowing concurrent instances without duplicate attention entries.

Any active engine activity, human task, event subscription, executable/timer/dead-letter/suspended job, suspended process or active child request protects the request from classification. The watchdog never triggers an execution, reconstructs a business outcome or marks a request completed. It moves proven empty requests to `needsAttention` with `RUN_WAITING_ON_NOTHING`, appends a timeline entry and creates a deduplicated initiator notice. Existing operator permissions, version checks, idempotency and audit apply to cancellation. Operators may cancel a missing-engine request; the UI does not offer retry without a retryable engine job.

This conservative detector does not classify a stranded engine token at a nonempty activity, repair arbitrary engine/projection corruption, or replace engine backup/restore procedures. Those remain separate gaps. `RunWatchdogTest` executes PostgreSQL/Flowable scenarios for preserved approvals and orphan classification plus authorized cancellation. Coordinated verification results are recorded in docs 22.
