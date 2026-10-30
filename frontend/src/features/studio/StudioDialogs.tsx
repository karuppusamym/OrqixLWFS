import { useEffect, useMemo, useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { useAnnounce } from '@/components/common/Announcer'
import { ConfirmDialog } from '@/components/common/ConfirmDialog'
import { FormField } from '@/components/common/FormField'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Dialog } from '@/components/ui/dialog'
import { Input, Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { downloadJson } from '@/lib/download'
import { formatDateTime } from '@/lib/dates'
import { savePayload } from '@/stores/studioStore'
import { ConditionEditor, defaultLiteral } from './inspector/ConditionEditor'
import { useStudio, useStudioContext } from './StudioContext'
import { availableFields } from './model/fieldRefs'
import { nodeById, outgoing, wouldCreateCycle } from './model/graph'
import { KINDS, PALETTE_KINDS } from './model/kinds'
import { planNodeDeletion, type OpResult } from './model/ops'
import { targetLabel } from './model/summary'
import { normalizeDefinition, type Condition, type Definition, type NodeKind, type RouteKind, type WorkflowNode } from './model/types'

// Delete confirmation -----------------------------------------------------------------------------------------------

export function DeleteNodeDialog({ nodeId, onClose }: { nodeId: string | null; onClose: () => void }) {
  const { store } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const announce = useAnnounce()
  const plan = nodeId ? planNodeDeletion(def, nodeId) : null
  return (
    <ConfirmDialog
      open={!!plan}
      title={plan ? `Delete "${plan.node.label}"?` : 'Delete step?'}
      confirmLabel="Delete step"
      destructive
      onCancel={onClose}
      onConfirm={() => {
        if (nodeId) {
          store.getState().removeNode(nodeId)
          announce('Step deleted. Press Ctrl+Z to undo.')
        }
        onClose()
      }}
    >
      {plan ? (
        <>
          <p>Deleting this step will also remove:</p>
          <ul className="list-disc space-y-1 pl-5">
            {plan.transitions.map((t) => (
              <li key={t.id}>
                The connection from {targetLabel(def, t.from)} to {targetLabel(def, t.to)}
              </li>
            ))}
            {plan.decisions.map((d) => (
              <li key={d}>The rule table &ldquo;{d}&rdquo;, which only this step uses</li>
            ))}
            {plan.outputs.map((o) => (
              <li key={o}>{o[0]?.toUpperCase()}{o.slice(1)}</li>
            ))}
            <li>Its position on the canvas</li>
          </ul>
          <p>You can undo this with Ctrl+Z (Cmd+Z on Mac).</p>
        </>
      ) : null}
    </ConfirmDialog>
  )
}

// Conflict ---------------------------------------------------------------------------------------------------------------

/**
 * Shown on 412 STALE_REVISION: "A newer version was saved by {name}." Reload latest (replaces the editor) or
 * Download my version (JSON) so no work is lost. Autosave stays stopped until the draft is reloaded.
 */
export function ConflictDialog() {
  const { store, saver, wsId, workflowId } = useStudioContext()
  const status = useStudio((s) => s.saveStatus)
  const conflictRevision = useStudio((s) => s.conflictRevision)
  const [latest, setLatest] = useState<{ name: string; savedAt: string; revision: number } | null>(null)
  const [reloading, setReloading] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const open = status === 'conflict'

  useEffect(() => {
    if (!open) return
    let cancelled = false
    endpoints
      .getDraft(wsId, workflowId)
      .then(({ draft }) => {
        if (!cancelled) setLatest({ name: draft.savedBy.displayName, savedAt: draft.savedAt, revision: draft.revision })
      })
      .catch(() => undefined)
    return () => {
      cancelled = true
    }
  }, [open, wsId, workflowId])

  const reload = async () => {
    setReloading(true)
    setError(null)
    try {
      const { draft } = await endpoints.getDraft(wsId, workflowId)
      store.getState().load({ workflowId, revision: draft.revision, definition: normalizeDefinition(draft.definition), savedAt: draft.savedAt, savedBy: draft.savedBy.displayName })
      saver.resume()
      setLatest(null)
    } catch (cause) {
      setError(cause)
    } finally {
      setReloading(false)
    }
  }

  const download = () => {
    const state = store.getState()
    downloadJson(savePayload(state), `${state.definition.workflowKey || 'workflow'}-my-version.json`)
  }

  return (
    <Dialog
      open={open}
      onClose={() => undefined}
      dismissible={false}
      title="Your draft is out of date"
      description={
        <span data-testid="conflict-message">
          A newer version was saved by {latest?.name ?? 'someone else'}
          {latest ? ` on ${formatDateTime(latest.savedAt)} (revision ${latest.revision})` : conflictRevision ? ` (revision ${conflictRevision})` : ''}.
        </span>
      }
      footer={
        <>
          <Button variant="secondary" onClick={download}>
            Download my version
          </Button>
          <Button variant="primary" onClick={() => void reload()} loading={reloading}>
            Reload latest
          </Button>
        </>
      }
    >
      <div className="space-y-3 text-sm">
        <p>Saving stopped so nothing is overwritten. Your changes are still on this screen.</p>
        <p>
          <strong>Download my version</strong> saves your draft as a JSON file. <strong>Reload latest</strong> replaces what you see with the newest saved draft, so download first if you want to keep your changes.
        </p>
        {error ? <ProblemAlert error={error} /> : null}
      </div>
    </Dialog>
  )
}

// Route (condition) dialog -----------------------------------------------------------------------------------------------

/** Condition editor for a new path out of a choice step, opened when a connection is drawn or "Add a path" is used. */
export function BranchRouteDialog({ request, onClose }: { request: { from: string; to: string | null } | null; onClose: () => void }) {
  const { store } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const announce = useAnnounce()
  const from = request ? nodeById(def, request.from) : undefined
  const fields = useMemo(() => (from ? availableFields(def, from.id) : []), [def, from])
  const hasDefault = request ? outgoing(def, request.from).some((t) => t.on === 'default') : false
  const first = fields.find((f) => f.type === 'number') ?? fields[0]
  const [to, setTo] = useState('')
  const [otherwise, setOtherwise] = useState(false)
  const [label, setLabel] = useState('')
  const [condition, setCondition] = useState<Condition>({ fieldRef: first?.ref ?? '', operator: 'eq', value: defaultLiteral(first?.type, first?.enumValues) })
  const [error, setError] = useState<string>()
  const target = request?.to ?? to
  const targets = request && from ? def.nodes.filter((n) => n.type !== 'start' && n.id !== from.id && !outgoing(def, from.id).some((t) => t.to === n.id) && !wouldCreateCycle(def, from.id, n.id)) : []

  const submit = () => {
    if (!request || !target) return setError('Choose the step this path leads to.')
    const result: OpResult & { transitionId?: string } = store.getState().connect({
      from: request.from,
      to: target,
      on: otherwise ? 'default' : 'condition',
      ...(otherwise ? {} : { condition }),
      ...(label.trim() ? { label: label.trim() } : {}),
    })
    if (!result.ok) return setError(result.reason)
    announce('Path added')
    onClose()
  }

  return (
    <Dialog
      open={!!request}
      onClose={onClose}
      title={request?.to ? `Path to ${targetLabel(def, request.to)}` : 'Add a path'}
      description={from ? `When should "${from.label}" take this path?` : undefined}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" onClick={submit}>
            Add path
          </Button>
        </>
      }
    >
      {request ? (
        <div className="space-y-4">
          {!request.to ? (
            <div className="space-y-1.5">
              <Label htmlFor="route-target">Leads to</Label>
              <Select id="route-target" value={to} onChange={(e) => setTo(e.target.value)}>
                <option value="">Choose a step</option>
                {targets.map((n) => (
                  <option key={n.id} value={n.id}>
                    {n.label}
                  </option>
                ))}
              </Select>
            </div>
          ) : null}
          <div className="flex items-start gap-3">
            <Checkbox id="route-otherwise" checked={otherwise} disabled={hasDefault} onCheckedChange={(v) => setOtherwise(v === true)} />
            <div>
              <Label htmlFor="route-otherwise">Otherwise: take this path when no condition matches</Label>
              {hasDefault ? <p className="text-xs text-muted-foreground">This choice already has an Otherwise path. Change it in the step&apos;s settings.</p> : null}
            </div>
          </div>
          {!otherwise ? <ConditionEditor idBase="route-new" dataFieldBase="newroute" condition={condition} fields={fields} onChange={setCondition} /> : null}
          <FormField label="Label on the canvas" optionalText id="route-new-label">
            {(ids) => <Input id={ids.input} value={label} maxLength={80} onChange={(e) => setLabel(e.target.value)} />}
          </FormField>
          {error ? <Alert tone="danger">{error}</Alert> : null}
        </div>
      ) : null}
    </Dialog>
  )
}

// Add step after... -------------------------------------------------------------------------------------------------------

export interface RouteSlot {
  on: RouteKind
  transitionId?: string
  label: string
}

/** The places where a new step can be added after `node`. */
export function routeSlots(def: Definition, node: WorkflowNode): RouteSlot[] {
  const out = outgoing(def, node.id)
  switch (node.type) {
    case 'end':
      return []
    case 'approval':
      return [
        { on: 'approved', label: 'When approved' },
        { on: 'rejected', label: 'When rejected' },
        ...(node.config.timeoutAfter ? [{ on: 'timedOut' as const, label: 'When timed out' }] : []),
      ]
    case 'waitForMessage':
      return [{ on: 'next', label: 'When the message arrives' }, ...(node.config.timeoutAfter ? [{ on: 'timedOut' as const, label: 'When no message arrives in time' }] : [])]
    case 'http':
      return [{ on: 'next', label: 'Next' }, { on: 'failed', label: 'When failed' }]
    case 'parallelSplit':
      return [...out.map((t) => ({ on: 'next' as const, transitionId: t.id, label: `After the branch to ${targetLabel(def, t.to)}` })), { on: 'next', label: 'New parallel branch' }]
    case 'branch':
      return [
        ...out.filter((t) => t.on === 'condition').map((t) => ({ on: 'condition' as const, transitionId: t.id, label: `After the path to ${targetLabel(def, t.to)}` })),
        ...(out.some((t) => t.on === 'default') ? [{ on: 'default' as const, label: 'After the Otherwise path' }] : []),
      ]
    default:
      return [{ on: 'next', label: 'Next' }]
  }
}

export function AddStepDialog({ afterId, onClose }: { afterId: string | null; onClose: () => void }) {
  const { store } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const announce = useAnnounce()
  const node = afterId ? nodeById(def, afterId) : undefined
  const slots = node ? routeSlots(def, node) : []
  const [kind, setKind] = useState<NodeKind>('approval')
  const [slotIndex, setSlotIndex] = useState('0')
  const [error, setError] = useState<string>()

  const submit = () => {
    if (!node) return
    const slot = slots[Number(slotIndex)] ?? slots[0] ?? null
    const result = store.getState().addNodeAfter(node.id, kind, slot ? { on: slot.on, ...(slot.transitionId ? { transitionId: slot.transitionId } : {}) } : null)
    if (!result.ok) return setError(result.reason)
    announce(`${KINDS[kind].paletteLabel} step added after ${node.label}`)
    onClose()
  }

  return (
    <Dialog
      open={!!node}
      onClose={onClose}
      title={node ? `Add a step after "${node.label}"` : 'Add a step'}
      description="The new step is placed on this route. If the route already leads somewhere, the new step sits in between."
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" onClick={submit}>
            Add step
          </Button>
        </>
      }
    >
      {node ? (
        <div className="space-y-5">
          <fieldset className="space-y-2">
            <legend className="text-sm font-semibold">What kind of step?</legend>
            <RadioGroup value={kind} onValueChange={(v) => setKind(v as NodeKind)}>
              {PALETTE_KINDS.map((k) => (
                <div key={k} className="flex items-start gap-3 rounded-control border border-border p-2.5">
                  <RadioGroupItem value={k} id={`add-kind-${k}`} />
                  <Label htmlFor={`add-kind-${k}`} className="font-normal">
                    <span className="font-semibold">{KINDS[k].paletteLabel}</span>
                    <span className="block text-muted-foreground">{KINDS[k].description}</span>
                  </Label>
                </div>
              ))}
            </RadioGroup>
          </fieldset>
          {slots.length > 1 ? (
            <div className="space-y-1.5">
              <Label htmlFor="add-slot">Where?</Label>
              <Select id="add-slot" value={slotIndex} onChange={(e) => setSlotIndex(e.target.value)}>
                {slots.map((s, i) => (
                  <option key={`${s.on}-${s.transitionId ?? i}`} value={String(i)}>
                    {s.label}
                  </option>
                ))}
              </Select>
            </div>
          ) : null}
          {error ? <Alert tone="danger">{error}</Alert> : null}
        </div>
      ) : null}
    </Dialog>
  )
}
