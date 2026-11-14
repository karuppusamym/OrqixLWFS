import { addNode, addNodeAfter, uniqueId, type OpResult } from './ops'
import type { Definition, WorkflowNode } from './types'

/**
 * Package steps in Studio (docs/build/S133): a step template from an installed connector package, and "Call and wait
 * for completion" (CG-W02), which turns one call of an asynchronous operation into two ordinary steps: the call, then
 * a Keep checking until done on the status operation with the status reference mapped in.
 */

/** The `async` block of a connection definition v1 operation. */
export interface AsyncDeclaration {
  acceptedStatuses: number[]
  statusFrom: { header?: string; bodyPointer?: string }
  statusOperation: string
  pollIntervalSeconds?: number
  maxWaitSeconds?: number
}

export interface AsyncOperation {
  label?: string
  maxTimeoutSeconds?: number
  pathParams?: Record<string, { const?: unknown }>
  async?: AsyncDeclaration
}

/** The id a step template uses for itself in its own field references. */
export const TEMPLATE_STEP_ID = 'templateStep'

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

/** Seconds as an ISO 8601 duration in the largest whole unit: 7200 → PT2H, 90 → PT90S. */
export function isoSeconds(seconds: number): string {
  if (seconds % 86_400 === 0) return `P${seconds / 86_400}D`
  if (seconds % 3600 === 0) return `PT${seconds / 3600}H`
  if (seconds % 60 === 0) return `PT${seconds / 60}M`
  return `PT${seconds}S`
}

/** The values an accepted answer gives: the status operation's path parameters that are not constants. */
export function asyncResultNames(operations: Record<string, AsyncOperation>, operation: string): string[] {
  const status = operations[operations[operation]?.async?.statusOperation ?? '']
  return Object.entries(status?.pathParams ?? {}).filter(([, p]) => p.const === undefined).map(([name]) => name)
}

/** Replaces `/steps/templateStep/` with the new step id in every string of a template's configuration. */
function rebind<T>(value: T, id: string): T {
  if (typeof value === 'string') return value.split(`/steps/${TEMPLATE_STEP_ID}/`).join(`/steps/${id}/`) as T
  if (Array.isArray(value)) return value.map((v) => rebind(v, id)) as T
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, rebind(v, id)])) as T
  }
  return value
}

/**
 * Adds a Call a system step from a package palette item: its label and configuration, the template's own references
 * pointed at the new step, and the package connection pinned at the version installed in this workspace.
 */
export function addPresetStep(def: Definition, preset: { label: string; config: Record<string, unknown>; connectionKey: string; connectionVersion: number },
  opts: { anchorId?: string | null } = {}): { def: Definition; id: string } {
  const added = addNode(def, 'http', { label: preset.label, anchorId: opts.anchorId ?? null })
  const next = added.def
  const node = next.nodes.find((n) => n.id === added.id) as WorkflowNode
  node.config = rebind(clone(preset.config), added.id)
  node.config.connectionRef = preset.connectionKey
  const deps = next.dependencies.connections
  const existing = deps.find((c) => c.key === preset.connectionKey)
  if (existing) existing.definitionVersion = preset.connectionVersion
  else deps.push({ key: preset.connectionKey, definitionVersion: preset.connectionVersion })
  return { def: next, id: added.id }
}

/**
 * Call and wait for completion: keeps the status reference of the call's accepted answer (`/@async/<name>`) and adds
 * a Keep checking until done on the status operation right after the call, with that reference mapped in. The done
 * condition and the timed-out path are left for the designer (the check panel lists them).
 */
export function callAndWait(def: Definition, startId: string, operations: Record<string, AsyncOperation>): OpResult & { id?: string } {
  const start = def.nodes.find((n) => n.id === startId)
  if (!start || start.type !== 'http') return { ok: false, reason: 'Choose a Call a system step.' }
  const operationName = start.config.operation as string | undefined
  const declaration = operations[operationName ?? '']?.async
  if (!declaration) return { ok: false, reason: 'This operation does not start work that finishes later.' }
  const status = operations[declaration.statusOperation]
  if (!status) return { ok: false, reason: `The status operation ${declaration.statusOperation} is not part of this connection.` }
  const names = asyncResultNames(operations, operationName ?? '')
  // 1. Keep the status reference on the call.
  let next = clone(def)
  const call = next.nodes.find((n) => n.id === startId) as WorkflowNode
  const results = { ...((call.config.resultMapping as Record<string, string> | undefined) ?? {}) }
  const kept: Record<string, string> = {}
  for (const name of names) {
    const pointer = `/@async/${name}`
    const existing = Object.entries(results).find(([, p]) => p === pointer)?.[0]
    let resultName = existing ?? name
    for (let i = 2; !existing && resultName in results; i++) resultName = `${name}${i}`
    results[resultName] = pointer
    kept[name] = resultName
  }
  call.config.resultMapping = results
  // 2. The check after it.
  const added = addNodeAfter(next, startId, 'http', { on: 'next' }, { label: `Wait for ${status.label ?? declaration.statusOperation}` })
  if (!added.ok || !added.id) return added
  next = added.def
  const check = next.nodes.find((n) => n.id === added.id) as WorkflowNode
  const timeout = Math.min(10, status.maxTimeoutSeconds ?? 10)
  check.config = {
    connectionRef: call.config.connectionRef,
    operation: declaration.statusOperation,
    inputMapping: Object.fromEntries(names.map((name) => [name, `/steps/${startId}/${kept[name]}`])),
    resultMapping: {},
    timeoutSeconds: timeout,
    poll: {
      every: isoSeconds(Math.max(10, declaration.pollIntervalSeconds ?? 60)),
      giveUpAfter: isoSeconds(Math.max(60, declaration.maxWaitSeconds ?? 86_400)),
      until: { fieldRef: '', operator: 'eq', value: '' },
    },
  }
  return { ok: true, def: next, id: added.id }
}

/** A step id for a template, used by tests and the palette: unique in the definition. */
export function templateStepId(def: Definition, base: string): string {
  return uniqueId(def, base)
}
