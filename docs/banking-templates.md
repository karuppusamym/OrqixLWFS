# Banking example workflows

Status: executable examples with integration tests. **Illustrative templates, not regulatory-compliant products.** No jurisdiction is assumed (decision B6 in the [decision register](13-decision-register.md) is open): the thresholds, country groups, time limits, group names and outcomes below are placeholders chosen to exercise the platform. A bank's compliance function must decide the real rules, map them to its regulator's obligations and approve the workflow before it is used for real customers. Nothing here screens a customer, scores credit or moves money; external effects are calls to systems the bank owns, and the tests replace them with a local stub.

The examples answer one question: which realistic banking workflows can Orqix express and run today with the capabilities it already has, and what is missing. The answer to the second half is in [banking-capability-matrix.md](banking-capability-matrix.md). Direction and scope are in [20](20-competitive-analysis.md) section 5; nothing here changes the product scope or a contract.

## Files

| Workflow | Key | Files | Child workflow |
|---|---|---|---|
| KYC customer onboarding review | `kyc-onboarding-review` | [`banking-kyc-onboarding.json`](../examples/banking-kyc-onboarding.json) | `kyc-periodic-review` ([`banking-kyc-periodic-review.json`](../examples/banking-kyc-periodic-review.json)) |
| Credit and loan approval | `credit-approval` | [`banking-credit-approval.json`](../examples/banking-credit-approval.json) | `credit-review-round` ([`banking-credit-review-round.json`](../examples/banking-credit-review-round.json)) |
| Card dispute claim | `card-dispute-claim` | [`banking-card-claim.json`](../examples/banking-card-claim.json) | `card-dispute` ([`banking-card-dispute.json`](../examples/banking-card-dispute.json)) |

Two connection definitions go with them in [`examples/connections`](../examples/connections): `sanctions-screening` (`screenParty`) and `core-banking` (`bookLoan`, `openChargeback`). Both operations are `idempotentWithKey`; the contract is what the test stub implements, not a real service.

Tests: `BankingWorkflowsTest` (42 tests) publishes these files through the public API and executes them against PostgreSQL and Flowable, with `BankingStub` (WireMock) as the external system. `contract_check.py` validates every file against the workflow and connection schemas.

## Publishing order

1. Register the connection definitions and bind them (an administrator; the binding's base URL is environment specific and not part of the definition).
2. Create the task groups below in the workspace and add members (an IdP group or named users).
3. Publish the **child** first, then the parent. The parent's `workflowRef` names the child by key; publishing pins the child's current release in the parent's manifest. A later republish of the child does not change a published parent.
4. If you import a child under a different key, change `workflowRef` in the parent.

The files are not registered as built-in templates; copying them into a workspace is a manual or API step.

## 1. KYC customer onboarding review

A relationship manager submits a new customer; reviewers decide. Illustrative only.

| Input | Type | Notes |
|---|---|---|
| `customerName` | text, 1 to 200 | Screened as given |
| `customerType` | `individual` or `corporate` | |
| `countryGroup` | `domestic`, `partner`, `elevated`, `restricted` | The compliance team's classification of the customer's country. The country list itself is maintained outside the workflow |
| `product` | `currentAccount`, `savingsAccount`, `businessLoan`, `tradeFinance` | |
| `expectedMonthlyVolume` | number, cents, 0 to 1,000,000,000 (USD) | |
| `politicallyExposed` | true/false | |

Flow: three rule tables rate the country (`country-policy`), the product (`product-policy`) and the volume (`volume-policy`, 1,000,000.00 or more is `large`). Then three checks run **in parallel**: identity verification (analysts), sanctions screening (a call to the screening service) and document completeness (document controllers). A rejection by either person ends the request and cancels the other task; a rejection always needs a reason. After the join, a branch sends the request to **compliance approval** when the country is high, the customer is politically exposed, the volume is large or screening reports a possible match. Compliance has a 24 hour time limit; nobody deciding escalates to the head of compliance (48 hours), and a second silence closes the request as `Expired`. A second branch sends medium-risk customers (elevated country, complex product or corporate) to a senior analyst; everyone else goes straight on. On approval the **first periodic review** runs as a pinned child workflow (a 90 day timer, then an analyst re-review), and the request completes as `Onboarded` when that review finishes.

| Group | Role in this template |
|---|---|
| `kyc-analysts` | Verify identity; re-review in the periodic review |
| `document-controllers` | Check documents are complete |
| `kyc-seniors` | Senior review of medium-risk customers |
| `compliance-officers` | Compliance approval of high-risk customers (24 hours) |
| `head-of-compliance` | Escalation (48 hours) |

Outcomes: `Onboarded` (success), `Rejected`, `Expired`. Outputs: `screeningReference`, `periodicReviewer`.

Limits you will meet: the country/customer/product combination is not one table (see the matrix), the periodic review interval is a fixed 90 days for every tier, the parent request stays open until the first review is done, and the screening service sees only what the mapping sends.

## 2. Credit and loan approval

An application for a loan in USD with cents. Illustrative only.

| Input | Type | Notes |
|---|---|---|
| `applicantReference` | text, 1 to 60 | A reference, not a name or document number |
| `product` | `personalLoan`, `smallBusinessLoan`, `overdraft` | |
| `amount` | number, cents, 0.01 to 10,000,000 (USD) | Decimal; no floating point is involved |
| `termMonths` | whole number, 1 to 360 | |

Flow: rule table `credit-limits` sets the level: under 5,000.00 is `auto`, 50,000.00 or more is `committee`, anything between is `manager`. `auto` goes straight to **booking**. Otherwise the child `credit-review-round` runs up to three times (bounded repetition): a credit manager approves, or rejects with a required reason, which sends the application back to the applicant. A round nobody decides in 72 hours escalates to a credit director. When the third round is also returned, the case is **referred to Operations**, who approve (override) or decline; a decline needs a reason. A review that passed goes on to the **credit committee** from 50,000.00. Booking is a call to the core system, made once. The requester can never decide any step of their own request, including the child rounds.

| Group | Role in this template |
|---|---|
| `credit-managers` | Review round |
| `credit-directors` | Escalation of an undecided round |
| `credit-committee` | Approval from 50,000.00 |
| `credit-operations` | Referred (exhausted) cases: override or decline |

Outcomes: `Approved` (success), `Declined`. Outputs: `bookingId`, `reviewRounds`.

Limits you will meet: the rework rounds reuse the original inputs, so an applicant's corrected figures cannot be fed back; the override approval does not require a reason (only a rejection does); the same person may approve several stages; the currency is USD only.

## 3. Card dispute claim

A claim for one card with up to five disputed transactions. Illustrative only.

| Input | Type | Notes |
|---|---|---|
| `claimReference` | text, 1 to 60 | |
| `cardToken` | text, 1 to 40 | A masked reference from the card system; never a card number |
| `reasonCode` | `fraud`, `merchantDispute`, `duplicateCharge`, `goodsNotReceived` | One reason for the whole claim |
| `transactions` | 1 to 5 transaction references | Text items |

Flow: the Claim starts one pinned `card-dispute` child per transaction, all at once (the example is `parallel`; set `forEach.mode` to `sequential` to run them one after another). In each dispute a fraud reason goes to the **fraud investigation** group, any other reason to the dispute analysts. An accepted dispute raises **one chargeback** in the card processor; a rejection needs a reason; a dispute nobody decides in 10 days lapses. The Claim finishes only when **every** dispute has finished, collects each outcome and reference in transaction order, and ends `Claim resolved` when all were accepted or `Claim has declined items` otherwise.

| Group | Role in this template |
|---|---|
| `fraud-investigators` | Disputes with reason `fraud` |
| `dispute-analysts` | All other disputes |

Outputs: `outcomeItems`, `referenceItems`, `allSucceeded`.

Limits you will meet: all transactions share one reason (items are scalars), the same transaction listed twice creates two disputes and two chargebacks, and there is no per-dispute SLA shown on the Claim.

## What the tests demonstrate

| Area | Evidence in `BankingWorkflowsTest` |
|---|---|
| Examples validate and publish with no issues | `everyBankingExampleValidatesAndPublishesWithoutBlockersOrUnexpectedWarnings` |
| Parallel checks, one screening, pinned child | `kycLowRiskRunsChecksInParallelScreensOnceAndCompletesAfterThePinnedPeriodicReview`, `kycPeriodicReviewIsPinnedToTheReleasePublishedWithTheParent` |
| Country, product, volume, customer type matrix | `kycJurisdictionProductAndVolumeMatrixSelectsTheExpectedReviewPath`, `kycVolumeRuleTableBoundariesUseExactDecimals` |
| Time limit, escalation, expiry | `kycHighRiskNeedsComplianceThenEscalates...`, `kycEscalationThatIsNotDecided...`, `creditManagerRoundThatNobodyDecidesEscalatesToADirector...`, `claimDisputeThatNobodyDecidesLapses...` |
| Rejection needs a reason | `kycRejectionNeedsAReason...`, `creditDeclineByOperationsNeedsAReason...` |
| Requester and approver separation | `kycRequesterCannotTakeTheirOwnTask...`, `creditRequesterCanNeverApproveTheirOwnRequest...`, `claimRequesterCannotDecideTheirOwnDisputes...` |
| Money at decimal boundaries | `creditRuleTableBoundariesAreExact`, `creditUnderTheThreshold...`, `creditAtTheThreshold...`, `creditFromTheHigherThreshold...`, `creditMoneyInputsAreDecimalExact...` |
| Bounded rework, exhaustion, referral | `creditReworkRoundsKeepEveryReturnReason...`, `creditReworkExhaustionReferredToOperations...` |
| Exactly one external effect | `assertOneBooking` in every credit path, retry with one key, permanent failure then operator retry |
| Collection of children, completion rule | `claimCompletesOnlyWhenEveryDisputeHasFinished...`, `claimWithEveryDisputeAccepted...`, `claimDisputesCanRunOneAfterAnother...` |
| Restart durability | `kycParallelChecksSurviveAnExecutorRestart`, `creditCaseSurvivesAnExecutorRestart...`, restart inside the claim test |
| Documented gaps (current behavior) | `gap_*` tests: they pass today and describe what a bank would need to be different |

## Running them

```
export JAVA_HOME="/c/Users/karup/.jdks/jdk-21.0.12.1+1"; export PATH="$JAVA_HOME/bin:$PATH"
cd backend && ./mvnw -B -pl app -am test -Dtest=BankingWorkflowsTest
python tools/contract-check/contract_check.py
```

The integration tests need Docker for the Testcontainers PostgreSQL.
