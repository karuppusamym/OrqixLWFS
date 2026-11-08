/**
 * What an Orqix expression in the studio may use (docs/build/S137): the request fields, the results of the steps that
 * always happen before, and (in a Calculate step) the earlier outputs. Types follow the compiler's rules.
 */
import { type ExprType, type Scope, typeOfSchema } from '@/lib/expr'
import type { Labels } from '@/lib/expr/describe'
import { availableFields, type ConnectionLookup, type FieldInfo } from './fieldRefs'
import type { Definition } from './types'

const ofFieldType = (t: FieldInfo['type']): ExprType =>
  t === 'number' || t === 'integer' ? 'number' : t === 'boolean' ? 'boolean' : t === 'date' ? 'date' : t === 'array' ? { list: 'any' } : 'string'

export interface StudioScope {
  scope: Scope
  labels: Labels
  /** Request fields and step results to offer in the field picker. */
  fields: FieldInfo[]
}

/** @param nodeId the step the expression runs at; null for form rules and computed fields (request fields only) */
export function studioScope(def: Definition, nodeId: string | null, lookup?: ConnectionLookup, locals: Record<string, ExprType> = {}): StudioScope {
  const properties = (def.inputSchema?.properties ?? {}) as Record<string, unknown>
  const input: Record<string, ExprType> = {}
  for (const [key, schema] of Object.entries(properties)) {
    const t = typeOfSchema(schema)
    if (t !== null && (schema as { format?: string }).format !== 'attachment') input[key] = t
  }
  const available = nodeId ? availableFields(def, nodeId, lookup) : []
  const steps = available.filter(f => f.ref.startsWith('/steps/'))
  const stepTypes = new Map(steps.map(f => [f.ref, ofFieldType(f.type)]))
  const inputFields: FieldInfo[] = Object.entries(properties)
    .filter(([key]) => key in input)
    .map(([key, schema]) => ({ ref: `/input/${key}`, label: (schema as { title?: string }).title ?? key, group: 'Request', type: ((schema as { format?: string }).format === 'date' ? 'date' : (schema as { type?: string }).type ?? 'string') as FieldInfo['type'] }))
  return {
    scope: {
      input: key => input[key],
      step: (id, key) => stepTypes.get(`/steps/${id}/${key}`),
      local: name => locals[name],
    },
    labels: {
      input: key => (properties[key] as { title?: string } | undefined)?.title,
      step: (id, key) => {
        const f = steps.find(s => s.ref === `/steps/${id}/${key}`)
        return f ? `${f.group}: ${f.label.toLowerCase()}` : undefined
      },
    },
    fields: [...inputFields, ...steps],
  }
}

/** The text that names a field in an expression: input.amount or steps.check-credit.score. */
export function refToExpression(ref: string): string {
  const parts = ref.split('/')
  return parts[1] === 'input' ? `input.${parts[2]}` : `steps.${parts[2]}.${parts[3]}`
}
