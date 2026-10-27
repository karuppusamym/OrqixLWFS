# S100 - Studio rehearsal on the demo-1028 stack

Real browser (the app's built-in pane) against `http://localhost:41080`, an isolated stack built from `main` at `0c8a4a2`, signed in as `dana.designer` with the documented development credentials.

| Screen | Result |
|---|---|
| Studio canvas, notation toggle "BPMN shapes" / "Cards" and the "BPMN symbols" legend | Shown. Start is a thin circle, approval and decision steps are task and decision shapes. |
| Left panel "Change this draft with AI" (instruction box, Send, Regenerate from scratch, collapsible) | Shown beside the step palette. Sending needs an AI key; not exercised. |
| Header buttons Explain, Preview form, Test workflow, Export, Check workflow, Publish | Shown. |
| Tab "Test" with sample request form and "What this test cannot show" | Opens beside the canvas. A full run was not exercised in this pass. |
| Settings tabs | Connections, Webhooks, Kafka, AI, Document templates, Shared assets (names confirmed for the user guide). |
| Browser console | One 401 on a resource before sign-in; no other errors. |

## Not covered in this pass

- No accessibility (axe) scan was run on these screens in the real browser; the Playwright/axe suite covers earlier screens only.
- The run-page callback panel, a webhook delivery and a Kafka event were not driven end to end here (covered by the automated tests in S98 and S99).
- The Cases notice was reworded after this stack was built, so 41080 still shows the old wording until the image is rebuilt.
