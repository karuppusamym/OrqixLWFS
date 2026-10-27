import { bpmnSize, labelOutside, OUTSIDE_LABEL, type Notation } from './notation'
import type { Definition, NodeKind, Position, Transition } from './types'

/**
 * Canvas geometry and automatic layout.
 *
 * Step cards are 296 px wide (approval cards are taller because they carry two labelled route handles). The sizes
 * here are what the layout and the placement of new steps assume; the cards in StepNode.tsx are drawn to match.
 *
 * Auto-layout uses ELK (layered, left to right). The library is loaded on demand the first time a layout is needed, so
 * the studio does not pay for it until then. It runs on the main thread (the bundled build): a Web Worker created from
 * a blob would be blocked by the platform CSP (`script-src 'self'`, docs/build/S6-security.md section 6).
 */
export const NODE_WIDTH = 296
export const NODE_HEIGHT = 128
export const APPROVAL_HEIGHT = 160
export const GAP_X = 96
export const GAP_Y = 56

/** Canvas footprint of the step card. The BPMN shapes (STU01) are smaller and use `nodeSize(kind, 'bpmn')`. */
export function cardSize(kind: NodeKind): { width: number; height: number } {
  return { width: NODE_WIDTH, height: kind === 'approval' ? APPROVAL_HEIGHT : NODE_HEIGHT }
}

/**
 * Bounding box of a step in a notation. Collision checks and the placement of new steps (`collides`,
 * `findFreePosition`, `placementFor`) always use the larger card footprint, so a placement is free in both notations.
 */
export function nodeSize(kind: NodeKind, notation: Notation = 'cards'): { width: number; height: number } {
  return notation === 'bpmn' ? bpmnSize(kind) : cardSize(kind)
}

/** Layer spacing for BPMN layouts: room for the labels printed beneath events and gateways. */
const BPMN_GAP_X = 128

interface Rect {
  x: number
  y: number
  width: number
  height: number
}

function rectOf(def: Definition, id: string, kind: NodeKind, pos?: Position, notation: Notation = 'cards'): Rect | null {
  const p = pos ?? def.uiLayout?.positions[id]
  return p ? { x: p.x, y: p.y, ...nodeSize(kind, notation) } : null
}

function intersects(a: Rect, b: Rect, margin: number): boolean {
  return a.x < b.x + b.width + margin && b.x < a.x + a.width + margin && a.y < b.y + b.height + margin && b.y < a.y + a.height + margin
}

/** True when `pos` for a step of `kind` would sit on top of (or touch) another step. */
export function collides(def: Definition, pos: Position, kind: NodeKind, ignoreId?: string): boolean {
  const mine: Rect = { x: pos.x, y: pos.y, ...nodeSize(kind) }
  return def.nodes.some((n) => {
    if (n.id === ignoreId) return false
    const other = rectOf(def, n.id, n.type)
    return other !== null && intersects(mine, other, 16)
  })
}

/** First free spot at or below `desired`; when a whole column is taken, the next column to the right. */
export function findFreePosition(def: Definition, desired: Position, kind: NodeKind): Position {
  const { width, height } = nodeSize(kind)
  for (let column = 0; column < 6; column++) {
    for (let row = 0; row < 16; row++) {
      const candidate = { x: desired.x + column * (width + GAP_X), y: desired.y + row * (height + GAP_Y) }
      if (!collides(def, candidate, kind)) return candidate
    }
  }
  return desired
}

/**
 * Where a new step goes: to the right of the anchor (the selected step), or below it when that spot is taken, and
 * further down the column when that is taken too. Without an anchor, to the right of the right-most step.
 */
export function placementFor(def: Definition, kind: NodeKind, anchorId?: string | null): Position {
  const positions = def.uiLayout?.positions ?? {}
  const known = def.nodes.filter((n) => positions[n.id])
  if (known.length === 0) return { x: 80, y: 120 }
  const anchor =
    (anchorId ? known.find((n) => n.id === anchorId) : undefined) ??
    known.reduce((best, n) => ((positions[n.id] as Position).x >= (positions[best.id] as Position).x ? n : best))
  const a = positions[anchor.id] as Position
  const size = nodeSize(anchor.type)
  const right = { x: a.x + size.width + GAP_X, y: a.y }
  if (!collides(def, right, kind)) return right
  const below = { x: a.x, y: a.y + size.height + GAP_Y }
  if (!collides(def, below, kind)) return below
  return findFreePosition(def, right, kind)
}

/** Width reserved for a route label pill on the canvas (it is truncated beyond this). */
export const LABEL_MAX_WIDTH = 192

export function routeLabelWidth(text: string): number {
  return Math.min(LABEL_MAX_WIDTH, Math.round(text.length * 7.6) + 28)
}

/** Steps without a stored position, or steps drawn on top of each other: the layout needs to be computed. */
export function needsAutoLayout(def: Definition, notation: Notation = 'cards'): boolean {
  const rects: Rect[] = []
  for (const n of def.nodes) {
    const r = rectOf(def, n.id, n.type, undefined, notation)
    if (!r) return true
    rects.push(r)
  }
  for (let i = 0; i < rects.length; i++) {
    for (let j = i + 1; j < rects.length; j++) if (intersects(rects[i] as Rect, rects[j] as Rect, 0)) return true
  }
  return false
}

/** A layout worth replacing: steps on top of each other, missing positions, or a route that runs backwards. */
export function hasPoorLayout(def: Definition, notation: Notation = 'cards'): boolean {
  if (needsAutoLayout(def, notation)) return true
  const positions = def.uiLayout?.positions ?? {}
  return def.transitions.some((t) => {
    const from = positions[t.from]
    const to = positions[t.to]
    const kind = def.nodes.find((n) => n.id === t.from)?.type
    const width = kind ? nodeSize(kind, notation).width : NODE_WIDTH
    return !!from && !!to && to.x < from.x + width / 2
  })
}

/** Changes whenever steps or connections are added, removed or re-pointed (not when steps only move). */
export function structureKey(def: Definition): string {
  return `${def.nodes.map((n) => `${n.id}:${n.type}`).join(',')}|${def.transitions.map((t) => `${t.id}:${t.from}>${t.to}:${t.on}`).join(',')}`
}

const ORIGIN: Position = { x: 80, y: 80 }

const portId = (nodeId: string, route: string) => `${nodeId}::${route}`

/**
 * Layered left-to-right layout. Approval steps get two fixed ports on their right side, Approved above Rejected, so
 * the two routes leave in the same order as the handles on the card. Returns top-left positions, rounded.
 */
export async function computeLayout(def: Definition, labelOf?: (t: Transition) => string, notation: Notation = 'cards'): Promise<Record<string, Position>> {
  const bpmn = notation === 'bpmn'
  const { default: ELK } = await import('elkjs/lib/elk.bundled.js')
  const elk = new ELK()
  const ids = new Set(def.nodes.map((n) => n.id))
  const graph = {
    id: 'root',
    layoutOptions: {
      'elk.algorithm': 'layered',
      'elk.direction': 'RIGHT',
      'elk.edgeRouting': 'ORTHOGONAL',
      'elk.layered.spacing.nodeNodeBetweenLayers': String((bpmn ? BPMN_GAP_X : GAP_X) - 12),
      'elk.spacing.nodeNode': String(GAP_Y + 8),
      'elk.layered.spacing.edgeEdgeBetweenLayers': '16',
      'elk.edgeLabels.placement': 'CENTER',
      'elk.spacing.componentComponent': String(GAP_Y * 2),
      'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES',
      'elk.layered.nodePlacement.strategy': 'NETWORK_SIMPLEX',
      'elk.padding': '[top=0,left=0,bottom=0,right=0]',
    },
    children: def.nodes.map((n) => {
      const { width, height } = nodeSize(n.type, notation)
      return {
        id: n.id,
        width,
        height,
        // BPMN events and gateways print their name beneath the shape: an outside label reserves the room for it.
        ...(bpmn && labelOutside(n.type)
          ? { labels: [{ text: n.label, width: OUTSIDE_LABEL.width, height: OUTSIDE_LABEL.height, layoutOptions: { 'elk.nodeLabels.placement': 'OUTSIDE V_BOTTOM H_CENTER' } }] }
          : {}),
        ...(n.type === 'approval'
          ? {
              layoutOptions: { 'elk.portConstraints': 'FIXED_ORDER' },
              ports: [
                { id: portId(n.id, 'approved'), layoutOptions: { 'elk.port.side': 'EAST', 'elk.port.index': '0' } },
                { id: portId(n.id, 'rejected'), layoutOptions: { 'elk.port.side': 'EAST', 'elk.port.index': '1' } },
              ],
            }
          : {}),
      }
    }),
    edges: def.transitions
      .filter((t) => ids.has(t.from) && ids.has(t.to))
      .map((t) => {
        const from = def.nodes.find((n) => n.id === t.from)
        const usesPort = from?.type === 'approval' && (t.on === 'approved' || t.on === 'rejected')
        // Labels that sit on the line (conditions, Otherwise) get room between the layers; Approved and Rejected are
        // already printed on the approval card (in BPMN notation they are printed on the line too).
        const text = !bpmn && (t.on === 'approved' || t.on === 'rejected') ? '' : (labelOf?.(t) ?? '')
        return {
          id: t.id,
          sources: [usesPort ? portId(t.from, t.on) : t.from],
          targets: [t.to],
          ...(text ? { labels: [{ text, width: routeLabelWidth(text), height: 26 }] } : {}),
        }
      }),
  }
  const laid = await elk.layout(graph)
  const positions: Record<string, Position> = {}
  // A fixed origin keeps stored coordinates positive and tidy; the canvas is fitted afterwards, so only relative positions matter.
  for (const child of laid.children ?? []) positions[child.id] = { x: Math.round(child.x ?? 0) + ORIGIN.x, y: Math.round(child.y ?? 0) + ORIGIN.y }
  return positions
}
