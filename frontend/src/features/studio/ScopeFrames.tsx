import { ViewportPortal, useNodes, type Node } from '@xyflow/react'
import type { Definition, SubprocessDef } from './model/types'

/**
 * Section and handler frames (docs/build/S66): a labelled rectangle drawn behind the steps of each section or handler,
 * on the studio canvas and on the run diagram. Frames are decoration over the flat graph, not containers: they follow
 * the steps (also while a step is dragged), cannot be selected or dragged, and never nest (a step belongs to at most
 * one subprocess). Sections have a solid border, handlers a dashed one. Sections and handlers are edited in the
 * request form inspector; the run diagram also lists them as text.
 */
export interface FrameSpec {
  id: string
  kind: 'section' | 'handler'
  /** First line: kind and name. */
  title: string
  /** Second part of the label: time limit, error route, trigger or state. */
  caption?: string
  nodes: string[]
  /** Run diagram only: the request is inside it now, has passed through it, or never entered it. */
  state?: 'active' | 'passed' | 'idle'
}

/** Space around the steps; the top leaves room for the label. */
export const FRAME_PAD = 18
export const FRAME_LABEL = 30
const FALLBACK = { width: 296, height: 128 }

export interface Box {
  x: number
  y: number
  width: number
  height: number
}

/** The frame around the given steps, or null when none of them is on the canvas. */
export function frameBox(nodes: ReadonlyArray<Pick<Node, 'id' | 'position' | 'measured' | 'width' | 'height'>>, members: readonly string[]): Box | null {
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  for (const n of nodes) {
    if (!members.includes(n.id)) continue
    const width = n.measured?.width ?? n.width ?? FALLBACK.width
    const height = n.measured?.height ?? n.height ?? FALLBACK.height
    minX = Math.min(minX, n.position.x)
    minY = Math.min(minY, n.position.y)
    maxX = Math.max(maxX, n.position.x + width)
    maxY = Math.max(maxY, n.position.y + height)
  }
  if (minX === Infinity) return null
  return { x: minX - FRAME_PAD, y: minY - FRAME_PAD - FRAME_LABEL, width: maxX - minX + 2 * FRAME_PAD, height: maxY - minY + 2 * FRAME_PAD + FRAME_LABEL }
}

function labelOf(def: Pick<Definition, 'nodes'>, id: string | undefined): string {
  if (!id) return '?'
  return def.nodes.find((n) => n.id === id)?.label ?? id
}

/** What a frame says about a section or handler of a definition (studio). */
export function frameCaption(def: Pick<Definition, 'nodes'>, s: SubprocessDef): string {
  if (s.type === 'embedded') {
    const parts: string[] = []
    if (s.timeoutAfter && s.onTimeout) parts.push(`time limit ${s.timeoutAfter} → ${labelOf(def, s.onTimeout)}`)
    if (s.onError) parts.push(`${s.onError.errorCode ? `error ${s.onError.errorCode}` : 'any error'} → ${labelOf(def, s.onError.to)}`)
    return parts.join(' · ')
  }
  const t = s.trigger
  const on = !t ? 'no trigger' : t.type === 'signal' ? `on signal ${t.signal ?? '?'}` : t.type === 'timer' ? `after ${t.after ?? '?'}` : t.errorCode ? `on error ${t.errorCode}` : 'on any error'
  return `${on} · ${s.interrupting ? 'stops the request' : 'runs alongside'}`
}

export function framesOf(def: Definition): FrameSpec[] {
  return (def.subprocesses ?? []).map((s) => ({
    id: s.id,
    kind: s.type === 'embedded' ? 'section' : 'handler',
    title: `${s.type === 'embedded' ? 'Section' : 'Handler'}: ${s.label}`,
    caption: frameCaption(def, s),
    nodes: s.nodes,
  }))
}

/** Rendered inside a ReactFlow: reads the steps' current positions and sizes from the flow. */
export function ScopeFrames({ frames }: { frames: FrameSpec[] }) {
  const nodes = useNodes()
  if (!frames.length) return null
  return (
    <ViewportPortal>
      {frames.map((f) => {
        const box = frameBox(nodes, f.nodes)
        if (!box) return null
        return (
          <div
            key={f.id}
            aria-hidden="true"
            className="orqix-scope-frame"
            data-kind={f.kind}
            data-state={f.state}
            data-testid={`scope-frame-${f.id}`}
            style={{ transform: `translate(${box.x}px, ${box.y}px)`, width: box.width, height: box.height }}
          >
            <span className="orqix-scope-frame-label">
              <span className="font-semibold">{f.title}</span>
              {f.caption ? <span className="orqix-scope-frame-caption"> · {f.caption}</span> : null}
            </span>
          </div>
        )
      })}
    </ViewportPortal>
  )
}
