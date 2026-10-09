import { BaseEdge, EdgeLabelRenderer, useNodes, type Edge, type EdgeProps } from '@xyflow/react'
import { cn } from '@/lib/cn'
import { useStudioContext } from './StudioContext'
import { APPROVAL_HEIGHT, LABEL_MAX_WIDTH, NODE_HEIGHT, NODE_WIDTH } from './model/layout'
import type { RouteKind } from './model/types'

export interface RouteEdgeData extends Record<string, unknown> {
  on: RouteKind
  /** Route text such as "When Amount (USD) at least 5000" or "Otherwise". Empty for plain next-step routes. */
  text: string
}

export type RouteFlowEdge = Edge<RouteEdgeData, 'route'>

interface Point {
  x: number
  y: number
}
interface Rect {
  x: number
  y: number
  width: number
  height: number
}

/** How far a route runs beside its source before it turns, and how far from its target it turns on a detour. */
const STUB = 28
const MARGIN = 8
const LANE_GAP = 44

function hitsRect(a: Point, b: Point, r: Rect): boolean {
  const x1 = Math.min(a.x, b.x)
  const x2 = Math.max(a.x, b.x)
  const y1 = Math.min(a.y, b.y)
  const y2 = Math.max(a.y, b.y)
  return x2 > r.x - MARGIN && x1 < r.x + r.width + MARGIN && y2 > r.y - MARGIN && y1 < r.y + r.height + MARGIN
}

/** An orthogonal path through `points` with rounded corners. */
export function roundedPath(points: Point[], radius: number): string {
  const first = points[0]
  if (!first) return ''
  let d = `M ${first.x} ${first.y}`
  for (let i = 1; i < points.length - 1; i++) {
    const prev = points[i - 1] as Point
    const here = points[i] as Point
    const next = points[i + 1] as Point
    const inLen = Math.hypot(here.x - prev.x, here.y - prev.y)
    const outLen = Math.hypot(next.x - here.x, next.y - here.y)
    const r = Math.min(radius, inLen / 2, outLen / 2)
    if (r < 1) {
      d += ` L ${here.x} ${here.y}`
      continue
    }
    const before = { x: here.x - ((here.x - prev.x) / inLen) * r, y: here.y - ((here.y - prev.y) / inLen) * r }
    const after = { x: here.x + ((next.x - here.x) / outLen) * r, y: here.y + ((next.y - here.y) / outLen) * r }
    d += ` L ${before.x} ${before.y} Q ${here.x} ${here.y} ${after.x} ${after.y}`
  }
  const last = points[points.length - 1] as Point
  return `${d} L ${last.x} ${last.y}`
}

export interface RouteGeometry {
  path: string
  /** Where the label pill is centred. */
  label: Point
  /** True for a detour below (or above) the steps, used when a direct route would cross another step. */
  detour: boolean
}

/**
 * Orthogonal route from a source handle to a target handle. It leaves the source, turns beside it, and runs into the
 * target along the target's row. When that would cross another step (a long route such as a shared Rejected end), it
 * takes a lane below the steps instead, so the line never runs through a card.
 */
export function routeGeometry(source: Point, target: Point, others: Rect[], labelWidth: number, laneShift = 0): RouteGeometry {
  const turnX = source.x + STUB
  const direct: Point[] = [source, { x: turnX, y: source.y }, { x: turnX, y: target.y }, target]
  const forward = target.x - source.x > STUB * 2
  const blocked = !forward || others.some((r) => hitsRect(direct[1] as Point, direct[2] as Point, r) || hitsRect(direct[2] as Point, direct[3] as Point, r))
  if (!blocked) {
    const run = target.x - turnX
    const half = labelWidth / 2
    // Right-aligned in the final run, clear of the turn and the arrowhead.
    const centre = run >= labelWidth + 48 ? target.x - 28 - half : turnX + run / 2
    return { path: roundedPath(direct, 14), label: { x: centre, y: target.y }, detour: false }
  }
  const left = Math.min(source.x, target.x)
  const right = Math.max(source.x, target.x)
  const spanned = others.filter((r) => r.x + r.width > left && r.x < right)
  const bottoms = [source.y, target.y, ...spanned.map((r) => r.y + r.height)]
  const laneY = Math.max(...bottoms) + LANE_GAP + laneShift
  const exit = source.x + STUB
  const enter = target.x - STUB
  const points: Point[] = [source, { x: exit, y: source.y }, { x: exit, y: laneY }, { x: enter, y: laneY }, { x: enter, y: target.y }, target]
  return { path: roundedPath(points, 16), label: { x: (exit + enter) / 2, y: laneY }, detour: true }
}

const TEXT_CLASS: Record<RouteKind, string> = {
  approved: 'text-edge-approved',
  rejected: 'text-edge-rejected',
  condition: 'text-edge-condition',
  default: 'text-foreground',
  next: 'text-foreground',
}

/**
 * Workflow connection (docs/build/S7-frontend.md section 5): orthogonal, rounded, colored by route, with the route
 * text in a pill that has its own background and sits on a straight stretch of the line, never on a bend.
 * Approved and Rejected are printed on the approval card next to their handles, so their pills only appear on long
 * detours and on the selected connection.
 */
export function RouteEdge({ id, source, target, sourceX, sourceY, targetX, targetY, data, selected, markerEnd, style, interactionWidth }: EdgeProps<RouteFlowEdge>) {
  const { store } = useStudioContext()
  const nodes = useNodes()
  const on = data?.on ?? 'next'
  const text = data?.text ?? ''
  const others: Rect[] = nodes
    .filter((n) => n.id !== source && n.id !== target)
    .map((n) => ({
      x: n.position.x,
      y: n.position.y,
      width: n.measured?.width ?? NODE_WIDTH,
      height: n.measured?.height ?? (n.data?.kind === 'approval' ? APPROVAL_HEIGHT : NODE_HEIGHT),
    }))
  const width = Math.min(LABEL_MAX_WIDTH, Math.round(text.length * 7.8) + 28)
  // Detours of different routes take lanes a few steps apart, so two of them never run on top of each other.
  const laneShift = ([...id].reduce((sum, ch) => sum + ch.charCodeAt(0), 0) % 3) * 22
  const geometry = routeGeometry({ x: sourceX, y: sourceY }, { x: targetX, y: targetY }, others, width, laneShift)
  const showLabel = text !== '' && (on !== 'approved' && on !== 'rejected' ? true : geometry.detour || !!selected)
  return (
    <>
      <BaseEdge id={id} path={geometry.path} markerEnd={markerEnd} style={style} interactionWidth={interactionWidth} />
      {showLabel ? (
        <EdgeLabelRenderer>
          <button
            type="button"
            tabIndex={-1}
            aria-hidden="true"
            title={text}
            onClick={() => store.getState().select({ type: 'edge', id })}
            className={cn(
              'nodrag nopan absolute max-w-48 cursor-pointer truncate rounded-full border border-border bg-surface px-2.5 py-0.5 text-sm leading-5 font-semibold shadow-card',
              TEXT_CLASS[on],
              selected && 'border-primary',
            )}
            style={{ transform: `translate(-50%, -50%) translate(${geometry.label.x}px, ${geometry.label.y}px)`, pointerEvents: 'all' }}
          >
            {text}
          </button>
        </EdgeLabelRenderer>
      ) : null}
    </>
  )
}
