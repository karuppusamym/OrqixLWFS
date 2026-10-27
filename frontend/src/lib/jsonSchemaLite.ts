/**
 * A small JSON Schema checker for mocked answers (STU03). It covers the keywords connection response schemas use
 * (type, required, enum, const, properties, additionalProperties, items, minLength, maxLength, minimum, maximum,
 * minItems, maxItems, pattern) and reports problems as {at, keyword} exactly like the dry run on the server
 * (`Mocks.schemaProblems`, which uses the full JSON Schema 2020-12 validator of the runtime): `at` is the JSON pointer of
 * the value, with the property appended for required and additionalProperties; sorted by place, then keyword; at most 10.
 */
export interface SchemaProblem {
  at: string
  keyword: string
}

type Schema = Record<string, unknown>

const escape = (token: string) => token.replace(/~/g, '~0').replace(/\//g, '~1')

function typeOk(type: string, v: unknown): boolean {
  switch (type) {
    case 'object':
      return typeof v === 'object' && v !== null && !Array.isArray(v)
    case 'array':
      return Array.isArray(v)
    case 'string':
      return typeof v === 'string'
    case 'integer':
      return typeof v === 'number' && Number.isInteger(v)
    case 'number':
      return typeof v === 'number'
    case 'boolean':
      return typeof v === 'boolean'
    case 'null':
      return v === null
    default:
      return true
  }
}

function equal(a: unknown, b: unknown): boolean {
  return JSON.stringify(a) === JSON.stringify(b)
}

function check(schema: Schema, v: unknown, at: string, out: SchemaProblem[]): void {
  const types = schema.type === undefined ? null : Array.isArray(schema.type) ? (schema.type as string[]) : [schema.type as string]
  if (types && !types.some((t) => typeOk(t, v))) {
    out.push({ at, keyword: 'type' })
    return
  }
  if (Array.isArray(schema.enum) && !schema.enum.some((e) => equal(e, v))) out.push({ at, keyword: 'enum' })
  if ('const' in schema && !equal(schema.const, v)) out.push({ at, keyword: 'const' })
  if (typeof v === 'string') {
    const length = [...v].length
    if (typeof schema.minLength === 'number' && length < schema.minLength) out.push({ at, keyword: 'minLength' })
    if (typeof schema.maxLength === 'number' && length > schema.maxLength) out.push({ at, keyword: 'maxLength' })
    if (typeof schema.pattern === 'string') {
      try {
        if (!new RegExp(schema.pattern, 'u').test(v)) out.push({ at, keyword: 'pattern' })
      } catch {
        /* an unusable pattern is not the answer's fault */
      }
    }
  }
  if (typeof v === 'number') {
    if (typeof schema.minimum === 'number' && v < schema.minimum) out.push({ at, keyword: 'minimum' })
    if (typeof schema.maximum === 'number' && v > schema.maximum) out.push({ at, keyword: 'maximum' })
  }
  if (Array.isArray(v)) {
    if (typeof schema.minItems === 'number' && v.length < schema.minItems) out.push({ at, keyword: 'minItems' })
    if (typeof schema.maxItems === 'number' && v.length > schema.maxItems) out.push({ at, keyword: 'maxItems' })
    if (schema.items && typeof schema.items === 'object') v.forEach((item, i) => check(schema.items as Schema, item, `${at}/${i}`, out))
  }
  if (typeof v === 'object' && v !== null && !Array.isArray(v)) {
    const obj = v as Record<string, unknown>
    const props = (schema.properties ?? {}) as Record<string, Schema>
    for (const name of Array.isArray(schema.required) ? (schema.required as string[]) : []) if (!(name in obj)) out.push({ at: `${at}/${escape(name)}`, keyword: 'required' })
    for (const [name, value] of Object.entries(obj)) {
      if (props[name]) check(props[name], value, `${at}/${escape(name)}`, out)
      else if (schema.additionalProperties === false) out.push({ at: `${at}/${escape(name)}`, keyword: 'additionalProperties' })
      else if (schema.additionalProperties && typeof schema.additionalProperties === 'object') check(schema.additionalProperties as Schema, value, `${at}/${escape(name)}`, out)
    }
  }
}

export function schemaProblems(schema: Schema, value: unknown): SchemaProblem[] {
  const out: SchemaProblem[] = []
  check(schema, value, '', out)
  const seen = new Set<string>()
  return out
    .filter((p) => {
      const key = `${p.at}|${p.keyword}`
      if (seen.has(key)) return false
      seen.add(key)
      return true
    })
    .sort((a, b) => (a.at < b.at ? -1 : a.at > b.at ? 1 : a.keyword < b.keyword ? -1 : a.keyword > b.keyword ? 1 : 0))
    .slice(0, 10)
}

/**
 * A sample answer built from a response schema, the same deterministic sample the dry run uses when a call has no mock
 * (SimulationRun.sample): every declared property, the first enum value, the minimum of a number. Required properties
 * of nested objects are kept; the designer edits from there.
 */
export function sampleFromSchema(schema: Schema, key = ''): unknown {
  const type = Array.isArray(schema.type) ? (schema.type as string[]).find((t) => t !== 'null') ?? 'string' : ((schema.type as string | undefined) ?? (schema.properties ? 'object' : 'string'))
  switch (type) {
    case 'object': {
      const out: Record<string, unknown> = {}
      const props = (schema.properties ?? {}) as Record<string, Schema>
      for (const name of Object.keys(props).sort()) out[name] = sampleFromSchema(props[name] as Schema, name)
      return out
    }
    case 'array':
      return schema.items && typeof schema.items === 'object' ? [sampleFromSchema(schema.items as Schema, key)] : []
    case 'boolean':
      return false
    case 'integer':
    case 'number': {
      let v = 1
      if (typeof schema.minimum === 'number' && schema.minimum > v) v = schema.minimum
      if (typeof schema.maximum === 'number' && schema.maximum < v) v = schema.maximum
      return v
    }
    default: {
      if (Array.isArray(schema.enum) && schema.enum.length > 0) return schema.enum[0]
      if (schema.format === 'date') return '2026-01-15'
      let t = `sample-${key}`
      if (typeof schema.maxLength === 'number' && t.length > schema.maxLength) t = t.slice(0, Math.max(0, schema.maxLength))
      return t
    }
  }
}

/** "/id is required" style sentence for one problem, for people. */
export function describeProblem(p: SchemaProblem): string {
  const where = p.at === '' ? 'The answer' : p.at
  switch (p.keyword) {
    case 'required':
      return `${where} is required but missing`
    case 'additionalProperties':
      return `${where} is not allowed by the response schema`
    case 'type':
      return `${where} has the wrong type`
    case 'enum':
    case 'const':
      return `${where} is not one of the allowed values`
    default:
      return `${where} breaks the rule ${p.keyword}`
  }
}
