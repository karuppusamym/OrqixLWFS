# Minimum Pega migration demo

Scope is accepted under [ADR 0003](adr/0003-minimum-demo-pega-scope.md). No customer Pega export, native Pega runtime or external credentials are required. The fixtures are synthetic; this demonstrates supported target conversion and execution, not native RAP rule equivalence.

## Before presenting

- Build and run one identified commit of the application, identity service and database, including the XPDL source-format migration (currently V48). Use an isolated demo stack; do not delete an existing user's volumes.
- Check sign-in for `dana.designer`, `paul.publisher`, `rhea.requester` and `mani.manager`.
- Use the real backend. The frontend mock backend intentionally rejects Pega conversion.
- Rehearse the cases below in the browser and record pass/fail against that commit/image. Existing focused tests are evidence for the implementation, not this final merged-stack rehearsal.

## Automatic flow conversion

As `dana.designer`, open Workflows -> Import workflow and select **Pega flow export (XPDL 2.0)**. Upload a fixture from `examples/migration/pega-xpdl/`. Give each conversion a unique lowercase workflow key. Show the compatibility report, source paths and metadata exclusions, acknowledge reviewed exclusions, and create the draft. No canonical target definition or manual archive-mapping package is uploaded.

| Fixture | Required review/mapping | Request and expected outcome |
|---|---|---|
| `small.xpdl` | Review the metadata exclusion | Empty input -> Done |
| `medium.xpdl` | Review the inferred Amount question and XOR/Otherwise paths | Amount 4,999.99 -> Standard; 5,000 and 5,000.01 -> Finance |
| `review-branch.xpdl` | Map `Managers` to `managers`; set the approval routes below | Amount 5,000, manager approves -> Finance; another request rejected by the manager -> Rejected |

Approval routes for `review-branch.xpdl`:

```json
{"review":{"approvedFlowId":"approvedPath","rejectedFlowId":"rejectedPath"}}
```

As `paul.publisher`, complete any configured independent migration review and publish the exact draft, acknowledging warnings. As `rhea.requester`, submit the requests. For the review workflow, `mani.manager` claims and approves/rejects. Show the resulting run diagram, outcome and timeline. Do not bypass a configured publication review to keep the presentation moving.

## Visible unsupported behavior

Upload `unsupported-trigger.xpdl` using the same source format. Show the source-linked event blocker: a message-triggered start is outside this adapter's supported grammar. No workflow should be created. This is an intentional refusal, not a demo failure.

## Blueprint PDF with a reviewed transcription (synthetic)

Select **Pega Blueprint (PDF)** and upload `examples/migration/pega-blueprint/synthetic-blueprint.pdf`. Say first that this PDF is a synthetic outline written for Orqix, not a Pega export, and that the reader is generic: compatibility with Pega's own Blueprint layout is not claimed until a real sample is supplied. Show each transcribed element next to the text of its source page and the findings: the Finance approval SLA has no unit, the purchase-order integration has no target and is not converted, title lines were not recognised. Set the unit to hours, exclude the integration with a reason, mark the remaining elements reviewed and save (revision 2). Map **Line Manager** to Managers and **Finance Officer** to Finance approvers, acknowledge, and convert. Disclose that the transcription was reviewed by a person; that review is mandatory. A second person approves the migration review and publishes; a 6,000 request goes to the manager and then finance, a 4,999.99 request finishes after the manager. Optionally upload a PDF with no outline to show that it produces findings and no workflow. [S91](build/S91-pega-blueprint-pdf.md) lists the exact subset.

## Optional archive assessment

Upload an existing synthetic Pega ZIP from `examples/migration/pega-synthetic/` as **Pega application archive**. Explain that arbitrary native rule pages receive mapping blockers; they are not automatically executed or silently omitted. The checksum-linked reviewed reconstruction path and its 105-check acceptance are documented in [S39](build/S39-pega-synthetic-acceptance.md). Running this full acceptance harness is optional for the short live walkthrough.

## Presenter wording and remaining gates

Use: "Automatic conversion works for the supported XPDL flow-export subset. A blueprint PDF becomes a reviewed transcription that converts within a stated subset. Native archive rules can be assessed and explicitly reconstructed. These examples are synthetic."

Do not claim automatic conversion of every Pega rule, a certified Pega source version or source-runtime equivalence. MIG11 is deferred future native-RAP engineering; genuine source qualification is AC01/EXT01. Neither is a prerequisite for this minimum demo. Deployment of the candidate and the real-browser rehearsal above remain pending until actually executed.
