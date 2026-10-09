import { AlertTriangle, Plus, Trash2 } from 'lucide-react'
import { useMemo } from 'react'
import { useAnnounce } from '@/components/common/Announcer'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/input'
import { cn } from '@/lib/cn'
import { useStudio, useStudioContext } from './StudioContext'
import { countByNode } from './model/issues'
import { topoOrder } from './model/graph'
import { KINDS } from './model/kinds'
import { canConnect } from './model/ops'
import { nodeSubtitle, routeSlotLabel } from './model/summary'
import type { RouteKind, Transition, WorkflowNode } from './model/types'
import { KIND_ICON } from './nodeMeta'

/**
 * Steps tab: the keyboard-first alternative to the canvas (required for WCAG 2.2 SC 2.5.7 Dragging Movements).
 * Steps appear in topological order. Everything the canvas can change about the graph can be changed here:
 * routes are selects ("When approved -> [step]"), steps are added after another step and can be deleted.
 */
export function StepsView({
  groupNames,
  onAddAfter,
  onDelete,
  onAddPath,
}: {
  groupNames: Record<string, string>
  onAddAfter: (nodeId: string) => void
  onDelete: (nodeId: string) => void
  onAddPath: (branchId: string) => void
}) {
  const def = useStudio((s) => s.definition)
  const issues = useStudio((s) => s.issues)
  const selection = useStudio((s) => s.selection)
  const { store, readOnly } = useStudioContext()
  const announce = useAnnounce()
  const order = useMemo(() => topoOrder(def), [def])
  const counts = useMemo(() => countByNode(def, issues), [def, issues])

  const setRoute = (from: WorkflowNode, on: RouteKind, to: string, transitionId?: string) => {
    const result = store.getState().setRoute(from.id, on, to === '' ? null : to, transitionId)
    if (!result.ok) announce(result.reason)
  }

  return (
    <div className="space-y-4 p-4">
      {readOnly ? (
        <Alert tone="info" live="off">
          You can read the steps here but not change them. Editing needs permission to edit workflows and a screen at least 1024 px wide.
        </Alert>
      ) : (
        <p className="text-sm text-muted-foreground">Steps in order. Use the selects to decide where each route leads, or add a step after another step. Everything you can do on the canvas you can do here.</p>
      )}
      <ol className="space-y-3" aria-label="Workflow steps">
        {order.map((node, index) => {
          const info = KINDS[node.type]
          const Icon = KIND_ICON[node.type]
          const selected = selection?.type === 'node' && selection.id === node.id
          const c = counts[node.id]
          const total = (c?.blockers ?? 0) + (c?.warnings ?? 0)
          const out = def.transitions.filter((t) => t.from === node.id)
          const slots: Array<{ on: RouteKind; transition?: Transition }> =
            node.type === 'approval'
              ? [{ on: 'approved', transition: out.find((t) => t.on === 'approved') }, { on: 'rejected', transition: out.find((t) => t.on === 'rejected') }]
              : node.type === 'branch'
                ? [...out.filter((t) => t.on === 'condition').map((t) => ({ on: 'condition' as const, transition: t })), { on: 'default', transition: out.find((t) => t.on === 'default') }]
                : node.type === 'end'
                  ? []
                  : [{ on: 'next', transition: out.find((t) => t.on === 'next') }]
          return (
            <li key={node.id} className={cn('rounded-card border bg-surface', selected ? 'border-primary ring-2 ring-primary' : 'border-border')} data-testid={`step-row-${node.id}`} data-selected={selected || undefined}>
              <div className="flex flex-wrap items-start gap-3 p-3">
                <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-neutral-soft text-xs font-bold" aria-hidden="true">
                  {index + 1}
                </span>
                <div className="min-w-0 flex-1">
                  <button
                    type="button"
                    className="text-left text-base font-semibold text-foreground underline-offset-2 hover:underline"
                    aria-pressed={selected}
                    onClick={() => store.getState().select({ type: 'node', id: node.id })}
                  >
                    <span className="sr-only">Step {index + 1}: </span>
                    {node.label}
                  </button>
                  <p className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                    <span className="inline-flex items-center gap-1">
                      <Icon className="size-3.5" aria-hidden="true" /> {info.caption}
                    </span>
                    <span>&middot; {nodeSubtitle(def, node, groupNames)}</span>
                  </p>
                </div>
                {total > 0 ? (
                  <Badge tone={c && c.blockers > 0 ? 'danger' : 'warning'}>
                    <AlertTriangle aria-hidden="true" /> {total} {total === 1 ? 'issue' : 'issues'}
                  </Badge>
                ) : null}
                {!readOnly ? (
                  <div className="flex gap-2">
                    {node.type !== 'end' ? (
                      <Button size="sm" variant="secondary" onClick={() => onAddAfter(node.id)} aria-label={`Add step after ${node.label}`}>
                        <Plus aria-hidden="true" /> Add step after&hellip;
                      </Button>
                    ) : null}
                    {node.type !== 'start' ? (
                      <Button size="sm" variant="ghost" onClick={() => onDelete(node.id)} aria-label={`Delete ${node.label}`}>
                        <Trash2 aria-hidden="true" /> Delete
                      </Button>
                    ) : null}
                  </div>
                ) : null}
              </div>
              {node.type === 'end' ? (
                <p className="border-t border-border px-3 py-2 text-sm text-muted-foreground">Ends the workflow.</p>
              ) : (
                <div className="space-y-2 border-t border-border bg-surface-muted px-3 py-3">
                  {slots.map((slot) => {
                    const id = `route-${node.id}-${slot.transition?.id ?? slot.on}`
                    const label = routeSlotLabel(def, slot.transition, slot.on)
                    return (
                      <div key={id} className="grid items-center gap-2 sm:grid-cols-[minmax(0,14rem)_auto_minmax(0,1fr)]">
                        <Label htmlFor={id} className="font-medium">
                          {label}
                        </Label>
                        <span aria-hidden="true" className="hidden text-muted-foreground sm:inline">
                          &rarr;
                        </span>
                        <Select id={id} value={slot.transition?.to ?? ''} disabled={readOnly} onChange={(e) => setRoute(node, slot.on, e.target.value, slot.transition?.id)}>
                          <option value="">(not connected)</option>
                          {def.nodes
                            .filter((n) => n.id !== node.id && n.type !== 'start')
                            .map((n) => {
                              const ok = n.id === slot.transition?.to || canConnect(def, { from: node.id, to: n.id, on: slot.on, ...(slot.transition ? { transitionId: slot.transition.id } : {}) }).ok
                              return (
                                <option key={n.id} value={n.id} disabled={!ok}>
                                  {n.label}
                                  {ok ? '' : ' (not possible here)'}
                                </option>
                              )
                            })}
                        </Select>
                      </div>
                    )
                  })}
                  {node.type === 'branch' && !readOnly ? (
                    <Button size="sm" variant="secondary" onClick={() => onAddPath(node.id)}>
                      <Plus aria-hidden="true" /> Add a path
                    </Button>
                  ) : null}
                </div>
              )}
            </li>
          )
        })}
      </ol>
    </div>
  )
}

