import { delay, http, HttpResponse, type HttpHandler } from 'msw'
import type { components } from '@/api/schema'
import { buildFields, parseFormSchema } from '@/lib/form/schema'
import { validateJsonInput } from '@/lib/form/validate'
import { claim, createRun, currentRelease, decide, requestTitleOf, selfApprovalAllowed, toTaskSummary } from './engine'
import { getCurrentUser } from './handlers'
import { WORKSPACES, db, inGroup, isMember, newId, permissionsOf, type MockRun, type MockTask, type MockUser } from './state'

/**
 * Mock-mode Assistant (S15). It follows the backend's guided commands for the purchase-approval flow: collect the
 * published form fields one at a time, show a labelled review, submit only on `confirm`, and decide a task only
 * after an explicit choice and a separate `confirm`. Like the server it never interprets free text with AI.
 */
type Conversation = components['schemas']['Conversation']
type Json = Record<string, unknown>
type Stage = Conversation['stage']

interface State {
  stage: Stage
  message: string
  choices: Array<{ label: string; command: string }>
  field?: Json
  review?: { workflowName: string; input: Json; fields: Array<{ name: string; label: string; value: unknown; display?: string }> }
  runId?: string
  taskId?: string
  taskAction?: 'claim' | 'approve' | 'reject'
  // Server-side only
  workflowId?: string
  releaseId?: string
  schema?: { properties: Record<string, Json>; required?: string[] }
  order?: string[]
  input?: Json
  skipped?: string[]
  reason?: string
  taskVersion?: number
  workflowName?: string
}
interface Stored { id: string; wsId: string; userId: string; version: number; state: State }

const B = '*/api/v1'
const STORAGE = 'orqix.mock.conversations.v1'
const conversations = new Map<string, Stored>()
let loaded = false

function load(): void {
  if (loaded) return
  loaded = true
  try {
    const raw = window.localStorage.getItem(STORAGE)
    if (raw) for (const item of JSON.parse(raw) as Stored[]) conversations.set(item.id, item)
  } catch { /* in-memory only */ }
}
function save(): void {
  try { window.localStorage.setItem(STORAGE, JSON.stringify([...conversations.values()].slice(-50))) } catch { /* in-memory only */ }
}
/** Tests and the "reset demo data" button start from nothing. */
export function resetConversations(): void {
  conversations.clear(); loaded = true
  try { window.localStorage.removeItem(STORAGE) } catch { /* nothing stored */ }
}

function problem(status: number, code: string, title: string, extra: Json = {}): Response {
  return HttpResponse.json(
    { type: `https://orqix.example/problems/${code.toLowerCase().replace(/_/g, '-')}`, title, status, code, correlationId: newId(), ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}
const notFound = () => problem(404, 'NOT_FOUND', 'Not found')

const WORDS: Record<string, string> = { running: 'in progress', waiting: 'waiting for a decision', needsAttention: 'needs attention', completed: 'finished', cancelled: 'cancelled' }
const words = (lifecycle: string) => WORDS[lifecycle] ?? lifecycle
const MENU = [{ label: 'Start a request', command: 'workflows' }, { label: 'My tasks', command: 'my tasks' }, { label: 'My requests', command: 'my requests' }]

function view(c: Stored): Conversation {
  const s = c.state
  const out: Json = { id: c.id, version: c.version, stage: s.stage, message: s.message, choices: s.choices }
  for (const key of ['field', 'review', 'runId', 'taskId', 'taskAction'] as const) if (s[key] !== undefined) out[key] = s[key]
  return out as unknown as Conversation
}

function startable(wsId: string, user: MockUser) {
  if (!permissionsOf(user, wsId).includes('workflow.start')) return []
  return [...db.workflows.values()].filter((w) => w.wsId === wsId && w.activeReleaseId)
}

function requiredOf(s: State, name: string): boolean {
  return !!s.schema?.required?.includes(name)
}

const DATE_HINT = 'Enter the date as year-month-day, for example 2026-10-14.'
const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December']
function readableDate(value: unknown): string | undefined {
  const m = typeof value === 'string' ? /^(\d{4})-(\d{2})-(\d{2})$/.exec(value) : null
  return m ? `${Number(m[3])} ${MONTHS[Number(m[2]) - 1] ?? ''} ${m[1]}` : undefined
}

function reviewFields(input: Json, schema: State['schema'], order: string[]) {
  const names = [...new Set([...order, ...Object.keys(input)])].filter((n) => n in input)
  return names.map((name) => {
    const property = schema?.properties[name]
    return { name, label: String(property?.title ?? name), value: input[name], ...(property?.format === 'date' && readableDate(input[name]) ? { display: readableDate(input[name]) } : {}) }
  })
}

function ask(s: State): void {
  const input = s.input ?? {}
  for (const name of s.order ?? []) {
    if (name in input || s.skipped?.includes(name)) continue
    const schema = s.schema?.properties[name]
    if (!schema) continue
    const title = String(schema.title ?? name)
    s.field = { name, ...schema, required: requiredOf(s, name) }
    delete s.review
    s.stage = 'collecting'
    s.message = `${title.endsWith('?') ? title : `What is ${title}?`}${schema.format === 'date' ? ` ${DATE_HINT}` : ''}`
    return
  }
  delete s.field
  s.stage = 'review'
  const name = s.workflowName ?? ''
  s.message = `Review ${name}. Send confirm to submit, or cancel to discard.`
  s.review = { workflowName: name, input: { ...input }, fields: reviewFields(input, s.schema, s.order ?? []) }
}

function parseScalar(schema: Json, raw: string): unknown {
  switch (schema.type) {
    case 'number':
    case 'integer': {
      if (!/^[-+]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\.[0-9]+)?$/.test(raw)) throw new Error('number')
      return Number(raw.replace(/,/g, ''))
    }
    case 'boolean': {
      const v = raw.toLowerCase()
      if (!['true', 'false', 'yes', 'no'].includes(v)) throw new Error('boolean')
      return v === 'true' || v === 'yes'
    }
    case 'array': {
      if (raw.startsWith('[')) return JSON.parse(raw) as unknown
      const items = (schema.items ?? {}) as Json
      if (items.type === 'object' || items.type === 'array') throw new Error('array')
      return raw.split(/[,\n]/).map((p) => p.trim()).filter(Boolean).map((p) => parseScalar(items, p))
    }
    default:
      return raw
  }
}

function invalid(schema: Json, name: string): string {
  const title = String(schema.title ?? name)
  switch (schema.type) {
    case 'number':
    case 'integer': return `Please enter ${title} as a number, for example 1500 or 1,500.50.`
    case 'boolean': return `Please answer yes or no for ${title}.`
    case 'array': return `Please list the items for ${title} separated by commas, for example Laptop, Monitor.`
    default: return schema.format === 'date' ? `Please enter ${title} as year-month-day, for example 2026-10-14.` : `That answer is not valid for ${title}. Please try again.`
  }
}

function collect(s: State, text: string): void {
  let name = (s.field?.name as string | undefined) ?? null
  let raw = text
  const equal = text.indexOf('=')
  if (equal > 0 && s.schema?.properties[text.slice(0, equal).trim()]) { name = text.slice(0, equal).trim(); raw = text.slice(equal + 1).trim() }
  if (!name) { s.message = 'To change a value, use the Change button. Send confirm to submit the reviewed request.'; return }
  const schema = s.schema!.properties[name]!
  if (raw.toLowerCase() === 'skip' && !requiredOf(s, name)) { delete (s.input as Json)[name]; (s.skipped ??= []).push(name); ask(s); return }
  const options = Array.isArray(schema.enum) ? (schema.enum as unknown[]) : []
  const matched = options.find((o) => typeof o === 'string' && o.toLowerCase() === raw.toLowerCase())
  if (typeof matched === 'string') raw = matched
  let value: unknown
  try { value = parseScalar(schema, raw) } catch { s.message = invalid(schema, name); return }
  const one = { type: 'object', additionalProperties: false, required: [name], properties: { [name]: schema } }
  const issues = validateJsonInput(parseFormSchema(one as never), { [name]: value })
  if (issues.length > 0) {
    const keyword = issues[0]?.keyword
    s.message = keyword === 'enum' ? `Choose one of: ${options.join(', ')}.`
      : keyword === 'minimum' || keyword === 'maximum' ? `${String(schema.title ?? name)} is outside the allowed range${schema.minimum !== undefined ? ` (minimum ${String(schema.minimum)})` : ''}${schema.maximum !== undefined ? ` (maximum ${String(schema.maximum)})` : ''}.`
      : keyword === 'formatMinimum' || keyword === 'formatMaximum' ? `Choose a date ${keyword === 'formatMinimum' ? 'on or after' : 'on or before'} ${readableDate(keyword === 'formatMinimum' ? schema.formatMinimum : schema.formatMaximum) ?? ''}.`
      : invalid(schema, name)
    return
  }
  ;(s.input as Json)[name] = value
  ask(s)
}

function begin(wsId: string, workflowId: string): State | null {
  const wf = db.workflows.get(workflowId)
  const release = wf?.releases.find((r) => r.id === wf.activeReleaseId)
  if (!wf || !release || wf.wsId !== wsId) return null
  const schema = release.definition.inputSchema as unknown as NonNullable<State['schema']>
  const ordered = new Set<string>([...(release.definition.inputUi?.order ?? []), ...Object.keys(schema.properties)])
  const s: State = { stage: 'collecting', message: '', choices: [], workflowId, releaseId: release.id, schema, order: [...ordered], input: {}, skipped: [] }
  s.workflowName = wf.name
  ask(s)
  return s
}

type TaskPick = { run: MockRun; task: MockTask }
function allTasks(wsId: string): TaskPick[] {
  return [...db.runs.values()].filter((r) => r.wsId === wsId).flatMap((run) => run.tasks.map((task) => ({ run, task })))
}
function actionable(user: MockUser, wsId: string, { run, task }: TaskPick): boolean {
  if (!permissionsOf(user, wsId).includes('task.act') || task.status !== 'open') return false
  if (task.assigneeId) return task.assigneeId === user.id
  return inGroup(user, task.candidateGroup) && (run.initiatorId !== user.id || selfApprovalAllowed(run, currentRelease(run)))
}

function taskReview(s: State, pick: TaskPick): void {
  const rel = currentRelease(pick.run)
  const schema = rel.definition.inputSchema as unknown as State['schema']
  s.review = { workflowName: taskName(pick), input: { ...pick.run.input }, fields: reviewFields(pick.run.input, schema, rel.definition.inputUi?.order ?? []) }
}
function taskName(pick: TaskPick): string {
  const title = toTaskSummary(pick.run, pick.task).requestTitle
  return `${pick.run.reference ?? pick.run.id}${title ? ` - ${title}` : ''}`
}

interface Outcome { state?: State; problem?: Response }

function handle(user: MockUser, wsId: string, prior: State | null, text: string): Outcome {
  const s: State = prior ? structuredClone(prior) : { stage: 'idle', message: '', choices: [] }
  s.choices = []
  const lower = text.toLowerCase()
  const idle = (message: string, choices: State['choices'] = []): Outcome => ({ state: { stage: 'idle', message, choices } })

  if (lower === 'cancel' || lower === 'reset') return idle('Nothing was changed and the draft is cleared. Say Start a request, My tasks or My requests when you are ready.')
  if (lower === 'confirm' || lower === 'submit') return confirm(user, wsId, s)

  if (lower === 'my tasks') {
    const seen = new Set<string>()
    const mine = allTasks(wsId).filter((p) => actionable(user, wsId, p)).filter((p) => !seen.has(p.task.id) && seen.add(p.task.id))
    const choices = mine.map((p) => {
      const title = toTaskSummary(p.run, p.task).requestTitle
      return { label: `${p.run.reference ?? p.run.id} - ${title ? `${title} (${p.task.label})` : p.task.label}`, command: `review ${p.task.id}` }
    })
    return idle(choices.length ? 'These tasks are waiting for you. Choose one to see the request before you decide.' : 'You have no tasks waiting for you right now.', choices)
  }
  if (lower === 'my requests') {
    const mine = [...db.runs.values()].filter((r) => r.wsId === wsId && r.initiatorId === user.id).sort((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, 25)
    const choices = mine.map((r) => ({ label: `${r.reference ?? r.id} - ${requestTitleOf(r) ? `${requestTitleOf(r)} - ` : ''}${words(r.lifecycle)}`, command: `status ${r.reference ?? r.id}` }))
    return idle(choices.length ? 'Your latest requests. Choose one to see where it stands.' : 'You have not made any requests yet. Choose Start a request to begin one.', choices)
  }
  if (lower.startsWith('status ')) {
    const ref = text.slice(7).trim()
    const run = [...db.runs.values()].find((r) => r.wsId === wsId && (r.reference?.toLowerCase() === ref.toLowerCase() || r.id === ref))
    const visible = run && (run.initiatorId === user.id || permissionsOf(user, wsId).includes('run.read') || run.tasks.some((t) => t.assigneeId === user.id || inGroup(user, t.candidateGroup)))
    if (!run || !visible) return { problem: notFound() }
    return { state: { stage: 'completed', message: `${run.reference ?? run.id}${requestTitleOf(run) ? ` (${requestTitleOf(run)})` : ''} is ${words(run.lifecycle)}${run.outcome ? ` (${run.outcome})` : ''}.${run.attention ? ` ${run.attention.message}` : ''}`, choices: [], runId: run.id } }
  }
  if (lower.startsWith('review ')) {
    const pick = allTasks(wsId).find((p) => p.task.id === text.slice(7).trim())
    if (!pick) return { problem: notFound() }
    if (!actionable(user, wsId, pick)) return { problem: problem(403, 'FORBIDDEN', 'Forbidden') }
    const next: State = { stage: 'confirmingTask', message: `Review ${taskName(pick)}. Nothing is recorded until you approve or reject and then confirm.`, choices: [{ label: 'Approve', command: `approve ${pick.task.id}` }], taskId: pick.task.id, taskVersion: pick.task.version }
    taskReview(next, pick)
    return { state: next }
  }
  if (/^(approve|reject|claim) /.test(lower)) {
    const action = lower.split(' ')[0] as 'approve' | 'reject' | 'claim'
    const rest = text.slice(text.indexOf(' ') + 1).trim()
    const colon = rest.indexOf(':')
    const taskId = (colon < 0 ? rest : rest.slice(0, colon)).trim()
    const reason = colon < 0 ? null : rest.slice(colon + 1).trim()
    const pick = allTasks(wsId).find((p) => p.task.id === taskId)
    if (!pick) return { problem: notFound() }
    if (!actionable(user, wsId, pick)) return { problem: problem(403, 'FORBIDDEN', 'Forbidden') }
    const verb = action === 'claim' ? 'take' : action
    const next: State = { stage: 'confirmingTask', choices: [], taskId, taskAction: action, taskVersion: pick.task.version, ...(reason ? { reason } : {}),
      message: action === 'reject' && !reason ? `A reason is required to reject ${taskName(pick)}. Tell the requester what to change.` : `You are about to ${verb} ${taskName(pick)}${reason ? ` because: ${reason}` : ''}. Nothing is recorded until you confirm.` }
    taskReview(next, pick)
    return { state: next }
  }
  if (lower === 'workflows' || lower.startsWith('start ')) {
    const query = lower === 'workflows' ? null : lower.slice(6).trim()
    const all = startable(wsId, user)
    const hits = query === null ? all : all.filter((w) => w.key.toLowerCase().includes(query) || w.name.toLowerCase().includes(query))
    const exact = query === null ? [] : hits.filter((w) => w.key.toLowerCase() === query || w.name.toLowerCase() === query)
    const candidates = exact.length ? exact : hits
    if (query !== null && candidates.length === 1) {
      const next = begin(wsId, candidates[0]!.id)
      return next ? { state: next } : { problem: notFound() }
    }
    return idle(candidates.length === 0 ? 'I could not find a request type you can start with that name. Choose Start a request to see what is available.' : query === null ? 'What would you like to request?' : 'Which of these did you mean?',
      candidates.map((w) => ({ label: w.name, command: `start ${w.key}` })))
  }
  if (s.stage === 'collecting' || s.stage === 'review') { collect(s, text); return { state: s } }
  if (s.stage === 'confirmingTask') { s.message = 'Choose Approve or Reject below. Nothing is recorded until you confirm, and Discard leaves the task untouched.'; return { state: s } }
  return idle('I can help you start a request, decide a task that is waiting for you, or check on a request you made. Choose one below, or type what you want, for example Start Purchase approval.', MENU)
}

function confirm(user: MockUser, wsId: string, s: State): Outcome {
  if (s.stage === 'review') {
    const wf = db.workflows.get(s.workflowId!)
    const release = wf?.releases.find((r) => r.id === s.releaseId)
    if (!wf || !release) return { problem: notFound() }
    const schema = parseFormSchema(release.definition.inputSchema)
    const issues = validateJsonInput(schema, s.input ?? {})
    if (issues.length > 0) {
      const fields = buildFields(schema, {})
      return { problem: problem(422, 'VALIDATION_FAILED', 'The request has invalid fields', { fieldErrors: issues.map((i) => ({ pointer: `/input${i.pointer}`, code: i.keyword, message: `${fields.find((f) => `/${f.key}` === i.pointer)?.label ?? i.pointer.slice(1)} is not valid.` })) }) }
    }
    if (release.id !== wf.activeReleaseId) return { problem: problem(409, 'RELEASE_NOT_ACTIVE', 'The form was updated') }
    const run = createRun({ wsId, workflow: wf, release, initiator: user, input: s.input ?? {}, businessKey: null })
    return { state: { stage: 'completed', message: `Submitted ${run.reference ?? run.id}${requestTitleOf(run) ? ` (${requestTitleOf(run)})` : ''}. You can follow its progress or start another request.`, choices: [], runId: run.id } }
  }
  if (s.stage === 'confirmingTask') {
    const action = s.taskAction
    if (!action) { s.message = 'Choose Approve or Reject first, then confirm.'; return { state: s } }
    if (action === 'reject' && !s.reason?.trim()) { s.message = 'A reason is required to reject. Tell the requester what to change.'; return { state: s } }
    const pick = allTasks(wsId).find((p) => p.task.id === s.taskId)
    if (!pick) return { problem: notFound() }
    if (!actionable(user, wsId, pick)) return { problem: problem(403, 'FORBIDDEN', 'Forbidden') }
    if (pick.task.version !== s.taskVersion) return { problem: problem(409, 'TASK_VERSION_CONFLICT', 'The task changed', { currentVersion: pick.task.version }) }
    if (!pick.task.assigneeId) claim(pick.run, pick.task, user)
    if (action !== 'claim') decide(pick.run, pick.task, user, action, action === 'reject' ? (s.reason?.trim() ?? null) : null, null)
    const ref = pick.run.reference ?? pick.run.id
    return { state: { stage: 'completed', taskId: pick.task.id, choices: [], message: action === 'claim' ? `You took ${ref}.` : `You ${action === 'approve' ? 'approved' : 'rejected'} ${ref}.` } }
  }
  return { state: { ...s, message: 'There is nothing to confirm yet. Start a request or choose a task first.' } }
}

export function createConversationHandlers(): HttpHandler[] {
  const slow = async () => { if (db.latencyMs > 0) await delay(db.latencyMs) }
  const memberOf = (ws: unknown): { user: MockUser; wsId: string } | null => {
    const wsId = Array.isArray(ws) ? String(ws[0]) : String(ws ?? '')
    const user = getCurrentUser()
    return WORKSPACES.some((w) => w.id === wsId) && isMember(user, wsId) ? { user, wsId } : null
  }
  return [
    http.post(`${B}/workspaces/:ws/conversations/messages`, async ({ params, request }) => {
      await slow(); load()
      const who = memberOf(params.ws)
      if (!who) return notFound()
      const key = request.headers.get('Idempotency-Key')
      if (!key || !/^[A-Za-z0-9._:-]{8,128}$/.test(key)) return problem(400, 'IDEMPOTENCY_KEY_REQUIRED', 'Idempotency-Key header required')
      let body: { message?: unknown; conversationId?: unknown; expectedVersion?: unknown }
      try { body = (await request.json()) as typeof body } catch { return problem(400, 'MALFORMED_REQUEST', 'Malformed request') }
      if (typeof body.message !== 'string' || !body.message.trim() || body.message.length > 4000) return problem(400, 'MALFORMED_REQUEST', 'message must be between 1 and 4000 characters.')
      const stored = `${who.user.id}|sendConversationMessage|${key}`
      const hash = JSON.stringify(body)
      const prior = db.idempotency.get(stored)
      if (prior) {
        if (prior.hash !== hash) return problem(409, 'IDEMPOTENCY_KEY_REUSED', 'Idempotency key reused with a different request')
        return HttpResponse.json(prior.body as Json, { status: 200, headers: { 'Idempotent-Replayed': 'true' } })
      }
      let current: Stored | undefined
      if (typeof body.conversationId === 'string') {
        current = conversations.get(body.conversationId)
        if (!current || current.wsId !== who.wsId || current.userId !== who.user.id) return notFound()
        if (body.expectedVersion !== current.version) return problem(409, 'TASK_VERSION_CONFLICT', 'The conversation changed; reload it before sending.', { currentVersion: current.version })
      }
      const outcome = handle(who.user, who.wsId, current?.state ?? null, body.message.trim())
      if (outcome.problem) return outcome.problem
      const saved: Stored = { id: current?.id ?? newId(), wsId: who.wsId, userId: who.user.id, version: (current?.version ?? 0) + 1, state: outcome.state! }
      conversations.set(saved.id, saved); save()
      const reply = view(saved)
      db.idempotency.set(stored, { hash, status: 200, body: reply })
      return HttpResponse.json(reply, { status: 200, headers: { 'Idempotent-Replayed': 'false' } })
    }),
    http.get(`${B}/workspaces/:ws/conversations/:id`, async ({ params }) => {
      await slow(); load()
      const who = memberOf(params.ws)
      if (!who) return notFound()
      const found = conversations.get(String(params.id))
      if (!found || found.wsId !== who.wsId || found.userId !== who.user.id) return notFound()
      return HttpResponse.json(view(found))
    }),
  ]
}
