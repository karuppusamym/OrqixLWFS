import type { Effect, Run, RunDetail, TaskDecision, TaskSummary } from '@/api/types'
import { conditionTerms, isAtomicCondition, type Condition, type Definition, type Literal, type WorkflowNode } from '@/features/studio/model/types'
import { nodeById, outgoing } from '@/features/studio/model/graph'
import { dateWaitDue } from './dateWait'
import { resolveInputAggregate } from '@/lib/form/aggregates'
import { reconcileStages, stageOfNode, stageProgress } from './stages'
import { resumeMessageTimers, startMessageWait } from './messages'
import {
  WORKSPACES,
  clock,
  connectionDefinition,
  db,
  inGroup,
  newId,
  permissionsOf,
  userById,
  type MockRelease,
  type MockRun,
  type MockTask,
  type MockUser,
  type MockWorkflow,
} from './state'

/**
 * A small deterministic stand-in for the Flowable-backed runtime (docs/build/S5-runtime.md): it walks the
 * released workflow definition, creates tasks, evaluates rule tables and branches, calls the procurement
 * "service" according to the selected fault mode, and writes the same event catalog the real backend writes.
 */

export const engineConfig = {
  /** How long a failed external call waits before its automatic retry (mock timers only). */
  retryDelayMs: 4000,
  /** Schedules the automatic retry. Tests replace this to run it immediately or never. */
  schedule: (fn: () => void, ms: number): void => {
    setTimeout(fn, ms)
  },
}

const MAX_ATTEMPTS = 3

export function addEvent(run: MockRun, type: string, opts: { nodeId?: string | null; actorId?: string | null; data?: Record<string, unknown> } = {}): void {
  run.eventSeq += 1
  run.events.push({
    seq: run.eventSeq,
    type,
    nodeId: opts.nodeId ?? null,
    actorId: opts.actorId ?? null,
    occurredAt: clock.iso(),
    data: opts.data ?? {},
  })
  run.updatedAt = clock.iso()
}

function resolveRef(run: MockRun, ref: string): Literal | undefined {
  const aggregate = resolveInputAggregate(run.input, ref)
  if (aggregate !== undefined) return aggregate
  const input = /^\/input\/(.+)$/.exec(ref)
  if (input) return run.input[input[1] as string] as Literal | undefined
  const step = /^\/steps\/([^/]+)\/(.+)$/.exec(ref)
  if (step) return run.steps[step[1] as string]?.[step[2] as string] as Literal | undefined
  return undefined
}

export function evaluateCondition(c: Condition, value: Literal | undefined): boolean {
  if (value === undefined || !isAtomicCondition(c)) return false
  switch (c.operator) {
    case 'eq':
      return typeof c.value === 'number' && typeof value === 'number' ? c.value === value : value === c.value
    case 'ne':
      return !(typeof c.value === 'number' && typeof value === 'number' ? c.value === value : value === c.value)
    case 'gte':
      return typeof value === 'number' && typeof c.value === 'number' && value >= c.value
    case 'gt':
      return typeof value === 'number' && typeof c.value === 'number' && value > c.value
    case 'lt':
      return typeof value === 'number' && typeof c.value === 'number' && value < c.value
    case 'lte':
      return typeof value === 'number' && typeof c.value === 'number' && value <= c.value
  }
}

function evaluateRunCondition(run: MockRun, c: Condition): boolean {
  if (isAtomicCondition(c)) return evaluateCondition(c, resolveRef(run, c.fieldRef))
  if ('all' in c) return (c.all ?? []).length > 0 && (c.all ?? []).every((term) => evaluateRunCondition(run, term))
  if ('any' in c) return (c.any ?? []).some((term) => evaluateRunCondition(run, term))
  return false
}

/** True when the row applies on {@code asOf} (YYYY-MM-DD); a row without a window always applies. */
export function inWindow(row: Definition['decisions'][number]['rows'][number], asOf: string | undefined): boolean {
  const w = row.effective
  if (!w || !asOf) return true
  return (!w.from || asOf >= w.from) && (!w.to || asOf <= w.to)
}

export function rowMatches(row: Definition['decisions'][number]['rows'][number], values: Record<string, Literal | undefined>, asOf?: string): boolean {
  const terms = conditionTerms(row.when)
  const hit = 'any' in row.when ? terms.some(term => evaluateCondition(term, values[term.fieldRef])) : terms.every(term => evaluateCondition(term, values[term.fieldRef]))
  return hit && inWindow(row, asOf)
}

export function evaluateTable(
  table: Definition['decisions'][number],
  values: Record<string, Literal | undefined>,
  asOf?: string,
): { matchedRow: number | null; usedDefault: boolean; output: Record<string, Literal> } {
  for (const [i, row] of table.rows.entries()) {
    if (rowMatches(row, values, asOf)) return { matchedRow: i, usedDefault: false, output: row.then }
  }
  return { matchedRow: null, usedDefault: true, output: table.default }
}

export function currentRelease(run: MockRun): MockRelease {
  // Requests of an archived workflow stay readable, as on the server (S103/S104).
  const wf = (db.workflows.get(run.workflowId) ?? db.archivedWorkflows?.get(run.workflowId)?.workflow) as MockWorkflow
  return wf.releases.find((r) => r.id === run.releaseId) as MockRelease
}

export function selfApprovalAllowed(run: MockRun, release: MockRelease): boolean {
  const ws = WORKSPACES.find((w) => w.id === run.wsId)
  return release.definition.security.selfApproval === 'allow' && !!ws?.allowSelfApprovalWorkflows
}

export function notify(run: MockRun, userId: string, kind: 'workflowMessage' | 'runAttention', message: string): void {
  db.notifications.unshift({ id: newId(), wsId: run.wsId, userId, runId: run.id, kind, message, createdAt: clock.iso(), readAt: null })
}

/** Whole minutes of a PT/P duration (days, hours, minutes); null when it is not one. */
export function durationMs(text: string): number | null {
  const m = /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?)?$/.exec(text)
  if (!m || text === 'P' || text.endsWith('T')) return null
  return (Number(m[1] ?? 0) * 1440 + Number(m[2] ?? 0) * 60 + Number(m[3] ?? 0)) * 60_000
}

/** Mock timers never take longer than this, so a demo moves on; the due time shown is the shortened one. */
const MOCK_TIMER_CAP_MS = 6000

function active(run: MockRun): boolean {
  return run.lifecycle === 'running' || run.lifecycle === 'waiting' || run.lifecycle === 'needsAttention'
}

function createTask(run: MockRun, node: WorkflowNode): void {
  db.taskCounter += 1
  const hours = node.config.dueAfterHours as number | undefined
  const dueAt = hours ? new Date(clock.now().getTime() + hours * 3_600_000).toISOString() : null
  const task: MockTask = {
    id: `task-${db.taskCounter}`,
    runId: run.id,
    nodeId: node.id,
    label: node.label,
    candidateGroup: node.config.candidateGroupRef as string,
    assigneeId: null,
    status: 'open',
    createdAt: clock.iso(),
    dueAt,
    version: 1,
    decision: null,
    completedById: null,
  }
  run.tasks.push(task)
  run.lifecycle = 'waiting'
  addEvent(run, 'TASK_CREATED', { nodeId: node.id, data: { label: node.label, candidateGroup: task.candidateGroup, dueAt } })
  const limit = typeof node.config.timeoutAfter === 'string' ? durationMs(node.config.timeoutAfter) : null
  if (limit !== null) engineConfig.schedule(() => fireTimeout(run, task, node), Math.min(limit, MOCK_TIMER_CAP_MS))
}

function fireTimeout(run: MockRun, task: MockTask, node: WorkflowNode): void {
  if (task.status !== 'open' || !active(run)) return
  task.status = 'cancelled'
  task.version += 1
  run.version += 1
  run.steps[node.id] = { decision: 'timedOut', decidedAt: clock.iso() }
  addEvent(run, 'TASK_TIMED_OUT', { nodeId: node.id, data: { label: node.label, after: node.config.timeoutAfter } })
  const route = outgoing(currentRelease(run).definition, node.id).find((t) => t.on === 'timedOut')
  if (route) advance(run, route.to)
}

function fireWait(run: MockRun, node: WorkflowNode): void {
  if (!run.waits?.[node.id] || !active(run)) return
  delete run.waits[node.id]
  addEvent(run, 'WAIT_ENDED', { nodeId: node.id, data: { label: node.label } })
  const next = outgoing(currentRelease(run).definition, node.id).find((t) => t.on === 'next')
  if (next) advance(run, next.to)
}

/**
 * Timers live in the page, so after a reload the ones that were running are scheduled again for the time that is
 * left (mock mode only; the server persists real timers).
 */
export function resumeTimers(): void {
  for (const run of db.runs.values()) {
    if (!active(run)) continue
    const def = currentRelease(run).definition
    for (const [nodeId, w] of Object.entries(run.waits ?? {})) {
      const node = nodeById(def, nodeId)
      if (node) engineConfig.schedule(() => fireWait(run, node), Math.min(MOCK_TIMER_CAP_MS, Math.max(0, new Date(w.dueAt).getTime() - clock.now().getTime())))
    }
    for (const nodeId of Object.keys(run.messageWaits ?? {})) {
      const node = nodeById(def, nodeId)
      if (node) resumeMessageTimers(run, node)
    }
    for (const task of run.tasks.filter((t) => t.status === 'open')) {
      const node = nodeById(def, task.nodeId)
      const limit = typeof node?.config.timeoutAfter === 'string' ? durationMs(node.config.timeoutAfter) : null
      if (node && limit !== null) {
        const left = new Date(task.createdAt).getTime() + Math.min(limit, MOCK_TIMER_CAP_MS) - clock.now().getTime()
        engineConfig.schedule(() => fireTimeout(run, task, node), Math.max(0, left))
      }
    }
  }
}

function startWait(run: MockRun, node: WorkflowNode): void {
  const untilField = node.config.untilField as string | undefined
  // Until a date: the real due moment is shown; a date in the past ends at once and, like every mock timer, a future
  // date ends after a few seconds (S12 section 10).
  const date = untilField ? resolveRef(run, untilField) : undefined
  const due = typeof date === 'string' ? dateWaitDue(date, node.config.atTime as string | undefined) : null
  const wanted = due ? Math.max(0, due.getTime() - clock.now().getTime()) : (durationMs(String(node.config.duration)) ?? 0)
  const ms = Math.min(wanted, MOCK_TIMER_CAP_MS)
  const dueAt = due ? due.toISOString() : new Date(clock.now().getTime() + ms).toISOString()
  run.waits = { ...(run.waits ?? {}), [node.id]: { dueAt } }
  run.lifecycle = 'waiting'
  addEvent(run, 'WAIT_STARTED', { nodeId: node.id, data: { label: node.label, duration: node.config.duration ?? null, until: dueAt } })
  engineConfig.schedule(() => fireWait(run, node), ms)
}

function startChild(run: MockRun, node: WorkflowNode): void {
  const ref = String(node.config.workflowRef)
  const child = [...db.workflows.values()].find((w) => w.wsId === run.wsId && w.key === ref)
  const release = child?.releases.find((r) => r.id === child.activeReleaseId)
  if (!child || !release) return
  const input: Record<string, unknown> = {}
  for (const [key, ref2] of Object.entries((node.config.inputMapping ?? {}) as Record<string, string>)) input[key] = resolveRef(run, ref2)
  run.lifecycle = 'waiting'
  const initiator = userById(run.initiatorId) as MockUser
  createRun({ wsId: run.wsId, workflow: child, release, initiator, input, businessKey: run.businessKey, parent: { run, nodeId: node.id } })
}

/** A child run ended: the waiting parent continues with its outcome and mapped results. */
function onChildEnded(child: MockRun): void {
  const parent = child.parentRunId ? db.runs.get(child.parentRunId) : undefined
  const nodeId = child.parentNodeId
  if (!parent || !nodeId || parent.childWaits?.[nodeId] !== child.id || !active(parent)) return
  delete parent.childWaits[nodeId]
  const def = currentRelease(parent).definition
  const node = nodeById(def, nodeId)
  const result: Record<string, unknown> = { outcome: child.outcome ?? 'Cancelled', outcomeKind: child.outcomeKind ?? 'cancelled', reference: child.reference }
  for (const [name, key] of Object.entries(((node?.config.resultMapping ?? {}) as Record<string, string>))) {
    const value = child.output?.[key]
    if (value !== undefined) result[name] = value
  }
  parent.steps[nodeId] = result
  addEvent(parent, 'CHILD_ENDED', { nodeId, data: { childRunId: child.id, childReference: child.reference, workflowName: (db.workflows.get(child.workflowId) as MockWorkflow).name, outcome: result.outcome, outcomeKind: result.outcomeKind } })
  const next = outgoing(def, nodeId).find((t) => t.on === 'next')
  if (next) advance(parent, next.to)
}

function finish(run: MockRun, release: MockRelease, node: WorkflowNode): void {
  if (!active(run)) return
  run.lifecycle = 'completed'
  run.outcome = (node.config.businessOutcome as string) ?? 'Completed'
  run.outcomeKind = (node.config.outcomeKind as NonNullable<MockRun['outcomeKind']>) ?? 'success'
  run.endedAt = clock.iso()
  const out: Record<string, unknown> = {}
  for (const [key, m] of Object.entries(release.definition.outputMapping)) {
    const v = resolveRef(run, m.fieldRef)
    if (v !== undefined) out[key] = v
  }
  run.output = out
  reconcileStages(run)
  addEvent(run, 'RUN_COMPLETED', { nodeId: node.id, data: { outcome: run.outcome, outcomeKind: run.outcomeKind } })
  // A rejection ends the whole request: branches, waits and child requests that are still open end with it.
  for (const t of run.tasks) if (t.status === 'open') { t.status = 'cancelled'; t.version += 1 }
  run.waits = {}
  run.messageWaits = {}
  run.externalWaits = {}
  for (const childId of Object.values(run.childWaits ?? {})) {
    const c = db.runs.get(childId)
    if (c && active(c)) cancelRun(c, userById(run.initiatorId) as MockUser, 'The request that started this one has ended.', true)
  }
  run.childWaits = {}
  onChildEnded(run)
}

/** Walk the graph from `nodeId` until a wait state, a failure that needs attention, or the end. */
/** Moves the run on from a node, then brings its case stages in line with where it stands (docs/build/S17). */
export function advance(run: MockRun, nodeId: string): void {
  advanceFrom(run, nodeId)
  reconcileStages(run)
}

function advanceFrom(run: MockRun, nodeId: string): void {
  const release = currentRelease(run)
  const def = release.definition
  let current: string | undefined = nodeId
  let guard = 0
  while (current && guard++ < 100) {
    const node = nodeById(def, current)
    if (!node) return
    const next = (on?: string): string | undefined => outgoing(def, node.id).find((t) => (on ? t.on === on : true))?.to
    switch (node.type) {
      case 'start':
        current = next('next')
        break
      case 'approval':
        createTask(run, node)
        return
      case 'decision': {
        const table = def.decisions.find((d) => d.key === node.config.decisionRef)
        if (!table) return
        const values: Record<string, Literal | undefined> = {}
        for (const row of table.rows) for (const term of conditionTerms(row.when)) values[term.fieldRef] = resolveRef(run, term.fieldRef)
        // Dated rows are judged by the day the request was submitted (S41).
        const asOf = table.rows.some(row => row.effective) ? run.createdAt.slice(0, 10) : undefined
        const result = evaluateTable(table, values, asOf)
        run.steps[node.id] = result.output
        addEvent(run, 'DECISION_EVALUATED', {
          nodeId: node.id,
          data: { label: node.label, decisionKey: table.key, version: table.version, matched: result.usedDefault ? 'default' : `r${(result.matchedRow ?? 0) + 1}`, outputs: result.output, evidence: {
            ...(asOf ? { asOf } : {}),
            inputs: Object.entries(values).map(([fieldRef,value]) => ({fieldRef,value})),
            rows: table.rows.map((row,index) => ({rowId:`r${index+1}`,condition:row.when,matched:rowMatches(row,values,asOf),selected:index===result.matchedRow,...(row.effective ? { inWindow: inWindow(row,asOf), effective: row.effective } : {})})),
            defaultSelected:result.usedDefault,
          } },
        })
        current = next('next')
        break
      }
      case 'branch': {
        const routes = outgoing(def, node.id)
        const chosen = routes.find((t) => t.on === 'condition' && t.condition && evaluateRunCondition(run, t.condition)) ?? routes.find((t) => t.on === 'default')
        current = chosen?.to
        break
      }
      case 'http': {
        const outcome = performHttp(run, release, node, 1)
        if (outcome !== 'succeeded') return
        current = next('next')
        break
      }
      case 'sendAdapter': {
        // S73: the mock, like this build's server, only records a stub delivery; no message content is kept.
        const kind = String(node.config.kind ?? '')
        run.steps[node.id] = kind === 'jdbc' ? { outcome: 'stubbed', rowCount: 0 } : { outcome: 'stubbed' }
        addEvent(run, 'EFFECT_SUCCEEDED', { nodeId: node.id, data: { label: node.label, adapterKey: String(node.config.adapter ?? ''), adapterKind: kind, attempt: 1, outcome: 'stubbed' } })
        current = next('next')
        break
      }
      case 'notification': {
        notify(run, run.initiatorId, 'workflowMessage', (node.config.message as string) ?? '')
        addEvent(run, 'NOTIFICATION_CREATED', { nodeId: node.id, data: { label: node.label, kind: 'workflowMessage', recipient: 'initiator' } })
        current = next('next')
        break
      }
      case 'parallelSplit':
        for (const t of outgoing(def, node.id)) {
          if (!active(run)) break
          advance(run, t.to)
        }
        return
      case 'parallelJoin': {
        run.joins = { ...(run.joins ?? {}), [node.id]: (run.joins?.[node.id] ?? 0) + 1 }
        if ((run.joins[node.id] ?? 0) < def.transitions.filter((t) => t.to === node.id).length) return
        current = next('next')
        break
      }
      case 'wait':
        startWait(run, node)
        return
      case 'waitForMessage':
        startMessageWait(run, node)
        return
      case 'externalTask':
        startExternal(run, node)
        return
      case 'startWorkflow':
        startChild(run, node)
        return
      case 'end':
        finish(run, release, node)
        return
    }
  }
}

/** A token reached a worker step: publish the job and wait. The mock has no worker API; tests complete it with completeExternalJob. */
function startExternal(run: MockRun, node: WorkflowNode): void {
  const topic = String(node.config.topic ?? '')
  run.externalWaits = { ...(run.externalWaits ?? {}), [node.id]: { topic, attempt: 1 } }
  run.lifecycle = 'waiting'
  addEvent(run, 'EXTERNAL_JOB_PUBLISHED', { nodeId: node.id, data: { topic } })
}

/** A worker reported the declared result for the job of {@code nodeId}; the request continues (mirrors ExternalJobService.complete). */
export function completeExternalJob(run: MockRun, nodeId: string, result: Record<string, unknown>): void {
  const wait = run.externalWaits?.[nodeId]
  if (!wait || !active(run) || run.suspension) return
  delete run.externalWaits![nodeId]
  run.steps[nodeId] = result as MockRun['steps'][string]
  run.version += 1
  addEvent(run, 'EXTERNAL_JOB_COMPLETED', { nodeId, data: { topic: wait.topic, attempt: wait.attempt } })
  const next = currentRelease(run).definition.transitions.find((t) => t.from === nodeId && t.on === 'next')
  if (next) advance(run, next.to)
}

function effectFor(run: MockRun, node: WorkflowNode): MockRun['effects'][number] {
  const existing = run.effects.find((e) => e.nodeId === node.id)
  if (existing) return existing
  const operation = node.config.operation as string
  const op = (connectionDefinition.operations as Record<string, { effectClass: Effect['effectClass'] }>)[operation]
  const effect: MockRun['effects'][number] = {
    id: newId(),
    runId: run.id,
    nodeId: node.id,
    label: node.label,
    connectionKey: node.config.connectionRef as string,
    operation,
    effectClass: op?.effectClass ?? 'idempotentWithKey',
    state: 'pending',
    attempts: 0,
    lastHttpStatus: null,
    lastErrorCode: null,
    lastErrorDetail: null,
    remoteReference: null,
    resolution: null,
    updatedAt: clock.iso(),
  }
  run.effects.push(effect)
  return effect
}

function setAttention(run: MockRun, node: WorkflowNode, effect: MockRun['effects'][number], reason: string, message: string): void {
  run.lifecycle = 'needsAttention'
  run.pendingHttpNode = node.id
  run.attention = { reason, nodeId: node.id, effectId: effect.id, message, since: clock.iso() }
  addEvent(run, 'RUN_NEEDS_ATTENTION', { nodeId: node.id, data: { label: node.label, reason, effectId: effect.id } })
  notify(run, run.initiatorId, 'runAttention', 'Your request is delayed; an operator has been alerted.')
}

type HttpOutcome = 'succeeded' | 'waiting' | 'attention'

/** One external call attempt, following the fault mode of the procurement "service". */
function performHttp(run: MockRun, release: MockRelease, node: WorkflowNode, attempt: number): HttpOutcome {
  const effect = effectFor(run, node)
  run.pendingHttpNode = node.id
  run.lifecycle = 'running'
  run.attention = null
  const fault = db.procurementFault
  effect.attempts = attempt
  effect.updatedAt = clock.iso()

  if (fault === 'unknown') {
    effect.state = 'unknown'
    effect.lastHttpStatus = 201
    effect.lastErrorCode = 'RESPONSE_INVALID'
    effect.lastErrorDetail = 'The service answered 201 without an order number.'
    addEvent(run, 'EFFECT_UNKNOWN', { nodeId: node.id, data: { label: node.label, attempt, errorCode: 'RESPONSE_INVALID' } })
    setAttention(run, node, effect, 'EFFECT_UNKNOWN', 'Procurement service may have created the order. Check it before retrying.')
    return 'attention'
  }

  if (fault === 'rejected') {
    // A definite "no" (HTTP 400): nothing was created, an operator fixes the cause and retries.
    effect.state = 'permanentFailure'
    effect.lastHttpStatus = 400
    effect.lastErrorCode = 'HTTP_400'
    effect.lastErrorDetail = 'The service answered 400.'
    addEvent(run, 'EFFECT_FAILED', { nodeId: node.id, data: { label: node.label, attempt, maxAttempts: MAX_ATTEMPTS, classification: 'permanent', httpStatus: 400, errorCode: 'HTTP_400' } })
    setAttention(run, node, effect, 'EFFECT_REJECTED', 'The procurement service rejected the order (HTTP 400).')
    return 'attention'
  }

  if (fault === 'down') {
    effect.state = 'retryableFailure'
    effect.lastHttpStatus = 503
    effect.lastErrorCode = 'UNEXPECTED_STATUS'
    effect.lastErrorDetail = 'The service answered 503.'
    for (let a = attempt; a <= MAX_ATTEMPTS; a++) {
      effect.attempts = a
      addEvent(run, 'EFFECT_FAILED', {
        nodeId: node.id,
        data: {
          label: node.label,
          attempt: a,
          maxAttempts: MAX_ATTEMPTS,
          classification: 'retryable',
          httpStatus: 503,
          errorCode: 'UNEXPECTED_STATUS',
          ...(a < MAX_ATTEMPTS ? { nextRetryAt: new Date(clock.now().getTime() + 30_000).toISOString() } : {}),
        },
      })
    }
    setAttention(run, node, effect, 'RETRIES_EXHAUSTED', 'Procurement service did not respond after 3 attempts.')
    return 'attention'
  }

  if (fault === 'failOnce' && attempt === 1) {
    effect.state = 'retryableFailure'
    effect.lastHttpStatus = 500
    effect.lastErrorCode = 'UNEXPECTED_STATUS'
    effect.lastErrorDetail = 'The service answered 500.'
    const nextRetryAt = new Date(clock.now().getTime() + 30_000).toISOString()
    addEvent(run, 'EFFECT_FAILED', { nodeId: node.id, data: { label: node.label, attempt, maxAttempts: MAX_ATTEMPTS, classification: 'retryable', httpStatus: 500, errorCode: 'UNEXPECTED_STATUS', nextRetryAt } })
    const runId = run.id
    engineConfig.schedule(() => {
      const live = db.runs.get(runId)
      if (!live || live.lifecycle !== 'running' || live.pendingHttpNode !== node.id) return
      const rel = currentRelease(live)
      const again = performHttp(live, rel, node, 2)
      if (again === 'succeeded') advance(live, outgoing(rel.definition, node.id)[0]?.to ?? '')
    }, engineConfig.retryDelayMs)
    return 'waiting'
  }

  db.orderCounter += 1
  const orderId = `PO-${db.orderCounter}`
  effect.state = 'succeeded'
  effect.lastHttpStatus = 201
  effect.lastErrorCode = null
  effect.lastErrorDetail = null
  effect.remoteReference = orderId
  const mapping = (node.config.resultMapping ?? {}) as Record<string, string>
  const response: Record<string, unknown> = { id: orderId, status: 'CREATED' }
  const result: Record<string, unknown> = {}
  for (const [k, pointer] of Object.entries(mapping)) result[k] = response[pointer.split('/').filter(Boolean)[0] ?? '']
  run.steps[node.id] = result
  run.pendingHttpNode = null
  addEvent(run, 'EFFECT_SUCCEEDED', { nodeId: node.id, data: { label: node.label, connectionKey: effect.connectionKey, operation: effect.operation, attempt, httpStatus: 201, remoteReference: orderId } })
  void release
  return 'succeeded'
}

export function createRun(opts: {
  wsId: string
  workflow: MockWorkflow
  release: MockRelease
  initiator: MockUser
  input: Record<string, unknown>
  businessKey: string | null
  parent?: { run: MockRun; nodeId: string }
 }): MockRun {
  const references = [...db.runs.values()].filter((run) => run.wsId === opts.wsId)
    .map((run) => run.reference ?? run.businessKey ?? '')
  const nextNumber = Math.max(1000, ...references.filter((value) => /^ORQ-[0-9]+$/.test(value))
    .map((value) => Number(value.slice(4)))) + 1
  const reference = opts.businessKey && /^ORQ-[0-9]+$/.test(opts.businessKey) && !references.includes(opts.businessKey)
    ? opts.businessKey : `ORQ-${nextNumber}`
  const run: MockRun = {
    id: newId(),
    wsId: opts.wsId,
    workflowId: opts.workflow.id,
    releaseId: opts.release.id,
    businessKey: opts.businessKey,
    reference,
    initiatorId: opts.initiator.id,
    lifecycle: 'running',
    outcome: null,
    outcomeKind: null,
    input: opts.input,
    output: null,
    steps: {},
    createdAt: clock.iso(),
    updatedAt: clock.iso(),
    endedAt: null,
    version: 1,
    eventSeq: 0,
    events: [],
    tasks: [],
    effects: [],
    attention: null,
    pendingHttpNode: null,
    cancelledEffects: [],
  }
  if (opts.parent) {
    run.parentRunId = opts.parent.run.id
    run.parentNodeId = opts.parent.nodeId
    opts.parent.run.childWaits = { ...(opts.parent.run.childWaits ?? {}), [opts.parent.nodeId]: run.id }
    addEvent(opts.parent.run, 'CHILD_STARTED', { nodeId: opts.parent.nodeId, data: { childRunId: run.id, childReference: reference, workflowName: opts.workflow.name } })
  }
  db.runs.set(run.id, run)
  addEvent(run, 'RUN_STARTED', { actorId: opts.initiator.id, data: { releaseNumber: opts.release.releaseNumber, businessKey: opts.businessKey, ...(opts.parent ? { parentRunId: opts.parent.run.id } : {}) } })
  const start = opts.release.definition.nodes.find((n) => n.type === 'start')
  if (start) advance(run, start.id)
  run.version = 1
  return run
}

export function claim(run: MockRun, task: MockTask, user: MockUser): void {
  task.assigneeId = user.id
  task.version += 1
  run.version += 1
  addEvent(run, 'TASK_CLAIMED', { nodeId: task.nodeId, actorId: user.id, data: { label: task.label, assignee: user.displayName } })
}

export function release(run: MockRun, task: MockTask, user: MockUser): void {
  task.assigneeId = null
  task.version += 1
  run.version += 1
  addEvent(run, 'TASK_RELEASED', { nodeId: task.nodeId, actorId: user.id, data: { label: task.label, candidateGroup: task.candidateGroup, assignee: user.displayName } })
}

export function decide(run: MockRun, task: MockTask, user: MockUser, action: 'approve' | 'reject', reason: string | null, comment: string | null, fields: Record<string, unknown> = {}): TaskDecision {
  const rel = currentRelease(run)
  const decision: TaskDecision = {
    id: newId(),
    taskId: task.id,
    runId: run.id,
    nodeId: task.nodeId,
    label: task.label,
    actor: { id: user.id, displayName: user.displayName },
    action,
    reason,
    comment,
    decidedAt: clock.iso(),
    policyFlags: user.id === run.initiatorId && selfApprovalAllowed(run, rel) ? ['selfApprovalAllowed'] : [],
  }
  task.status = 'completed'
  task.decision = decision
  task.completedById = user.id
  task.version += 1
  run.version += 1
  run.steps[task.nodeId] = {
    decision: action === 'approve' ? 'approved' : 'rejected',
    ...(reason ? { reason } : {}),
    ...(comment ? { comment } : {}),
    decidedBy: user.displayName,
    decidedAt: decision.decidedAt,
    // Fields the step fills in (S123), only when approving.
    ...(action === 'approve' ? fields : {}),
  }
  const captured = action === 'approve' ? Object.keys(fields).length : 0
  addEvent(run, 'TASK_DECIDED', {
    nodeId: task.nodeId,
    actorId: user.id,
    data: { label: task.label, action, hasReason: !!reason, hasComment: !!comment, policyFlags: decision.policyFlags, ...(captured ? { fieldsCaptured: captured } : {}) },
  })
  const route = outgoing(rel.definition, task.nodeId).find((t) => t.on === (action === 'approve' ? 'approved' : 'rejected'))
  if (route) advance(run, route.to)
  return decision
}

export function retryRun(run: MockRun, user: MockUser, reason: string): void {
  const node = nodeById(currentRelease(run).definition, run.pendingHttpNode ?? '')
  if (!node) return
  run.version += 1
  addEvent(run, 'OPERATOR_RETRY', { nodeId: node.id, actorId: user.id, data: { label: node.label, reason } })
  const rel = currentRelease(run)
  const outcome = performHttp(run, rel, node, (run.effects.find((e) => e.nodeId === node.id)?.attempts ?? 0) + 1)
  if (outcome === 'succeeded') advance(run, outgoing(rel.definition, node.id)[0]?.to ?? '')
}

export function reconcileRun(
  run: MockRun,
  effect: MockRun['effects'][number],
  user: MockUser,
  resolution: 'confirmedApplied' | 'confirmedNotApplied',
  evidence: { note: string; remoteReference?: string },
  result?: Record<string, string | number | boolean>,
): void {
  const rel = currentRelease(run)
  run.version += 1
  effect.updatedAt = clock.iso()
  effect.resolution = { resolution, note: evidence.note, remoteReference: evidence.remoteReference ?? null, by: user.displayName } as never
  addEvent(run, 'EFFECT_RECONCILED', { nodeId: effect.nodeId, actorId: user.id, data: { label: effect.label, resolution, remoteReference: evidence.remoteReference ?? null } })
  if (resolution === 'confirmedApplied') {
    effect.state = 'succeeded'
    effect.remoteReference = evidence.remoteReference ?? effect.remoteReference
    run.steps[effect.nodeId] = result ?? {}
    run.pendingHttpNode = null
    run.attention = null
    run.lifecycle = 'running'
    advance(run, outgoing(rel.definition, effect.nodeId)[0]?.to ?? '')
  } else {
    effect.state = 'notApplied'
    if (run.attention) run.attention = { ...run.attention, message: `${effect.label ?? effect.nodeId} was confirmed as not done. Retry it when the other system is ready.` }
  }
}

/** Why a request cannot be suspended now, or null (mirrors OperatorService.suspendBlocker). */
export function suspendBlocker(run: MockRun): string | null {
  if (run.lifecycle === 'needsAttention') return 'A request that needs attention is recovered, not suspended.'
  if (run.parentRunId) return 'A child request follows its parent; suspend the parent request instead.'
  for (const c of db.runs.values()) if (c.parentRunId === run.id && active(c)) return 'A request with running child requests cannot be suspended.'
  return null
}

export function suspendRun(run: MockRun, user: MockUser, reason: string): void {
  run.suspension = { since: clock.iso(), reason, byId: user.id }
  run.version += 1
  addEvent(run, 'RUN_SUSPENDED', { actorId: user.id, data: { reason } })
  notify(run, run.initiatorId, 'workflowMessage', 'Your request was paused by an operator. It continues when it is resumed.')
}

export function resumeRun(run: MockRun, user: MockUser, reason: string): void {
  run.suspension = null
  run.version += 1
  addEvent(run, 'RUN_RESUMED', { actorId: user.id, data: { reason } })
  notify(run, run.initiatorId, 'workflowMessage', 'Your request was resumed by an operator.')
}

/** Re-points a suspended request at another release (mirrors OperatorService.migrate; compatibility is checked by the caller). */
export function migrateRunTo(run: MockRun, user: MockUser, releaseId: string, reason: string): void {
  const from = currentRelease(run)
  run.releaseId = releaseId
  const to = currentRelease(run)
  for (const t of run.tasks) {
    if (t.status !== 'open') continue
    const label = nodeById(to.definition, t.nodeId)?.label
    if (label) t.label = label
  }
  run.version += 1
  addEvent(run, 'RUN_MIGRATED', { actorId: user.id, data: { fromReleaseNumber: from.releaseNumber, toReleaseNumber: to.releaseNumber, reason } })
  notify(run, run.initiatorId, 'workflowMessage', `Your request now follows version ${to.releaseNumber} of the workflow. Where it is waiting has not changed.`)
}

export function cancelRun(run: MockRun, user: MockUser, reason: string, cascade = false): void {
  run.suspension = null
  for (const childId of Object.values(run.childWaits ?? {})) {
    const c = db.runs.get(childId)
    if (c && active(c)) cancelRun(c, user, 'The request that started this one was cancelled.', true)
  }
  run.childWaits = {}
  run.waits = {}
  run.messageWaits = {}
  run.externalWaits = {}
  const succeeded = run.effects.filter((e) => e.state === 'succeeded')
  run.lifecycle = 'cancelled'
  run.endedAt = clock.iso()
  run.pendingHttpNode = null
  run.attention = null
  run.version += 1
  run.cancelledEffects = succeeded.map((e) => e.id)
  for (const t of run.tasks) if (t.status === 'open') { t.status = 'cancelled'; t.version += 1 }
  reconcileStages(run)
  addEvent(run, 'RUN_CANCELLED', { actorId: user.id, data: { reason, completedEffects: succeeded.length } })
  if (!cascade) notify(run, run.initiatorId, 'workflowMessage', `Your request was cancelled: ${reason}`)
  if (!cascade) onChildEnded(run)
}

// Representations -----------------------------------------------------------------------------------

/**
 * What the request is about, like the server: the first text field of the form in form order (inputUi.order first),
 * trimmed and cut to 120 characters. Null when the form has no text field.
 */
export function requestTitleOf(run: MockRun): string | null {
  const rel = currentRelease(run)
  const properties = rel.definition.inputSchema.properties
  const preferred = (rel.definition.inputUi?.order ?? []).filter((k) => k in properties)
  const order = [...preferred, ...Object.keys(properties).filter((k) => !preferred.includes(k))]
  for (const field of order) {
    const value = run.input[field]
    if (properties[field]?.type === 'string' && !properties[field]?.enum && !properties[field]?.format && typeof value === 'string' && value.trim() !== '') return value.trim().slice(0, 120).trimEnd()
  }
  return null
}

/** The rule for request data (docs/build/S3-api.md section 3 rule 4): the initiator, a task participant, or run.read-data. */
export function hasTaskSkills(run: MockRun, task: MockTask, user: MockUser): boolean {
  const required = nodeById(currentRelease(run).definition, task.nodeId)?.config.requiredSkills
  return !Array.isArray(required) || required.every(skill => typeof skill === 'string' && inGroup(user,skill))
}

/** Separation of duties (docs/build/S41): the user decided one of the earlier steps this task must be kept apart from. */
export function isSeparatedFrom(run: MockRun, task: MockTask, user: MockUser): boolean {
  const apart = nodeById(currentRelease(run).definition, task.nodeId)?.config.separateFrom
  return Array.isArray(apart) && run.tasks.some(t => t.decision?.actor.id === user.id && apart.includes(t.nodeId))
}

export function isRunParticipant(run: MockRun, user: MockUser): boolean {
  const permissions = permissionsOf(user,run.wsId)
  return run.tasks.some((t) => t.assigneeId === user.id || t.completedById === user.id || t.status === 'open' && permissions.includes('task.act') && inGroup(user,t.candidateGroup) && hasTaskSkills(run,t,user) && (run.initiatorId !== user.id || selfApprovalAllowed(run,currentRelease(run))))
}

export function canSeeRunData(run: MockRun, user: MockUser): boolean {
  return run.initiatorId === user.id || isRunParticipant(run,user) || permissionsOf(user,run.wsId).includes('run.read-data')
}

export function toRun(run: MockRun, user: MockUser): Run {
  const wf = db.workflows.get(run.workflowId) as MockWorkflow
  const rel = currentRelease(run)
  const initiator = userById(run.initiatorId)
  const steps: Run['currentSteps'] = []
  for (const t of run.tasks.filter((x) => x.status === 'open')) {
    const assignee = t.assigneeId ? userById(t.assigneeId) : undefined
    steps.push({
      nodeId: t.nodeId,
      label: t.label,
      kind: 'approval',
      since: t.createdAt,
      candidateGroup: t.candidateGroup,
      assignee: assignee ? { id: assignee.id, displayName: assignee.displayName } : null,
      dueAt: t.dueAt,
    })
  }
  for (const [nodeId, w] of Object.entries(run.waits ?? {})) {
    steps.push({ nodeId, label: nodeById(rel.definition, nodeId)?.label ?? nodeId, kind: 'wait', since: run.updatedAt, candidateGroup: null, assignee: null, dueAt: w.dueAt })
  }
  for (const [nodeId, w] of Object.entries(run.externalWaits ?? {})) {
    steps.push({ nodeId, label: nodeById(rel.definition, nodeId)?.label ?? nodeId, kind: 'external', since: run.updatedAt, candidateGroup: null, assignee: null, dueAt: null, topic: w.topic } as Run['currentSteps'][number])
  }
  for (const [nodeId, w] of Object.entries(run.messageWaits ?? {})) {
    steps.push({ nodeId, label: nodeById(rel.definition, nodeId)?.label ?? nodeId, kind: 'wait', since: run.updatedAt, candidateGroup: null, assignee: null, dueAt: w.dueAt, messageName: w.message })
  }
  for (const nodeId of Object.keys(run.childWaits ?? {})) {
    steps.push({ nodeId, label: nodeById(rel.definition, nodeId)?.label ?? nodeId, kind: 'child', since: run.updatedAt, candidateGroup: null, assignee: null, dueAt: null })
  }
  if (run.pendingHttpNode && (run.lifecycle === 'running' || run.lifecycle === 'needsAttention')) {
    const node = nodeById(rel.definition, run.pendingHttpNode)
    steps.push({ nodeId: run.pendingHttpNode, label: node?.label ?? run.pendingHttpNode, kind: 'http', since: run.attention?.since ?? run.updatedAt, candidateGroup: null, assignee: null, dueAt: null })
  }
  const summary: Run = {
    id: run.id,
    reference: run.reference ?? undefined,
    workflow: { id: wf.id, key: wf.key, name: wf.name },
    release: { id: rel.id, releaseNumber: rel.releaseNumber },
    businessKey: run.businessKey,
    requestTitle: canSeeRunData(run, user) ? requestTitleOf(run) : null,
    initiator: { id: run.initiatorId, displayName: initiator?.displayName ?? 'Unknown' },
    lifecycle: run.lifecycle,
    outcome: run.outcome,
    outcomeKind: run.outcomeKind,
    currentSteps: steps,
    attention: run.attention ? { reason: run.attention.reason as never, nodeId: run.attention.nodeId, effectId: run.attention.effectId, message: run.attention.message, since: run.attention.since } : null,
    createdAt: run.createdAt,
    updatedAt: run.updatedAt,
    endedAt: run.endedAt,
    isTest: WORKSPACES.find((w) => w.id === run.wsId)?.isTest ?? false,
    version: run.version,
    stage: stageProgress(run).current,
    parentRun: ((): Run['parentRun'] => {
      const parent = run.parentRunId ? db.runs.get(run.parentRunId) : undefined
      if (!parent || !run.parentNodeId) return null
      return { id: parent.id, reference: parent.reference ?? parent.id, nodeId: run.parentNodeId, nodeLabel: nodeById(currentRelease(parent).definition, run.parentNodeId)?.label ?? null }
    })(),
  }
  return summary
}

export function toTaskSummary(run: MockRun, task: MockTask): TaskSummary {
  const assignee = task.assigneeId ? userById(task.assigneeId) : undefined
  const initiator = userById(run.initiatorId)
  const groupName = task.candidateGroup === 'finance-approvers' ? 'Finance approvers' : task.candidateGroup.charAt(0).toUpperCase() + task.candidateGroup.slice(1).replace(/-/g, ' ')
  const wf = db.workflows.get(run.workflowId) as MockWorkflow
  return {
    id: task.id,
    runId: run.id,
    workflowName: wf.name,
    runReference: run.reference ?? undefined,
    businessKey: run.businessKey,
    nodeId: task.nodeId,
    label: task.label,
    requestTitle: requestTitleOf(run),
    requester: { id: run.initiatorId, displayName: initiator?.displayName ?? 'Unknown' },
    candidateGroup: { key: task.candidateGroup as never, name: groupName },
    assignee: assignee ? { id: assignee.id, displayName: assignee.displayName } : null,
    status: task.status,
    dueAt: task.dueAt,
    createdAt: task.createdAt,
    version: task.version,
    priority: task.priority ?? 0,
    decision: task.decision,
    stage: stageOfNode(run, task.nodeId),
  }
}

/** Sections follow the caller's permissions (docs/build/S3-api.md section 3 rule 4). */
export function toRunDetail(run: MockRun, user: MockUser): RunDetail {
  const base = toRun(run, user)
  const perms = permissionsOf(user, run.wsId)
  const canSeeData = canSeeRunData(run, user)
  const operator = perms.includes('run.operate')
  const rel = currentRelease(run)
  const labels: Record<string, string> = {}
  // Like the server: titles in form order (inputUi.order first, then schema order).
  const properties = rel.definition.inputSchema.properties
  const order = [...(rel.definition.inputUi?.order ?? []).filter((k) => k in properties), ...Object.keys(properties).filter((k) => !(rel.definition.inputUi?.order ?? []).includes(k))]
  for (const k of order) labels[k] = properties[k]!.title
  const unknownEffect = run.effects.some((e) => e.state === 'unknown')
  const active = run.lifecycle === 'running' || run.lifecycle === 'waiting' || run.lifecycle === 'needsAttention'
  const attention = run.lifecycle === 'needsAttention'
  return {
    ...base,
    input: (canSeeData ? run.input : null) as never,
    inputLabels: labels,
    output: (canSeeData ? run.output : null) as never,
    stages: stageProgress(run).stages,
    tasks: run.tasks.map((t) => toTaskSummary(run, t)),
    children: [...db.runs.values()].filter((c) => c.parentRunId === run.id).map((c) => {
      const wf = db.workflows.get(c.workflowId) as MockWorkflow
      const crel = currentRelease(c)
      return {
        id: c.id,
        reference: c.reference ?? c.id,
        workflow: { id: wf.id, key: wf.key, name: wf.name },
        release: { id: crel.id, releaseNumber: crel.releaseNumber },
        nodeId: c.parentNodeId ?? '',
        nodeLabel: nodeById(currentRelease(run).definition, c.parentNodeId ?? '')?.label ?? null,
        lifecycle: c.lifecycle,
        outcome: c.outcome,
        outcomeKind: c.outcomeKind,
        attention: c.attention ? { reason: c.attention.reason as never, nodeId: c.attention.nodeId, effectId: c.attention.effectId, message: c.attention.message, since: c.attention.since } : null,
        startedAt: c.createdAt,
        endedAt: c.endedAt,
        iteration: 1,
      }
    }),
    ...(operator ? { effects: run.effects.map(({ runId: _runId, ...e }) => e) } : {}),
    ...(run.lifecycle === 'cancelled' ? { completedEffectsNotReversed: run.effects.filter((e) => run.cancelledEffects.includes(e.id)).map(({ runId: _runId, ...e }) => e) } : {}),
    ...(run.suspension ? { suspension: { since: run.suspension.since, ...(operator ? { reason: run.suspension.reason } : {}) } } : {}),
    allowedActions: {
      suspend: !operator ? { allowed: false, reasonCode: 'FORBIDDEN' } : !active ? { allowed: false, reasonCode: 'RUN_NOT_ACTIVE' } : run.suspension ? { allowed: false, reasonCode: 'RUN_SUSPENDED' } : suspendBlocker(run) ? { allowed: false, reasonCode: 'RUN_NOT_SUSPENDABLE' } : { allowed: true },
      resume: !operator ? { allowed: false, reasonCode: 'FORBIDDEN' } : !active ? { allowed: false, reasonCode: 'RUN_NOT_ACTIVE' } : run.suspension ? { allowed: true } : { allowed: false, reasonCode: 'RUN_NOT_SUSPENDED' },
      cancel: active ? { allowed: operator } : { allowed: false, reasonCode: 'RUN_NOT_ACTIVE' },
      retry: !attention ? { allowed: false, reasonCode: 'RUN_NOT_RECOVERABLE' } : unknownEffect ? { allowed: false, reasonCode: 'EFFECT_NOT_RECONCILED' } : { allowed: operator },
      reconcile: run.effects.some((e) => e.state === 'unknown' || e.state === 'permanentFailure' || e.state === 'retryableFailure') && attention ? { allowed: operator } : { allowed: false, reasonCode: 'NOTHING_TO_RECONCILE' },
    },
  }
}
