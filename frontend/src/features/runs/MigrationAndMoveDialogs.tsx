import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { fingerprintOf, useIdempotencyKey } from '@/api/idempotency'
import { type ProblemError } from '@/api/problem'
import { keys, useRelease, useRunMigrationOptions } from '@/api/queries'
import type { components } from '@/api/schema'
import type { RunDetail } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { useAnnounce } from '@/components/common/Announcer'
import { FormField } from '@/components/common/FormField'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'

/**
 * S143 operator dialogs: move a request to another release with a step mapping (CG-O02, with a batch preview for the
 * other requests on the same release) and move, skip or re-run a step (CG-O03). Every command first shows its dry
 * run, needs a reason, and keeps one idempotency key per unchanged input.
 */
type S = components['schemas']
type Finding = S['MigrationFinding']
type StepNode = { id: string; label?: string; type: string }

interface DialogProps {
  open: boolean
  run: RunDetail
  onClose: () => void
}

function stepsOf(definition: unknown): StepNode[] {
  const nodes = (definition as { nodes?: StepNode[] } | undefined)?.nodes ?? []
  return nodes.filter((n) => n.type !== 'start')
}

function labelOf(nodes: StepNode[], id: string) {
  return nodes.find((n) => n.id === id)?.label ?? id
}

function Findings({ findings, testId }: { findings: Finding[]; testId: string }) {
  if (findings.length === 0) return null
  return (
    <ul className="list-disc space-y-1 pl-5 text-sm" data-testid={testId}>
      {findings.map((f, i) => (
        <li key={`${f.code}-${i}`}>{f.message}</li>
      ))}
    </ul>
  )
}

function Reason({ id, value, onChange, error }: { id: string; value: string; onChange: (v: string) => void; error?: string | undefined }) {
  return (
    <FormField label="Reason" required id={id} error={error} description="Recorded on the request's timeline and in the audit log with your name.">
      {(ids) => <Textarea id={ids.input} value={value} onChange={(e) => onChange(e.target.value)} maxLength={500} rows={2} aria-invalid={error ? true : undefined} aria-describedby={ids.describedBy} aria-required />}
    </FormField>
  )
}

function useRefresh(runId: string) {
  const client = useQueryClient()
  const { workspace } = useWorkspace()
  const ws = workspace.id
  return async () => {
    await Promise.all([
      client.invalidateQueries({ queryKey: keys.run(ws, runId), exact: true }),
      client.invalidateQueries({ queryKey: keys.runEvents(ws, runId) }),
      client.invalidateQueries({ queryKey: ['ws', ws, 'run', runId, 'moves'] }),
      client.invalidateQueries({ queryKey: ['ws', ws, 'runs'] }),
    ])
  }
}

// Move to another version with a step mapping ------------------------------------------------------

export function MappedMigrateDialog({ open, run, onClose }: DialogProps) {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const refresh = useRefresh(run.id)
  const announce = useAnnounce()
  const key = useIdempotencyKey()
  const batchKey = useIdempotencyKey()
  const options = useRunMigrationOptions(ws, run.id, open)
  const [targetId, setTargetId] = useState('')
  const target = useRelease(ws, run.workflow.id, targetId || null)
  const current = useRelease(ws, run.workflow.id, open ? run.release.id : null)
  const [mapping, setMapping] = useState<Record<string, string>>({})
  const [plan, setPlan] = useState<S['MigrationPlan'] | null>(null)
  const [planned, setPlanned] = useState('')
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState<string>()
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ProblemError | null>(null)
  const [preview, setPreview] = useState<S['MigrationBatchPreview'] | null>(null)
  const [batch, setBatch] = useState<S['MigrationBatch'] | null>(null)

  const targetSteps = stepsOf(target.data?.definition)
  const currentSteps = stepsOf(current.data?.definition)
  const mappings = Object.entries(mapping).filter(([from, to]) => to && to !== from).map(([from, to]) => ({ from, to }))
  const fingerprint = fingerprintOf(targetId, mappings)
  const rows = useMemo(() => {
    const ids = new Set<string>(run.currentSteps.map((s) => s.nodeId))
    for (const s of plan?.suggestedMappings ?? []) ids.add(s.from)
    for (const m of Object.keys(mapping)) ids.add(m)
    return [...ids]
  }, [run.currentSteps, plan, mapping])

  const check = async () => {
    if (!targetId) return
    setError(null)
    setPending(true)
    try {
      setPlan(await endpoints.planRunMigration(ws, run.id, { targetReleaseId: targetId, mappings }))
      setPlanned(fingerprint)
    } catch (cause) {
      setError(toProblem(cause))
    } finally {
      setPending(false)
    }
  }

  const submit = async () => {
    if (!plan?.allowed || planned !== fingerprint) return
    if (!reason.trim()) return setReasonError('Add a reason. It is recorded in the audit log.')
    setReasonError(undefined)
    setPending(true)
    setError(null)
    try {
      const idem = key.keyFor(fingerprintOf(run.id, 'migrate-mapped', fingerprint, reason.trim(), run.version))
      await endpoints.migrateRunWithMapping(ws, run.id, { targetReleaseId: targetId, mappings, reason: reason.trim(), expectedVersion: run.version }, idem)
      await refresh()
      announce(`The request now follows version ${plan.target.releaseNumber}.`)
      onClose()
    } catch (cause) {
      setError(toProblem(cause))
    } finally {
      setPending(false)
    }
  }

  const previewBatch = async () => {
    setError(null)
    setPending(true)
    try {
      setPreview(await endpoints.previewMigrationBatch(ws, { sourceReleaseId: run.release.id, targetReleaseId: targetId, mappings, suspendDuring: true }))
      setBatch(null)
    } catch (cause) {
      setError(toProblem(cause))
    } finally {
      setPending(false)
    }
  }

  const allowedIds = (preview?.items ?? []).filter((i) => i.allowed).map((i) => i.runId)
  const runBatch = async () => {
    if (allowedIds.length === 0) return
    if (!reason.trim()) return setReasonError('Add a reason. It is recorded in the audit log.')
    setReasonError(undefined)
    setPending(true)
    setError(null)
    try {
      const idem = batchKey.keyFor(fingerprintOf('batch', fingerprint, allowedIds, reason.trim()))
      const result = await endpoints.createMigrationBatch(ws, { sourceReleaseId: run.release.id, targetReleaseId: targetId, mappings, runIds: allowedIds, reason: reason.trim(), suspendDuring: true, expectedCount: allowedIds.length }, idem)
      setBatch(result)
      await refresh()
      announce(`${result.migrated} requests moved, ${result.refused} refused, ${result.failed} failed.`)
    } catch (cause) {
      setError(toProblem(cause))
    } finally {
      setPending(false)
    }
  }

  const list = options.data?.options ?? []
  const stale = plan !== null && planned !== fingerprint
  return (
    <Dialog
      open={open}
      onClose={onClose}
      size="xl"
      title="Move with a step mapping"
      dismissible={!pending}
      description="Choose a version and, where its steps are named differently, which step each waiting step continues as. Check the plan before moving: it lists every reason the move would be unsafe."
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={pending}>
            Close
          </Button>
          <Button variant="secondary" onClick={() => void check()} loading={pending} disabled={!targetId}>
            Check plan
          </Button>
          <Button variant="primary" onClick={() => void submit()} loading={pending} disabled={!plan?.allowed || stale}>
            Move this request
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        {options.isError ? <ProblemAlert error={toProblem(options.error)} /> : null}
        <FormField label="Version to move to" required id="mapped-target">
          {(ids) => (
            <Select id={ids.input} value={targetId} onChange={(e) => { setTargetId(e.target.value); setPlan(null); setMapping({}); setPreview(null) }}>
              <option value="">Choose a version</option>
              {list.map((o) => (
                <option key={o.release.id} value={o.release.id}>
                  Version {o.release.releaseNumber}
                  {o.active ? ' (used for new requests)' : ''}
                </option>
              ))}
            </Select>
          )}
        </FormField>
        {targetId && rows.length > 0 ? (
          <fieldset className="space-y-2">
            <legend className="text-sm font-semibold">Step mapping</legend>
            {rows.map((from) => (
              <div key={from} className="grid grid-cols-1 items-center gap-2 sm:grid-cols-2" data-testid={`mapping-${from}`}>
                <Label htmlFor={`map-${from}`} className="font-normal">
                  {labelOf(currentSteps, from)} <span className="text-muted-foreground">({from})</span>
                </Label>
                <Select id={`map-${from}`} value={mapping[from] ?? from} onChange={(e) => setMapping({ ...mapping, [from]: e.target.value })}>
                  <option value={from}>Same step id</option>
                  {targetSteps.filter((n) => n.id !== from).map((n) => (
                    <option key={n.id} value={n.id}>
                      {n.label ?? n.id} ({n.id})
                    </option>
                  ))}
                </Select>
              </div>
            ))}
            {(plan?.suggestedMappings ?? []).some((s) => mapping[s.from] !== s.to) ? (
              <Button variant="secondary" size="sm" onClick={() => setMapping({ ...mapping, ...Object.fromEntries((plan?.suggestedMappings ?? []).map((s) => [s.from, s.to])) })}>
                Use suggested mapping
              </Button>
            ) : null}
          </fieldset>
        ) : null}
        {plan ? (
          <section aria-label="Plan" className="space-y-2 rounded-md border p-3" data-testid="migration-plan">
            {stale ? <Alert tone="info" title="The mapping changed">Check the plan again before moving.</Alert> : null}
            {!plan.ready ? <Alert tone="warning" title="Not ready to move">{plan.notReadyReason}</Alert> : null}
            {plan.allowed ? <Alert tone="success" title="The request can move">{plan.hold === 'childRequests' ? 'It waits only for its child requests, so it does not need to be suspended.' : 'Nothing it has done or waits for is lost.'}</Alert> : null}
            <ul className="space-y-1 text-sm">
              {plan.steps.map((s) => (
                <li key={s.from}>
                  {labelOf(currentSteps, s.from)} {s.action === 'rename' ? `continues as ${s.label} (${s.to})` : 'stays as it is'}
                </li>
              ))}
              {plan.dataCopies.map((c) => (
                <li key={`copy-${c.from}`}>
                  The data of {c.from} is kept as {c.to}
                </li>
              ))}
            </ul>
            <Findings findings={plan.findings} testId="migration-findings" />
          </section>
        ) : null}
        <Reason id="mapped-reason" value={reason} onChange={setReason} error={reasonError} />
        {targetId ? (
          <section aria-label="Other requests" className="space-y-2 border-t pt-3">
            <h3 className="text-sm font-semibold">Other requests on version {run.release.releaseNumber}</h3>
            <p className="text-sm text-muted-foreground">Preview the same mapping for the active requests of this version (up to 100). Each is suspended for its move and resumed afterwards.</p>
            <Button variant="secondary" size="sm" onClick={() => void previewBatch()} loading={pending}>
              Preview batch
            </Button>
            {preview ? (
              <div data-testid="batch-preview" className="space-y-2">
                <p className="text-sm">
                  {preview.allowed} of {preview.total} can move{preview.truncated ? ' (more requests exist; the newest 100 are shown)' : ''}.
                </p>
                <ul className="max-h-48 space-y-1 overflow-auto text-sm">
                  {preview.items.map((i) => (
                    <li key={i.runId}>
                      <span className="font-medium">{i.reference ?? i.runId}</span>: {i.allowed ? 'can move' : (i.findings[0]?.message ?? i.notReadyReason ?? 'cannot move')}
                    </li>
                  ))}
                </ul>
                <Button variant="primary" size="sm" onClick={() => void runBatch()} loading={pending} disabled={allowedIds.length === 0 || batch !== null}>
                  Move {allowedIds.length} requests
                </Button>
              </div>
            ) : null}
            {batch ? (
              <Alert tone={batch.failed > 0 || batch.refused > 0 ? 'warning' : 'success'} title="Batch finished">
                {batch.migrated} moved, {batch.refused} refused, {batch.failed} failed.
              </Alert>
            ) : null}
          </section>
        ) : null}
        {error ? <ProblemAlert error={error} /> : null}
      </div>
    </Dialog>
  )
}

// Move, skip or re-run a step ----------------------------------------------------------------------

const KIND_TEXT: Record<'skip' | 'rerun' | 'move', string> = {
  skip: 'Skip the current step',
  rerun: 'Run a step again',
  move: 'Move to another step',
}

const HANDLING_TEXT: Record<string, string> = {
  notRun: 'is not run',
  runsAgain: 'runs again',
  reuseRecorded: 'runs again and reuses its recorded result; nothing is sent again',
  wouldRepeat: 'would repeat its effect',
}

export function MoveStepDialog({ open, run, onClose }: DialogProps) {
  const { workspace, me } = useWorkspace()
  const ws = workspace.id
  const refresh = useRefresh(run.id)
  const announce = useAnnounce()
  const key = useIdempotencyKey()
  const decisionKey = useIdempotencyKey()
  const release = useRelease(ws, run.workflow.id, open ? run.release.id : null)
  const moves = useQuery({ queryKey: ['ws', ws, 'run', run.id, 'moves'], queryFn: () => endpoints.listRunMoves(ws, run.id), enabled: open, retry: false })
  const steps = stepsOf(release.data?.definition)
  const attention = (run.attention as { nodeId?: string } | null | undefined)?.nodeId
  const here = [...new Set([...run.currentSteps.map((s) => s.nodeId), ...(attention ? [attention] : [])])]
  const [kind, setKind] = useState<'skip' | 'rerun' | 'move'>('skip')
  const [from, setFrom] = useState(here[0] ?? '')
  const [to, setTo] = useState('')
  const [plan, setPlan] = useState<S['MovePlan'] | null>(null)
  const [planned, setPlanned] = useState('')
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState<string>()
  const [note, setNote] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ProblemError | null>(null)
  const fingerprint = fingerprintOf(kind, from, to)
  const stale = plan !== null && planned !== fingerprint
  const waiting = (moves.data?.items ?? []).filter((m) => m.state === 'pendingApproval')

  const check = async () => {
    if (!from || !to) return
    setPending(true)
    setError(null)
    try {
      setPlan(await endpoints.planRunMove(ws, run.id, { kind, fromStepId: from, toStepId: to }))
      setPlanned(fingerprint)
    } catch (cause) {
      setError(toProblem(cause))
    } finally {
      setPending(false)
    }
  }

  const submit = async () => {
    if (!plan?.allowed || stale) return
    if (!reason.trim()) return setReasonError('Add a reason. It is recorded in the audit log.')
    setReasonError(undefined)
    setPending(true)
    setError(null)
    try {
      const idem = key.keyFor(fingerprintOf(run.id, 'move', fingerprint, reason.trim(), plan.runVersion))
      const result = await endpoints.moveRun(ws, run.id, { kind, fromStepId: from, toStepId: to, reason: reason.trim(), expectedVersion: plan.runVersion }, idem)
      await refresh()
      announce(result.state === 'pendingApproval' ? 'The move waits for a second operator.' : 'The request was moved.')
      if (result.state !== 'pendingApproval') onClose()
    } catch (cause) {
      setError(toProblem(cause))
    } finally {
      setPending(false)
    }
  }

  const decide = async (moveId: string, decision: 'approve' | 'reject') => {
    setPending(true)
    setError(null)
    try {
      const idem = decisionKey.keyFor(fingerprintOf(moveId, decision, note.trim()))
      const result = await endpoints.decideRunMove(ws, run.id, moveId, decision, { note: note.trim() || null }, idem)
      await refresh()
      announce(result.state === 'executed' ? 'The move was approved and ran.' : `The move is now ${result.state}.`)
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
      size="lg"
      title="Move, skip or re-run a step"
      dismissible={!pending}
      description="Moves never repeat what already happened in another system: calls recorded in the effect journal reuse their result, and steps with other effects are not run again."
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={pending}>
            Close
          </Button>
          <Button variant="secondary" onClick={() => void check()} loading={pending} disabled={!from || !to}>
            Check plan
          </Button>
          <Button variant="primary" onClick={() => void submit()} loading={pending} disabled={!plan?.allowed || stale}>
            {plan?.approvalRequired ? 'Ask a second operator' : 'Move request'}
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        {waiting.map((m) => (
          <Alert key={m.id} tone="warning" title="A move waits for a second operator">
            <p>
              {KIND_TEXT[m.kind]}: {m.fromLabel ?? m.fromStepId} to {m.toLabel ?? m.toStepId}. Reason: {m.reason}
            </p>
            <Textarea aria-label="Note for the decision" value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} rows={2} className="mt-2" />
            <div className="mt-2 flex gap-2">
              <Button variant="primary" size="sm" onClick={() => void decide(m.id, 'approve')} disabled={pending || m.requestedBy === me.subject.id} title={m.requestedBy === me.subject.id ? 'Another operator must approve your move' : undefined}>
                Approve and run
              </Button>
              <Button variant="secondary" size="sm" onClick={() => void decide(m.id, 'reject')} disabled={pending}>
                {m.requestedBy === me.subject.id ? 'Withdraw' : 'Reject'}
              </Button>
            </div>
          </Alert>
        ))}
        <fieldset className="space-y-2">
          <legend className="text-sm font-semibold">What to do</legend>
          <RadioGroup value={kind} onValueChange={(v) => setKind(v as 'skip' | 'rerun' | 'move')}>
            {(['skip', 'rerun', 'move'] as const).map((k) => (
              <div key={k} className="flex items-center gap-2">
                <RadioGroupItem value={k} id={`move-kind-${k}`} />
                <Label htmlFor={`move-kind-${k}`} className="font-normal">
                  {KIND_TEXT[k]}
                </Label>
              </div>
            ))}
          </RadioGroup>
        </fieldset>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <FormField label="From the step" required id="move-from">
            {(ids) => (
              <Select id={ids.input} value={from} onChange={(e) => setFrom(e.target.value)}>
                {here.map((id) => (
                  <option key={id} value={id}>
                    {labelOf(steps, id)}
                  </option>
                ))}
              </Select>
            )}
          </FormField>
          <FormField label="To the step" required id="move-to">
            {(ids) => (
              <Select id={ids.input} value={to} onChange={(e) => setTo(e.target.value)}>
                <option value="">Choose a step</option>
                {steps.filter((n) => n.type !== 'end').map((n) => (
                  <option key={n.id} value={n.id}>
                    {n.label ?? n.id}
                  </option>
                ))}
              </Select>
            )}
          </FormField>
        </div>
        {plan ? (
          <section aria-label="Plan" className="space-y-2 rounded-md border p-3" data-testid="move-plan">
            {stale ? <Alert tone="info" title="The choice changed">Check the plan again before moving.</Alert> : null}
            {plan.allowed ? <Alert tone="success" title="The move is safe">{plan.approvalRequired ? plan.approvalReason : 'It runs as soon as you confirm.'}</Alert> : null}
            <ul className="space-y-1 text-sm">
              {[...plan.skipped, ...plan.rerun].map((a) => (
                <li key={`${a.nodeId}-${a.handling}`}>
                  {a.label} {HANDLING_TEXT[a.handling] ?? a.handling}
                </li>
              ))}
            </ul>
            <Findings findings={plan.findings} testId="move-findings" />
          </section>
        ) : null}
        <Reason id="move-reason" value={reason} onChange={setReason} error={reasonError} />
        {error ? <ProblemAlert error={error} /> : null}
      </div>
    </Dialog>
  )
}
