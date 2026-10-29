import sizes from '../../../../../contracts/bpmn-shape-sizes.json'

/**
 * BPMN shape bounding boxes on the canvas (STU01, docs/25-studio-and-integration-roadmap.md section 1).
 *
 * The numbers live in contracts/bpmn-shape-sizes.json, which the BPMN export (STU04, diagram interchange) reads too
 * (S106), so a downloaded file opens laid out exactly as the canvas draws it. A step's saved position is the top-left
 * corner of its bounding box in both notations, so saved diagrams stay valid when the notation changes.
 *
 * - events (circles): 48 x 48
 * - gateways (diamonds): 64 x 64
 * - tasks and call activities (rounded rectangles): 200 x 80
 */
export const SHAPE_SIZES = {
  event: { width: sizes.event.width, height: sizes.event.height },
  gateway: { width: sizes.gateway.width, height: sizes.gateway.height },
  task: { width: sizes.task.width, height: sizes.task.height },
} as const

export type ShapeClass = keyof typeof SHAPE_SIZES
