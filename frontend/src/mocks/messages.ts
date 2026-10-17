import type { components } from '@/api/schema'
import type { WorkflowNode } from '@/features/studio/model/types'
import { addEvent, advance, currentRelease, engineConfig, durationMs } from './engine'
import { clock, db, type MockRun } from './state'

type Accepted = components['schemas']['MessageAccepted']
type FieldError = { pointer: string; code: string; message: string }

/** How long a message that finds no waiting request is kept (S16: 24 hours; the mock keeps the same window). */
export const MESSAGE_BUFFER_MS = 24 * 3_600_000
/** Mock timers never take longer than this, so a demo moves on (as for every other timer). */
const MOCK_TIMER_CAP_MS = 6000

export interface MockMessage {
  id: string
  wsId: string
  name: string
  key: string
  payload: Record<string, unknown>
  receivedAt: string
  expiresAt: string
  status: 'buffered' | 'delivered' | 'expired' | 'discarded'
}

const NAME = /^[a-z][a-zA-Z0-9]*(-[a-z0-9][a-zA-Z0-9]*)*$/
const VALUE = /^[a-z][A-Za-z0-9]{0,63}$/

function buffer(): MockMessage[] {
  return (db.messageBuffer ??= [])
}

type Declared = { name: string; type: string; required?: boolean }
const declared = (node: WorkflowNode): Declared[] => (Array.isArray(node.config.payload) ? (node.config.payload as Declared[]) : [])

function keyOf(run: MockRun, node: WorkflowNode): string | null {
  const ref = String(node.config.correlationField ?? '')
  const input = /^\/input\/(.+)$/.exec(ref)
  const step = /^\/steps\/([^/]+)\/(.+)$/.exec(ref)
  const value = input ? run.input[input[1] as string] : step ? run.steps[step[1] as string]?.[step[2] as string] : undefined
  return typeof value === 'string' || typeof value === 'number' ? String(value) : null
}

/** Same checks as the server (MessagePayloads): flat scalars, then the values the wait declares. */
export function payloadShapeErrors(payload: unknown): FieldError[] {
  if (payload === undefined || payload === null) return []
  if (typeof payload !== 'object' || Array.isArray(payload)) return [{ pointer: '/payload', code: 'type', message: 'The payload must be an object of values.' }]
  const errors: FieldError[] = []
  const entries = Object.entries(payload as Record<string, unknown>)
  if (entries.length > 10) errors.push({ pointer: '/payload', code: 'maxProperties', message: 'A message can carry at most 10 values.' })
  for (const [k, v] of entries) {
    if (!VALUE.test(k)) errors.push({ pointer: `/payload/${k}`, code: 'propertyNames', message: 'Value names start with a lowercase letter and use only letters and digits.' })
    else if (typeof v === 'string' ? v.length > 1000 : !(typeof v === 'number' || typeof v === 'boolean' || v === null)) errors.push({ pointer: `/payload/${k}`, code: typeof v === 'string' ? 'maxLength' : 'type', message: typeof v === 'string' ? 'Text can be at most 1000 characters.' : 'A value must be text, a number or true or false.' })
  }
  return errors
}

export function payloadErrors(node: WorkflowNode, payload: Record<string, unknown>): { errors: FieldError[]; output: Record<string, unknown> } {
  const errors: FieldError[] = []
  const output: Record<string, unknown> = {}
  const fields = declared(node)
  for (const f of fields) {
    const v = payload[f.name]
    if (v === undefined || v === null) {
      if (f.required) errors.push({ pointer: `/payload/${f.name}`, code: 'required', message: `The message must carry ${f.name}.` })
      continue
    }
    const ok = f.type === 'string' ? typeof v === 'string'
      : f.type === 'number' ? typeof v === 'number'
        : f.type === 'integer' ? typeof v === 'number' && Number.isInteger(v)
          : f.type === 'boolean' ? typeof v === 'boolean'
            : typeof v === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(v)
    if (ok) output[f.name] = v
    else errors.push({ pointer: `/payload/${f.name}`, code: 'type', message: `${f.name} has the wrong kind of value.` })
  }
  for (const k of Object.keys(payload)) if (!fields.some((f) => f.name === k)) errors.push({ pointer: `/payload/${k}`, code: 'unknown', message: `This wait does not declare a value named ${k}.` })
  return { errors, output }
}

/** A token reached "Wait for a message": register the wait, use a buffered message if there is one, else start the time limit. */
export function startMessageWait(run: MockRun, node: WorkflowNode): void {
  const key = keyOf(run, node)
  if (key === null) return
  const name = String(node.config.message)
  const limit = typeof node.config.timeoutAfter === 'string' ? durationMs(node.config.timeoutAfter) : null
  const ms = limit === null ? null : Math.min(limit, MOCK_TIMER_CAP_MS)
  const dueAt = ms === null ? null : new Date(clock.now().getTime() + ms).toISOString()
  run.messageWaits = { ...(run.messageWaits ?? {}), [node.id]: { message: name, key, dueAt } }
  run.lifecycle = 'waiting'
  addEvent(run, 'WAIT_STARTED', { nodeId: node.id, data: { label: node.label, message: name, duration: null, until: dueAt } })
  const early = buffer().find((m) => m.status === 'buffered' && m.wsId === run.wsId && m.name === name && m.key === key && m.expiresAt > clock.iso())
  if (early) {
    const result = payloadErrors(node, early.payload)
    if (result.errors.length === 0) {
      early.status = 'delivered'
      early.payload = {}
      // Consume and continue atomically before the request snapshot is persisted.
      // A zero-delay callback can be lost when navigation reloads the mock page.
      deliver(run, node, result.output, true)
      return
    }
    early.status = 'discarded'
    early.payload = {}
    addEvent(run, 'MESSAGE_DISCARDED', { nodeId: node.id, data: { message: name, reason: result.errors[0]?.message ?? '' } })
  }
  if (ms !== null) engineConfig.schedule(() => fireMessageTimeout(run, node), ms)
}

/** After a reload the shortened time limits of waits still in progress run again for the time that is left. */
export function resumeMessageTimers(run: MockRun, node: WorkflowNode): void {
  const w = run.messageWaits?.[node.id]
  if (!w?.dueAt) return
  engineConfig.schedule(() => fireMessageTimeout(run, node), Math.max(0, new Date(w.dueAt).getTime() - clock.now().getTime()))
}

function active(run: MockRun): boolean {
  return run.lifecycle === 'running' || run.lifecycle === 'waiting' || run.lifecycle === 'needsAttention'
}

function deliver(run: MockRun, node: WorkflowNode, output: Record<string, unknown>, buffered: boolean, actorId?: string): void {
  const w = run.messageWaits?.[node.id]
  if (!w || !active(run)) return
  delete run.messageWaits![node.id]
  run.steps[node.id] = output
  run.version += 1
  addEvent(run, 'WAIT_ENDED', { nodeId: node.id, actorId: actorId ?? null, data: { label: node.label, message: w.message, outcome: 'received', buffered, fields: Object.keys(output) } })
  const next = currentRelease(run).definition.transitions.find((t) => t.from === node.id && t.on === 'next')
  if (next) advance(run, next.to)
}

function fireMessageTimeout(run: MockRun, node: WorkflowNode): void {
  const w = run.messageWaits?.[node.id]
  if (!w || !active(run)) return
  delete run.messageWaits![node.id]
  run.version += 1
  addEvent(run, 'WAIT_ENDED', { nodeId: node.id, data: { label: node.label, message: w.message, outcome: 'timedOut', after: node.config.timeoutAfter } })
  const route = currentRelease(run).definition.transitions.find((t) => t.from === node.id && t.on === 'timedOut')
  if (route) advance(run, route.to)
}

/** POST /messages in the mock: the same outcomes as the server (delivered to every matching wait, or buffered). */
export function sendMessage(wsId: string, name: string, key: string, payload: Record<string, unknown> | null | undefined, senderId: string, newId: () => string): { status: number; body: Accepted } | { errors: FieldError[] } {
  const shape = payloadShapeErrors(payload)
  if (shape.length > 0) return { errors: shape }
  const values = payload ?? {}
  const waiting: Array<{ run: MockRun; node: WorkflowNode; output: Record<string, unknown> }> = []
  for (const run of db.runs.values()) {
    if (run.wsId !== wsId || !active(run)) continue
    for (const [nodeId, w] of Object.entries(run.messageWaits ?? {})) {
      if (w.message !== name || w.key !== key) continue
      const node = currentRelease(run).definition.nodes.find((n) => n.id === nodeId)
      if (!node) continue
      const result = payloadErrors(node, values)
      if (result.errors.length > 0) return { errors: result.errors }
      waiting.push({ run, node, output: result.output })
    }
  }
  const id = newId()
  const now = clock.iso()
  if (waiting.length === 0) {
    buffer().push({ id, wsId, name, key, payload: values, receivedAt: now, expiresAt: new Date(clock.now().getTime() + MESSAGE_BUFFER_MS).toISOString(), status: 'buffered' })
    return { status: 202, body: { id, status: 'buffered', matched: 0, delivered: [], bufferedUntil: new Date(clock.now().getTime() + MESSAGE_BUFFER_MS).toISOString() } }
  }
  for (const w of waiting) deliver(w.run, w.node, w.output, false, senderId)
  return { status: 202, body: { id, status: 'delivered', matched: waiting.length, delivered: waiting.map((w) => ({ runId: w.run.id, runReference: w.run.reference ?? '', nodeId: w.node.id })), bufferedUntil: null } }
}

export function validMessageName(name: string): boolean {
  return NAME.test(name) && name.length >= 2 && name.length <= 64
}
