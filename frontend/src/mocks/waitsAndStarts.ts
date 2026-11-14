import { http, HttpResponse, type HttpHandler } from 'msw'
import type { components } from '@/api/schema'

type S = components['schemas']

/**
 * UI double of docs/build/S139 (mock mode and unit tests only): shared working calendars, the reminders of a request
 * and scheduled starts. The mock keeps them in memory, starts nothing and sends nothing; previews use simple rules
 * (the real timing is the server's).
 */
const B = '/api/v1/workspaces/:ws'

interface StoredCalendar {
  summary: S['WorkingCalendarSummary']
  versions: S['WorkingCalendarVersion'][]
}

const calendars = new Map<string, StoredCalendar>()
const schedules = new Map<string, S['Schedule']>()
const MOCK_USER = '00000000-0000-7000-8000-0000000000aa'

export function resetWaitsAndStartsMock() {
  calendars.clear()
  schedules.clear()
}

function problem(status: number, code: string, detail: string, fieldErrors?: Array<{ pointer: string; code: string; message: string }>) {
  return HttpResponse.json({ type: 'about:blank', title: detail, status, code, detail, correlationId: 'mock', ...(fieldErrors ? { fieldErrors } : {}) }, { status })
}

function detail(c: StoredCalendar): S['WorkingCalendarDetail'] {
  return { calendar: c.summary, versions: [...c.versions].sort((a, b) => b.version - a.version) }
}

/** Next times of a daily "m h * * *" cron in UTC; other expressions get hourly placeholders. */
function nextTimes(expression: string, count: number): string[] {
  const m = /^(\d{1,2}) (\d{1,2}) \* \* \*$/.exec(expression.trim())
  const out: string[] = []
  const now = new Date()
  for (let i = 0; out.length < count && i < 400; i++) {
    const d = new Date(now.getTime() + (m ? i * 86_400_000 : (i + 1) * 3_600_000))
    if (m) d.setUTCHours(Number(m[2]), Number(m[1]), 0, 0)
    else d.setUTCMinutes(0, 0, 0)
    if (d > now) out.push(d.toISOString())
  }
  return out
}

export function createWaitsAndStartsHandlers(): HttpHandler[] {
  return [
    http.get(`${B}/calendars`, () => HttpResponse.json({ items: [...calendars.values()].map((c) => c.summary) } satisfies S['WorkingCalendarList'])),
    http.post(`${B}/calendars`, async ({ request }) => {
      const body = (await request.json()) as S['CreateWorkingCalendar']
      if (!/^[a-z][a-z0-9-]{0,62}[a-z0-9]$/.test(body.key ?? '')) return problem(422, 'VALIDATION_FAILED', 'Some values are not valid.', [{ pointer: '/key', code: 'pattern', message: 'Use lowercase letters, digits and hyphens.' }])
      if (calendars.has(body.key)) return problem(409, 'DUPLICATE_KEY', 'A working calendar with this key or name already exists.')
      const now = new Date().toISOString()
      const stored: StoredCalendar = {
        summary: { key: body.key, name: body.name, description: body.description ?? null, latestVersion: 1, archived: false, createdAt: now, updatedAt: now },
        versions: [{ version: 1, content: body.content, changeNote: null, createdBy: MOCK_USER, createdAt: now }],
      }
      calendars.set(body.key, stored)
      return HttpResponse.json(detail(stored), { status: 201 })
    }),
    http.get(`${B}/calendars/:key`, ({ params }) => {
      const c = calendars.get(String(params.key))
      return c ? HttpResponse.json(detail(c)) : problem(404, 'NOT_FOUND', 'Not found')
    }),
    http.post(`${B}/calendars/:key/versions`, async ({ params, request }) => {
      const c = calendars.get(String(params.key))
      if (!c) return problem(404, 'NOT_FOUND', 'Not found')
      const body = (await request.json()) as S['AddWorkingCalendarVersion']
      if (body.expectedVersion !== c.summary.latestVersion) return problem(409, 'CONFIGURATION_VERSION_CONFLICT', 'The calendar has a newer version.')
      const now = new Date().toISOString()
      const version = c.summary.latestVersion + 1
      c.versions.push({ version, content: body.content, changeNote: body.changeNote ?? null, createdBy: MOCK_USER, createdAt: now })
      c.summary = { ...c.summary, latestVersion: version, updatedAt: now }
      return HttpResponse.json(detail(c))
    }),
    http.post(`${B}/calendars/:key/archive`, async ({ params, request }) => {
      const c = calendars.get(String(params.key))
      if (!c) return problem(404, 'NOT_FOUND', 'Not found')
      const body = (await request.json().catch(() => ({}))) as S['ArchiveWorkingCalendar']
      c.summary = { ...c.summary, archived: body?.archived !== false, updatedAt: new Date().toISOString() }
      return HttpResponse.json(detail(c))
    }),
    http.post(`${B}/calendars/:key/preview`, async ({ params, request }) => {
      if (!calendars.has(String(params.key))) return problem(404, 'NOT_FOUND', 'Not found')
      const body = (await request.json()) as S['WorkingCalendarPreview']
      const days = Number(/(\d+)D/.exec(body.workingDuration ?? '')?.[1] ?? 0)
      const hours = Number(/(\d+)H/.exec(body.workingDuration ?? '')?.[1] ?? 0)
      const from = body.from ? new Date(body.from) : new Date()
      return HttpResponse.json({ dueAt: new Date(from.getTime() + (days * 1.4 * 24 + hours) * 3_600_000).toISOString() } satisfies S['WorkingCalendarDue'])
    }),
    http.get(`${B}/runs/:runId/reminders`, () => HttpResponse.json({ items: [] } satisfies S['RunReminderList'])),
    http.get(`${B}/schedules`, ({ request }) => {
      const wf = new URL(request.url).searchParams.get('workflowId')
      return HttpResponse.json({ items: [...schedules.values()].filter((s) => !wf || s.workflowId === wf) } satisfies S['ScheduleList'])
    }),
    http.post(`${B}/schedules/preview`, async ({ request }) => {
      const body = (await request.json()) as S['ScheduleRequest']
      return HttpResponse.json({ next: nextTimes(body.expression ?? '', 10) } satisfies S['SchedulePreview'])
    }),
    http.post(`${B}/schedules`, async ({ request }) => {
      const body = (await request.json()) as S['ScheduleRequest']
      if (!body.workflowId || !body.name || !body.expression) return problem(422, 'VALIDATION_FAILED', 'Some values are not valid.', [{ pointer: '/name', code: 'required', message: 'Give the schedule a name.' }])
      const now = new Date().toISOString()
      const upcoming = nextTimes(body.expression, 5)
      const id = crypto.randomUUID()
      const schedule: S['Schedule'] = {
        id, workflowId: body.workflowId, workflowName: 'Workflow', name: body.name, kind: body.kind ?? 'cron', expression: body.expression,
        zone: body.zone ?? 'UTC', startsAt: now, endsAt: body.endsAt ?? null, calendarKey: body.calendarKey ?? null,
        calendarVersion: body.calendarKey ? calendars.get(body.calendarKey)?.summary.latestVersion ?? 1 : null, input: body.input ?? {},
        missedRunPolicy: body.missedRunPolicy ?? 'catchUpOnce', overlapPolicy: body.overlapPolicy ?? 'skip', enabled: body.enabled !== false,
        runAs: MOCK_USER, runAsName: 'You', nextFireAt: upcoming[0] ?? null, lastFireAt: null, lastOutcome: null, lastRunId: null, version: 1,
        createdAt: now, updatedAt: now, upcoming, recent: [],
      }
      schedules.set(id, schedule)
      return HttpResponse.json(schedule, { status: 201 })
    }),
    http.get(`${B}/schedules/:id`, ({ params }) => {
      const s = schedules.get(String(params.id))
      return s ? HttpResponse.json(s) : problem(404, 'NOT_FOUND', 'Not found')
    }),
    http.patch(`${B}/schedules/:id`, async ({ params, request }) => {
      const s = schedules.get(String(params.id))
      if (!s) return problem(404, 'NOT_FOUND', 'Not found')
      const body = (await request.json()) as S['ScheduleRequest']
      if (body.expectedVersion !== s.version) return problem(409, 'CONFIGURATION_VERSION_CONFLICT', 'The schedule changed since you loaded it.')
      const enabled = body.enabled ?? s.enabled
      const next: S['Schedule'] = { ...s, name: body.name ?? s.name, expression: body.expression ?? s.expression, enabled,
        nextFireAt: enabled ? s.upcoming[0] ?? null : null, version: s.version + 1, updatedAt: new Date().toISOString() }
      schedules.set(s.id, next)
      return HttpResponse.json(next)
    }),
    http.delete(`${B}/schedules/:id`, ({ params }) => {
      schedules.delete(String(params.id))
      return new HttpResponse(null, { status: 204 })
    }),
  ]
}
