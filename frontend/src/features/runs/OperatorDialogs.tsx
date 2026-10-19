import { useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, Ban, Pause, Play } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import * as endpoints from '@/api/endpoints'
import { fingerprintOf, useIdempotencyKey } from '@/api/idempotency'
import { type ProblemError } from '@/api/problem'
import { keys, useConnection, useGroups, useRelease, useRunDiagnosis, useRunMigrationOptions } from '@/api/queries'
import type { Effect, RunDetail, TaskSummary } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { useAnnounce } from '@/components/common/Announcer'
import { FormField } from '@/components/common/FormField'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { formatDateTime } from '@/lib/dates'
import { describeProblem, fieldErrorMap } from '@/lib/problemMessages'
import { candidateEffects, EFFECT_STATE_LABEL } from './operatorText'

/**
 * Operator dialogs (docs/build/S7-frontend.md section 4): Retry, Reconcile, Cancel, Reassign, and Suspend/Resume
 * (docs/build/S37-suspend-resume.md).
 * Every one requires a reason. Each keeps one idempotency key per unchanged input, so a retry after a
 * timeout cannot repeat the action.
 */
interface DialogProps {
  open: boolean
  run: RunDetail
  onClose: () => void
}

function useRefresh(runId: string) {
  const client = useQueryClient()
  const { workspace } = useWorkspace()
  const ws = workspace.id
  return async () => {
    await Promise.all([
      client.invalidateQueries({ queryKey: keys.run(ws, runId), exact: true }),
      client.invalidateQueries({ queryKey: keys.runEvents(ws, runId) }),
      client.invalidateQueries({ queryKey: keys.tasks(ws) }),
      client.invalidateQueries({ queryKey: ['ws', ws, 'runs'] }),
    ])
  }
}

function ReasonField({ id, label = 'Reason', value, onChange, error, min, max = 500, help }: { id: string; label?: string; value: string; onChange: (v: string) => void; error?: string | undefined; min?: number; max?: number; help?: ReactNode }) {
  return (
    <FormField label={label} required id={id} error={error} description={help ?? 'Recorded in the audit log with your name.'}>
      {(ids) => (
        <>
          <Textarea id={ids.input} value={value} onChange={(e) => onChange(e.target.value)} maxLength={max} rows={3} aria-invalid={error ? true : undefined} aria-describedby={ids.describedBy} aria-required />
          <p className="text-right text-xs text-muted-foreground" aria-hidden="true">
            {value.length} / {max}
            {min ? ` (at least ${min})` : ''}
          </p>
        </>
      )}
    </FormField>
  )
}

function DialogError({ error, fallback }: { error: ProblemError | null; fallback?: ReactNode }) {
  if (!error) return null
  const ui = describeProblem(error)
  return (
    <div className="mt-4">
      <ProblemAlert error={error} />
      {fallback ? <div className="mt-2">{fallback}</div> : null}
      {ui.behavior === 'operator-message' && ui.retryable ? <p className="mt-2 text-sm text-muted-foreground">You can press the button again; it will not repeat the action.</p> : null}
    </div>
  )
}

// Retry -------------------------------------------------------------------------------------------

export function RetryDialog({ open, run, onClose, onNeedReconcile }: DialogProps & { onNeedReconcile: () => void }) {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const refresh = useRefresh(run.id)
  const announce = useAnnounce()
  const key = useIdempotencyKey()
  const effects = candidateEffects(run.effects)
  const [effectId, setEffectId] = useState(effects[0]?.id ?? '')
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState<string>()
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ProblemError | null>(null)

  const submit = async () => {
    if (!reason.trim()) return setReasonError('Add a reason. It is recorded in the audit log.')
    setReasonError(undefined)
    setPending(true)
    setError(null)
    try {
      const idem = key.keyFor(fingerprintOf(run.id, 'retry', effectId, reason.trim(), run.version))
      await endpoints.retryRun(ws, run.id, { reason: reason.trim(), expectedVersion: run.version, ...(effectId ? { effectId } : {}) }, idem)
      await refresh()
      announce('Retry scheduled. The request is running again.')
      onClose()
    } catch (cause) {
      setError(toProblem(cause))
    } finally {
      setPending(false)
    }
  }

  const needsReconcile = error?.code === 'EFFECT_NOT_RECONCILED'
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="Retry this step"
      dismissible={!pending}
      description="Run the failed step again. Only do this when you know the other system did not already complete it."
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={pending}>
            Cancel
          </Button>
          <Button variant="primary" onClick={() => void submit()} loading={pending}>
            Retry step
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        {effects.length > 1 ? (
          <div className="space-y-1.5">
            <Label htmlFor="retry-effect">Step to retry</Label>
            <Select id="retry-effect" value={effectId} onChange={(e) => setEffectId(e.target.value)}>
              {effects.map((e) => (
                <option key={e.id} value={e.id}>
                  {e.label ?? e.nodeId} ({EFFECT_STATE_LABEL[e.state]})
                </option>
              ))}
            </Select>
          </div>
        ) : null}
        <ReasonField id="retry-reason" value={reason} onChange={setReason} error={reasonError} />
        <DialogError
          error={error}
          fallback={
            needsReconcile ? (
              <Button variant="secondary" onClick={onNeedReconcile}>
                Reconcile the outcome first
              </Button>
            ) : null
          }
        />
      </div>
    </Dialog>
  )
}

// Cancel ------------------------------------------------------------------------------------------

export function CancelDialog({ open, run, onClose }: DialogProps) {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const refresh = useRefresh(run.id)
  const announce = useAnnounce()
  const key = useIdempotencyKey()
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState<string>()
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ProblemError | null>(null)
  const done = (run.effects ?? []).filter((e) => e.state === 'succeeded')

  const submit = async () => {
    if (!reason.trim()) return setReasonError('Add a reason. The requester sees it.')
    setReasonError(undefined)
    setPending(true)
    setError(null)
    try {
      const idem = key.keyFor(fingerprintOf(run.id, 'cancel', reason.trim(), run.version))
      await endpoints.cancelRun(ws, run.id, { reason: reason.trim(), expectedVersion: run.version }, idem)
      await refresh()
      announce('The request was cancelled.')
      onClose()
    } catch (cause) {
      setError(toProblem(cause))
    } finally {
      setPending(false)
    }
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="Cancel this request"
      dismissible={!pending}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={pending}>
            Keep request
          </Button>
          <Button variant="danger-solid" onClick={() => void submit()} loading={pending}>
            {pending ? null : <Ban aria-hidden="true" />}
            Cancel request
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <Alert tone="warning" title="Cancelling does not undo work in other systems">
          {done.length > 0 ? (
            <>
              These steps already happened and stay as they are:
              <ul className="mt-1 list-disc pl-5">
                {done.map((e) => (
                  <li key={e.id}>
                    {e.label ?? e.nodeId}
                    {e.remoteReference ? ` (reference ${e.remoteReference})` : ''}
                  </li>
                ))}
              </ul>
            </>
          ) : (
            'No external step has completed yet. Anything that completes before the cancel takes effect is reported, not reversed.'
          )}
        </Alert>
        <ReasonField id="cancel-reason" value={reason} onChange={setReason} error={reasonError} help="Shown to the requester and recorded in the audit log." />
        <DialogError error={error} />
      </div>
    </Dialog>
  )
}

// Suspend and resume ------------------------------------------------------------------------------

function SuspendResumeDialog({ open, run, onClose, mode }: DialogProps & { mode: 'suspend' | 'resume' }) {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const refresh = useRefresh(run.id)
  const announce = useAnnounce()
  const key = useIdempotencyKey()
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState<string>()
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ProblemError | null>(null)
  const suspending = mode === 'suspend'

  const submit = async () => {
    if (!reason.trim()) return setReasonError('Add a reason. It is recorded in the audit log.')
    setReasonError(undefined)
    setPending(true)
    setError(null)
    try {
      const idem = key.keyFor(fingerprintOf(run.id, mode, reason.trim(), run.version))
      const body = { reason: reason.trim(), expectedVersion: run.version }
      await (suspending ? endpoints.suspendRun(ws, run.id, body, idem) : endpoints.resumeRun(ws, run.id, body, idem))
      await refresh()
      announce(suspending ? 'The request is suspended.' : 'The request was resumed.')
      onClose()
    } catch (cause) {
      setError(toProblem(cause))
    } finally {
      setPending(false)
    }
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={suspending ? 'Suspend this request' : 'Resume this request'}
      dismissible={!pending}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={pending}>
            Cancel
          </Button>
          <Button variant="primary" onClick={() => void submit()} loading={pending}>
            {pending ? null : suspending ? <Pause aria-hidden="true" /> : <Play aria-hidden="true" />}
            {suspending ? 'Suspend request' : 'Resume request'}
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <Alert tone="info" title={suspending ? 'Nothing moves while a request is suspended' : 'The request continues where it stopped'}>
          {suspending
            ? 'Approvals cannot be claimed or decided, timers do not run and messages wait. The requester is told it is paused. Cancelling is still possible.'
            : 'Timers that came due while the request was suspended fire now, and waiting messages are delivered.'}
        </Alert>
        <ReasonField id={`${mode}-reason`} value={reason} onChange={setReason} error={reasonError} />
        <DialogError error={error} />
      </div>
    </Dialog>
  )
}

export function SuspendDialog(props: DialogProps) {
  return <SuspendResumeDialog {...props} mode="suspend" />
}

export function ResumeDialog(props: DialogProps) {
  return <SuspendResumeDialog {...props} mode="resume" />
}

// Consistency check and repair (EX09) --------------------------------------------------------------
const DIAGNOSIS_TITLE: Record<string, string> = {
  inSync: 'Everything matches',
  projectionDrift: 'Open steps are missing from this request',
  strandedToken: 'A step is stuck and nothing can move it',
  emptyRequest: 'The engine holds no work for this request',
  engineMissing: 'The workflow engine has no record of this request',
  unsupported: 'The difference cannot be repaired automatically',
  protected: 'The request is suspended',
  ended: 'The request has ended',
}

export function RepairDialog({ open, run, onClose, onCancelRequest }: DialogProps & { onCancelRequest: () => void }) {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const diagnosis = useRunDiagnosis(ws, run.id, open)
  const refresh = useRefresh(run.id)
  const announce = useAnnounce()
  const key = useIdempotencyKey()
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState<string>()
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ProblemError | null>(null)
  const found = diagnosis.data
  const cancelOnly = found && (found.state === 'strandedToken' || found.state === 'engineMissing' || found.state === 'emptyRequest')

  const submit = async () => {
    if (!found) return
    if (!reason.trim()) return setReasonError('Add a reason. It is recorded in the audit log.')
    setReasonError(undefined)
    setPending(true)
    setError(null)
    try {
      const idem = key.keyFor(fingerprintOf(run.id, 'repair', reason.trim(), found.version))
      await endpoints.repairRun(ws, run.id, { reason: reason.trim(), expectedVersion: found.version }, idem)
      await refresh()
      announce('The open steps were rebuilt.')
      onClose()
    } catch (cause) {
      setError(toProblem(cause))
    } finally {
      setPending(false)
    }
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="Check this request"
      dismissible={!pending}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={pending}>
            Close
          </Button>
          {cancelOnly ? (
            <Button variant="danger" onClick={onCancelRequest}>
              <Ban aria-hidden="true" /> Cancel request
            </Button>
          ) : null}
          {found?.repairable ? (
            <Button variant="primary" onClick={() => void submit()} loading={pending}>
              Rebuild steps
            </Button>
          ) : null}
        </>
      }
    >
      <div className="space-y-4">
        {diagnosis.isLoading ? <LoadingBlock label="Comparing with the workflow engine" /> : null}
        {diagnosis.isError ? <DialogError error={toProblem(diagnosis.error)} /> : null}
        {found ? (
          <Alert tone={found.state === 'inSync' ? 'info' : found.repairable ? 'warning' : 'danger'} title={DIAGNOSIS_TITLE[found.state] ?? found.state}>
            {found.message}
          </Alert>
        ) : null}
        {found?.repairable ? (
          <>
            <p className="text-sm text-muted-foreground">
              Rebuilding only rewrites what this page shows, from approvals, date or message waits and worker steps the engine still holds. The engine is not triggered, so nothing runs twice and no outcome is invented.
            </p>
            <ReasonField id="repair-reason" value={reason} onChange={setReason} error={reasonError} />
          </>
        ) : null}
        <DialogError error={error} />
      </div>
    </Dialog>
  )
}

// Move to another release -------------------------------------------------------------------------

export function MigrateDialog({ open, run, onClose }: DialogProps) {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const refresh = useRefresh(run.id)
  const announce = useAnnounce()
  const key = useIdempotencyKey()
  const options = useRunMigrationOptions(ws, run.id, open)
  const [targetId, setTargetId] = useState('')
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState<string>()
  const [targetError, setTargetError] = useState<string>()
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ProblemError | null>(null)
  const list = options.data?.options ?? []
  const chosen = list.find((o) => o.release.id === targetId)

  const submit = async () => {
    if (!chosen?.compatible) return setTargetError('Choose a version the request can move to.')
    setTargetError(undefined)
    if (!reason.trim()) return setReasonError('Add a reason. It is recorded in the audit log.')
    setReasonError(undefined)
    setPending(true)
    setError(null)
    try {
      const idem = key.keyFor(fingerprintOf(run.id, 'migrate', chosen.release.id, reason.trim(), run.version))
      await endpoints.migrateRun(ws, run.id, { targetReleaseId: chosen.release.id, reason: reason.trim(), expectedVersion: run.version }, idem)
      await refresh()
      announce(`The request now follows version ${chosen.release.releaseNumber}. It is still suspended.`)
      onClose()
    } catch (cause) {
      setError(toProblem(cause))
    } finally {
      setPending(false)
    }
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="Move to another version"
      dismissible={!pending}
      description={`This request follows version ${run.release.releaseNumber} of the workflow. Move it to a version that keeps the part it has already been through and the step it is waiting at exactly as they are.`}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={pending}>
            Cancel
          </Button>
          <Button variant="primary" onClick={() => void submit()} loading={pending} disabled={!options.data?.ready}>
            Move request
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        {options.isPending ? <LoadingBlock rows={2} label="Loading versions" /> : null}
        {options.isError ? <ProblemAlert error={toProblem(options.error)} /> : null}
        {options.data && !options.data.ready ? <Alert tone="warning" title="Not ready to move">{options.data.notReadyReason}</Alert> : null}
        {options.data && list.length === 0 ? <p className="text-sm text-muted-foreground">There is no other published version of this workflow.</p> : null}
        {list.length > 0 ? (
          <fieldset className="space-y-2" aria-describedby={targetError ? 'migrate-target-error' : undefined}>
            <legend className="text-sm font-semibold">Version to move to</legend>
            <RadioGroup value={targetId} onValueChange={setTargetId}>
              {list.map((o) => (
                <div key={o.release.id} className="flex items-start gap-3" data-testid={`migrate-option-${o.release.releaseNumber}`}>
                  <RadioGroupItem value={o.release.id} id={`mig-${o.release.id}`} disabled={!o.compatible || !options.data?.ready} />
                  <Label htmlFor={`mig-${o.release.id}`} className="font-normal">
                    <span className="font-semibold">Version {o.release.releaseNumber}</span>
                    {o.active ? ' (used for new requests)' : ''} &middot; published {formatDateTime(o.publishedAt)}
                    {o.compatible ? (
                      <span className="block text-sm text-muted-foreground">The request can continue here.</span>
                    ) : (
                      <ul className="mt-1 list-disc pl-5 text-sm text-muted-foreground">
                        {o.findings.map((f, i) => (
                          <li key={`${f.code}-${i}`}>{f.message}</li>
                        ))}
                      </ul>
                    )}
                  </Label>
                </div>
              ))}
            </RadioGroup>
            {targetError ? <p id="migrate-target-error" className="text-sm text-danger" role="alert">{targetError}</p> : null}
          </fieldset>
        ) : null}
        <ReasonField id="migrate-reason" value={reason} onChange={setReason} error={reasonError} />
        <DialogError error={error} />
      </div>
    </Dialog>
  )
}

// Reassign ----------------------------------------------------------------------------------------

export function ReassignDialog({ open, run, onClose }: DialogProps) {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const refresh = useRefresh(run.id)
  const announce = useAnnounce()
  const openTasks = run.tasks.filter((t) => t.status === 'open')
  const [taskId, setTaskId] = useState(openTasks[0]?.id ?? '')
  const task: TaskSummary | undefined = openTasks.find((t) => t.id === taskId) ?? openTasks[0]
  const groups = useGroups(ws, open && can('workflow.read', 'workspace.manage-access'))
  const members = (groups.data?.items.find((g) => g.key === task?.candidateGroup.key)?.members ?? []).filter((m) => m.type === 'subject')
  const [assignee, setAssignee] = useState('')
  const [manualId, setManualId] = useState('')
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState<string>()
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ProblemError | null>(null)

  const target = members.length > 0 ? assignee : manualId.trim()

  const submit = async () => {
    if (!task) return
    if (!reason.trim()) return setReasonError('Add a reason. It is recorded in the audit log.')
    setReasonError(undefined)
    setPending(true)
    setError(null)
    try {
      await endpoints.reassignTask(ws, task.id, { expectedVersion: task.version, reason: reason.trim(), ...(target ? { assigneeSubjectId: target } : {}) })
      await refresh()
      announce(target ? 'The task was reassigned.' : 'The task was returned to its group.')
      onClose()
    } catch (cause) {
      setError(toProblem(cause))
    } finally {
      setPending(false)
    }
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="Reassign this task"
      dismissible={!pending}
      description="Move the waiting task to a specific person, or return it to its group. This never records a decision."
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={pending}>
            Cancel
          </Button>
          <Button variant="primary" onClick={() => void submit()} loading={pending} disabled={!task}>
            Reassign
          </Button>
        </>
      }
    >
      {!task ? (
        <p className="text-sm">There is no open task on this request.</p>
      ) : (
        <div className="space-y-4">
          {openTasks.length > 1 ? (
            <div className="space-y-1.5">
              <Label htmlFor="reassign-task">Task</Label>
              <Select id="reassign-task" value={task.id} onChange={(e) => setTaskId(e.target.value)}>
                {openTasks.map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.label}
                  </option>
                ))}
              </Select>
            </div>
          ) : (
            <p className="text-sm">
              <span className="font-semibold">{task.label}</span> &middot; currently {task.assignee ? `with ${task.assignee.displayName}` : `waiting for ${task.candidateGroup.name}`}
            </p>
          )}
          {members.length > 0 ? (
            <div className="space-y-1.5">
              <Label htmlFor="reassign-to">Assign to</Label>
              <Select id="reassign-to" value={assignee} onChange={(e) => setAssignee(e.target.value)}>
                <option value="">Return to {task.candidateGroup.name} (anyone in the group)</option>
                {members.map((m) => (
                  <option key={m.value} value={m.value}>
                    {m.displayName ?? m.value}
                  </option>
                ))}
              </Select>
            </div>
          ) : (
            <FormField label="Person to assign (subject ID)" id="reassign-manual" optionalText description="Leave empty to return the task to its group. The person must be an eligible member of the group.">
              {(ids) => <Input id={ids.input} value={manualId} onChange={(e) => setManualId(e.target.value)} aria-describedby={ids.describedBy} autoComplete="off" />}
            </FormField>
          )}
          <ReasonField id="reassign-reason" value={reason} onChange={setReason} error={reasonError} />
          <DialogError error={error} />
        </div>
      )}
    </Dialog>
  )
}

// Reconcile ---------------------------------------------------------------------------------------

type Resolution = 'confirmedApplied' | 'confirmedNotApplied'

function jsonPointerKey(pointer: string): string {
  return (pointer.split('/').filter(Boolean).pop() ?? '').replace(/~1/g, '/').replace(/~0/g, '~')
}

export function ReconcileDialog({ open, run, onClose, initialEffectId }: DialogProps & { initialEffectId?: string | undefined }) {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const refresh = useRefresh(run.id)
  const announce = useAnnounce()
  const key = useIdempotencyKey()
  const candidates = candidateEffects(run.effects)
  const [effectId, setEffectId] = useState(initialEffectId ?? candidates.find((e) => e.state === 'unknown')?.id ?? candidates[0]?.id ?? '')
  const effect: Effect | undefined = candidates.find((e) => e.id === effectId) ?? candidates[0]
  const [resolution, setResolution] = useState<Resolution>('confirmedApplied')
  const [note, setNote] = useState('')
  const [remoteReference, setRemoteReference] = useState('')
  const [resultValues, setResultValues] = useState<Record<string, string>>({})
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ProblemError | null>(null)

  // Result fields come from the node's resultMapping (read from the pinned release when the caller may read it).
  const release = useRelease(ws, run.workflow.id, open && can('workflow.read') ? run.release.id : null)
  const node = release.data?.definition.nodes.find((n) => n.id === effect?.nodeId)
  const mapping = node && node.type === 'http' ? (node.config as { resultMapping?: Record<string, string> }).resultMapping : undefined
  const connection = useConnection(ws, effect?.connectionKey, open && can('connection.read'))
  const resultKeys = Object.keys(mapping ?? {})
  const resultTypes = ((): Record<string, string> => {
    const out: Record<string, string> = {}
    const latest = connection.data?.definitions.at(-1)?.definition as { operations?: Record<string, { responseSchema?: { properties?: Record<string, { type?: string }> } }> } | undefined
    const props = effect ? latest?.operations?.[effect.operation]?.responseSchema?.properties : undefined
    for (const [k, pointer] of Object.entries(mapping ?? {})) out[k] = props?.[jsonPointerKey(pointer)]?.type ?? 'string'
    return out
  })()
  const [extraKey, setExtraKey] = useState('')
  const freeKeys = Object.keys(resultValues).filter((k) => !resultKeys.includes(k))
  const allKeys = [...resultKeys, ...freeKeys]
  const mappingUnknown = resultKeys.length === 0

  const submit = async () => {
    if (!effect) return
    const next: Record<string, string> = {}
    if (note.trim().length < 10) next.note = 'Describe what you checked, in at least 10 characters.'
    let result: Record<string, string | number | boolean> | undefined
    if (resolution === 'confirmedApplied') {
      result = {}
      for (const k of allKeys) {
        const raw = (resultValues[k] ?? '').trim()
        if (raw === '') {
          next[`result.${k}`] = 'Enter the value the other system returned.'
          continue
        }
        const type = resultTypes[k] ?? 'string'
        if ((type === 'number' || type === 'integer') && !/^-?\d+(\.\d+)?$/.test(raw)) next[`result.${k}`] = 'Enter a number.'
        else if (type === 'boolean' && raw !== 'true' && raw !== 'false') next[`result.${k}`] = 'Choose Yes or No.'
        else result[k] = type === 'number' || type === 'integer' ? Number(raw) : type === 'boolean' ? raw === 'true' : raw
      }
      if (allKeys.length === 0) next.result = 'Add at least one result field returned by the other system.'
    }
    setErrors(next)
    if (Object.keys(next).length > 0) return
    setPending(true)
    setError(null)
    try {
      const idem = key.keyFor(fingerprintOf(run.id, effect.id, resolution, note.trim(), remoteReference.trim(), result))
      await endpoints.reconcileEffect(
        ws,
        run.id,
        effect.id,
        {
          resolution,
          evidence: { note: note.trim(), ...(remoteReference.trim() ? { remoteReference: remoteReference.trim() } : {}) },
          ...(resolution === 'confirmedApplied' && result ? { result } : {}),
        },
        idem,
      )
      await refresh()
      announce(resolution === 'confirmedApplied' ? 'Outcome confirmed. The request continues.' : 'Recorded as not done. You can now retry the step.')
      onClose()
    } catch (cause) {
      const problem = toProblem(cause)
      const mapped = fieldErrorMap(problem)
      if (Object.keys(mapped).length > 0) {
        setErrors(Object.fromEntries(Object.entries(mapped).map(([k, v]) => [k === 'evidence' ? 'note' : k, v])))
      }
      setError(problem)
    } finally {
      setPending(false)
    }
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="Reconcile the outcome"
      size="lg"
      dismissible={!pending}
      description="Check the other system yourself, then record what you found. Orqix never assumes success without your evidence."
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={pending}>
            Cancel
          </Button>
          <Button variant="primary" onClick={() => void submit()} loading={pending} disabled={!effect}>
            Record outcome
          </Button>
        </>
      }
    >
      {!effect ? (
        <p className="text-sm">No step on this request needs reconciling.</p>
      ) : (
        <div className="space-y-5">
          {candidates.length > 1 ? (
            <div className="space-y-1.5">
              <Label htmlFor="reconcile-effect">Step</Label>
              <Select id="reconcile-effect" value={effect.id} onChange={(e) => setEffectId(e.target.value)}>
                {candidates.map((e) => (
                  <option key={e.id} value={e.id}>
                    {e.label ?? e.nodeId} ({EFFECT_STATE_LABEL[e.state]})
                  </option>
                ))}
              </Select>
            </div>
          ) : (
            <p className="text-sm">
              <span className="font-semibold">{effect.label ?? effect.nodeId}</span> &middot; {EFFECT_STATE_LABEL[effect.state]}
              {effect.lastErrorDetail ? <span className="block text-muted-foreground">{effect.lastErrorDetail}</span> : null}
            </p>
          )}

          <fieldset className="space-y-2">
            <legend className="text-sm font-semibold">What did you find?</legend>
            <RadioGroup value={resolution} onValueChange={(v) => setResolution(v as Resolution)}>
              <div className="flex items-start gap-3">
                <RadioGroupItem value="confirmedApplied" id="res-applied" />
                <Label htmlFor="res-applied" className="font-normal">
                  <span className="font-semibold">The other system did the work.</span> The request continues without calling it again.
                </Label>
              </div>
              <div className="flex items-start gap-3">
                <RadioGroupItem value="confirmedNotApplied" id="res-not" />
                <Label htmlFor="res-not" className="font-normal">
                  <span className="font-semibold">The other system did not do the work.</span> You can then retry the step.
                </Label>
              </div>
            </RadioGroup>
          </fieldset>

          <FormField label="Evidence" required id="reconcile-note" error={errors.note} description="What did you check, and what did you see? For example: Order PO-1042 exists in the procurement system.">
            {(ids) => <Textarea id={ids.input} value={note} onChange={(e) => setNote(e.target.value)} rows={3} maxLength={1000} aria-invalid={errors.note ? true : undefined} aria-describedby={ids.describedBy} aria-required />}
          </FormField>

          <FormField label="Reference in the other system" optionalText id="reconcile-ref">
            {(ids) => <Input id={ids.input} value={remoteReference} onChange={(e) => setRemoteReference(e.target.value)} maxLength={200} autoComplete="off" />}
          </FormField>

          {resolution === 'confirmedApplied' ? (
            <fieldset className="space-y-3 rounded-card border border-border p-4">
              <legend className="px-1 text-sm font-semibold">Values returned by the other system</legend>
              {mappingUnknown ? (
                <p className="text-sm text-muted-foreground">
                  The fields to keep for this step could not be read. Add each field name and the value the other system returned.
                </p>
              ) : null}
              {allKeys.map((k) => (
                <FormField key={k} label={k} required id={`reconcile-result-${k}`} error={errors[`result.${k}`]}>
                  {(ids) =>
                    resultTypes[k] === 'boolean' ? (
                      <Select id={ids.input} value={resultValues[k] ?? ''} onChange={(e) => setResultValues((p) => ({ ...p, [k]: e.target.value }))} aria-describedby={ids.describedBy}>
                        <option value="">Choose</option>
                        <option value="true">Yes</option>
                        <option value="false">No</option>
                      </Select>
                    ) : (
                      <Input id={ids.input} value={resultValues[k] ?? ''} onChange={(e) => setResultValues((p) => ({ ...p, [k]: e.target.value }))} aria-invalid={errors[`result.${k}`] ? true : undefined} aria-describedby={ids.describedBy} autoComplete="off" />
                    )
                  }
                </FormField>
              ))}
              {mappingUnknown ? (
                <div className="flex items-end gap-2">
                  <FormField label="Add a field" id="reconcile-extra" className="flex-1">
                    {(ids) => <Input id={ids.input} value={extraKey} onChange={(e) => setExtraKey(e.target.value)} placeholder="procurementId" autoComplete="off" />}
                  </FormField>
                  <Button
                    variant="secondary"
                    onClick={() => {
                      const k = extraKey.trim()
                      if (k && /^[a-z][A-Za-z0-9]{0,63}$/.test(k)) {
                        setResultValues((p) => ({ ...p, [k]: p[k] ?? '' }))
                        setExtraKey('')
                      }
                    }}
                  >
                    Add
                  </Button>
                </div>
              ) : null}
              {errors.result ? (
                <p className="flex items-center gap-1.5 text-sm font-medium text-danger">
                  <AlertTriangle className="size-4" aria-hidden="true" /> {errors.result}
                </p>
              ) : null}
            </fieldset>
          ) : null}
          <DialogError error={error} />
        </div>
      )}
    </Dialog>
  )
}
