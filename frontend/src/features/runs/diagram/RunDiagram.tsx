import '@xyflow/react/dist/style.css'
import '@/features/studio/studio.css'
import './runDiagram.css'
import {
  Background,
  BackgroundVariant,
  Controls,
  MarkerType,
  NodeToolbar,
  Position,
  ReactFlow,
  ReactFlowProvider,
  applyNodeChanges,
  getNodesBounds,
  getViewportForBounds,
  useNodesInitialized,
  useReactFlow,
  type NodeChange,
} from '@xyflow/react'
import { AlertTriangle, CheckCircle2, MapPin, MinusCircle } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router'
import type { RunDetail } from '@/api/types'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { QueryError } from '@/components/common/QueryError'
import { ScrollTable } from '@/components/common/ScrollTable'
import { TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { KINDS } from '@/features/studio/model/kinds'
import { computeLayout, hasPoorLayout, structureKey } from '@/features/studio/model/layout'
import { normalizeDefinition, type Definition, type NodeKind, type Position as StudioPosition, type RouteKind } from '@/features/studio/model/types'
import type { StepNodeData } from '@/features/studio/StepNode'
import { ScopeFrames, type FrameSpec } from '@/features/studio/ScopeFrames'
import { cn } from '@/lib/cn'
import { useRunPath, type PathNode, type RunPath } from './api'
import { RunEdge, type RunFlowEdge } from './RunEdge'
import { RunStepNode, type RunFlowNode } from './RunStepNode'
import { buildStepViews, describeStep, statusLabel, stepAriaDescription, stepAriaLabel, takenRoutes, type StepView } from './pathModel'

const nodeTypes = { runStep: RunStepNode }
const edgeTypes = { runRoute: RunEdge }

const READABLE_ZOOM = 0.85
const FIT = { padding: 0.12, maxZoom: 1 } as const

function prefersReducedMotion(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/** Kinds this build can draw; a step type added later still draws, as a plain step. */
function knownKind(kind: string): NodeKind {
  return (kind in KINDS ? kind : 'notification') as NodeKind
}

/** The graph from the server as a studio definition, so the studio's layout code can arrange it when no layout was saved. */
function toDefinition(path: RunPath): Definition {
  const positions: Record<string, StudioPosition> = {}
  for (const n of path.diagram.nodes) if (n.position) positions[n.id] = { x: n.position.x, y: n.position.y }
  return normalizeDefinition({
    nodes: path.diagram.nodes.map((n) => ({ id: n.id, type: knownKind(n.kind), label: n.label, config: {} })),
    transitions: path.diagram.transitions.map((t) => ({ id: t.id, from: t.from, to: t.to, on: t.on, ...(t.label ? { label: t.label } : {}) })),
    uiLayout: { positions },
  })
}

const SUBTITLE: Partial<Record<NodeKind, string>> = {
  start: 'The request form',
  decision: 'Applies a rule',
  branch: 'Chooses a route',
  http: 'Calls another system',
  notification: 'Sends a message',
  wait: 'Waits',
  startWorkflow: 'Starts another request',
  parallelSplit: 'Starts parallel steps',
  parallelJoin: 'Waits for parallel steps',
}

function subtitleOf(node: PathNode, run: RunDetail): string {
  const kind = knownKind(node.kind)
  if (kind === 'approval') {
    const task = run.tasks.find((t) => t.nodeId === node.id)
    return task ? `Asks ${task.candidateGroup.name}` : 'Asks for approval'
  }
  return SUBTITLE[kind] ?? KINDS[kind].caption
}

/** The route color follows the route kind (theme tokens in src/index.css). */
function routeColor(on: RouteKind): string {
  return `var(--edge-${on === 'failed' ? 'rejected' : on === 'timedOut' ? 'condition' : on === 'default' ? 'default' : on})`
}

function handleFor(on: RouteKind): string {
  return on === 'approved' ? 'approved' : on === 'rejected' || on === 'failed' ? 'rejected' : on === 'timedOut' ? 'timedOut' : on === 'condition' || on === 'default' ? 'route' : 'next'
}

interface Props {
  ws: string
  run: RunDetail
  operator: boolean
}

/**
 * "Where is my request": the workflow of the request's pinned release on a read-only canvas (docs/build/S7-frontend.md
 * section 4). It reuses the studio's step cards and layout code but not its editing store. The current step has an
 * outline that pulses and a text chip; steps passed have a check mark and a status word; routes used are drawn thick;
 * steps the request did not use are dimmed and say so. Details of a step appear in a popover on the step and, for
 * keyboard and screen reader users, in the "Step details" panel under the canvas.
 */
export default function RunDiagram(props: Props) {
  const stamp = `${props.run.version}:${props.run.updatedAt}`
  const path = useRunPath(props.ws, props.run.id, stamp, true)
  if (path.isPending) return <LoadingBlock rows={4} label="Loading the diagram" />
  if (path.isError)
    return (
      <QueryError
        error={path.error}
        onRetry={() => void path.refetch()}
        what="the diagram"
      />
    )
  if (path.data.historyTruncated) return (
    <section role="status" className="space-y-3 rounded-card border border-border p-4">
      <p>This request has more history than the diagram can display. Use the Timeline to review its history.</p>
      <h3 className="font-semibold">Current steps</h3>
      {path.data.current.length ? <ul>{path.data.current.map((step) => <li key={step.nodeId}>{step.label}: {step.status === 'needsAttention' ? (props.operator ? 'Needs attention' : 'Delayed') : step.status === 'running' ? 'Running' : 'Waiting'}</li>)}</ul> : <p>No active steps.</p>}
    </section>
  )
  return (
    <ReactFlowProvider>
      <DiagramInner {...props} path={path.data} />
    </ReactFlowProvider>
  )
}

function DiagramInner({ ws, run, operator, path }: Props & { path: RunPath }) {
  const flow = useReactFlow()
  const nodesInitialized = useNodesInitialized()
  const views = useMemo(() => buildStepViews(path), [path])
  const taken = useMemo(() => takenRoutes(path), [path])
  const definition = useMemo(() => toDefinition(path), [path])
  const key = useMemo(() => structureKey(definition), [definition])
  const needsLayout = useMemo(() => hasPoorLayout(definition), [definition])

  // Without a saved layout (a workflow nobody arranged in the studio) the studio's layout code arranges the steps.
  const [arranged, setArranged] = useState<{ key: string; positions: Record<string, StudioPosition> } | null>(null)
  useEffect(() => {
    if (!needsLayout) return
    let cancelled = false
    computeLayout(definition)
      .then((positions) => {
        if (!cancelled) setArranged({ key, positions })
      })
      .catch(() => {
        if (!cancelled) setArranged({ key, positions: {} })
      })
    return () => {
      cancelled = true
    }
  }, [needsLayout, definition, key])
  const positions = useMemo<Record<string, StudioPosition> | null>(
    () => (needsLayout ? (arranged?.key === key ? arranged.positions : null) : (definition.uiLayout?.positions ?? {})),
    [needsLayout, arranged, key, definition],
  )

  const [selectedId, setSelectedId] = useState<string | null>(null)
  const ctx = useMemo(() => ({ run, operator }), [run, operator])

  const derivedNodes = useMemo<RunFlowNode[]>(() => {
    if (!positions) return []
    return path.diagram.nodes.map((n, index) => {
      const view = views.get(n.id) as StepView
      const kind = knownKind(n.kind)
      const card: StepNodeData = { kind, label: n.label, subtitle: subtitleOf(n, run), blockers: 0, warnings: 0 }
      if (kind === 'approval' || kind === 'waitForMessage') card.timeout = n.hasTimeout === true
      if (kind === 'end') {
        card.outcomeKind = n.outcomeKind === 'failed' ? 'failed' : n.outcomeKind === 'rejected' ? 'rejected' : 'success'
        if (n.outcome) card.outcome = n.outcome
      }
      const descriptionId = `run-step-desc-${index}`
      return {
        id: n.id,
        type: 'runStep',
        position: positions[n.id] ?? { x: 0, y: 0 },
        selected: selectedId === n.id,
        draggable: false,
        connectable: false,
        deletable: false,
        ariaLabel: stepAriaLabel(view, operator, KINDS[kind].caption),
        domAttributes: { 'aria-describedby': descriptionId },
        data: { card, status: view.status, operator, descriptionId, description: stepAriaDescription(view, ctx), occurrences: view.visits.length },
      } satisfies RunFlowNode
    })
  }, [positions, path, views, run, operator, ctx, selectedId])

  const derivedEdges = useMemo<RunFlowEdge[]>(
    () =>
      path.diagram.transitions.map((t) => {
        // Section routes ("error") live in diagram.scopes, never in transitions.
        const on = t.on as RouteKind
        const used = taken.has(t.id)
        const color = used ? routeColor(on) : 'var(--border)'
        return {
          id: t.id,
          source: t.from,
          target: t.to,
          sourceHandle: handleFor(on),
          targetHandle: 'in',
          type: 'runRoute',
          data: { taken: used, text: t.label ?? '' },
          focusable: false,
          selectable: false,
          markerEnd: { type: MarkerType.ArrowClosed, color, width: 16, height: 16 },
          style: { stroke: color, strokeWidth: used ? 5 : 2, ...(used ? {} : { strokeDasharray: '7 6' }) },
        } satisfies RunFlowEdge
      }),
    [path, taken],
  )

  // React Flow keeps the measured sizes in its copy of the nodes; the derived list replaces it when the data changes.
  const [nodes, setNodes] = useState<RunFlowNode[]>(derivedNodes)
  const [syncedNodes, setSyncedNodes] = useState(derivedNodes)
  if (syncedNodes !== derivedNodes) {
    setSyncedNodes(derivedNodes)
    setNodes(derivedNodes)
  }

  const onNodesChange = useCallback((changes: NodeChange<RunFlowNode>[]) => {
    setNodes((current) => applyNodeChanges(changes, current))
    const picked = changes.find((c) => c.type === 'select' && c.selected)
    if (picked && picked.type === 'select') setSelectedId(picked.id)
  }, [])

  const rootRef = useRef<HTMLDivElement>(null)
  const fitted = useRef<string>('')
  // Fit the whole workflow when it is readable; otherwise start at the step the request is at, at a readable size, and
  // let the person pan (Fit view in the controls shows everything).
  useEffect(() => {
    if (!nodesInitialized || !positions || fitted.current === key) return
    fitted.current = key
    requestAnimationFrame(() => {
      const box = rootRef.current?.getBoundingClientRect()
      const all = flow.getNodes()
      if (!box || all.length === 0) return
      const bounds = getNodesBounds(all)
      const whole = getViewportForBounds(bounds, box.width, box.height, 0.1, FIT.maxZoom, FIT.padding)
      if (whole.zoom >= 0.6) {
        void flow.setViewport(whole)
        return
      }
      const focusId = path.current[0]?.nodeId ?? [...path.visited].reverse()[0]?.nodeId ?? all[0]?.id
      const focus = all.find((n) => n.id === focusId) ?? all[0]
      if (!focus) return
      const width = focus.measured?.width ?? 296
      const height = focus.measured?.height ?? 128
      void flow.setViewport({
        x: box.width / 2 - (focus.position.x + width / 2) * READABLE_ZOOM,
        y: box.height / 2 - (focus.position.y + height / 2) * READABLE_ZOOM,
        zoom: READABLE_ZOOM,
      })
    })
  }, [nodesInitialized, positions, key, flow, path])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setSelectedId(null)
    }
    const root = rootRef.current
    root?.addEventListener('keydown', onKey)
    return () => root?.removeEventListener('keydown', onKey)
  }, [])

  const frames = useMemo(() => runFrames(path), [path])
  const selected = selectedId ? (views.get(selectedId) ?? null) : null
  const currentViews = [...views.values()].filter((v) => v.current)

  return (
    <div className="space-y-4" data-testid="run-diagram">
      <p className="text-sm text-muted-foreground" id="run-diagram-help">
        This is the request&apos;s workflow, read only. Steps the request has passed are marked, the step it is at now is outlined, and routes it used are drawn thick. Select a step, or press Enter on it, to see its details. The timeline shows the same history as a list.
      </p>

      <ul className="flex flex-wrap gap-x-5 gap-y-2 text-sm" aria-label="Legend" data-testid="diagram-legend">
        <li className="inline-flex items-center gap-1.5">
          <MapPin className="size-4 text-status-waiting-fg" aria-hidden="true" /> {operator ? 'Waiting here' : 'You are here'}: outlined step
        </li>
        <li className="inline-flex items-center gap-1.5">
          <CheckCircle2 className="size-4 text-status-success-fg" aria-hidden="true" /> Done, approved: step passed
        </li>
        <li className="inline-flex items-center gap-1.5">
          <AlertTriangle className="size-4 text-status-attention-fg" aria-hidden="true" /> {operator ? 'Needs attention' : 'Delayed'}
        </li>
        <li className="inline-flex items-center gap-1.5">
          <MinusCircle className="size-4 text-muted-foreground" aria-hidden="true" /> Not used: dimmed step
        </li>
      </ul>

      <div
        ref={rootRef}
        className="orqix-flow orqix-flow-run relative h-[26rem] min-w-0 max-w-full overflow-hidden rounded-card border border-border"
        role="group"
        aria-label="Workflow diagram, read only"
        aria-describedby="run-diagram-help"
        data-testid="run-diagram-canvas"
      >
        {positions ? (
          <ReactFlow
            nodes={nodes}
            edges={derivedEdges}
            nodeTypes={nodeTypes}
            edgeTypes={edgeTypes}
            onNodesChange={onNodesChange}
            onNodeClick={(_e, node) => setSelectedId(node.id)}
            onPaneClick={() => setSelectedId(null)}
            nodesDraggable={false}
            nodesConnectable={false}
            elementsSelectable
            nodesFocusable
            edgesFocusable={false}
            deleteKeyCode={null}
            minZoom={0.25}
            maxZoom={1.6}
            proOptions={{ hideAttribution: false }}
          >
            <Background variant={BackgroundVariant.Dots} gap={22} size={1.5} color="var(--canvas-dot)" />
            <ScopeFrames frames={frames} />
            <Controls showInteractive={false} position="bottom-left" fitViewOptions={{ ...FIT, minZoom: 0.1, duration: prefersReducedMotion() ? 0 : 200 }} />
            {selected ? <StepPopover view={selected} run={run} operator={operator} /> : null}
          </ReactFlow>
        ) : (
          <p role="status" className="absolute inset-0 flex items-center justify-center text-sm text-muted-foreground">
            Arranging the steps…
          </p>
        )}
      </div>

      <ScopeList path={path} />

      <section aria-labelledby="step-details-heading" aria-live="polite" className="rounded-card border border-border bg-surface p-4" data-testid="step-details">
        <h3 id="step-details-heading" className="text-base">
          Step details
        </h3>
        {selected ? (
          <StepDetails ws={ws} view={selected} run={run} operator={operator} />
        ) : currentViews.length > 0 ? (
          <ul className="mt-2 space-y-1 text-sm">
            {currentViews.map((v) => (
              <li key={v.id}>
                <button type="button" className="font-semibold underline" onClick={() => setSelectedId(v.id)}>
                  {v.label}
                </button>
                : {statusLabel(v.status, operator)}. {describeStep(v, ctx).summary}
              </li>
            ))}
          </ul>
        ) : (
          <p className="mt-1 text-sm text-muted-foreground">Select a step to see its details.</p>
        )}
      </section>

      <details className="rounded-card border border-border bg-surface" data-testid="diagram-step-list">
        <summary className="cursor-pointer px-4 py-3 text-sm font-semibold">Steps as a list</summary>
        <div className="px-4 pb-4">
          <ScrollTable label="Every step of the workflow and where this request stands">
            <THead>
              <TR>
                <TH>Step</TH>
                <TH>Status</TH>
                <TH>What it means</TH>
              </TR>
            </THead>
            <TBody>
              {path.diagram.nodes.map((n) => {
                const v = views.get(n.id) as StepView
                return (
                  <TR key={n.id}>
                    <TD className="font-semibold">{n.label}</TD>
                    <TD>{statusLabel(v.status, operator)}</TD>
                    <TD className="text-muted-foreground">{describeStep(v, ctx).summary}</TD>
                  </TR>
                )
              })}
            </TBody>
          </ScrollTable>
        </div>
      </details>
    </div>
  )
}

/** The popover on the step: text only (the details panel under the canvas holds the links). */
function StepPopover({ view, run, operator }: { view: StepView; run: RunDetail; operator: boolean }) {
  const d = describeStep(view, { run, operator })
  return (
    <NodeToolbar nodeId={view.id} isVisible position={Position.Bottom} offset={14}>
      <div aria-hidden="true" className="pointer-events-none max-w-72 rounded-control border border-border bg-surface px-3 py-2 text-sm shadow-card" data-testid="step-popover">
        <p className="font-semibold">{statusLabel(view.status, operator)}</p>
        <p>{d.summary}</p>
        {d.lines.slice(0, 2).map((l) => (
          <p key={l.label} className="text-muted-foreground">
            {l.label}: {l.value}
          </p>
        ))}
      </div>
    </NodeToolbar>
  )
}

function StepDetails({ ws, view, run, operator }: { ws: string; view: StepView; run: RunDetail; operator: boolean }) {
  const d = describeStep(view, { run, operator })
  return (
    <div className="mt-2 space-y-3 text-sm" data-testid="step-details-body">
      <p>
        <span className="font-semibold">{view.label}</span> <span className="text-muted-foreground">({statusLabel(view.status, operator)})</span>
      </p>
      <p>{d.summary}</p>
      {d.lines.length > 0 ? (
        <dl className="grid gap-x-4 gap-y-1 sm:grid-cols-[10rem_1fr]">
          {d.lines.map((l) => (
            <div key={l.label} className="contents">
              <dt className="font-semibold text-muted-foreground">{l.label}</dt>
              <dd>{l.value}</dd>
            </div>
          ))}
        </dl>
      ) : null}
      {d.childRuns.length > 0 ? (
        <ul className="space-y-1">
          {d.childRuns.map((c) => (
            <li key={c.id}>
              <Link className={cn('font-semibold underline')} to={`/w/${ws}/runs/${c.id}`}>
                {c.text}
              </Link>
            </li>
          ))}
        </ul>
      ) : null}
      {operator && d.technical.length > 0 ? (
        <div>
          <h4 className="mb-1 text-xs tracking-wide text-muted-foreground uppercase">Technical details</h4>
          <dl className="grid gap-x-4 gap-y-1 font-mono text-xs sm:grid-cols-[10rem_1fr]">
            {d.technical.map((l) => (
              <div key={l.label} className="contents">
                <dt className="font-semibold text-muted-foreground">{l.label}</dt>
                <dd className="break-all">{l.value}</dd>
              </div>
            ))}
          </dl>
        </div>
      ) : null}
    </div>
  )
}

const SCOPE_HOW: Record<string, string> = {
  completed: 'finished',
  timedOut: 'left because its time limit passed',
  error: 'left because a step raised an error',
  signal: 'stopped by a signal handler',
  timer: 'stopped by a timed handler',
}

/** What a section or handler frame on the run diagram says, and how it is drawn (docs/build/S71). */
export function runFrames(path: RunPath): FrameSpec[] {
  const label = (id: string) => path.diagram.nodes.find((n) => n.id === id)?.label ?? id
  return (path.diagram.scopes ?? []).map((s) => {
    const visits = (path.scopeVisits ?? []).filter((v) => v.scopeId === s.id)
    const last = visits[visits.length - 1]
    const event = s.type === 'event'
    const state: FrameSpec['state'] = !last ? 'idle' : last.how ? 'passed' : 'active'
    const word = !last ? (event ? 'not started' : 'not entered') : last.how ? (SCOPE_HOW[last.how] ?? last.how) : 'in progress'
    const route = s.routes.find((r) => path.taken.includes(r.id))
    return {
      id: s.id,
      kind: event ? 'handler' : 'section',
      title: `${event ? 'Handler' : 'Section'}: ${s.label}`,
      caption: route ? `${word}, continued at ${label(route.to)}` : word,
      nodes: s.nodes,
      state,
    }
  })
}

/** Sections and handlers of the release and what happened to them in this request (docs/build/S59). */
export function ScopeList({ path }: { path: RunPath }) {
  const scopes = path.diagram.scopes ?? []
  if (!scopes.length) return null
  const label = (id: string) => path.diagram.nodes.find((n) => n.id === id)?.label ?? id
  return (
    <section aria-labelledby="run-scopes-heading" className="rounded-card border border-border bg-surface p-4" data-testid="run-scopes">
      <h3 id="run-scopes-heading" className="text-sm font-semibold">Sections and handlers</h3>
      <ul className="mt-2 space-y-2 text-sm">
        {scopes.map((s) => {
          const visits = (path.scopeVisits ?? []).filter((v) => v.scopeId === s.id)
          const last = visits[visits.length - 1]
          const state = !last ? (s.type === 'event' ? 'not started' : 'not entered') : last.how ? (SCOPE_HOW[last.how] ?? last.how) : 'in progress'
          const routes = s.routes.filter((r) => path.taken.includes(r.id)).map((r) => `continued at ${label(r.to)}`)
          return (
            <li key={s.id}>
              <span className="font-semibold">{s.type === 'event' ? 'Handler' : 'Section'} {s.label}</span>
              {s.type === 'event' ? ` (starts on ${s.trigger ?? 'a trigger'}${s.interrupting ? ', stops the rest of the request' : ', runs alongside'})` : ''}: {state}
              {routes.length ? `, ${routes.join(', ')}` : ''}. Steps: {s.nodes.map(label).join(', ') || 'none'}.
            </li>
          )
        })}
      </ul>
    </section>
  )
}
