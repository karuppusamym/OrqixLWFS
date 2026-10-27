import { ArrowLeftRight, FileText, Mail, Settings, Table2, User, type LucideIcon } from 'lucide-react'
import { SHAPE_SIZES } from '../model/shapeSizes'
import type { BpmnShape } from '../model/notation'
import { BPMN_NOTATION } from '../model/notation'
import type { NodeKind } from '../model/types'

/** Type icon in the top-left corner of a task (BPMN task markers). */
export const TASK_ICON: Partial<Record<BpmnShape, LucideIcon>> = {
  userTask: User,
  businessRuleTask: Table2,
  serviceTask: Settings,
  sendTask: Mail,
  documentTask: FileText,
  externalServiceTask: Settings,
}

export interface GlyphColors {
  stroke: string
  fill: string
  marker: string
}

interface Props {
  shape: BpmnShape
  colors: GlyphColors
  /** Drawn size; the drawing always uses the shape's own bounding box as its viewBox. */
  width?: number
  height?: number
  className?: string
  /** Thicker outline (selected, current step). */
  emphasis?: boolean
}

/**
 * The BPMN 2.0 symbol of a step, as an SVG in the step's bounding box (SHAPE_SIZES). Decorative: the step around it
 * carries the accessible name, and the label and kind caption are printed as text.
 */
export function BpmnGlyph({ shape, colors, width, height, className, emphasis }: Props) {
  const box = SHAPE_SIZES[sizeClassOf(shape)]
  const w = box.width
  const h = box.height
  const extra = emphasis ? 1.5 : 0
  return (
    <svg
      viewBox={`0 0 ${w} ${h}`}
      width={width ?? w}
      height={height ?? h}
      className={className}
      aria-hidden="true"
      focusable="false"
      overflow="visible"
      data-glyph={shape}
    >
      {body(shape, w, h, colors, extra)}
    </svg>
  )
}

function sizeClassOf(shape: BpmnShape) {
  const kind = (Object.keys(BPMN_NOTATION) as NodeKind[]).find((k) => BPMN_NOTATION[k].shape === shape) as NodeKind
  return BPMN_NOTATION[kind].sizeClass
}

function body(shape: BpmnShape, w: number, h: number, c: GlyphColors, extra: number) {
  const cx = w / 2
  const cy = h / 2
  const outline = { className: 'bpmn-outline', stroke: c.stroke, fill: c.fill } as const
  switch (shape) {
    case 'startEvent':
      return (
        <circle {...outline} cx={cx} cy={cy} r={22} strokeWidth={2 + extra} />
      )
    case 'endEvent':
      return <circle {...outline} cx={cx} cy={cy} r={20.5} strokeWidth={5 + extra} />
    case 'errorEndEvent':
      return (
        <>
          <circle {...outline} cx={cx} cy={cy} r={20.5} strokeWidth={5 + extra} />
          <polygon points="14,34 20,16 26,27 34,14 28,32 22,22" fill={c.marker} stroke={c.marker} strokeWidth={1} strokeLinejoin="round" />
        </>
      )
    case 'timerCatchEvent':
    case 'messageCatchEvent':
    case 'signalCatchEvent':
    case 'signalThrowEvent':
    case 'compensationThrowEvent':
      return (
        <>
          <circle {...outline} cx={cx} cy={cy} r={22.5} strokeWidth={1.75 + extra} />
          <circle className="bpmn-outline" cx={cx} cy={cy} r={19} fill="none" stroke={c.stroke} strokeWidth={1.75 + extra} />
          {intermediateMarker(shape, c.marker)}
        </>
      )
    case 'exclusiveGateway':
    case 'parallelGateway':
      return (
        <>
          <polygon {...outline} points={`${cx},2 ${w - 2},${cy} ${cx},${h - 2} 2,${cy}`} strokeWidth={2 + extra} strokeLinejoin="round" />
          {shape === 'exclusiveGateway' ? (
            <path d={`M ${cx - 10} ${cy - 10} L ${cx + 10} ${cy + 10} M ${cx + 10} ${cy - 10} L ${cx - 10} ${cy + 10}`} stroke={c.marker} strokeWidth={5} strokeLinecap="round" />
          ) : (
            <path d={`M ${cx} ${cy - 14} L ${cx} ${cy + 14} M ${cx - 14} ${cy} L ${cx + 14} ${cy}`} stroke={c.marker} strokeWidth={5} strokeLinecap="round" />
          )}
        </>
      )
    case 'callActivity':
      return (
        <>
          <rect {...outline} x={2.5} y={2.5} width={w - 5} height={h - 5} rx={10} strokeWidth={5 + extra} />
          <rect x={cx - 8} y={h - 22} width={16} height={16} rx={2} fill="none" stroke={c.marker} strokeWidth={1.75} />
          <path d={`M ${cx} ${h - 19} L ${cx} ${h - 9} M ${cx - 5} ${h - 14} L ${cx + 5} ${h - 14}`} stroke={c.marker} strokeWidth={1.75} strokeLinecap="round" />
        </>
      )
    default: {
      const Icon = TASK_ICON[shape]
      return (
        <>
          <rect {...outline} x={1} y={1} width={w - 2} height={h - 2} rx={10} strokeWidth={2 + extra} />
          {/* A send task's envelope is filled (BPMN "throw" marker): a filled envelope with light flap lines. */}
          {Icon ? <Icon x={8} y={7} width={18} height={18} color={shape === 'sendTask' ? c.fill : c.marker} strokeWidth={shape === 'sendTask' ? 1.75 : 2.25} fill={shape === 'sendTask' ? c.marker : 'none'} /> : null}
          {shape === 'externalServiceTask' ? <ArrowLeftRight x={26} y={11} width={14} height={14} color={c.marker} strokeWidth={2.5} /> : null}
        </>
      )
    }
  }
}

function intermediateMarker(shape: BpmnShape, color: string) {
  switch (shape) {
    case 'timerCatchEvent':
      return (
        <>
          <circle cx={24} cy={24} r={11} fill="none" stroke={color} strokeWidth={1.75} />
          <path d="M 24 16 L 24 24 L 30 27" fill="none" stroke={color} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
        </>
      )
    case 'messageCatchEvent':
      return (
        <>
          <rect x={14} y={17} width={20} height={14} fill="none" stroke={color} strokeWidth={1.75} />
          <path d="M 14 17 L 24 25 L 34 17" fill="none" stroke={color} strokeWidth={1.75} strokeLinejoin="round" />
        </>
      )
    case 'signalCatchEvent':
      return <polygon points="24,13 34,31 14,31" fill="none" stroke={color} strokeWidth={1.75} strokeLinejoin="round" />
    case 'signalThrowEvent':
      return <polygon points="24,13 34,31 14,31" fill={color} stroke={color} strokeWidth={1.75} strokeLinejoin="round" />
    case 'compensationThrowEvent':
      return <path d="M 13 24 L 23 17 L 23 31 Z M 23 24 L 33 17 L 33 31 Z" fill={color} stroke={color} strokeWidth={1} strokeLinejoin="round" />
    default:
      return null
  }
}
