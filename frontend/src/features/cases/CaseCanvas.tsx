import '@xyflow/react/dist/style.css'
import {
  Background,
  BackgroundVariant,
  Controls,
  Handle,
  MarkerType,
  NodeResizer,
  Position,
  ReactFlow,
  ReactFlowProvider,
  applyNodeChanges,
  useReactFlow,
  type Connection,
  type Edge,
  type Node,
  type NodeChange,
  type NodeProps,
} from '@xyflow/react'
import { Clock, FolderOpen, User, Workflow } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { useAnnounce } from '@/components/common/Announcer'
import { cn } from '@/lib/cn'
import { KIND_LABEL, autoLayout, bounds, caseItems, entrySources, hasEntry, isListener, occurs, type CaseModelItem } from './caseModel'
import { connect, connectionProblem, disconnect, fitStages, moveItem, parentOf, reparent, resizeStage, stageAt, type RuleKind } from './caseCanvasOps'

interface CaseNodeData extends Record<string, unknown> {
  item: CaseModelItem
  selected: boolean
  readOnly: boolean
  onResize: (id: string, w: number, h: number) => void
}
type CaseFlowNode = Node<CaseNodeData, 'cmmn'>

/** Words for the marker row (manual, required, repeats, discretionary) also printed on the shape. */
function markers(item: CaseModelItem): string[] {
  const words: string[] = []
  if (item.manual) words.push('Manual start')
  if (item.required) words.push('Required')
  if (item.repetitions) words.push(`Repeats ${item.repetitions}×`)
  if (item.discretionary) words.push('Discretionary')
  if (item.when?.clauses.length) words.push('Data condition')
  if (item.kind === 'timerEventListener' && item.timer) words.push(item.timer)
  return words
}

const HANDLE = '!h-4 !w-3 !rounded-none !border-0 !bg-transparent'

/** Hollow diamond (entry criterion) on the left, filled diamond (exit criterion) on the right, a source dot below. */
function RuleHandles({ item, readOnly }: { item: CaseModelItem; readOnly: boolean }) {
  const canEnter = !isListener(item) && !item.discretionary
  const canExit = canEnter && item.kind !== 'milestone'
  return <>
    {canEnter ? <Handle type="target" id="entry" position={Position.Left} isConnectable={!readOnly} className={HANDLE} title="Entry rule: drop a connector here (starts after)">
      <svg width={12} height={16} viewBox="0 0 12 16" aria-hidden="true" className="pointer-events-none text-foreground"><polygon points="6,1 11,8 6,15 1,8" fill={hasEntry(item) ? 'var(--surface)' : 'transparent'} stroke="currentColor" strokeWidth={1.6} opacity={hasEntry(item) ? 1 : 0.45} /></svg>
    </Handle> : null}
    {canExit ? <Handle type="target" id="exit" position={Position.Right} isConnectable={!readOnly} className={HANDLE} title="Exit rule: drop a connector here (ends when)">
      <svg width={12} height={16} viewBox="0 0 12 16" aria-hidden="true" className="pointer-events-none text-foreground"><polygon points="6,1 11,8 6,15 1,8" fill={item.exitAfter ? 'currentColor' : 'transparent'} stroke="currentColor" strokeWidth={1.6} opacity={item.exitAfter ? 1 : 0.45} /></svg>
    </Handle> : null}
    {!item.discretionary ? <Handle type="source" id="out" position={Position.Bottom} isConnectable={!readOnly} className="!size-3 !border-2 !border-foreground !bg-surface" title={`Drag from here to make another item wait until this ${occurs(item) ? 'occurs' : 'completes'}`} /> : null}
  </>
}

function CaseNode({ data }: NodeProps<CaseFlowNode>) {
  const { item, selected, readOnly } = data
  const words = markers(item)
  const ring = selected ? 'outline outline-3 outline-offset-2 outline-primary' : ''
  const dashed = item.discretionary ? 'border-dashed' : ''
  if (item.kind === 'stage') return <div className={cn('relative h-full w-full', ring)} data-testid={`canvas-${item.id}`}>
    {!readOnly ? <NodeResizer isVisible={selected} minWidth={200} minHeight={120} onResizeEnd={(_event, size) => data.onResize(item.id, size.width, size.height)} /> : null}
    <span aria-hidden="true" className="absolute inset-0 bg-foreground [clip-path:polygon(14px_0,calc(100%-14px)_0,100%_14px,100%_calc(100%-14px),calc(100%-14px)_100%,14px_100%,0_calc(100%-14px),0_14px)]" />
    <span aria-hidden="true" className="absolute inset-[2px] bg-surface/95 [clip-path:polygon(14px_0,calc(100%-14px)_0,100%_14px,100%_calc(100%-14px),calc(100%-14px)_100%,14px_100%,0_calc(100%-14px),0_14px)]" />
    <div className="relative px-4 pt-2 text-sm"><span className="font-semibold text-muted-foreground">Stage</span> <span className="font-semibold">{item.label}</span>{words.length ? <span className="text-muted-foreground"> · {words.join(' · ')}</span> : null}</div>
    <RuleHandles item={item} readOnly={readOnly} />
  </div>
  if (isListener(item)) return <div className={cn('flex h-full w-full flex-col items-center text-center', ring)} data-testid={`canvas-${item.id}`}>
    <svg width={44} height={44} viewBox="0 0 44 44" className="text-foreground" aria-hidden="true">
      <circle cx={22} cy={22} r={20.5} fill="var(--surface)" stroke="currentColor" strokeWidth={1.6} /><circle cx={22} cy={22} r={17} fill="none" stroke="currentColor" strokeWidth={1.6} />
      {item.kind === 'timerEventListener' ? <Clock x={13} y={13} width={18} height={18} strokeWidth={2.2} /> : <User x={13} y={13} width={18} height={18} strokeWidth={2.2} />}
    </svg>
    <span className="text-xs font-semibold text-muted-foreground">{KIND_LABEL[item.kind]}</span><span className="text-sm leading-4 font-semibold">{item.label}</span>{words.length ? <span className="text-xs text-muted-foreground">{words.join(' · ')}</span> : null}
    <RuleHandles item={item} readOnly={readOnly} />
  </div>
  const Icon = item.kind === 'processTask' ? Workflow : item.kind === 'caseTask' ? FolderOpen : User
  return <div className={cn('relative flex h-full w-full flex-col justify-center border-2 border-foreground bg-surface px-3 text-left', item.kind === 'milestone' ? 'rounded-full px-6 text-center' : 'rounded-lg', dashed, ring)} data-testid={`canvas-${item.id}`}>
    {item.kind !== 'milestone' ? <Icon className="absolute top-1.5 right-1.5 size-4" aria-hidden="true" /> : null}
    <span className="text-xs font-semibold text-muted-foreground">{KIND_LABEL[item.kind]}</span>
    <span className="text-sm leading-4 font-semibold break-words">{item.label}</span>
    {words.length ? <span className="text-xs text-muted-foreground">{words.join(' · ')}</span> : null}
    <RuleHandles item={item} readOnly={readOnly} />
  </div>
}

const nodeTypes = { cmmn: CaseNode }

function ariaLabel(item: CaseModelItem, lookup: Map<string, CaseModelItem>): string {
  const parts = [`${KIND_LABEL[item.kind]}: ${item.label}`, ...markers(item)]
  const sources = entrySources(item).map(id => lookup.get(id)?.label).filter(Boolean)
  if (sources.length) parts.push(`starts after ${sources.join(' and ')}`)
  if (item.exitAfter && lookup.get(item.exitAfter)) parts.push(`ends when ${lookup.get(item.exitAfter)!.label} ${occurs(lookup.get(item.exitAfter)) ? 'occurs' : 'completes'}`)
  return parts.join(', ')
}

export interface CaseCanvasProps {
  name: string
  items: CaseModelItem[]
  selected: string | null
  readOnly?: boolean
  onSelect: (id: string | null) => void
  /** Applies one undoable model change. */
  onChange: (change: (items: CaseModelItem[]) => CaseModelItem[]) => void
}

/**
 * Drawable CMMN 1.1 case plan (WM04, S110): drag items to arrange them or into a stage to nest them, resize stages,
 * and draw connectors from an item's lower dot to another item's hollow diamond (starts after) or filled diamond (ends
 * when). Every change is an ordinary undoable model edit; the form view and the inspector offer the same edits without
 * a pointer.
 */
export function CaseCanvas(props: CaseCanvasProps) {
  return <ReactFlowProvider><CanvasInner {...props} /></ReactFlowProvider>
}

function CanvasInner({ name, items, selected, readOnly = false, onSelect, onChange }: CaseCanvasProps) {
  const announce = useAnnounce()
  const laid = useMemo(() => autoLayout(items), [items])
  const lookup = useMemo(() => new Map(caseItems(laid).map(item => [item.id, item])), [laid])
  const [edgeSelected, setEdgeSelected] = useState<string | null>(null)
  const flow = useReactFlow()
  const count = caseItems(items).length
  // Show every shape again when one is added or removed (moves keep the person's zoom).
  useEffect(() => { const frame = requestAnimationFrame(() => void flow.fitView({ padding: 0.15, duration: 200 })); return () => cancelAnimationFrame(frame) }, [count, flow])

  const base = useMemo<CaseFlowNode[]>(() => {
    const out: CaseFlowNode[] = []
    const add = (nodes: CaseModelItem[], parent: CaseModelItem | null) => {
      for (const item of nodes) {
        const b = item.layout!
        out.push({
          id: item.id, type: 'cmmn', position: { x: b.x - (parent?.layout?.x ?? 0), y: b.y - (parent?.layout?.y ?? 0) },
          parentId: parent?.id, width: b.w, height: b.h, style: { width: b.w, height: b.h },
          zIndex: item.kind === 'stage' ? 0 : 1, draggable: !readOnly, selectable: true, connectable: !readOnly,
          ariaLabel: ariaLabel(item, lookup),
          data: { item, selected: item.id === selected, readOnly, onResize: (id, w, h) => onChange(current => resizeStage(autoLayout(current), id, w, h)) },
        })
        if (item.children) add(item.children, item)
      }
    }
    add(laid, null)
    return out
  }, [laid, lookup, selected, readOnly, onChange])
  // React Flow moves nodes while dragging; a new model (after any edit) replaces them.
  const [nodes, setNodes] = useState(base)
  const [shown, setShown] = useState(base)
  if (shown !== base) { setShown(base); setNodes(base) }

  const edges = useMemo<Edge[]>(() => caseItems(laid).flatMap(item => [
    ...entrySources(item).map(source => ({ id: `entry:${source}:${item.id}`, source, target: item.id, sourceHandle: 'out', targetHandle: 'entry', zIndex: 2,
      label: occurs(lookup.get(source)) ? 'occurs' : 'completes', ariaLabel: `${item.label} starts after ${lookup.get(source)?.label ?? source}`,
      style: { strokeDasharray: '6 4' }, markerEnd: { type: MarkerType.Arrow }, selected: edgeSelected === `entry:${source}:${item.id}` })),
    ...(item.exitAfter ? [{ id: `exit:${item.exitAfter}:${item.id}`, source: item.exitAfter, target: item.id, sourceHandle: 'out', targetHandle: 'exit', zIndex: 2,
      label: 'ends it', ariaLabel: `${item.label} ends when ${lookup.get(item.exitAfter)?.label ?? item.exitAfter}`,
      style: { strokeDasharray: '2 4' }, markerEnd: { type: MarkerType.Arrow }, selected: edgeSelected === `exit:${item.exitAfter}:${item.id}` }] : []),
  ]), [laid, lookup, edgeSelected])

  const change = (apply: (items: CaseModelItem[]) => CaseModelItem[], done?: string) => {
    try { onChange(current => fitStages(apply(autoLayout(current)))); if (done) announce(done) }
    catch (cause) { announce(cause instanceof Error ? cause.message : 'That change is not possible.') }
  }
  const onConnect = (connection: Connection) => {
    if (!connection.source || !connection.target) return
    const kind: RuleKind = connection.targetHandle === 'exit' ? 'exit' : 'entry'
    const target = lookup.get(connection.target)?.label, source = lookup.get(connection.source)?.label
    change(current => connect(current, connection.source!, connection.target!, kind), kind === 'exit' ? `${target} now ends when ${source} finishes.` : `${target} now starts after ${source}.`)
  }
  const isValidConnection = (connection: Connection | Edge) =>
    !!connection.source && !!connection.target && connectionProblem(laid, connection.source, connection.target, connection.targetHandle === 'exit' ? 'exit' : 'entry') === null
  const onNodeDragStop = (_event: unknown, node: Node) => {
    const item = lookup.get(node.id)
    if (!item?.layout) return
    const parent = parentOf(laid, node.id)
    const x = node.position.x + (parent?.layout?.x ?? 0), y = node.position.y + (parent?.layout?.y ?? 0)
    const target = stageAt(laid, x + item.layout.w / 2, y + item.layout.h / 2, node.id)
    if ((parent?.id ?? null) !== target) {
      const into = target ? lookup.get(target)?.label : null
      change(current => reparent(moveItem(current, node.id, x, y), node.id, target), into ? `${item.label} moved into stage ${into}. Rules that crossed stages were removed.` : `${item.label} moved to the case plan. Rules that crossed stages were removed.`)
    } else change(current => moveItem(current, node.id, x, y))
  }
  const removeSelectedEdge = () => {
    if (!edgeSelected || readOnly) return
    const [kind, source, target] = edgeSelected.split(':') as [RuleKind, string, string]
    change(current => disconnect(current, source, target, kind), 'Rule removed.')
    setEdgeSelected(null)
  }
  const frame = bounds(laid)

  return <div className="space-y-2">
    <div className="relative h-[520px] overflow-hidden rounded-card border border-border bg-canvas" data-testid="case-canvas">
      <span className="absolute top-2 left-3 z-10 rounded-t-md border-2 border-b-0 border-foreground bg-surface px-3 py-0.5 text-sm font-semibold">Case plan: {name || 'Untitled case'}</span>
      <ReactFlow
        nodes={nodes} edges={edges} nodeTypes={nodeTypes}
        onNodesChange={(changes: NodeChange<CaseFlowNode>[]) => setNodes(current => applyNodeChanges(changes.filter(c => c.type === 'position' || c.type === 'dimensions' || c.type === 'select'), current))}
        onConnect={onConnect} isValidConnection={isValidConnection}
        onNodeClick={(_event, node) => { onSelect(node.id); setEdgeSelected(null) }}
        onEdgeClick={(_event, edge) => { setEdgeSelected(edge.id); onSelect(null) }}
        onPaneClick={() => { onSelect(null); setEdgeSelected(null) }}
        onNodeDragStop={onNodeDragStop}
        nodesDraggable={!readOnly} nodesConnectable={!readOnly} elementsSelectable deleteKeyCode={null}
        fitView fitViewOptions={{ padding: 0.15 }} minZoom={0.3} maxZoom={1.6} proOptions={{ hideAttribution: true }}
        aria-label={`Case plan canvas for ${name || 'Untitled case'}${frame ? '' : ', empty'}`}
      >
        <Background variant={BackgroundVariant.Dots} gap={16} size={1} />
        <Controls showInteractive={false} />
      </ReactFlow>
    </div>
    {edgeSelected && !readOnly ? <div className="flex flex-wrap items-center gap-2 text-sm" role="group" aria-label="Selected rule">
      <span>{edges.find(edge => edge.id === edgeSelected)?.ariaLabel}</span>
      <button type="button" className="rounded border px-2 py-1" onClick={removeSelectedEdge}>Remove this rule</button>
    </div> : null}
    <p className="text-sm text-muted-foreground">Drag shapes to arrange them, or onto a stage to put them inside it. Drag from the dot under an item to the hollow diamond of another item to make it start after the first, or to the filled diamond to make it end then. Select a connector to remove it. Every change can be undone, and the form view offers the same edits from the keyboard.</p>
  </div>
}
