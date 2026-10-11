# Banking capability matrix

Status: evidence from building and running three banking example workflows ([banking-templates.md](banking-templates.md)) against the capabilities Orqix has today. Written 10 October 2026. This is an engineering finding, not a product commitment and not legal or regulatory advice; no jurisdiction is assumed (decision B6 is open) and the examples are illustrative templates, not regulatory-compliant products.

**Status values.** *Supported*: expressed and executed in an example with a passing test. *Partial*: some of the need is met; the cell for the nearest workaround says what is missing. *Missing*: cannot be expressed or is not enforced today; nothing was faked in the examples. Test names are in `backend/app/src/test/java/com/orqix/runtime/BankingWorkflowsTest.java` (abbreviated with `...`); examples are in `examples/`. Proposed epics refer to [10](10-full-product-backlog.md); waves refer to [20](20-competitive-analysis.md) section 8. Proposals are not scheduled.

## Orchestration

| Need | Status | Evidence | Nearest workaround | Proposed epic |
|---|---|---|---|---|
| Parallel independent checks (identity, screening, documents) with a join | Supported | `banking-kyc-onboarding.json`; `kycLowRiskRunsChecksInParallel...`, `kycParallelChecksSurviveAnExecutorRestart` | None needed. A rejection inside the parallel region ends the whole request; other exits from a branch are not allowed, so the risk decision happens after the join | E04 (done) |
| Sanctions screening through a stubbed service, exactly one call per request | Supported | `sanctions-screening.connection.json`; `kycLowRisk...`, `kycScreeningFailureIsRetriedAndStillScreensOnce` | Orqix orchestrates and records, the bank's service screens. A hit is data (`/steps/screen/hit`) routed after the join | E08 (done) |
| Required checks chosen by data (a check that exists only for some customers) | Partial | KYC example: every check always runs; the risk path adds approvals after the join | Model the extra check as a step on a branch after the join, not as an optional parallel branch (a parallel region always starts all of its branches) | E04 |
| Exactly one external effect (booking, chargeback) | Supported | `assertOneBooking` in every credit test; `creditBookingRetriesAfterTransientFailures...`, `creditPermanentBookingRejectionNeedsAttentionAndNeverBooks` (operator retry, one booking); `claimCompletesOnlyWhenEveryDisputeHasFinished...`; no effect on declined paths | Effect journal and one key per run/step/occurrence | E08 (done) |
| Business key as the idempotency key (one chargeback per transaction, whatever the run) | Missing | `gap_theSameTransactionListedTwiceCreatesTwoDisputesAndTwoChargebacks` | The `Idempotency-Key` is the platform's effect key for the run, step and occurrence; the remote cannot deduplicate by transaction reference. Deduplicate before the request or in the remote system | E08 |
| Unknown-outcome handling for non-idempotent calls | Supported (not exercised by the banking examples) | Existing `EffectsTest`, `ReconcileResumeTest`; both banking operations are `idempotentWithKey` | Choose `nonIdempotent` for a booking API without a key, then reconcile by an operator | E08 (done) |
| Bounded AI agent step with schema-validated result for KYC enrichment | Supported (not used by the banking examples) | S13 `AgentFlowsTest`, `examples/vendor-onboarding-check.json` | Left out so the templates run without an AI provider | E10, E11 (done R2 slice) |

## Decisions and money

| Need | Status | Evidence | Nearest workaround | Proposed epic |
|---|---|---|---|---|
| Decimal money with two places, no floating point | Supported | `creditMoneyInputsAreDecimalExactAndFloatNoiseIsRefused...`, `creditRuleTableBoundariesAreExact`, `assertOneBooking` | Amounts are numbers with `multipleOf: 0.01`. `0.30000000000000004` (the binary result of 0.1 + 0.2) and `4999.995` are refused, `5E3` and `5000.00` are accepted and sent to the core system as the same cents. The rule table compares as decimals: 4999.9999999999999999 is below 5,000 (a double would round it to 5,000) | — |
| Boundary decisions (4,999.99 / 5,000.00 / 5,000.01; 49,999.99 / 50,000.00 / 50,000.01) | Supported | `credit...Boundaries...`, `creditAtTheThreshold...`, `creditFromTheHigherThreshold...`, `kycVolumeRuleTableBoundariesUseExactDecimals` | None needed. Draft tests (`evaluateDecision`) and real runs agree | E07 (done) |
| Rule table with a band (5,000 up to but excluding 50,000) | Partial | `credit-limits`: two rows and the default | A row has one atomic condition, rows must be disjoint, so a middle band is the default. A table with two middle bands needs a chain of tables or branches | E07 |
| Matrix lookup country x customer type x product -> checks or risk tier | Partial | `country-policy`, `product-policy`, `volume-policy` plus two chained branches with compound `any` conditions; `kycJurisdictionProductAndVolumeMatrix...` (13 combinations) | A table tests one field. Combine one table per dimension in branches; because routes must be disjoint, priority ("high wins over medium") is a chain of branches. The combined tier is not a stored value, only the path taken | E07 (multi-input tables, Wave 4) |
| Required checks list as a table output that instantiates tasks | Missing | KYC example | A table cannot emit a list that creates tasks; the path through branches decides which approvals exist | E07, E04 |
| Multi-currency amounts, currency per field, rounding mode, arithmetic | Missing | `inputUi.currency` is the constant `USD`; no arithmetic node | One currency per workflow. Convert before the request; compare amounts only in the field's own unit | E03, E07 |
| Comparison operators beyond equal, at least, below | Supported for `lte` and `gt` (`ne` not exercised) | `operatorsAtMostAndAboveGiveTheSameTiersAsBelowAndAtLeast` | An inclusive upper bound ("at most 4,999.99") is a different rule from "below 5,000" for values between two cents; the table states which it means. The Camunda importer still maps only `eq`, `gte`, `lt` (doc 20 G5) | E03 (importer) |
| Per-decision record: input, rule version, matched row, outcome | Partial | Every run has a `DECISION_EVALUATED` event with table key, version, matched row (`r1`, `default`) and outputs (`creditAtTheThreshold...`, `gap_decisionEventDoesNotRecordTheInputValue`) | The input value, who or what decided, redaction, and "candidate rows not chosen" are not recorded; no decision-record API. Draft tester returns `matchedRow` | E07, E17 (Wave 4.1) |
| Rule packs with effective dates, approved change record, bundled test fixtures | Partial | Tables are versioned, pinned in each release and testable in drafts | No `effectiveFrom`/`effectiveTo`, no change record, no fixtures stored with the release; a threshold change is a new release published by a person | E07, E15 (Wave 4.2) |
| Decision tests as a publish gate | Missing | Boundary tests here are code tests | Run them from CI against a draft before publishing | E07 |

## People and separation of duties

| Need | Status | Evidence | Nearest workaround | Proposed epic |
|---|---|---|---|---|
| Requester cannot approve their own request, also in child workflows | Supported | `kycRequesterCannotTakeTheirOwnTask...`, `creditRequesterCanNeverApproveTheirOwnRequest...`, `claimRequesterCannotDecideTheirOwnDisputes...` | Child runs inherit the initiator | E14 (done) |
| Approvals by different groups per step; outsiders refused | Supported | `assertInvisible` assertions in every domain test | Candidate group per approval step. A person outside the group (and without run.read) gets 404, not 403: the task is invisible, which leaks no case existence. A candidate who is the requester gets 403 `SELF_APPROVAL_DENIED` | E14 (done) |
| A later approver must differ from an earlier approver of the same request (four eyes across stages) | Missing | `gap_sameApproverMayDecideEveryStageOfOneRequest` | Keep the stage groups disjoint by administration; nothing enforces it | E14 |
| Rejection requires a reason | Supported | `kycRejectionNeedsAReason...`, `creditDeclineByOperationsNeedsAReason...` (API 422 and a database constraint) | — | E06 (done) |
| Override or approval requires a reason | Missing | `gap_overrideApprovalByOperationsDoesNotRequireAReason` | Only a rejection must carry a reason. Make the override step a rejection of a "decline" proposal, or collect the reason in a comment | E06 |
| Publisher differs from the last editor; mapper differs from approver | Missing | Not part of the examples | Doc 20 section 5.2 item 2 | E14, E16 (Wave 1.5) |
| Field-level redaction and an audited reveal | Partial | `kycCustomerDataIsHiddenFromViewersButNotFieldByField` (also existing `EffectsTest`) | Role-based, all or nothing: a viewer sees no request data or effects, a participant or operator sees every field. Customer data is stored and shown in clear text; there is no masking, reveal-with-reason or redaction in events and AI prompts | E14, E17 |

## Time, rework and escalation

| Need | Status | Evidence | Nearest workaround | Proposed epic |
|---|---|---|---|---|
| Time limit on an approval with an escalation route and a final expiry | Supported | `kycHighRiskNeedsComplianceThenEscalates...`, `kycEscalationThatIsNotDecided...`, `creditManagerRoundThatNobodyDecides...`, `claimDisputeThatNobodyDecidesLapses...`; restart safe via the engine's durable timers | Wall-clock durations from one minute to 90 days; a second approval on the `timedOut` route is the escalation | E04 (done) |
| Business calendars (working days, holidays, cut-off times) | Missing | The time limits above are wall-clock | State the difference in the template, or choose durations that tolerate weekends | E04, E06 |
| Repeated reminders and repeated breach escalation | Missing | One-shot reminders exist; no recurrence | Chain several escalation approvals | E06, E13 |
| Durations that depend on data (risk-tier review frequency) | Missing | The periodic review waits a fixed 90 days for every tier | One wait per branch with its own duration | E04 |
| Waits longer than 90 days; recurring and scheduled starts (annual review) | Missing | `wait` limits are `PT1M` to `P90D`; no scheduler | Start the review from an external scheduler through the API | E13 |
| Start a child without waiting for it (fire and forget) | Missing | `kycLowRiskRuns...`: the parent waits for the periodic review | The onboarding request stays open until the first review ends; use a separate run started by a scheduler instead | E04, E13 |
| Bounded rework with the reasons kept | Supported | `creditReworkRoundsKeepEveryReturnReason...` | Repeat a child up to 25 times, each round is its own run, tasks and decisions | E04 (done) |
| Send back to the applicant who then corrects data | Partial | The rounds reuse the original inputs | A return is a rejection plus a repeated review; corrected values cannot enter the loop (mutable correction forms are not built) | E06 (send-back/rework) |
| Exhaustion route to a referral decision | Supported | `creditReworkExhaustionReferredToOperations...`, `creditDeclineByOperationsNeedsAReason...` | Branch on `limitReached` | E04 (done) |
| Message correlation (customer sends the missing document) | Missing | No message wait; `wait` is a timer | An approval task by staff "documents received", or an API start after the document arrives | E13 (Wave 3.6) |
| n of m approvals (multi-instance, quorum) | Missing | The committee is one decision by one member | Several approval steps in parallel, each to its own group (works for fixed, named members) | E04, E05 (Wave 3.1) |

## Cases, collections and queues

| Need | Status | Evidence | Nearest workaround | Proposed epic |
|---|---|---|---|---|
| Parent creates pinned children per item, per-child outcomes collected, parent completes when all finish | Supported | `claimCompletesOnlyWhenEveryDisputeHasFinished...`, `claimWithEveryDisputeAccepted...`, `claimDisputesCanRunOneAfterAnother...`, `claimDisputeThatNobodyDecidesLapses...` | Parallel or sequential `forEach` on a scalar array (at most 25) | E04 (done) |
| Fraud answer routes to a specialist group | Supported | `claimStartsOnePinnedDisputePerTransactionAndRoutesFraudToTheFraudGroup` | A branch on the reason code before the approval | — |
| Parent status derived from children by a stated rule, per-child SLA on the parent | Partial | Only `allSucceeded` and the per-item outcome arrays | The rule is "all succeeded or not"; no "majority", "any", or partial outcome as a first-class status; no child SLA display | E04 (Wave 4.3) |
| Structured per-item data (transaction, amount, reason per item) | Missing | `card-dispute-claim` has one reason for the whole claim | Items are scalars; start one Claim per reason, or encode fields into the reference outside Orqix | E04, E06 |
| Unique items in a list | Missing | `gap_theSameTransactionListedTwice...` | Validate before submission | E06, E03 |
| Child cancellation propagation | Supported | `claimCancelledByAnOperatorCancelsEveryOpenDispute...` | — | E04 (done) |
| Child release pinning | Supported | `kycPeriodicReviewIsPinnedToTheReleasePublishedWithTheParent` | — | E04 (done) |
| Queue priority, ageing, SLA sort | Partial | Current closeout adds durable audited 0 to 100 priority, oldest/newest sorting and creation age; TaskPriorityTest and frontend queue tests | Automatic aging/escalation, business-calendar SLA ordering and cross-stage deadlines remain open | E06 (Wave 4.4), [S19](build/S19-task-priority.md) |
| Durable recovery after an engine restart | Supported | `kycParallelChecksSurviveAnExecutorRestart`, `creditCaseSurvivesAnExecutorRestart...`, restart inside the claim test | Executor restart only; process kill and database failover are covered by the resilience tools, not by these tests | E18 |

## Defect found and fixed while building the examples

`RunService.detail` and `TaskService.detail` built each task summary with `requestTitle`, the first text field of the request (for the KYC template, the customer name), even for a caller who may not read the request data. A viewer therefore saw the name in `tasks[].requestTitle` while `input` was correctly null. The title is now withheld together with the input (callers without run.read-data who are not participants); candidates, the requester and operators are unchanged, and no contract changed (`requestTitle` was already nullable). Regression: `kycCustomerDataIsHiddenFromViewersButNotFieldByField`.

## Not assessed

Evidence pack and audit export per release, data residency and retention, regulator-specific controls (doc 20 section 5.3), real sanctions or card-network integrations, model and AI governance beyond S13, production identity (these tests use the seeded test users), and performance at volume. Nothing here claims them.
