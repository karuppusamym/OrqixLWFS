import { parseFormSchema, parseFormUi } from '@/lib/form/schema'
import { validateJsonForm } from '@/lib/form/validate'
import type { Definition } from '@/features/studio/model/types'
import type { components } from '@/api/schema'
import { db, type MockRun, type MockWorkflow } from './state'
import { currentRelease, toRun } from './engine'
import { userById } from './state'

type Finding = components['schemas']['MigrationFinding']
type Options = components['schemas']['MigrationOptions']

/**
 * Mock of the server's release-migration rules (docs/build/S38, ReleaseCompatibility.java): the travelled part of the
 * graph and the waiting step must be identical in both releases; only what lies ahead may differ.
 */

const MIGRATABLE = new Set(['approval', 'wait', 'waitForMessage'])

function same(a: unknown, b: unknown): boolean {
  return JSON.stringify(canonical(a)) === JSON.stringify(canonical(b))
}

function canonical(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonical)
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value as Record<string, unknown>).sort(([x], [y]) => x.localeCompare(y)).map(([k, v]) => [k, canonical(v)]))
  }
  return value
}

/** The decision table and connection version a node points at; unchanged for every step the request already passed. */
function references(def: Definition, node: { config: unknown }): unknown[] {
  const config = node.config as Record<string, unknown>
  const out: unknown[] = []
  const d = def as unknown as { decisions?: Array<{ key: string }>; dependencies?: Array<{ key: string }> }
  if (typeof config.decisionRef === 'string') out.push(...(d.decisions ?? []).filter((t) => t.key === config.decisionRef))
  if (typeof config.connectionRef === 'string') out.push(...(d.dependencies ?? []).filter((t) => t.key === config.connectionRef))
  return out
}

function ancestors(def: Definition, nodeId: string): Set<string> {
  const seen = new Set<string>()
  const queue = [nodeId]
  while (queue.length > 0) {
    const current = queue.shift() as string
    for (const t of def.transitions) {
      if (t.to === current && !seen.has(t.from)) {
        seen.add(t.from)
        queue.push(t.from)
      }
    }
  }
  seen.delete(nodeId)
  return seen
}

function flows(def: Definition, scope: Set<string>): string[] {
  return def.transitions.filter((t) => scope.has(t.from) && scope.has(t.to)).map((t) => `${t.from}|${t.to}|${t.on}`).sort()
}

export function compatibility(source: Definition, target: Definition, active: string[]): Finding[] {
  if (active.length !== 1) {
    return [{ code: 'STEP_COUNT', message: active.length === 0 ? 'The request is not waiting at a step, so there is nothing to move.' : 'The request waits at more than one step at once; only a single waiting step can be moved.', nodeId: null }]
  }
  const id = active[0] as string
  const findings: Finding[] = []
  const node = (def: Definition, n: string) => def.nodes.find((x) => x.id === n)
  const s = node(source, id)
  const t = node(target, id)
  if (!s) return [{ code: 'STEP_UNKNOWN', message: 'The current step is not part of the current release.', nodeId: id }]
  if (!MIGRATABLE.has(s.type)) findings.push({ code: 'STEP_KIND', message: `A request waiting at a '${s.type}' step cannot be moved; only approvals and waits can.`, nodeId: id })
  if (!t) return [...findings, { code: 'STEP_MISSING', message: `The target release no longer has the step '${s.label}' where the request is waiting.`, nodeId: id }]
  if (s.type !== t.type) findings.push({ code: 'STEP_CHANGED', message: `The step '${s.label}' is a different kind of step in the target release.`, nodeId: id })
  else if (!same(s.config, t.config) || !same(references(source, s), references(target, t))) findings.push({ code: 'STEP_CHANGED', message: `The settings of the step '${s.label}' differ in the target release (for example its group, deadline or time limit).`, nodeId: id })
  const sh = ancestors(source, id)
  const th = ancestors(target, id)
  for (const n of sh) if (!th.has(n)) findings.push({ code: 'HISTORY_CHANGED', message: `The step '${node(source, n)?.label ?? n}' the request already passed no longer leads to this point in the target release.`, nodeId: n })
  for (const n of th) if (!sh.has(n)) findings.push({ code: 'HISTORY_CHANGED', message: `The target release adds the step '${node(target, n)?.label ?? n}' before this point; the request has not been through it.`, nodeId: n })
  for (const n of sh) {
    const a = node(source, n)
    const b = node(target, n)
    if (a && b && th.has(n) && (a.type !== b.type || !same(a.config, b.config) || !same(references(source, a), references(target, b)))) findings.push({ code: 'HISTORY_CHANGED', message: `The step '${a.label}' the request already passed is configured differently in the target release.`, nodeId: n })
  }
  const scope = new Set([...sh, id])
  if (!same(flows(source, scope), flows(target, scope))) findings.push({ code: 'HISTORY_CHANGED', message: 'The routes between the steps the request already passed differ in the target release.', nodeId: null })
  for (const n of sh) {
    const a = node(source, n)
    if (a?.type === 'parallelSplit' && !sh.has(String((a.config as Record<string, unknown>).joinRef ?? ''))) findings.push({ code: 'PARALLEL_REGION', message: 'The request is inside a parallel section; requests in a parallel section cannot be moved.', nodeId: n })
  }
  if (!same(source.stages ?? [], target.stages ?? [])) findings.push({ code: 'STAGES_CHANGED', message: 'The case stages differ between the releases, so stage progress could not be carried over.', nodeId: null })
  return findings
}

export function evaluate(run: MockRun, targetDef: Definition): Finding[] {
  const source = currentRelease(run).definition
  const initiator = userById(run.initiatorId)
  const active = initiator ? toRun(run, initiator).currentSteps.map((s) => s.nodeId) : []
  const findings = compatibility(source, targetDef, active)
  const schema = parseFormSchema(targetDef.inputSchema)
  const issues = validateJsonForm(schema, parseFormUi(targetDef.inputUi), run.input)
  if (issues.length > 0) findings.push({ code: 'INPUT_INVALID', message: `The request's answers do not satisfy the target release's form: ${issues.slice(0, 3).map((i) => `${i.pointer.slice(1)} (${i.keyword})`).join('; ')}`, nodeId: null })
  return findings
}

export function migrationOptions(run: MockRun): Options {
  const wf = db.workflows.get(run.workflowId) as MockWorkflow
  const current = currentRelease(run)
  const ready = !!run.suspension && run.lifecycle !== 'completed' && run.lifecycle !== 'cancelled'
  const options = [...wf.releases]
    .filter((r) => r.id !== run.releaseId)
    .sort((a, b) => b.releaseNumber - a.releaseNumber)
    .slice(0, 20)
    .map((r) => {
      const findings = evaluate(run, r.definition)
      return { release: { id: r.id, releaseNumber: r.releaseNumber }, active: wf.activeReleaseId === r.id, publishedAt: r.publishedAt, compatible: findings.length === 0, findings }
    })
  return { current: { id: current.id, releaseNumber: current.releaseNumber }, ready, notReadyReason: ready ? null : 'Suspend the request first. A request is moved only while nothing can change under it.', options }
}
