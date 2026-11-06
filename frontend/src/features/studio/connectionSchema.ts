import type { ConnectionDetail } from '@/api/types'
import type { FieldType } from './model/fieldRefs'

/** A pinned definition must never silently resolve to another version. */
export function connectionDefinition(detail: ConnectionDetail | undefined, version: number | undefined) {
  if (version !== undefined) return detail?.definitions.find((d) => d.version === version)
  return detail?.definitions.reduce<(NonNullable<ConnectionDetail>['definitions'])[number] | undefined>(
    (latest, definition) => !latest || definition.version > latest.version ? definition : latest, undefined,
  )
}

interface Schema {
  type?: string
  properties?: Record<string, Schema>
}

/** Resolve object-property JSON Pointers, including escaped property names. */
export function responsePointerType(schema: Schema | undefined, pointer: string): FieldType | undefined {
  if (!pointer.startsWith('/') || /~(?:[^01]|$)/.test(pointer)) return undefined
  let field = schema
  for (const segment of pointer.slice(1).split('/')) {
    field = field?.properties?.[segment.replace(/~1/g, '/').replace(/~0/g, '~')]
  }
  const type = field?.type
  return type === 'string' || type === 'number' || type === 'integer' || type === 'boolean' ? type : undefined
}

// ------------------------------------------------------------------ connection definition v1 (docs/build/S130)

interface V1Schema extends Schema {
  items?: V1Schema
  properties?: Record<string, V1Schema>
  format?: string
  enum?: string[]
  pattern?: string
}

/** The parts of an operation that v1 result pointers and inputs read. */
export interface V1Operation {
  requestSchema?: { required?: string[]; properties: Record<string, V1Schema> }
  pathParams?: Record<string, V1Schema & { const?: string | number }>
  responseSchema?: V1Schema
  responseHeaders?: Record<string, V1Schema>
  reconcile?: { keyField: string; operation: string }
}

/** A result a designer can keep: a single value, a list of rows, a list of single values or a response header. */
export interface ResultCandidate {
  pointer: string
  /** Path in the answer, e.g. "result › number". */
  label: string
  kind: 'value' | 'rows' | 'list' | 'header'
  /** Name suggested for the kept result. */
  name: string
  type: FieldType
}

const SCALARS = new Set(['string', 'number', 'integer', 'boolean'])

function escapeToken(token: string) {
  return token.replace(/~/g, '~0').replace(/\//g, '~1')
}

function scalarType(schema: V1Schema | undefined): FieldType | undefined {
  const type = schema?.type
  if (!type || !SCALARS.has(type)) return undefined
  return type === 'string' && schema?.format === 'date' ? 'date' : (type as FieldType)
}

function camel(tokens: string[]) {
  const words = tokens.join(' ').split(/[^A-Za-z0-9]+/).filter(Boolean)
  const text = words.map((w, i) => (i === 0 ? w.charAt(0).toLowerCase() + w.slice(1) : w.charAt(0).toUpperCase() + w.slice(1))).join('')
  return /^[a-z]/.test(text) ? text.slice(0, 64) : `value${text}`.slice(0, 64)
}

/**
 * Results a step can keep from an operation (S130): every single value (nested objects, the first item of a list),
 * every list as rows (its scalar columns) or as single values, and declared response headers. For a flat v0 response
 * this is exactly the top-level values with pointer "/name".
 */
export function resultCandidates(operation: V1Operation | undefined): ResultCandidate[] {
  const out: ResultCandidate[] = []
  const walk = (schema: V1Schema | undefined, tokens: string[], depth: number) => {
    if (!schema || depth > 5) return
    const pointer = tokens.map((t) => `/${escapeToken(t)}`).join('')
    const scalar = scalarType(schema)
    if (scalar && tokens.length > 0) {
      const plain = tokens.length === 1 && /^[a-z][A-Za-z0-9]{0,63}$/.test(tokens[0]!) ? tokens[0]! : undefined
      const label = tokens.map((t) => (/^\d+$/.test(t) ? 'first item' : t)).join(' › ')
      out.push({ pointer, label, kind: 'value', name: plain ?? camel(tokens.filter((t) => !/^\d+$/.test(t)).slice(-1)), type: scalar })
      return
    }
    if (schema.type === 'object') {
      for (const [name, child] of Object.entries(schema.properties ?? {})) walk(child, [...tokens, name], depth + 1)
      return
    }
    if (schema.type === 'array' && tokens.length > 0) {
      const items = schema.items
      const last = tokens.filter((t) => !/^\d+$/.test(t)).slice(-1)
      if (items?.type === 'object' && Object.values(items.properties ?? {}).some((c) => scalarType(c))) {
        out.push({ pointer: `${pointer}/[*]`, label: `${tokens.join(' › ')} (list of rows)`, kind: 'rows', name: camel(last.length ? last : ['rows']), type: 'array' })
        for (const [column, child] of Object.entries(items.properties ?? {})) {
          if (scalarType(child)) {
            out.push({ pointer: `${pointer}/[*]/${escapeToken(column)}`, label: `${tokens.join(' › ')} › every ${column}`, kind: 'list', name: camel([column, 'list']), type: 'array' })
          }
        }
      } else if (scalarType(items)) {
        out.push({ pointer: `${pointer}/[*]`, label: `${tokens.join(' › ')} (list)`, kind: 'list', name: camel(last.length ? last : ['values']), type: 'array' })
      }
      walk(items, [...tokens, '0'], depth + 1)
    }
  }
  walk(operation?.responseSchema, [], 0)
  for (const [name, schema] of Object.entries(operation?.responseHeaders ?? {})) {
    const type = scalarType(schema)
    if (type) out.push({ pointer: `/@headers/${name}`, label: `Header ${name}`, kind: 'header', name: camel([name]), type })
  }
  return out
}

/** Type of a kept result, including v1 lists ('array') and headers; undefined when the pointer does not resolve. */
export function resultPointerType(operation: V1Operation | undefined, pointer: string): FieldType | undefined {
  const match = resultCandidates(operation).find((c) => c.pointer === pointer)
  if (match) return match.type
  return responsePointerType(operation?.responseSchema, pointer)
}

/** One input of an operation the workflow can fill. */
export interface OperationInput {
  name: string
  schema: V1Schema
  required: boolean
  /** "address" for a path parameter (always required). */
  place: 'address' | 'request'
}

/** Inputs of an operation (S130): request fields, then path parameters that are not constants; never the reconcile key. */
export function operationInputs(operation: V1Operation | undefined): OperationInput[] {
  const required = new Set(operation?.requestSchema?.required ?? [])
  const key = operation?.reconcile?.keyField
  const inputs: OperationInput[] = Object.entries(operation?.requestSchema?.properties ?? {})
    .filter(([name]) => name !== key)
    .map(([name, schema]) => ({ name, schema, required: required.has(name), place: 'request' as const }))
  for (const [name, schema] of Object.entries(operation?.pathParams ?? {})) {
    if (schema.const === undefined && !inputs.some((i) => i.name === name)) inputs.push({ name, schema, required: true, place: 'address' })
  }
  return inputs
}
