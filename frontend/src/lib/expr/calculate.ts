/**
 * A Calculate step in the browser (docs/build/S137): the twin of com.orqix.compiler.expr.Calculation for the mock
 * engine and the browser simulator. Outputs run in order, each seeing the earlier ones by name; numbers are rounded to
 * their declared scale and rounding; an output without a value fails unless it is optional.
 */
import { compile, toJson } from './index'
import { Dec, type RoundingMode, setScale } from './decimal'
import { type WorkingCalendar } from './dates'
import { type Value } from './evaluator'
import { type ExprType, type Scope, ExpressionError } from './types'

export interface CalculateOutput {
  key: string
  expression: string
  type: 'number' | 'integer' | 'string' | 'boolean' | 'date'
  scale?: number
  rounding?: RoundingMode
  description?: string
  optional?: boolean
}

export interface CalculateConfig {
  outputs: CalculateOutput[]
  calendar?: WorkingCalendar
}

export class CalculationFailure extends Error {
  readonly output: string
  readonly code: string
  constructor(output: string, code: string, message: string) {
    super(message)
    this.output = output
    this.code = code
  }
}

export const declaredType = (type: string): ExprType =>
  type === 'number' || type === 'integer' ? 'number' : type === 'boolean' ? 'boolean' : type === 'date' ? 'date' : 'string'

/**
 * @returns the outputs as JSON-ready values in declared order (null for an optional output without a value)
 * @throws CalculationFailure
 */
export function runCalculation(config: CalculateConfig, scope: Scope, input: (key: string) => unknown,
  step: (id: string, key: string) => unknown, now: string | Date): Record<string, unknown> {
  const localTypes: Record<string, ExprType> = {}
  const locals: Record<string, Value> = {}
  const out: Record<string, unknown> = {}
  for (const o of config.outputs) {
    let value: Value
    try {
      const e = compile(o.expression, { input: k => scope.input(k), step: (s, k) => scope.step(s, k), local: n => localTypes[n] })
      value = e.evaluate({ input, step, local: n => locals[n], now, calendar: config.calendar ?? null })
    } catch (problem) {
      if (problem instanceof ExpressionError) throw new CalculationFailure(o.key, problem.code, `The output ${o.key} cannot be calculated: ${problem.message}`)
      throw problem
    }
    if (value instanceof Dec && (o.type === 'integer' || o.scale !== undefined)) {
      value = setScale(value, o.type === 'integer' ? 0 : o.scale!, o.rounding ?? 'halfUp')
    }
    if (value === null && !o.optional) {
      throw new CalculationFailure(o.key, 'EXPR_NO_VALUE', `The output ${o.key} has no value for this request (a missing field, a division by zero or a value that is not ${o.type}).`)
    }
    locals[o.key] = value
    localTypes[o.key] = declaredType(o.type)
    out[o.key] = toJson(value)
  }
  return out
}
