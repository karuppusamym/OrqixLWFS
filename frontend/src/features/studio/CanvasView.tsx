import '@xyflow/react/dist/style.css'
import './studio.css'
import {
  Background,
  BackgroundVariant,
  Controls,
  MarkerType,
  MiniMap,
  Panel,
  ReactFlow,
  ReactFlowProvider,
  applyEdgeChanges,
  applyNodeChanges,
  getNodesBounds,
  getViewportForBounds,
  useNodesInitialized,
  useReactFlow,
  type Connection,
  type Edge,
  type EdgeChange,
  type NodeChange,
} from '@xyflow/react'
import { Network } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useAnnounce } from '@/components/common/Announcer'
import { Button } from '@/components/ui/button'
import { useStudio, useStudioContext } from './StudioContext'
import { RouteEdge, type RouteFlowEdge } from './RouteEdge'
import { StepNode, type StepFlowNode, type StepNodeData } from './StepNode'
import { countByNode } from './model/issues'
import { nodeById } from './model/graph'
import { computeLayout, hasPoorLayout, structureKey } from './model/layout'
import { canConnect, routeForHandle } from './model/ops'
import { nodeAriaLabel, nodeSubtitle, routeText } from './model/summary'
import type { Definition, NodeKind, Position, RouteKind } from './model/types'
import { KIND_VAR } from './nodeMeta'
import { usePlayback } from './test/PlaybackContext'

const nodeTypes = { step: StepNode }
const edgeTypes = { route: RouteEdge }

/** Route colors come from theme tokens (src/index.css `--edge-*`), so a brand change recolors the canvas too. */
const edgeColor = (on: RouteKind): string => `var(--edge-${on === 'timedOut' ? 'condition' : on})`

export const PALETTE_MIME = 'application/x-orqix-node'

/**
 * The first view fits the whole workflow, but never smaller than this zoom: step text is 17 to 18 px, so at 0.85 it
 * is still 14 px or more on screen. A longer workflow starts at the request form at this zoom and is panned; the
 * Fit view button (bottom left) shows all of it.
 */
const FIT_MIN_ZOOM = 0.85
const FIT = { padding: 0.12, maxZoom: 1 } as const
const FIT_ALL = { ...FIT, minZoom: 0.1 } as const

function prefersReducedMotion(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

interface CanvasProps {
  groupNames: Record<string, string>
  /** A new connection leaving a choice step needs a condition: the page opens the route dialog. */
  onBranchConnect: (from: string, to: string) => void
}

export function CanvasView(props: CanvasProps) {
  return (
    <ReactFlowProvider>
      <CanvasInner {...props} />
    </ReactFlowProvider>
  )
}

function CanvasInner({ groupNames, onBranchConnect }: CanvasProps) {
  const { store, readOnly, connections } = useStudioContext()
  const announce = useAnnounce()
  const def = useStudio((s) => s.definition)
  const selection = useStudio((s) => s.selection)
  const issues = useStudio((s) => s.issues)
  const focusRequest = useStudio((s) => s.focusRequest)
  const flow = useReactFlow()
  const nodesInitialized = useNodesInitialized()
  const { active: playActive, current: playCurrent, visited: playVisited, reached: playReached, edges: playEdges } = usePlayback()

  const counts = useMemo(() => countByNode(def, issues), [def, issues])
  const key = useMemo(() => structureKey(def), [def])

  /** Text of a route that sits on a line (conditions, Otherwise, named routes). ELK reserves room for it. */
  const labelOf = useCallback((d: Definition) => (t: Definition['transitions'][number]) => (t.on === 'approved' || t.on === 'rejected' ? '' : routeText(d, t, connections)), [connections])

  // A stored layout with steps on top of each other, missing positions or backwards routes is arranged once, when the
  // studio opens. It is an ordinary undoable change, but it counts as already saved, so nothing is autosaved until
  // the person edits something.
  const [prepared, setPrepared] = useState(() => !hasPoorLayout(store.getState().definition))
  useEffect(() => {
    if (prepared) return
    let cancelled = false
    const current = store.getState().definition
    computeLayout(current, labelOf(current))
      .then((positions) => {
        if (!cancelled) store.getState().arrange(positions, { baseline: true })
      })
      .catch(() => undefined)
      .finally(() => {
        if (!cancelled) setPrepared(true)
      })
    return () => {
      cancelled = true
    }
  }, [prepared, store, labelOf])

  const derivedNodes = useMemo<StepFlowNode[]>(
    () =>
      def.nodes.map((n) => {
        const c = counts[n.id]
        const total = (c?.blockers ?? 0) + (c?.warnings ?? 0)
        const data: StepNodeData = { kind: n.type, label: n.label, subtitle: nodeSubtitle(def, n, groupNames), blockers: c?.blockers ?? 0, warnings: c?.warnings ?? 0 }
        const stage = def.stages?.find((s) => s.nodes.includes(n.id))
        if (stage) data.stage = stage.label
        if (n.type === 'approval' || n.type === 'waitForMessage') data.timeout = !!n.config.timeoutAfter
        if (n.type === 'end') {
          data.outcomeKind = n.config.outcomeKind === 'rejected' ? 'rejected' : 'success'
          if (typeof n.config.businessOutcome === 'string') data.outcome = n.config.businessOutcome
        }
        return {
          id: n.id,
          type: 'step',
          position: def.uiLayout?.positions[n.id] ?? { x: 0, y: 0 },
          selected: selection?.type === 'node' && selection.id === n.id,
          ...(playActive ? { className: playCurrent === n.id ? 'sim-current' : playVisited.has(n.id) ? 'sim-visited' : playReached.has(n.id) ? 'sim-ahead' : 'sim-unreached' } : {}),
          deletable: n.type !== 'start',
          ariaLabel: nodeAriaLabel(n, total),
          data,
        }
      }),
    [def, counts, selection, groupNames, playActive, playCurrent, playVisited, playReached],
  )

  const derivedEdges = useMemo<RouteFlowEdge[]>(
    () =>
      def.transitions
        .filter((t) => nodeById(def, t.from) && nodeById(def, t.to))
        .map((t) => {
          const color = edgeColor(t.on)
          const text = routeText(def, t, connections)
          return {
            id: t.id,
            source: t.from,
            target: t.to,
            sourceHandle: t.on === 'approved' ? 'approved' : t.on === 'rejected' ? 'rejected' : t.on === 'timedOut' ? 'timedOut' : t.on === 'condition' || t.on === 'default' ? 'route' : 'next',
            targetHandle: 'in',
            type: 'route',
            data: { on: t.on, text },
            selected: selection?.type === 'edge' && selection.id === t.id,
            ...(playActive ? { className: playEdges.has(t.id) ? 'sim-taken' : 'sim-untaken' } : {}),
            ariaLabel: `${text || 'Next'}: ${nodeById(def, t.from)?.label} to ${nodeById(def, t.to)?.label}`,
            interactionWidth: 28,
            markerEnd: { type: MarkerType.ArrowClosed, color, width: 16, height: 16 },
            style: { stroke: color, strokeWidth: 2.5, ...(t.on === 'default' ? { strokeDasharray: '7 5' } : {}) },
          }
        }),
    [def, selection, connections, playActive, playEdges],
  )

  // React Flow owns positions while a drag is in progress; the store receives them at drag end (one undo entry).
  const [nodes, setNodes] = useState<StepFlowNode[]>(derivedNodes)
  const [edges, setEdges] = useState<RouteFlowEdge[]>(derivedEdges)
  const [syncedNodes, setSyncedNodes] = useState(derivedNodes)
  const [syncedEdges, setSyncedEdges] = useState(derivedEdges)
  // The document changed (edit, undo, selection): React Flow's local copy follows it.
  if (syncedNodes !== derivedNodes) {
    setSyncedNodes(derivedNodes)
    setNodes(derivedNodes)
  }
  if (syncedEdges !== derivedEdges) {
    setSyncedEdges(derivedEdges)
    setEdges(derivedEdges)
  }

  const nodesRef = useRef(nodes)
  useEffect(() => {
    nodesRef.current = nodes
  })
  const keyboardTimer = useRef<number | undefined>(undefined)

  // Fit the workflow into view on load and after every structural change (steps or connections added, removed or
  // re-pointed, undo and redo, auto-layout). Moving a step does not refit. A step dropped from the palette stays
  // exactly where it was dropped.
  const rootRef = useRef<HTMLDivElement>(null)
  const fitPending = useRef(true)
  const fitted = useRef(false)
  const skipNextFit = useRef(false)
  const lastKey = useRef(key)
  useEffect(() => {
    if (lastKey.current === key) return
    lastKey.current = key
    if (skipNextFit.current) skipNextFit.current = false
    else fitPending.current = true
  }, [key])
  useEffect(() => {
    if (!fitPending.current || !nodesInitialized || !prepared) return
    fitPending.current = false
    const duration = fitted.current && !prefersReducedMotion() ? 250 : 0
    fitted.current = true
    // One frame later, so React Flow has applied the new positions and sizes.
    requestAnimationFrame(() => {
      const box = rootRef.current?.getBoundingClientRect()
      const all = flow.getNodes()
      if (!box || all.length === 0) return
      const bounds = getNodesBounds(all)
      const whole = getViewportForBounds(bounds, box.width, box.height, 0.1, FIT.maxZoom, FIT.padding)
      if (whole.zoom >= FIT_MIN_ZOOM) {
        void flow.setViewport(whole, { duration })
        return
      }
      // Too wide to show whole at a readable size: start at the request form, readable, and pan for the rest.
      const start = all.find((n) => n.data.kind === 'start') ?? all[0]
      if (!start) return
      const fitsTall = bounds.height * FIT_MIN_ZOOM <= box.height - 48
      const centerY = fitsTall ? bounds.y + bounds.height / 2 : start.position.y + (start.measured?.height ?? 128) / 2
      void flow.setViewport({ x: 40 - start.position.x * FIT_MIN_ZOOM, y: box.height / 2 - centerY * FIT_MIN_ZOOM, zoom: FIT_MIN_ZOOM }, { duration })
    })
  })

  const commitPositions = useCallback(() => {
    const positions: Record<string, Position> = {}
    for (const n of nodesRef.current) positions[n.id] = n.position
    store.getState().moveNodes(positions)
  }, [store])

  useEffect(() => () => window.clearTimeout(keyboardTimer.current), [])

  const [arranging, setArranging] = useState(false)
  const autoLayout = useCallback(async () => {
    setArranging(true)
    try {
      const current = store.getState().definition
      const positions = await computeLayout(current, labelOf(current))
      const before = store.getState().definition
      store.getState().moveNodes(positions)
      fitPending.current = true
      announce(store.getState().definition === before ? 'The steps are already arranged.' : 'Steps arranged. Press Ctrl+Z to undo.')
    } catch {
      announce('The steps could not be arranged.')
    } finally {
      setArranging(false)
    }
  }, [announce, labelOf, store])

  const onNodesChange = useCallback(
    (changes: NodeChange<StepFlowNode>[]) => {
      if (readOnly) changes = changes.filter((c) => c.type === 'select' || c.type === 'dimensions')
      setNodes((current) => applyNodeChanges(changes, current))
      for (const c of changes) {
        if (c.type === 'select' && c.selected) store.getState().select({ type: 'node', id: c.id })
        // Arrow-key moves arrive as non-drag position changes; commit them as one entry after the burst.
        if (c.type === 'position' && c.position && c.dragging !== true) {
          window.clearTimeout(keyboardTimer.current)
          keyboardTimer.current = window.setTimeout(commitPositions, 400)
        }
      }
    },
    [commitPositions, readOnly, store],
  )

  const onEdgesChange = useCallback(
    (changes: EdgeChange<RouteFlowEdge>[]) => {
      setEdges((current) => applyEdgeChanges(changes, current))
      for (const c of changes) if (c.type === 'select' && c.selected) store.getState().select({ type: 'edge', id: c.id })
    },
    [store],
  )

  const isValidConnection = useCallback(
    (c: Connection | Edge) => {
      const current = store.getState().definition
      const source = nodeById(current, c.source)
      if (!source) return false
      return canConnect(current, { from: c.source, to: c.target, on: routeForHandle(source, c.sourceHandle) }).ok
    },
    [store],
  )

  const onConnect = useCallback(
    (c: Connection) => {
      const current = store.getState().definition
      const source = nodeById(current, c.source)
      if (!source) return
      if (source.type === 'branch') {
        onBranchConnect(c.source, c.target)
        return
      }
      const result = store.getState().connect({ from: c.source, to: c.target, on: routeForHandle(source, c.sourceHandle) })
      if (!result.ok) announce(result.reason)
    },
    [announce, onBranchConnect, store],
  )

  // Selecting from outside (Go to step, Steps tab) brings the step into view when it is not already visible.
  useEffect(() => {
    if (!focusRequest || fitPending.current) return
    const node = flow.getNode(focusRequest.nodeId)
    if (!node) return
    const viewport = flow.getViewport()
    const box = rootRef.current?.getBoundingClientRect()
    const width = node.measured?.width ?? node.width ?? 296
    const height = node.measured?.height ?? node.height ?? 128
    if (box) {
      const left = node.position.x * viewport.zoom + viewport.x
      const top = node.position.y * viewport.zoom + viewport.y
      const visible = left >= 0 && top >= 0 && left + width * viewport.zoom <= box.width && top + height * viewport.zoom <= box.height
      if (visible) return
    }
    void flow.setCenter(node.position.x + width / 2, node.position.y + height / 2, { zoom: Math.min(Math.max(viewport.zoom, FIT_MIN_ZOOM), 1.1), duration: prefersReducedMotion() ? 0 : 250 })
  }, [focusRequest, flow])

  // While a test run is played back, the step being explained is brought into view when it is not already visible.
  useEffect(() => {
    if (!playCurrent || fitPending.current) return
    const node = flow.getNode(playCurrent)
    if (!node) return
    const viewport = flow.getViewport()
    const box = rootRef.current?.getBoundingClientRect()
    const width = node.measured?.width ?? node.width ?? 296
    const height = node.measured?.height ?? node.height ?? 128
    if (box) {
      const left = node.position.x * viewport.zoom + viewport.x
      const top = node.position.y * viewport.zoom + viewport.y
      if (left >= 0 && top >= 0 && left + width * viewport.zoom <= box.width && top + height * viewport.zoom <= box.height) return
    }
    void flow.setCenter(node.position.x + width / 2, node.position.y + height / 2, { zoom: Math.min(Math.max(viewport.zoom, FIT_MIN_ZOOM), 1.1), duration: prefersReducedMotion() ? 0 : 250 })
  }, [playCurrent, flow])

  const onDrop = useCallback(
    (event: React.DragEvent) => {
      event.preventDefault()
      const kind = event.dataTransfer.getData(PALETTE_MIME) as NodeKind
      if (!kind || readOnly) return
      const position = flow.screenToFlowPosition({ x: event.clientX - 148, y: event.clientY - 64 })
      skipNextFit.current = true
      store.getState().addNode(kind, { position: { x: Math.round(position.x), y: Math.round(position.y) } })
    },
    [flow, readOnly, store],
  )

  const onlyStart = def.nodes.length <= 1

  return (
    <div ref={rootRef} className="orqix-flow relative h-full min-h-[480px] w-full" aria-label="Workflow canvas" role="group">
      <div className={prepared ? 'h-full w-full' : 'h-full w-full opacity-0'}>
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          edgeTypes={edgeTypes}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={onConnect}
          isValidConnection={isValidConnection}
          onNodeClick={(_e, node) => store.getState().select({ type: 'node', id: node.id })}
          onEdgeClick={(_e, edge) => store.getState().select({ type: 'edge', id: edge.id })}
          onPaneClick={() => store.getState().select(null)}
          onNodeDragStop={commitPositions}
          onMoveEnd={(_e, vp) => store.getState().setViewport({ x: Math.round(vp.x), y: Math.round(vp.y), zoom: Number(vp.zoom.toFixed(2)) })}
          onDragOver={(e) => {
            e.preventDefault()
            e.dataTransfer.dropEffect = 'copy'
          }}
          onDrop={onDrop}
          deleteKeyCode={null}
          nodesDraggable={!readOnly}
          nodesConnectable={!readOnly}
          elementsSelectable
          nodesFocusable
          edgesFocusable
          minZoom={0.25}
          maxZoom={1.8}
          connectionRadius={32}
          proOptions={{ hideAttribution: false }}
        >
          <Background variant={BackgroundVariant.Dots} gap={22} size={1.5} color="var(--canvas-dot)" />
          <Controls showInteractive={false} position="bottom-left" fitViewOptions={{ ...FIT_ALL, duration: 200 }} />
          <MiniMap
            pannable
            zoomable
            ariaLabel="Overview of the workflow"
            nodeColor={(n) => KIND_VAR[(n.data as StepNodeData).kind]}
            nodeStrokeWidth={0}
            position="bottom-right"
            style={{ width: 144, height: 88 }}
          />
          {!readOnly ? (
            <Panel position="top-left" className="m-3!">
              <Button variant="secondary" onClick={() => void autoLayout()} loading={arranging} title="Arrange the steps from left to right. You can undo it.">
                {arranging ? null : <Network aria-hidden="true" />} Auto-layout
              </Button>
            </Panel>
          ) : null}
        </ReactFlow>
      </div>
      {!prepared ? (
        <p role="status" className="absolute inset-0 flex items-center justify-center text-sm text-muted-foreground">
          Arranging the steps…
        </p>
      ) : null}
      {onlyStart && prepared ? (
        <div className="pointer-events-none absolute inset-x-0 bottom-28 flex justify-center px-6" data-testid="canvas-empty-hint">
          <div className="max-w-md rounded-card border border-dashed border-input-border bg-surface px-5 py-4 text-center shadow-card">
            <p className="font-semibold">{readOnly ? 'This workflow only has its request form' : 'Your workflow only has its request form'}</p>
            <p className="mt-1 text-sm text-muted-foreground">
              {readOnly
                ? 'No other steps have been added yet.'
                : 'Add a step from the list on the left, or drag one onto the canvas. Then connect steps: drag from the dot on the right edge of one card to the dot on the left edge of the next.'}
            </p>
          </div>
        </div>
      ) : null}
    </div>
  )
}
