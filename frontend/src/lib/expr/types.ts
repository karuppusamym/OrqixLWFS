/** Static types of Orqix expressions (docs/build/S137), as com.orqix.compiler.expr.ExprType. */

export type Scalar = 'number' | 'string' | 'boolean' | 'date' | 'null' | 'any'
export type ExprType = Scalar | { list: ExprType } | { row: Record<string, ExprType> }

export const isList = (t: ExprType): t is { list: ExprType } => typeof t === 'object' && 'list' in t
export const isRow = (t: ExprType): t is { row: Record<string, ExprType> } => typeof t === 'object' && 'row' in t
export const isScalar = (t: ExprType): t is Scalar => typeof t === 'string'

export function typeEquals(a: ExprType, b: ExprType): boolean {
  if (isScalar(a) || isScalar(b)) return a === b
  if (isList(a) && isList(b)) return typeEquals(a.list, b.list)
  if (isRow(a) && isRow(b)) {
    const ka = Object.keys(a.row)
    const kb = Object.keys(b.row)
    return ka.length === kb.length && ka.every(k => k in b.row && typeEquals(a.row[k]!, b.row[k]!))
  }
  return false
}

const PHRASE: Record<Scalar, string> = {
  number: 'a number', string: 'text', boolean: 'true or false', date: 'a date', null: 'nothing', any: 'a value',
}

function plural(t: ExprType): string {
  if (t === 'number') return 'numbers'
  if (t === 'string') return 'texts'
  if (t === 'boolean') return 'true or false values'
  if (t === 'date') return 'dates'
  if (isRow(t)) return 'rows'
  if (isList(t)) return 'lists'
  return 'values'
}

/** Business wording, e.g. "a number" or "a list of numbers". */
export function describeType(t: ExprType): string {
  if (isScalar(t)) return PHRASE[t]
  if (isList(t)) return t.list === 'any' ? 'a list' : `a list of ${plural(t.list)}`
  return 'a row'
}

/** The short tag: number, string, boolean, date, null, any, list or row. */
export const tag = (t: ExprType): string => (isScalar(t) ? t : isList(t) ? 'list' : 'row')

/** The case-file and compiler notation: "number" | "integer" | "string" | "boolean" | "date" | {list} | {row}. */
export function readType(node: unknown): ExprType {
  if (typeof node === 'string') {
    if (node === 'number' || node === 'integer') return 'number'
    if (node === 'string' || node === 'boolean' || node === 'date' || node === 'any') return node
    throw new Error(`Unknown type ${node}`)
  }
  if (node && typeof node === 'object') {
    const o = node as Record<string, unknown>
    if ('list' in o) return { list: readType(o.list) }
    if ('row' in o) {
      const row: Record<string, ExprType> = {}
      for (const [k, v] of Object.entries(o.row as Record<string, unknown>)) row[k] = readType(v)
      return { row }
    }
  }
  throw new Error(`Unknown type ${JSON.stringify(node)}`)
}

/** The type of a form field schema (number/integer, boolean, date strings, text, arrays of scalars or rows). */
export function typeOfSchema(schema: unknown): ExprType | null {
  if (!schema || typeof schema !== 'object') return null
  const s = schema as Record<string, unknown>
  switch (s.type) {
    case 'number': case 'integer': return 'number'
    case 'boolean': return 'boolean'
    case 'string': return s.format === 'date' ? 'date' : 'string'
    case 'array': {
      const item = typeOfSchema(s.items)
      return item === null ? null : { list: item }
    }
    case 'object': {
      const row: Record<string, ExprType> = {}
      for (const [k, v] of Object.entries((s.properties ?? {}) as Record<string, unknown>)) {
        const f = typeOfSchema(v)
        if (f !== null) row[k] = f
      }
      return { row }
    }
    default: return null
  }
}

/** A problem with an expression: the same codes and offsets as com.orqix.compiler.expr.ExpressionException. */
export class ExpressionError extends Error {
  readonly code: string
  readonly position: number
  constructor(code: string, position: number, message: string) {
    super(message)
    this.code = code
    this.position = position
  }

  get evaluation(): boolean {
    return ['EXPR_COST_LIMIT', 'EXPR_NUMBER_RANGE', 'EXPR_TEXT_LIMIT', 'EXPR_LIST_LIMIT'].includes(this.code)
  }
}

/** The names an expression may use; each returns null/undefined for an unknown name. */
export interface Scope {
  input(key: string): ExprType | null | undefined
  step(stepId: string, key: string): ExprType | null | undefined
  local?(name: string): ExprType | null | undefined
}

export function scopeOf(input: Record<string, ExprType>, steps: Record<string, Record<string, ExprType>> = {}, locals: Record<string, ExprType> = {}): Scope {
  const own = <T,>(o: Record<string, T> | undefined, k: string): T | undefined => (o && Object.hasOwn(o, k) ? o[k] : undefined)
  return {
    input: key => own(input, key),
    step: (id, key) => own(own(steps, id), key),
    local: name => own(locals, name),
  }
}
