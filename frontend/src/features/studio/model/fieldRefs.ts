import { humanize } from '@/lib/text'
import { guaranteedBefore, nodeById } from './graph'
import { conditionTerms, type Definition, type Literal, type WorkflowNode } from './types'

/** `date` is a string field with format date: a calendar date YYYY-MM-DD. */
export type FieldType = 'string' | 'number' | 'integer' | 'boolean' | 'array' | 'date'

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
  return ordered.filter(key => def.inputSchema.properties[key]?.type !== 'object').flatMap((key) => {
    const field = def.inputSchema.properties[key]
    const base: FieldInfo = {
      ref: `/input/${key}`,
      label: field?.title || humanize(key),
      group: REQUEST_GROUP,
      type: field?.format === 'date' && field.type === 'string' ? 'date' : (field?.type === 'object' ? 'array' : field?.type ?? 'string'),
      ...(field?.enum ? { enumValues: field.enum } : {}),
    }
    if (field?.type !== 'array') return [base]
    const derived: FieldInfo[] = [{ ref: `/input/${key}/count`, label: `${base.label}: count`, group: REQUEST_GROUP, type: 'integer' }]
    if (field.items?.type === 'object') for (const [columnKey, column] of Object.entries(field.items.properties ?? {})) {
      if (column.type === 'number' || column.type === 'integer') derived.push({ ref: `/input/${key}/sum/${columnKey}`, label: `${base.label}: total ${column.title || humanize(columnKey)}`, group: REQUEST_GROUP, type: column.type })
    }
    return [base, ...derived]
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
    case 'waitForMessage': {
      const payload = (Array.isArray(node.config.payload) ? node.config.payload : []) as Array<{ name?: string; type?: FieldType; title?: string }>
      return payload.filter((p) => p.name).map((p) => base(p.name as string, p.type ?? 'string', p.title || humanize(p.name as string)))
    }
    case 'externalTask': {
      const result = (Array.isArray(node.config.result) ? node.config.result : []) as Array<{ name?: string; type?: FieldType; title?: string }>
      return result.filter((p) => p.name).map((p) => base(p.name as string, p.type ?? 'string', p.title || humanize(p.name as string)))
    }
    case 'sendAdapter':
      // S73: how the adapter took the message; a database read also reports its row count.
      return node.config.kind === 'jdbc' ? [base('outcome', 'string'), base('rowCount', 'integer', 'Row count')] : [base('outcome', 'string')]
    case 'startWorkflow': {
      const mapping = (node.config.resultMapping ?? {}) as Record<string, string>
      const collections = (node.config.collectionMapping ?? {}) as Record<string, string>
      return [base('outcome', 'string'), base('outcomeKind', 'string'), base('reference', 'string'),
        base('iterations', 'integer'), base('limitReached', 'boolean'), base('allSucceeded', 'boolean'),
        ...Object.entries(mapping).map(([key, output]) => base(key, lookup?.workflowOutputType?.(node.config.workflowRef as string, output) ?? 'string')),
        ...Object.keys(collections).map((key) => base(key, 'array'))]
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
  const input = /^\/input\/([a-z][A-Za-z0-9]{0,63})(?:\/(?:count|sum\/[a-z][A-Za-z0-9]{0,63}))?$/.exec(ref)
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
  for (const condition of [...Object.values(def.inputUi?.visibleWhen ?? {}), ...Object.values(def.inputUi?.requiredWhen ?? {}), ...(def.inputUi?.layout?.sections ?? []).flatMap(section => section.visibleWhen ? [section.visibleWhen] : [])]) for (const term of conditionTerms(condition)) term.fieldRef = map(term.fieldRef)
  for (const t of def.transitions) if (t.condition) for (const term of conditionTerms(t.condition)) term.fieldRef = map(term.fieldRef)
  for (const d of def.decisions) {
    for (const r of d.rows) for (const term of conditionTerms(r.when)) term.fieldRef = map(term.fieldRef)
  }
  for (const n of def.nodes) {
    if (n.type === 'waitForMessage' && typeof n.config.correlationField === 'string') n.config.correlationField = map(n.config.correlationField)
    if (n.type === 'http' || n.type === 'generateDocument' || n.type === 'startWorkflow' || n.type === 'externalTask' || n.type === 'sendAdapter') {
      const mapping = n.config.inputMapping as Record<string, string> | undefined
      if (mapping) for (const k of Object.keys(mapping)) mapping[k] = map(mapping[k] as string)
      const each = n.config.forEach as { itemsRef: string } | undefined
      if (each) each.itemsRef = map(each.itemsRef)
    }
  }
  for (const m of Object.values(def.outputMapping)) m.fieldRef = map(m.fieldRef)
}

/** Does the document reference this field anywhere? Returns human-readable places. */
export function usagesOfRef(def: Definition, predicate: (ref: string) => boolean): string[] {
  const places: string[] = []
  for (const t of def.transitions) {
    if (t.condition && conditionTerms(t.condition).some(term => predicate(term.fieldRef))) places.push(`the path "${t.label ?? t.id}" from ${nodeById(def, t.from)?.label ?? t.from}`)
  }
  for (const d of def.decisions) {
    if (d.rows.some((r) => conditionTerms(r.when).some(term => predicate(term.fieldRef)))) places.push(`the rule table "${d.key}"`)
  }
  for (const n of def.nodes) {
    if (n.type === 'waitForMessage' && typeof n.config.correlationField === 'string' && predicate(n.config.correlationField)) places.push(`the step "${n.label}"`)
    if (n.type === 'http' || n.type === 'generateDocument' || n.type === 'startWorkflow' || n.type === 'externalTask' || n.type === 'sendAdapter') {
      const mapping = (n.config.inputMapping ?? {}) as Record<string, string>
      if (Object.values(mapping).some(predicate)) places.push(`the step "${n.label}"`)
      const each = n.config.forEach as { itemsRef: string } | undefined
      if (each && predicate(each.itemsRef)) places.push(`the collection of "${n.label}"`)
    }
  }
  for (const [k, m] of Object.entries(def.outputMapping)) if (predicate(m.fieldRef)) places.push(`the output "${k}"`)
  return places
}

export { pointerKey }
