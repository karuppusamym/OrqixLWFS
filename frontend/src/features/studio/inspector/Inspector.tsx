import { AlertTriangle, Trash2 } from 'lucide-react'
import { useMemo } from 'react'
import { FormField } from '@/components/common/FormField'
import { Button } from '@/components/ui/button'
import { useStudio, useStudioContext } from '../StudioContext'
import { locateIssue } from '../model/issues'
import { KINDS } from '../model/kinds'
import { nodeById } from '../model/graph'
import { routeText } from '../model/summary'
import { KIND_ICON } from '../nodeMeta'
import { BranchInspector } from './BranchInspector'
import { DecisionInspector } from './DecisionInspector'
import { HttpInspector } from './HttpInspector'
import { ChildInspector, ParallelInspector, WaitInspector } from './PatternInspectors'
import { ApprovalInspector, EndInspector, NotificationInspector, WorkflowInspector } from './SimpleInspectors'
import { StartInspector } from './StartInspector'
import { CommitInput } from './controls'

/** Right-hand inspector (320 px): basic fields first, Advanced collapsed. */
export function Inspector({ onDeleteNode }: { onDeleteNode: (nodeId: string) => void }) {
  const selection = useStudio((s) => s.selection)
  const def = useStudio((s) => s.definition)
  const { store, readOnly, connections } = useStudioContext()

  if (!selection) return <WorkflowInspector />

  if (selection.type === 'edge') {
    const t = def.transitions.find((x) => x.id === selection.id)
    if (!t) return <WorkflowInspector />
    const from = nodeById(def, t.from)
    return (
      <div className="space-y-4 p-4">
        <h2 className="text-base">Connection</h2>
        <p className="text-sm">
          <span className="font-semibold">{routeText(def, t, connections) || 'Next'}</span>: {from?.label} &rarr; {nodeById(def, t.to)?.label}
        </p>
        <FormField label="Label on the canvas" optionalText id={`edge-label-${t.id}`}>
          {(ids) => <CommitInput id={ids.input} value={t.label ?? ''} maxLength={80} disabled={readOnly} onCommit={(v) => store.getState().updateTransition(t.id, { label: v.trim() })} />}
        </FormField>
        {t.on === 'condition' || t.on === 'default' ? (
          <Button variant="secondary" onClick={() => from && store.getState().select({ type: 'node', id: from.id })}>
            Edit the paths of &ldquo;{from?.label}&rdquo;
          </Button>
        ) : null}
        {!readOnly ? (
          <Button variant="danger" onClick={() => store.getState().removeTransition(t.id)}>
            <Trash2 aria-hidden="true" /> Delete connection
          </Button>
        ) : null}
      </div>
    )
  }

  const node = nodeById(def, selection.id)
  if (!node) return <WorkflowInspector />
  return <NodeInspector key={node.id} nodeId={node.id} onDeleteNode={onDeleteNode} />
}

function NodeInspector({ nodeId, onDeleteNode }: { nodeId: string; onDeleteNode: (nodeId: string) => void }) {
  const def = useStudio((s) => s.definition)
  const issues = useStudio((s) => s.issues)
  const { store, readOnly } = useStudioContext()
  const node = nodeById(def, nodeId)
  const mine = useMemo(() => (node ? issues.filter((i) => locateIssue(def, i).nodeId === node.id) : []), [def, issues, node])
  if (!node) return null
  const info = KINDS[node.type]
  const Icon = KIND_ICON[node.type]

  return (
    <div className="space-y-5 p-4" data-testid="inspector">
      <header className="space-y-3">
        <p className="flex items-center gap-1.5 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
          <Icon className="size-4" aria-hidden="true" /> {info.caption}
        </p>
        <FormField label="Step name" required id={`node-label-${node.id}`}>
          {(ids) => (
            <CommitInput
              id={ids.input}
              data-field="label"
              value={node.label}
              maxLength={200}
              disabled={readOnly}
              onCommit={(v) => store.getState().updateNodeLabel(node.id, v)}
            />
          )}
        </FormField>
        {mine.length > 0 ? (
          <ul className="space-y-1.5" aria-label="Problems with this step">
            {mine.map((i, n) => (
              <li key={`${i.code}-${i.path}-${n}`} className={i.severity === 'blocker' ? 'flex gap-2 text-sm text-danger' : 'flex gap-2 text-sm text-warning-foreground'}>
                <AlertTriangle className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
                <span>
                  <span className="font-semibold">{i.severity === 'blocker' ? 'Blocker' : 'Warning'}:</span> {i.message}
                </span>
              </li>
            ))}
          </ul>
        ) : null}
      </header>

      {node.type === 'start' ? <StartInspector node={node} /> : null}
      {node.type === 'approval' ? <ApprovalInspector node={node} /> : null}
      {node.type === 'decision' ? <DecisionInspector node={node} /> : null}
      {node.type === 'branch' ? <BranchInspector node={node} /> : null}
      {node.type === 'http' ? <HttpInspector node={node} /> : null}
      {node.type === 'parallelSplit' || node.type === 'parallelJoin' ? <ParallelInspector node={node} /> : null}
      {node.type === 'wait' ? <WaitInspector node={node} /> : null}
      {node.type === 'startWorkflow' ? <ChildInspector node={node} /> : null}
      {node.type === 'notification' ? <NotificationInspector node={node} /> : null}
      {node.type === 'end' ? <EndInspector node={node} /> : null}

      {node.type !== 'start' && !readOnly ? (
        <div className="border-t border-border pt-4">
          <Button variant="danger" onClick={() => onDeleteNode(node.id)}>
            <Trash2 aria-hidden="true" /> Delete step
          </Button>
        </div>
      ) : null}
    </div>
  )
}
