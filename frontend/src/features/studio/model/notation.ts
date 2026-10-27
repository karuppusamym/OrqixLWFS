import { SHAPE_SIZES, type ShapeClass } from './shapeSizes'
import type { NodeKind } from './types'

/**
 * BPMN 2.0 notation of each step kind on the canvas and the run diagram (STU01). The table is the one in
 * docs/25-studio-and-integration-roadmap.md section 1, which is also the conformance record for STD01. Shapes are
 * decoration of the step: the step's accessible name, its label and its kind caption are always printed as text too.
 */
export type Notation = 'bpmn' | 'cards'

export type BpmnShape =
  | 'startEvent'
  | 'endEvent'
  | 'errorEndEvent'
  | 'timerCatchEvent'
  | 'messageCatchEvent'
  | 'signalCatchEvent'
  | 'signalThrowEvent'
  | 'compensationThrowEvent'
  | 'exclusiveGateway'
  | 'parallelGateway'
  | 'userTask'
  | 'businessRuleTask'
  | 'serviceTask'
  | 'sendTask'
  | 'documentTask'
  | 'externalServiceTask'
  | 'callActivity'

export interface BpmnNotation {
  shape: BpmnShape
  /** Bounding box class: SHAPE_SIZES[sizeClass]. */
  sizeClass: ShapeClass
  /** The BPMN 2.0 element the step compiles to. */
  element: string
  /** The symbol in words, for the legend. */
  symbol: string
}

export const BPMN_NOTATION: Record<NodeKind, BpmnNotation> = {
  start: { shape: 'startEvent', sizeClass: 'event', element: 'none start event', symbol: 'Thin circle' },
  approval: { shape: 'userTask', sizeClass: 'task', element: 'user task', symbol: 'Rounded rectangle with a person icon' },
  decision: { shape: 'businessRuleTask', sizeClass: 'task', element: 'business rule task', symbol: 'Rounded rectangle with a table icon' },
  branch: { shape: 'exclusiveGateway', sizeClass: 'gateway', element: 'exclusive gateway', symbol: 'Diamond with an X' },
  parallelSplit: { shape: 'parallelGateway', sizeClass: 'gateway', element: 'parallel gateway (split)', symbol: 'Diamond with a plus' },
  parallelJoin: { shape: 'parallelGateway', sizeClass: 'gateway', element: 'parallel gateway (join)', symbol: 'Diamond with a plus' },
  http: { shape: 'serviceTask', sizeClass: 'task', element: 'service task', symbol: 'Rounded rectangle with a gear' },
  notification: { shape: 'sendTask', sizeClass: 'task', element: 'send task', symbol: 'Rounded rectangle with an envelope' },
  sendAdapter: { shape: 'sendTask', sizeClass: 'task', element: 'send task', symbol: 'Rounded rectangle with an envelope' },
  generateDocument: { shape: 'documentTask', sizeClass: 'task', element: 'service task', symbol: 'Rounded rectangle with a page icon' },
  externalTask: { shape: 'externalServiceTask', sizeClass: 'task', element: 'external service task', symbol: 'Rounded rectangle with a gear and arrows' },
  wait: { shape: 'timerCatchEvent', sizeClass: 'event', element: 'intermediate timer catch event', symbol: 'Double circle with a clock' },
  waitForMessage: { shape: 'messageCatchEvent', sizeClass: 'event', element: 'intermediate message catch event', symbol: 'Double circle with an envelope' },
  waitForSignal: { shape: 'signalCatchEvent', sizeClass: 'event', element: 'intermediate signal catch event', symbol: 'Double circle with an outlined triangle' },
  sendSignal: { shape: 'signalThrowEvent', sizeClass: 'event', element: 'intermediate signal throw event', symbol: 'Double circle with a filled triangle' },
  raiseError: { shape: 'errorEndEvent', sizeClass: 'event', element: 'error end event', symbol: 'Thick circle with a lightning bolt' },
  compensate: { shape: 'compensationThrowEvent', sizeClass: 'event', element: 'intermediate compensation throw event', symbol: 'Double circle with a filled double arrow' },
  startWorkflow: { shape: 'callActivity', sizeClass: 'task', element: 'call activity', symbol: 'Thick-bordered rounded rectangle with a plus' },
  end: { shape: 'endEvent', sizeClass: 'event', element: 'end event', symbol: 'Thick circle' },
}

/** Canvas bounding box of a step in BPMN notation (top-left is the saved position). */
export function bpmnSize(kind: NodeKind): { width: number; height: number } {
  const size = SHAPE_SIZES[BPMN_NOTATION[kind].sizeClass]
  return { width: size.width, height: size.height }
}

/** Events and gateways print their label beneath the shape; ELK reserves this much room for it. */
export const OUTSIDE_LABEL = { width: 168, height: 56 } as const

export function labelOutside(kind: NodeKind): boolean {
  return BPMN_NOTATION[kind].sizeClass !== 'task'
}
