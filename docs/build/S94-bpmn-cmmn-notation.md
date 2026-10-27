# S94 - BPMN and CMMN notation on the canvas (STU01)

Date 2026-10-26. Branch `build/stu01-notation`, cut from `main` 57a8d7e. Frontend only: no backend change, no migration, no Docker container, volume or image touched. Plan and mapping: [docs/25 section 1](../25-studio-and-integration-roadmap.md#1-stu01---bpmn-and-cmmn-notation-on-the-canvas).

## What was delivered

- **BPMN 2.0 shapes for all 19 step kinds** on the Studio canvas and the run diagram ("Where is my request"), exactly per the docs/25 mapping table. The mapping is one record, `frontend/src/features/studio/model/notation.ts` (`BPMN_NOTATION`: shape, size class, BPMN element, symbol in words). Shapes are SVG (`notation/BpmnGlyph.tsx`), the step is `notation/BpmnStepNode.tsx`; `StepNode` draws it when `data.notation === 'bpmn'` and otherwise the unchanged card.
  - Events: thin circle with play marker (Request form); thick circle (Finish); thick circle with lightning (Raise an error); double circle with clock / envelope / outlined triangle (Wait, Wait for a message, Wait for a signal); double circle with filled triangle (Send a signal) and filled double arrow (Undo completed calls).
  - Gateways: diamond with X (Choose a path), diamond with plus (Run in parallel, Wait for all).
  - Tasks: rounded rectangle with a type icon - person (approval, user task), table (decision, business rule task), gear (call a system), filled envelope (notify, send through an adapter: send task), page (generate a document), gear and arrows (external worker); thick-bordered rounded rectangle with a [+] marker (Start another workflow, call activity).
  - Sections and handlers keep the existing frames (sub-process, event sub-process); the legend says so.
- **One shared size table** `frontend/src/features/studio/model/shapeSizes.ts` (`SHAPE_SIZES`): events 48x48, gateways 64x64, tasks and call activities 200x80. STU04 (diagram interchange) must use this constant.
- **Saved positions keep their meaning**: a position is the top-left of the step's bounding box in both notations. The node element is exactly the bounding box, so React Flow's left/right/top/bottom handles sit on the circle's and diamond's extreme points (left vertex, right vertex, bottom point). Handle ids are unchanged (`in`, `next`, `approved`, `rejected`, `failed`, `timedOut`, `route`).
- **Labels**: tasks print the kind caption and the step name inside; events and gateways print the name and the kind caption (Finish: the outcome with its icon) beneath the shape, on a canvas-coloured backing; above the shape when a Timed out route leaves from the bottom. Approved, Rejected and Failed routes are printed on their lines in BPMN notation (the task keeps a check / cross icon beside each handle); the run diagram does the same for outcome routes.
- **Notation toggle** "BPMN shapes / Cards" (`notation/NotationControls.tsx`) on the Studio canvas (also read-only canvases) and above the run diagram; default BPMN shapes. Remembered per person in localStorage under `orqix.canvasNotation.v1:<subject id>`, every access in try/catch; if storage is blocked the choice lasts for the page view. Switching refits the view and never moves a step; if the layout overlaps in the new notation an announcement suggests Auto-layout.
- **Collapsible legend** "BPMN symbols" (closed by default): one row per symbol with the step kinds using it and the BPMN element, plus the section/handler frames.
- **Layout**: `nodeSize(kind, notation)`, `needsAutoLayout`, `hasPoorLayout` and `computeLayout` take the notation. BPMN layouts give ELK the shape sizes plus an outside label (168x56) under events and gateways and a wider layer gap. Collision checks for placing new steps (`collides`, `findFreePosition`, `placementFor`, used by the store) keep the larger card footprint, so a placement is free in both notations.
- **CMMN 1.1 notation for cases**: the case model designer is a form (a tree of plan items), not a canvas, so a **read-only CMMN preview** was added beneath it (`features/cases/CmmnPreview.tsx`) that follows every edit: case plan model as a folder with the case name on its tab; stage as a rounded rectangle with a collapse marker (minus, shown expanded); human task as a rounded rectangle with a person icon; milestone as a stadium; user event as a double circle with a person icon; entry criteria as small hollow diamonds on the left edge; manual activation (play), required (!) and repetition (#) markers at the bottom. Each item also prints its kind, markers and entry condition in words; there is a collapsed "CMMN symbols" legend.
- Small fixes found on the way: the run diagram drew a failed call's route from a non-existent `rejected` handle (now `failed`, as in the studio); the Timed out handle carried `aria-label` on a role-less element (axe `aria-prohibited-attr`, serious) in the card and the shape, now `aria-hidden` with a `title` like every other handle; the legend's scroll area is focusable (axe `scrollable-region-focusable`).

## Accessibility

Every step keeps React Flow's focusable wrapper with its accessible name (for example "Choose a path: Which path?"), the 3 px focus ring (round for events), and the inspector, Steps tab and run diagram step list as non-canvas alternatives. Shape and colour never carry meaning alone: the kind caption, the name, the outcome and the route words are printed; selection adds a thicker outline. Toggle buttons use `aria-pressed` in a labelled group.

## Verification

| Check | Result |
|---|---|
| `npx tsc -b --noEmit` | pass |
| `npm run api:check` | pass (schema matches contract) |
| `npx eslint .` | 1 error, **pre-existing and untouched**: `src/mocks/decisionCsv.ts:139` irregular whitespace (not in this change); all changed files clean |
| Full frontend suite `npx vitest run` | **148 files, 1111 tests passed** |
| New tests | `model/notation.test.ts` (every kind mapped, none unmapped, docs/25 table row by row, shared sizes, saved card layouts stay valid, BPMN layout without overlaps); `notation/BpmnStepNode.test.tsx` (each of the 19 kinds: shape, bounding box, text, decorative SVG; handle ids and sides; outcome and issues in words; selection; cards unchanged); `notation/NotationCanvas.test.tsx` (default BPMN, toggle, persistence per person, blocked storage, read-only toggle without moving positions, jsdom axe, legend lists every kind once); `cases/CmmnPreview.test.tsx` (shapes, markers, sentries, words, legend, jsdom axe) |
| Existing tests | `StepNode.outcomes`, `ScopeFrames`, `layout`, `RouteEdge`, `RunDiagram`, `CaseModelDesigner`, `StudioPage` pass unchanged |
| Real browser (Chromium 1243 via Playwright, mock-mode Vite dev server, 1920x1080) | see below; script `evidence/stu01/capture-evidence.cjs`, log `evidence/stu01/results.txt` |

Browser results (from `results.txt`): all 19 kinds drawn with the mapped shape at 48/64/200x80 (scaled by the 0.85 zoom); axe-core (all rules including colour contrast) **no violations** on the canvas with every kind, the open legend, the four example workflows (purchase approval, new hire onboarding, order payment wait, loan case with stages), the run diagram and the CMMN preview; focus on the gateway gives a solid 3 px ring and the accessible name; click selects; drag moves an event (undoable); connect from an event's right handle to a gateway's left vertex adds a route (undoable); Delete asks for confirmation and removes the step, Ctrl+Z restores it; Cards persists across a reload under the per-person key.

Screenshots in `evidence/stu01/`: `01-all-kinds-bpmn-fit.png`, `02a-all-kinds-zoom-start.png`, `02b-all-kinds-zoom-parallel.png`, `02c-all-kinds-zoom-end.png`, `03-legend-open.png`, `04-focus-ring-gateway.png`, `05-all-kinds-cards.png`, `06-example-purchase-approval-bpmn.png`, `07-example-new-hire-onboarding-bpmn.png`, `08-example-order-payment-wait-bpmn.png`, `09-example-case-with-stages-bpmn.png`, `10-run-diagram-bpmn.png`, `11-run-diagram-cards.png`, `12-cmmn-preview.png`.

## Decisions for the user

1. **Saved card layouts in BPMN**: positions are top-left, so a row laid out for 296 px cards shows small vertical jogs between shapes of different heights (visible on the run diagram). Auto-layout fixes it in the studio; nothing is re-laid out automatically unless the layout overlaps.
2. **Switching notation can trigger the one-time arrange on open**: a layout made in BPMN (compact) overlaps as cards, so opening the studio in Cards arranges it once (as today for any overlapping layout, counted as saved until the next edit). Toggling inside an open studio never moves steps.
3. **Intermediate events are double circles** (BPMN-correct) where docs/25 says "circle"; the start marker is a play triangle as docs/25 asks (not standard BPMN, which has no marker on a none start event).
4. **CMMN stage drawn as a rounded rectangle** as the brief asks; CMMN 1.1 strictly draws stages with angled (octagonal) corners. Easy to switch if STD01 wants strict conformance.
5. **Approved/Rejected text moves onto the lines** in BPMN notation (the 200x80 task has no room for the card's chips).

## Not covered

- No CMMN canvas (the designer stays form-based; the preview is read-only and has no connectors drawn between sentries and their sources - the entry condition is written instead).
- Non-text contrast (WCAG 1.4.11) of SVG strokes is not computed by axe; strokes use the existing kind and status tokens (dark, text-grade colours) and were checked by eye only.
- The step label beneath a small event can overlap a neighbour's label in very dense manual layouts; ELK layouts reserve the room.
- Not checked against the live stack, other browsers, forced-colours mode, or a screen reader; no e2e spec was added.
- BPMN diagram interchange (STU04) and the conformance statement (STD01) are separate items.
