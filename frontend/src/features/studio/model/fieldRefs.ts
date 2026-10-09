import { humanize } from '@/lib/text'
import { guaranteedBefore, nodeById } from './graph'
import type { Definition, Literal, WorkflowNode } from './types'

export type FieldType = 'string' | 'number' | 'integer' | 'boolean'

export interface FieldInfo {
  /** Structured reference stored in the document, e.g. /input/amount or /steps/manager/decision. Never an expression. */
  ref: string
  /** Field name only, e.g. "Amount (USD)". */
  label: string
  /** Where the value comes from, e.g. "Request" or the step name. */
  group: string
  type: FieldType
  /** Finite string domain, when known. */
  enumValues?: string[]
}

/** Lets the model learn response types of connection operations without depending on the API layer. */
export interface ConnectionLookup {
  /** Type of the response property addressed by a JSON Pointer, if known. */
  responseType(connectionKey: string, version: number | undefined, operation: string, pointer: string): FieldType | undefined
  workflowOutputType?(workflowKey: string, output: string): FieldType | undefined
}

export const REQUEST_GROUP = 'Request'

export function chipLabel(info: Pick<FieldInfo, 'group' | 'label'>): string {
  return `${info.group} → ${info.label}`
}

function literalType(value: Literal): FieldType {
  return typeof value === 'number' ? 'number' : typeof value === 'boolean' ? 'boolean' : 'string'
}

function pointerKey(pointer: string): string {
  return pointer.split('/').filter(Boolean).pop() ?? ''
}

function inputFields(def: Definition): FieldInfo[] {
  const order = def.inputUi?.order ?? []
  const keys = Object.keys(def.inputSchema.properties)
  const ordered = [...order.filter((k) => keys.includes(k)), ...keys.filter((k) => !order.includes(k))]
  return ordered.map((key) => {
    const field = def.inputSchema.properties[key]
    return {
      ref: `/input/${key}`,
      label: field?.title || humanize(key),
      group: REQUEST_GROUP,
      type: field?.type ?? 'string',
      ...(field?.enum ? { enumValues: field.enum } : {}),
    }
  })
}

function stepOutputs(def: Definition, node: WorkflowNode, lookup?: ConnectionLookup): FieldInfo[] {
  const base = (key: string, type: FieldType, label = humanize(key), enumValues?: string[]): FieldInfo => ({
    ref: `/steps/${node.id}/${key}`,
    label,
    group: node.label || node.id,
    type,
    ...(enumValues ? { enumValues } : {}),
  })
  switch (node.type) {
    case 'approval':
      return [
        base('decision', 'string', 'Decision', node.config.timeoutAfter ? ['approved', 'rejected', 'timedOut'] : ['approved', 'rejected']),
        base('reason', 'string', 'Reason'),
        base('comment', 'string', 'Comment'),
        base('decidedBy', 'string', 'Decided by'),
        base('decidedAt', 'string', 'Decided at'),
      ]
    case 'decision': {
      const table = def.decisions.find((d) => d.key === (node.config.decisionRef as string | undefined))
      if (!table) return []
      const outputs: Record<string, Literal[]> = {}
      for (const out of [...table.rows.map((r) => r.then), table.default]) {
        for (const [k, v] of Object.entries(out ?? {})) (outputs[k] ??= []).push(v)
      }
      return Object.entries(outputs).map(([k, values]) => {
        const type = literalType(values[0] as Literal)
        const strings = type === 'string' ? [...new Set(values.map(String))] : undefined
        return base(k, type, humanize(k), strings)
      })
    }
    case 'http': {
      const mapping = (node.config.resultMapping ?? {}) as Record<string, string>
      const connectionKey = node.config.connectionRef as string | undefined
      const operation = node.config.operation as string | undefined
      const version = def.dependencies.connections.find((c) => c.key === connectionKey)?.definitionVersion
      return Object.entries(mapping).map(([k, pointer]) => {
        const type = (connectionKey && operation ? lookup?.responseType(connectionKey, version, operation, pointer) : undefined) ?? 'string'
        return base(k, type, humanize(k))
      })
    }
    case 'startWorkflow': {
      const mapping = (node.config.resultMapping ?? {}) as Record<string, string>
      return [base('outcome', 'string'), base('outcomeKind', 'string'), base('reference', 'string'),
        ...Object.entries(mapping).map(([key, output]) => base(key, lookup?.workflowOutputType?.(node.config.workflowRef as string, output) ?? 'string'))]
    }
    default:
      return []
  }
}

/** Every field that exists somewhere in the workflow, whether or not it is guaranteed at a given step. */
export function allFields(def: Definition, lookup?: ConnectionLookup): FieldInfo[] {
  return [...inputFields(def), ...def.nodes.flatMap((n) => stepOutputs(def, n, lookup))]
}

/**
 * Fields guaranteed at `nodeId`: all request fields plus outputs of steps that dominate it (S4 section 4).
 * The server's check is authoritative and reports WF_FIELDREF_NOT_GUARANTEED; this only keeps the chips honest.
 */
export function availableFields(def: Definition, nodeId: string, lookup?: ConnectionLookup): FieldInfo[] {
  const before = guaranteedBefore(def, nodeId)
  const steps = def.nodes.filter((n) => before.has(n.id)).flatMap((n) => stepOutputs(def, n, lookup))
  return [...inputFields(def), ...steps]
}

export function fieldInfo(def: Definition, ref: string, lookup?: ConnectionLookup): FieldInfo | undefined {
  return allFields(def, lookup).find((f) => f.ref === ref)
}

/** Parse a field reference. */
export function parseRef(ref: string): { kind: 'input'; key: string } | { kind: 'step'; nodeId: string; key: string } | null {
  const input = /^\/input\/([a-z][A-Za-z0-9]{0,63})$/.exec(ref)
  if (input) return { kind: 'input', key: input[1] as string }
  const step = /^\/steps\/([A-Za-z][A-Za-z0-9_-]{0,63})\/([a-z][A-Za-z0-9]{0,63})$/.exec(ref)
  if (step) return { kind: 'step', nodeId: step[1] as string, key: step[2] as string }
  return null
}

/** Readable chip for any stored reference, even one that no longer resolves. */
export function chipFor(def: Definition, ref: string, lookup?: ConnectionLookup): string {
  const info = fieldInfo(def, ref, lookup)
  if (info) return chipLabel(info)
  const parsed = parseRef(ref)
  if (!parsed) return ref
  if (parsed.kind === 'input') return `${REQUEST_GROUP} → ${humanize(parsed.key)}`
  return `${nodeById(def, parsed.nodeId)?.label ?? humanize(parsed.nodeId)} → ${humanize(parsed.key)}`
}

/** Rewrite every reference in the document with `map`. Used when a field key or step id changes. */
export function rewriteRefs(def: Definition, map: (ref: string) => string): void {
  for (const t of def.transitions) if (t.condition) t.condition.fieldRef = map(t.condition.fieldRef)
  for (const d of def.decisions) {
    for (const r of d.rows) r.when.fieldRef = map(r.when.fieldRef)
  }
  for (const n of def.nodes) {
    if (n.type === 'http' || n.type === 'startWorkflow') {
      const mapping = n.config.inputMapping as Record<string, string> | undefined
      if (mapping) for (const k of Object.keys(mapping)) mapping[k] = map(mapping[k] as string)
    }
  }
  for (const m of Object.values(def.outputMapping)) m.fieldRef = map(m.fieldRef)
}

/** Does the document reference this field anywhere? Returns human-readable places. */
export function usagesOfRef(def: Definition, predicate: (ref: string) => boolean): string[] {
  const places: string[] = []
  for (const t of def.transitions) {
    if (t.condition && predicate(t.condition.fieldRef)) places.push(`the path "${t.label ?? t.id}" from ${nodeById(def, t.from)?.label ?? t.from}`)
  }
  for (const d of def.decisions) {
    if (d.rows.some((r) => predicate(r.when.fieldRef))) places.push(`the rule table "${d.key}"`)
  }
  for (const n of def.nodes) {
    if (n.type === 'http' || n.type === 'startWorkflow') {
      const mapping = (n.config.inputMapping ?? {}) as Record<string, string>
      if (Object.values(mapping).some(predicate)) places.push(`the step "${n.label}"`)
    }
  }
  for (const [k, m] of Object.entries(def.outputMapping)) if (predicate(m.fieldRef)) places.push(`the output "${k}"`)
  return places
}

export { pointerKey }
