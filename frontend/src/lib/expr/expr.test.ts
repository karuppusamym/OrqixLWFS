import { describe, expect, it } from 'vitest'
import cases from '../../../../examples/fixtures/expression-cases.json'
import { type ExprType, type Values, canonical, compile, ExpressionError, readType, scopeOf, tag } from './index'

/** CG-X01 (docs/build/S137): the browser engine on the cases the Java engine also runs (ExpressionCasesTest). */
type Case = (typeof cases.cases)[number] & {
  expr?: string; repeat?: { text: string; times: number; then: string }; values?: Record<string, unknown>
  error?: string; at?: number; evalError?: string; type?: string; value?: unknown
}

const typesOf = (o: Record<string, unknown>) => Object.fromEntries(Object.entries(o).map(([k, v]) => [k, readType(v)])) as Record<string, ExprType>

const scope = scopeOf(
  typesOf(cases.types.input),
  Object.fromEntries(Object.entries(cases.types.steps).map(([id, o]) => [id, typesOf(o)])),
  typesOf(cases.types.locals),
)

function source(c: Case): string {
  if (c.expr !== undefined) return c.expr
  return c.repeat!.text.repeat(c.repeat!.times) + c.repeat!.then
}

function values(c: Case): Values {
  const base = JSON.parse(JSON.stringify(cases.values)) as Record<string, unknown> & { input: Record<string, unknown> }
  for (const [k, v] of Object.entries(c.values ?? {})) {
    if (k === 'input') Object.assign(base.input, v)
    else base[k] = v
  }
  const steps = base.steps as Record<string, Record<string, unknown>>
  const locals = base.locals as Record<string, unknown>
  return {
    input: key => base.input[key],
    step: (id, key) => steps[id]?.[key],
    local: name => locals[name],
    now: base.now as string,
    calendar: (base.calendar ?? null) as Values['calendar'],
  }
}

describe('Orqix expression engine', () => {
  const all = cases.cases as Case[]
  it.each(all.map((c, i) => [i, source(c).slice(0, 70), c] as const))('shared case %i: %s', (_, __, c) => {
    let e
    try {
      e = compile(source(c), scope)
    } catch (problem) {
      if (!(problem instanceof ExpressionError)) throw problem
      expect(c.error, `compile failed: ${problem.code} at ${problem.position}: ${problem.message}`).toBeDefined()
      expect(problem.code).toBe(c.error)
      expect(problem.position).toBe(c.at)
      expect(problem.message).not.toBe('')
      return
    }
    expect(c.error, 'expected a compile problem').toBeUndefined()
    let result
    try {
      result = e.evaluate(values(c))
    } catch (problem) {
      if (!(problem instanceof ExpressionError)) throw problem
      expect(c.evalError, `evaluation failed: ${problem.code}: ${problem.message}`).toBeDefined()
      expect(problem.code).toBe(c.evalError)
      expect(problem.evaluation).toBe(true)
      if (c.at !== undefined) expect(problem.position).toBe(c.at)
      return
    }
    expect(c.evalError, 'expected an evaluation problem').toBeUndefined()
    expect(tag(e.type)).toBe(c.type)
    expect(canonical(result)).toEqual(c.value)
  })

  it('reports the request fields and earlier outputs an expression uses', () => {
    const e = compile('sum(input.lines, item.qty) + steps.check-credit.score + subtotal', scope)
    expect(e.fieldRefs).toEqual(['/input/lines', '/steps/check-credit/score'])
    expect(e.locals).toEqual(['subtotal'])
  })
})
