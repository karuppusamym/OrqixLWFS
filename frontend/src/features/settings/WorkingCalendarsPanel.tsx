import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api, call } from '@/api/client'
import type { components } from '@/api/schema'
import type { ProblemError } from '@/api/problem'
import { useWorkspace } from '@/app/workspace'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { formatDateTime } from '@/lib/dates'

type Content = components['schemas']['WorkingCalendarContent']
type Detail = components['schemas']['WorkingCalendarDetail']

const DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']
const KEY = /^[a-z][a-z0-9-]{0,62}[a-z0-9]$/

/** "09:00-12:00, 13:00-17:00" -> intervals; null when not readable. */
export function parsePeriods(text: string): Array<{ start: string; end: string }> | null {
  const parts = text.split(',').map((p) => p.trim()).filter(Boolean)
  const out: Array<{ start: string; end: string }> = []
  for (const part of parts) {
    const m = /^([01]\d|2[0-3]):([0-5]\d)\s*-\s*([01]\d|2[0-3]):([0-5]\d)$/.exec(part)
    if (!m) return null
    out.push({ start: `${m[1]}:${m[2]}`, end: `${m[3]}:${m[4]}` })
  }
  return out.length ? out : null
}

export function periodsText(content: Content | undefined): string {
  const list = content?.intervals?.length ? content.intervals : content?.start && content?.end ? [{ start: content.start, end: content.end }] : []
  return list.map((w) => `${w.start}-${w.end}`).join(', ')
}

/** The editable form of a working week; null with a message when it is not complete. */
export function toContent(zone: string, days: number[], periods: string, holidays: string): { content: Content | null; problem?: string } {
  const intervals = parsePeriods(periods)
  if (!zone.trim()) return { content: null, problem: 'Choose a time zone, for example Europe/London.' }
  if (days.length === 0) return { content: null, problem: 'Choose at least one working day.' }
  if (!intervals) return { content: null, problem: 'Write the working hours as HH:mm-HH:mm, separated by commas, for example 09:00-12:00, 13:00-17:00.' }
  const dates = holidays.split(/[\s,]+/).map((d) => d.trim()).filter(Boolean)
  if (dates.some((d) => !/^\d{4}-\d{2}-\d{2}$/.test(d))) return { content: null, problem: 'Write holidays as dates, YYYY-MM-DD.' }
  return { content: { zone: zone.trim(), weekdays: [...days].sort(), start: intervals[0]!.start, end: intervals[intervals.length - 1]!.end, intervals, holidays: dates } }
}

/**
 * Shared working calendars (docs/build/S139, CG-W05): read with workflow.read, changed with workflow.edit. A calendar
 * has numbered versions; a workflow pins one, so a new version never changes a published workflow or a running request.
 */
export function WorkingCalendarsPanel() {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const edit = can('workflow.edit')
  const client = useQueryClient()
  const list = useQuery({ queryKey: ['ws', ws, 'calendars'], enabled: can('workflow.read'),
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/calendars', { params: { path: { workspaceId: ws } } })) })
  const [open, setOpen] = useState<string | null>(null)
  const detail = useQuery({ queryKey: ['ws', ws, 'calendars', open], enabled: open !== null,
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/calendars/{calendarKey}', { params: { path: { workspaceId: ws, calendarKey: open as string } } })) })
  const [error, setError] = useState<ProblemError | null>(null)
  const [notice, setNotice] = useState('')
  const [key, setKey] = useState('')
  const [name, setName] = useState('')
  const [zone, setZone] = useState('Europe/London')
  const [days, setDays] = useState<number[]>([1, 2, 3, 4, 5])
  const [periods, setPeriods] = useState('09:00-17:00')
  const [holidays, setHolidays] = useState('')
  const [pending, setPending] = useState(false)
  const [preview, setPreview] = useState('P3D')
  const [due, setDue] = useState('')

  const run = async (task: () => Promise<void>) => {
    setPending(true); setError(null); setNotice('')
    try { await task(); await client.invalidateQueries({ queryKey: ['ws', ws, 'calendars'] }) } catch (cause) { setError(toProblem(cause)) } finally { setPending(false) }
  }
  const built = toContent(zone, days, periods, holidays)
  const load = (d: Detail) => {
    const latest = d.versions[0]?.content
    setZone(latest?.zone ?? 'Europe/London')
    setDays(latest?.weekdays ?? [1, 2, 3, 4, 5])
    setPeriods(periodsText(latest))
    setHolidays((latest?.holidays ?? []).join(', '))
  }

  return (
    <section className="space-y-4" aria-labelledby="calendars-heading">
      <div>
        <h2 id="calendars-heading" className="text-lg font-semibold">Working calendars</h2>
        <p className="text-sm text-muted-foreground">Working weeks that waits and time limits can count in, such as "3 working days". Workflows keep the version they were published with.</p>
      </div>
      {error ? <ProblemAlert error={error} /> : null}
      {notice ? <Alert>{notice}</Alert> : null}
      <ul className="divide-y divide-border rounded-control border border-border" aria-label="Working calendars">
        {(list.data?.items ?? []).length === 0 ? <li className="p-3 text-sm text-muted-foreground">No working calendars yet.</li> : null}
        {(list.data?.items ?? []).map((c) => (
          <li key={c.key} className="flex flex-wrap items-center justify-between gap-2 p-3">
            <div>
              <p className="font-medium">{c.name}{c.archived ? ' (archived)' : ''}</p>
              <p className="text-sm text-muted-foreground">{c.key} · version {c.latestVersion} · changed {formatDateTime(c.updatedAt)}</p>
            </div>
            <div className="flex gap-2">
              <Button variant="secondary" onClick={() => setOpen(open === c.key ? null : c.key)}>{open === c.key ? 'Close' : 'Open'}</Button>
              {edit ? <Button variant="secondary" disabled={pending} onClick={() => run(async () => {
                await call(api.POST('/workspaces/{workspaceId}/calendars/{calendarKey}/archive', { params: { path: { workspaceId: ws, calendarKey: c.key } }, body: { archived: !c.archived } }))
                setNotice(c.archived ? `${c.name} is restored.` : `${c.name} is archived. Workflows that pin it keep working; it cannot be chosen again.`)
              })}>{c.archived ? 'Restore' : 'Archive'}</Button> : null}
            </div>
          </li>
        ))}
      </ul>

      {open && detail.data ? (
        <div className="space-y-2 rounded-control border border-border p-3" data-testid="calendar-detail">
          <p className="font-medium">{detail.data.calendar.name}: versions</p>
          <ol className="space-y-1 text-sm">
            {detail.data.versions.map((v) => (
              <li key={v.version}>Version {v.version}: {v.content.zone}, {(v.content.weekdays ?? []).map((d) => DAYS[d - 1]?.slice(0, 3)).join(' ')}, {periodsText(v.content)}{v.content.holidays?.length ? `, ${v.content.holidays.length} holidays` : ''}{v.changeNote ? ` (${v.changeNote})` : ''}</li>
            ))}
          </ol>
          <div className="flex flex-wrap items-end gap-2">
            <div>
              <Label htmlFor="calendar-preview">Try a working time</Label>
              <Input id="calendar-preview" value={preview} onChange={(e) => setPreview(e.target.value)} />
            </div>
            <Button variant="secondary" disabled={pending} onClick={() => run(async () => {
              const r = await call(api.POST('/workspaces/{workspaceId}/calendars/{calendarKey}/preview', { params: { path: { workspaceId: ws, calendarKey: open } }, body: { workingDuration: preview } }))
              setDue(`${preview} from now ends ${formatDateTime(r.dueAt)}.`)
            })}>Show when it ends</Button>
          </div>
          {due ? <p role="status" className="text-sm">{due}</p> : null}
          {edit ? <Button variant="secondary" onClick={() => { load(detail.data as Detail); setKey(''); setName(detail.data?.calendar.name ?? '') }}>Edit as a new version</Button> : null}
        </div>
      ) : null}

      {edit ? (
        <form className="space-y-3 rounded-control border border-border p-3" aria-label={open ? 'New version' : 'New working calendar'} onSubmit={(e) => {
          e.preventDefault()
          if (!built.content) return
          void run(async () => {
            if (open && detail.data && !key) {
              await call(api.POST('/workspaces/{workspaceId}/calendars/{calendarKey}/versions', { params: { path: { workspaceId: ws, calendarKey: open } }, body: { expectedVersion: detail.data.calendar.latestVersion, content: built.content as Content } }))
              setNotice(`Version ${detail.data.calendar.latestVersion + 1} of ${detail.data.calendar.name} is saved. Workflows choose it when they are next edited.`)
            } else {
              await call(api.POST('/workspaces/{workspaceId}/calendars', { params: { path: { workspaceId: ws } }, body: { key, name, content: built.content as Content } }))
              setNotice(`${name} is saved.`)
              setKey(''); setName('')
            }
          })
        }}>
          <p className="font-medium">{open && detail.data && !key ? `New version of ${detail.data.calendar.name}` : 'New working calendar'}</p>
          {!(open && detail.data && !key) ? <div className="grid gap-3 sm:grid-cols-2">
            <div><Label htmlFor="calendar-key">Key</Label><Input id="calendar-key" value={key} placeholder="office-hours" onChange={(e) => setKey(e.target.value)} aria-invalid={key !== '' && !KEY.test(key) || undefined} /></div>
            <div><Label htmlFor="calendar-name">Name</Label><Input id="calendar-name" value={name} maxLength={80} onChange={(e) => setName(e.target.value)} /></div>
          </div> : null}
          <div><Label htmlFor="calendar-zone">Time zone</Label><Input id="calendar-zone" value={zone} onChange={(e) => setZone(e.target.value)} /></div>
          <fieldset className="flex flex-wrap gap-3">
            <legend className="text-sm font-medium">Working days</legend>
            {DAYS.map((d, i) => (
              <span key={d} className="flex items-center gap-1.5">
                <Checkbox id={`calendar-day-${i + 1}`} checked={days.includes(i + 1)} onCheckedChange={(c) => setDays(c === true ? [...days, i + 1] : days.filter((x) => x !== i + 1))} />
                <Label htmlFor={`calendar-day-${i + 1}`}>{d}</Label>
              </span>
            ))}
          </fieldset>
          <div><Label htmlFor="calendar-periods">Working hours</Label><Input id="calendar-periods" value={periods} onChange={(e) => setPeriods(e.target.value)} aria-describedby="calendar-periods-help" />
            <p id="calendar-periods-help" className="text-sm text-muted-foreground">For example 09:00-12:00, 13:00-17:00. Periods do not cross midnight.</p></div>
          <div><Label htmlFor="calendar-holidays">Holidays</Label><Textarea id="calendar-holidays" rows={2} value={holidays} onChange={(e) => setHolidays(e.target.value)} placeholder="2026-12-25, 2026-12-26" /></div>
          {built.problem ? <p className="text-sm text-danger-foreground" role="status">{built.problem}</p> : null}
          <Button type="submit" disabled={pending || !built.content || (!(open && detail.data) && (!KEY.test(key) || !name.trim()))}>Save</Button>
        </form>
      ) : null}
    </section>
  )
}
