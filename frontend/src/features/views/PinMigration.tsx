import { useQuery, useQueryClient } from '@tanstack/react-query'
import { GitCompareArrows } from 'lucide-react'
import { useState } from 'react'
import { isProblemError } from '@/api/problem'
import { listViewPinMigrations, listViewPins, migrateViewPins, type ViewPinItem, type ViewPinMigration } from '@/api/viewPins'
import { useWorkspace } from '@/app/workspace'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Dialog } from '@/components/ui/dialog'
import { Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { formatDateTime } from '@/lib/dates'

const STATUS: Record<ViewPinItem['status'], { label: string; tone: 'success' | 'warning' | 'danger' | 'neutral' | 'info' }> = {
  ready: { label: 'Can update', tone: 'info' },
  current: { label: 'Already on this version', tone: 'success' },
  newer: { label: 'On a later version', tone: 'neutral' },
  blocked: { label: 'Not compatible', tone: 'danger' },
  readOnly: { label: 'Fixed version', tone: 'neutral' },
}
const HOLDER: Record<ViewPinItem['holderType'], string> = {
  workflowDraft: 'Workflow draft', workflowRelease: 'Workflow release', caseDefinition: 'Case definition', worklist: 'Worklist',
  pageDraft: 'Page draft', pageVersion: 'Published page', route: 'Page route',
}

/**
 * Reviewed version migration (S114): every place that pins this view, checked against a chosen published version. The
 * update applies the selected pins in one server operation that is refused if anything changed since this review, and
 * each holder changes through its own rules: drafts get a new revision, cases a new definition version, routes and the
 * worklist a new revision. Published releases and page versions are listed for review and never change.
 */
export function PinMigration({ viewId, versions }: { viewId: string; versions: number[] }) {
  const { workspace, can } = useWorkspace(), ws = workspace.id
  const client = useQueryClient()
  const newest = versions.length ? Math.max(...versions) : null
  const [target, setTarget] = useState<number | null>(newest)
  const chosenTarget = target ?? newest
  const pins = useQuery({ queryKey: ['viewPins', ws, viewId, chosenTarget], queryFn: () => listViewPins(ws, viewId, chosenTarget ?? undefined), enabled: !!chosenTarget })
  const history = useQuery({ queryKey: ['viewPinMigrations', ws, viewId], queryFn: () => listViewPinMigrations(ws, viewId), enabled: !!chosenTarget })
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [review, setReview] = useState(false)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [done, setDone] = useState<ViewPinMigration | null>(null)
  if (!newest) return null
  if (pins.isError) return <QueryError error={pins.error} onRetry={() => void pins.refetch()} what="the places that use this view" />
  const items = pins.data?.items ?? []
  const ready = items.filter(item => item.status === 'ready')
  const chosen = ready.filter(item => selected.has(item.key))
  const canUpdate = can('workflow.edit')

  const apply = async () => {
    if (!pins.data) return
    setBusy(true)
    setError(null)
    try {
      const result = await migrateViewPins(ws, viewId, { targetVersion: pins.data.targetVersion, planHash: pins.data.planHash, items: chosen.map(item => item.key), reason: reason.trim() })
      setDone(result)
      setReview(false)
      setSelected(new Set())
      setReason('')
      await client.invalidateQueries()
    } catch (cause) {
      setError(cause)
      // Anything changed since the review: show the new state, which the person reviews again.
      if (isProblemError(cause) && cause.code === 'WORKFLOW_VERSION_CONFLICT') await pins.refetch()
    } finally {
      setBusy(false)
    }
  }

  return <section className="space-y-3 rounded-card border border-border bg-surface p-4" aria-label="Where this view is used">
    <div className="flex flex-wrap items-center gap-2"><h2 className="mr-auto text-lg font-semibold"><GitCompareArrows className="mr-1 inline size-4" aria-hidden="true" /> Where this view is used</h2>
      <Label htmlFor={`pins-${viewId}-target`} className="sr-only">Version to move pins to</Label>
      <Select id={`pins-${viewId}-target`} className="w-auto" value={chosenTarget ?? ''} onChange={event => { setTarget(Number(event.target.value)); setSelected(new Set()); setDone(null) }}>
        {[...versions].sort((a, b) => b - a).map(version => <option key={version} value={version}>Check against version {version}</option>)}
      </Select>
    </div>
    <p className="text-sm text-muted-foreground">Pins never move on their own. Choose the places to update; the server checks each one again and updates all of them or none.</p>
    {pins.isPending ? <p className="text-sm text-muted-foreground" role="status">Checking pins</p>
      : items.length === 0 ? <p className="text-sm text-muted-foreground">Nothing pins this view yet.</p>
        : <ul className="space-y-2">{items.map(item => <li key={item.key} className="rounded border border-border p-3">
          <div className="flex flex-wrap items-start gap-2">
            {item.status === 'ready' && canUpdate ? <Checkbox id={`pin-${item.key}`} checked={selected.has(item.key)} onCheckedChange={checked => setSelected(current => { const next = new Set(current); if (checked === true) next.add(item.key); else next.delete(item.key); return next })} /> : null}
            <div className="min-w-0 flex-1">
              {item.status === 'ready' && canUpdate ? <Label htmlFor={`pin-${item.key}`} className="font-semibold">{item.holderName}</Label> : <p className="font-semibold">{item.holderName}</p>}
              <p className="text-sm text-muted-foreground">{HOLDER[item.holderType]} · {item.location} · pins version {item.pinnedVersions.join(', ')}</p>
            </div>
            <Badge tone={STATUS[item.status].tone}>{STATUS[item.status].label}</Badge>
          </div>
          {item.problems.length ? <ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-danger">{item.problems.map(problem => <li key={problem}>{problem}</li>)}</ul> : null}
          {item.status === 'ready' || item.status === 'readOnly' ? <p className="mt-1 text-sm">{item.effect}</p> : null}
        </li>)}</ul>}
    {canUpdate && ready.length ? <Button disabled={chosen.length === 0 || busy} onClick={() => { setError(null); setReview(true) }}>{chosen.length ? `Review update of ${chosen.length} ${chosen.length === 1 ? 'pin' : 'pins'}` : 'Select pins to update'}</Button> : null}
    {error && !review ? <ProblemAlert error={error} /> : null}
    {done ? <Alert tone="success" title={`Moved ${done.items.length} ${done.items.length === 1 ? 'pin' : 'pins'} to version ${done.targetVersion}`}><ul className="list-disc pl-5">{done.items.map(item => <li key={item.key}>{item.holderName}: {item.outcome}</li>)}</ul></Alert> : null}
    {history.data?.items.length ? <details className="text-sm"><summary className="cursor-pointer">Earlier updates ({history.data.items.length})</summary>
      <ul className="mt-2 space-y-1">{history.data.items.map(entry => <li key={entry.id}>{formatDateTime(entry.appliedAt)}: version {entry.targetVersion}, {entry.items.length} {entry.items.length === 1 ? 'pin' : 'pins'}. {entry.reason}</li>)}</ul>
    </details> : null}
    <Dialog open={review} onClose={() => setReview(false)} title={`Move ${chosen.length} ${chosen.length === 1 ? 'pin' : 'pins'} to version ${chosenTarget}`} description="All selected pins update together, or none do. Published releases and page versions keep their pins."
      footer={<><Button variant="secondary" onClick={() => setReview(false)} disabled={busy}>Cancel</Button><Button onClick={() => void apply()} disabled={busy || !reason.trim()} loading={busy}>Update {chosen.length} {chosen.length === 1 ? 'pin' : 'pins'}</Button></>}>
      <ul className="list-disc space-y-1 pl-5 text-sm">{chosen.map(item => <li key={item.key}><strong>{item.holderName}</strong>: {item.effect}</li>)}</ul>
      <div className="mt-3"><Label htmlFor={`pins-${viewId}-reason`}>Reason (recorded with the update)</Label><Textarea id={`pins-${viewId}-reason`} value={reason} maxLength={500} onChange={event => setReason(event.target.value)} /></div>
      {error ? <div className="mt-3"><ProblemAlert error={error} /></div> : null}
    </Dialog>
  </section>
}
