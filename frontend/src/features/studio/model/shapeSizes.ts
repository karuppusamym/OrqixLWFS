/**
 * BPMN shape bounding boxes on the canvas (STU01, docs/25-studio-and-integration-roadmap.md section 1).
 *
 * This is the ONE place these numbers live. The BPMN export (STU04, diagram interchange) uses the same sizes for
 * `dc:Bounds`, so a downloaded file opens laid out exactly as the canvas draws it. A step's saved position is the
 * top-left corner of its bounding box in both notations, so saved diagrams stay valid when the notation changes.
 *
 * - events (circles): 48 x 48
 * - gateways (diamonds): 64 x 64
 * - tasks and call activities (rounded rectangles): 200 x 80
 */
export const SHAPE_SIZES = {
  event: { width: 48, height: 48 },
  gateway: { width: 64, height: 64 },
  task: { width: 200, height: 80 },
} as const

export type ShapeClass = keyof typeof SHAPE_SIZES
