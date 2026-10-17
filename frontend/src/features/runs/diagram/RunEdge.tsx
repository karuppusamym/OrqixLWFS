import { BaseEdge, EdgeLabelRenderer, useNodes, type Edge, type EdgeProps } from '@xyflow/react'
import { cn } from '@/lib/cn'
import { routeGeometry } from '@/features/studio/RouteEdge'
import { APPROVAL_HEIGHT, LABEL_MAX_WIDTH, NODE_HEIGHT, NODE_WIDTH } from '@/features/studio/model/layout'

export interface RunEdgeData extends Record<string, unknown> {
  /** The request used this route: drawn thick. Routes not used are thin and dashed. */
  taken: boolean
  /** The designer's route label (never a condition), such as "Otherwise". */
  text: string
}

export type RunFlowEdge = Edge<RunEdgeData, 'runRoute'>

/**
 * Read-only route for the run diagram: the studio's orthogonal geometry, thicker when the request used the route.
 * It does not need the studio store.
 */
export function RunEdge({ id, source, target, sourceX, sourceY, targetX, targetY, data, markerEnd, style }: EdgeProps<RunFlowEdge>) {
  const nodes = useNodes()
  const text = data?.text ?? ''
  const others = nodes
    .filter((n) => n.id !== source && n.id !== target)
    .map((n) => ({
      x: n.position.x,
      y: n.position.y,
      width: n.measured?.width ?? NODE_WIDTH,
      height: n.measured?.height ?? (n.data?.kind === 'approval' ? APPROVAL_HEIGHT : NODE_HEIGHT),
    }))
  const width = Math.min(LABEL_MAX_WIDTH, Math.round(text.length * 7.8) + 28)
  const laneShift = ([...id].reduce((sum, ch) => sum + ch.charCodeAt(0), 0) % 3) * 22
  const geometry = routeGeometry({ x: sourceX, y: sourceY }, { x: targetX, y: targetY }, others, width, laneShift)
  return (
    <>
      <BaseEdge id={id} path={geometry.path} markerEnd={markerEnd} style={style} interactionWidth={0} />
      {text ? (
        <EdgeLabelRenderer>
          <span
            aria-hidden="true"
            className={cn(
              'pointer-events-none absolute max-w-48 truncate rounded-full border bg-surface px-2.5 py-0.5 text-sm leading-5 font-semibold shadow-card',
              data?.taken ? 'border-foreground text-foreground' : 'border-border text-muted-foreground',
            )}
            style={{ transform: `translate(-50%, -50%) translate(${geometry.label.x}px, ${geometry.label.y}px)` }}
          >
            {text}
          </span>
        </EdgeLabelRenderer>
      ) : null}
    </>
  )
}
