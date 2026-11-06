/**
 * Orqix expressions (docs/build/S137, doc 29 CG-X01): the browser engine. compile() parses and type checks against a
 * scope; evaluate() runs on values. It gives the same results, codes and offsets as the server engine
 * (com.orqix.compiler.expr) on examples/fixtures/expression-cases.json; the server stays authoritative.
 */
import { Checker } from './checker'
import { Dec, plain, toNumber } from './decimal'
import { ExprDate } from './dates'
import { type Value, type Values, Evaluator } from './evaluator'
import { type Ast, parse } from './parser'
import { type ExprType, type Scope, ExpressionError } from './types'

export { ExpressionError, scopeOf, typeOfSchema, readType, describeType, tag, type ExprType, type Scope } from './types'
export { type Value, type Values } from './evaluator'
export { type WorkingCalendar, DEFAULT_CALENDAR } from './dates'
export { ROUNDING } from './checker'
export { Dec } from './decimal'
export { ExprDate } from './dates'

export interface Expression {
  readonly source: string
  readonly type: ExprType
  /** Request fields and step outputs used, as field references: /input/a, /steps/s/b. */
  readonly fieldRefs: string[]
  /** Earlier outputs of the same step used by name. */
  readonly locals: string[]
  readonly ast: Ast
  evaluate(values: Values): Value
}

export function compile(source: string, scope: Scope): Expression {
  const ast = parse(source)
  const checker = new Checker(scope)
  const type = checker.check(ast)
  const types = checker.types
  return {
    source, type, ast,
    fieldRefs: [...checker.fieldRefs],
    locals: [...checker.locals],
    evaluate: values => new Evaluator(types, values).eval(ast),
  }
}

export type CheckResult = { ok: true; expression: Expression } | { ok: false; error: ExpressionError }

/** compile() without throwing: for editors that show the problem next to the text. */
export function check(source: string, scope: Scope): CheckResult {
  try {
    return { ok: true, expression: compile(source, scope) }
  } catch (e) {
    if (e instanceof ExpressionError) return { ok: false, error: e }
    throw e
  }
}

/** Canonical form of a value: numbers and dates as text, lists as arrays, rows as objects (the case-file form). */
export function canonical(value: Value): unknown {
  if (value instanceof Dec) return plain(value)
  if (value instanceof ExprDate) return value.toString()
  if (Array.isArray(value)) return value.map(canonical)
  if (value instanceof Map) return Object.fromEntries([...value.entries()].map(([k, v]) => [k, canonical(v)]))
  return value
}

/** JSON-ready form of a value, as stored in a request: numbers as JS numbers, dates as YYYY-MM-DD. */
export function toJson(value: Value): unknown {
  if (value instanceof Dec) return toNumber(value)
  if (value instanceof ExprDate) return value.toString()
  if (Array.isArray(value)) return value.map(toJson)
  if (value instanceof Map) return Object.fromEntries([...value.entries()].map(([k, v]) => [k, toJson(v)]))
  return value
}
