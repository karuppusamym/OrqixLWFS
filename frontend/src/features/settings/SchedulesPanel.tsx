import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api, call } from '@/api/client'
import type { components } from '@/api/schema'
import type { ProblemError } from '@/api/problem'
import { useWorkflows } from '@/api/queries'
import { useWorkspace } from '@/app/workspace'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { formatDateTime } from '@/lib/dates'

type Schedule = components['schemas']['Schedule']
type Request = components['schemas']['ScheduleRequest']

const OUTCOME: Record<string, string> = { started: 'Started', skipped: 'Skipped', failed: 'Failed' }

/** Plain words for when a schedule runs. */
export function scheduleText(s: Pick<Schedule, 'kind' | 'expression' | 'zone' | 'calendarKey'>): string {
  return `${s.kind === 'cron' ? 'Cron' : 'Rule'} ${s.expression} in ${s.zone}${s.calendarKey ? `, only in the working hours of ${s.calendarKey}` : ''}`
}

/**
 * Scheduled starts (docs/build/S139, CG-E02). Anyone who may read workflows sees the schedules; people with
 * workflow.publish who may also start the workflow create, change, pause and delete them. A schedule starts requests as
 * the person who last saved it. With {@code workflowId} the panel shows one workflow's schedules (Studio).
 */
export function SchedulesPanel({ workflowId }: { workflowId?: string }) {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const manage = can('workflow.publish')
  const client = useQueryClient()
  const workflows = (useWorkflows(ws, {}, manage && !workflowId).data?.pages.flatMap((p) => p.items) ?? [])
  const calendars = useQuery({ queryKey: ['ws', ws, 'calendars'], enabled: can('workflow.read'),
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/calendars', { params: { path: { workspaceId: ws } } })) }).data?.items ?? []
  const list = useQuery({ queryKey: ['ws', ws, 'schedules', workflowId ?? 'all'], enabled: can('workflow.read'),
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/schedules', { params: { path: { workspaceId: ws }, query: workflowId ? { workflowId } : {} } })) })
  const [error, setError] = useState<ProblemError | null>(null)
  const [notice, setNotice] = useState('')
  const [pending, setPending] = useState(false)
  const [form, setForm] = useState<{ workflowId: string; name: string; kind: 'cron' | 'rrule'; expression: string; zone: string; calendarKey: string; input: string; missedRunPolicy: 'catchUpOnce' | 'skip'; overlapPolicy: 'allow' | 'skip' }>({
    workflowId: workflowId ?? '', name: '', kind: 'cron', expression: '0 7 * * MON-FRI', zone: 'Europe/London', calendarKey: '', input: '{}', missedRunPolicy: 'catchUpOnce', overlapPolicy: 'skip',
  })
  const [next, setNext] = useState<string[]>([])

  const run = async (task: () => Promise<void>) => {
    setPending(true); setError(null); setNotice('')
    try { await task(); await client.invalidateQueries({ queryKey: ['ws', ws, 'schedules'] }) } catch (cause) { setError(toProblem(cause)) } finally { setPending(false) }
  }
  const body = (): Request | null => {
    try {
      const input = JSON.parse(form.input || '{}') as Record<string, unknown>
      return { workflowId: form.workflowId, name: form.name, kind: form.kind, expression: form.expression, zone: form.zone, calendarKey: form.calendarKey || null, input, missedRunPolicy: form.missedRunPolicy, overlapPolicy: form.overlapPolicy }
    } catch {
      return null
    }
  }
  const request = body()
  const set = (p: Partial<typeof form>) => setForm({ ...form, ...p })
  const items = list.data?.items ?? []

  return (
    <section className="space-y-4" aria-labelledby={`schedules-heading-${workflowId ?? 'all'}`}>
      <div>
        <h2 id={`schedules-heading-${workflowId ?? 'all'}`} className="text-lg font-semibold">Schedules</h2>
        <p className="text-sm text-muted-foreground">Start a workflow at set times with fixed answers, for example every weekday at 07:00. One start every 5 minutes at most.</p>
      </div>
      {error ? <ProblemAlert error={error} /> : null}
      {notice ? <Alert>{notice}</Alert> : null}
      <ul className="divide-y divide-border rounded-control border border-border" aria-label="Schedules">
        {items.length === 0 ? <li className="p-3 text-sm text-muted-foreground">No schedules yet.</li> : null}
        {items.map((s) => (
          <li key={s.id} className="space-y-1 p-3" data-testid={`schedule-${s.id}`}>
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div>
                <p className="font-medium">{s.name}{workflowId ? '' : ` · ${s.workflowName}`}{s.enabled ? '' : ' (paused)'}</p>
                <p className="text-sm text-muted-foreground">{scheduleText(s)} · runs as {s.runAsName}</p>
                <p className="text-sm text-muted-foreground">{s.nextFireAt ? `Next start ${formatDateTime(s.nextFireAt)}` : 'No next start'}{s.lastFireAt ? ` · last ${OUTCOME[s.lastOutcome ?? ''] ?? s.lastOutcome} ${formatDateTime(s.lastFireAt)}` : ''}</p>
              </div>
              {manage ? <div className="flex gap-2">
                <Button variant="secondary" disabled={pending} onClick={() => run(async () => {
                  await call(api.PATCH('/workspaces/{workspaceId}/schedules/{scheduleId}', { params: { path: { workspaceId: ws, scheduleId: s.id } }, body: { expectedVersion: s.version, enabled: !s.enabled } }))
                  setNotice(s.enabled ? `${s.name} is paused.` : `${s.name} runs again, as you.`)
                })}>{s.enabled ? 'Pause' : 'Resume'}</Button>
                <Button variant="secondary" disabled={pending} onClick={() => run(async () => {
                  await call(api.DELETE('/workspaces/{workspaceId}/schedules/{scheduleId}', { params: { path: { workspaceId: ws, scheduleId: s.id } } }))
                  setNotice(`${s.name} is deleted. Requests it started are not affected.`)
                })}>Delete</Button>
              </div> : null}
            </div>
          </li>
        ))}
      </ul>

      {manage ? (
        <form className="space-y-3 rounded-control border border-border p-3" aria-label="New schedule" onSubmit={(e) => {
          e.preventDefault()
          if (!request) return
          void run(async () => {
            await call(api.POST('/workspaces/{workspaceId}/schedules', { params: { path: { workspaceId: ws } }, body: request }))
            setNotice(`${form.name} is saved. It starts requests as you.`)
            setNext([])
          })
        }}>
          <p className="font-medium">New schedule</p>
          {!workflowId ? <div><Label htmlFor="schedule-workflow">Workflow</Label>
            <Select id="schedule-workflow" value={form.workflowId} onChange={(e) => set({ workflowId: e.target.value })}>
              <option value="">Choose a workflow</option>
              {workflows.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
            </Select></div> : null}
          <div className="grid gap-3 sm:grid-cols-2">
            <div><Label htmlFor="schedule-name">Name</Label><Input id="schedule-name" value={form.name} maxLength={80} onChange={(e) => set({ name: e.target.value })} /></div>
            <div><Label htmlFor="schedule-zone">Time zone</Label><Input id="schedule-zone" value={form.zone} onChange={(e) => set({ zone: e.target.value })} /></div>
            <div><Label htmlFor="schedule-kind">Written as</Label>
              <Select id="schedule-kind" value={form.kind} onChange={(e) => set({ kind: e.target.value as 'cron' | 'rrule', expression: e.target.value === 'cron' ? '0 7 * * MON-FRI' : 'FREQ=WEEKLY;BYDAY=MO;BYHOUR=8' })}>
                <option value="cron">Cron (minute hour day month weekday)</option>
                <option value="rrule">Recurrence rule (RRULE)</option>
              </Select></div>
            <div><Label htmlFor="schedule-expression">When</Label><Input id="schedule-expression" value={form.expression} onChange={(e) => set({ expression: e.target.value })} /></div>
            <div><Label htmlFor="schedule-calendar">Only in working hours of</Label>
              <Select id="schedule-calendar" value={form.calendarKey} onChange={(e) => set({ calendarKey: e.target.value })}>
                <option value="">Any time</option>
                {calendars.filter((c) => !c.archived).map((c) => <option key={c.key} value={c.key}>{c.name}</option>)}
              </Select></div>
            <div><Label htmlFor="schedule-missed">After an outage</Label>
              <Select id="schedule-missed" value={form.missedRunPolicy} onChange={(e) => set({ missedRunPolicy: e.target.value as 'catchUpOnce' | 'skip' })}>
                <option value="catchUpOnce">Start once for the missed times</option>
                <option value="skip">Skip the missed times</option>
              </Select></div>
            <div><Label htmlFor="schedule-overlap">While the last request is still open</Label>
              <Select id="schedule-overlap" value={form.overlapPolicy} onChange={(e) => set({ overlapPolicy: e.target.value as 'allow' | 'skip' })}>
                <option value="skip">Skip this time</option>
                <option value="allow">Start another one</option>
              </Select></div>
          </div>
          <div><Label htmlFor="schedule-input">Answers to the request form (JSON)</Label>
            <Textarea id="schedule-input" rows={4} value={form.input} onChange={(e) => set({ input: e.target.value })} aria-invalid={!request || undefined} />
            {!request ? <p className="text-sm text-danger-foreground">The answers are not valid JSON.</p> : null}</div>
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="secondary" disabled={pending || !request} onClick={() => run(async () => {
              const r = await call(api.POST('/workspaces/{workspaceId}/schedules/preview', { params: { path: { workspaceId: ws } }, body: request as Request }))
              setNext(r.next)
            })}>Show the next times</Button>
            <Button type="submit" disabled={pending || !request || !form.name.trim() || !form.workflowId}>Save schedule</Button>
          </div>
          {next.length ? <ol className="list-decimal pl-5 text-sm" aria-label="Next start times">{next.map((t) => <li key={t}>{formatDateTime(t)}</li>)}</ol> : null}
        </form>
      ) : null}
    </section>
  )
}
