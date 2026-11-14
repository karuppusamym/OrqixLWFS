/**
 * Computed form fields and cross-field rules (CG-F04, docs/build/S137): the browser twin of
 * com.orqix.compiler.FormComputation, on the same expression engine. The server recomputes computed fields when the
 * request is submitted and refuses a request that breaks a rule, so this only assists.
 */
import { type Expression, type ExprType, check, compile, scopeOf, toJson, typeOfSchema } from '@/lib/expr'
import type { FormRule, FormSchema, FormUi } from './schema'

export function formScope(schema: FormSchema) {
  const input: Record<string, ExprType> = {}
  for (const [key, field] of Object.entries(schema.properties)) {
    const t = typeOfSchema(field)
    if (t !== null && (field as { format?: string }).format !== 'attachment') input[key] = t
  }
  return scopeOf(input)
}

/** The order computed fields are filled in: repeatedly the alphabetically first one whose computed inputs are known. */
export function computeOrder(computed: Record<string, string>, refsOf: (key: string) => string[]): string[] | null {
  const needs = new Map<string, string[]>()
  for (const key of Object.keys(computed).sort()) {
    const deps = [...new Set(refsOf(key).filter(r => r.startsWith('/input/')).map(r => r.slice(7)))]
      .filter(f => f in computed && f !== key).sort()
    needs.set(key, deps)
  }
  const order: string[] = []
  while (order.length < needs.size) {
    let next: string | null = null
    for (const [key, deps] of needs) {
      if (!order.includes(key) && deps.every(d => order.includes(d))) { next = key; break }
    }
    if (next === null) return null
    order.push(next)
  }
  return order
}

const valuesOf = (input: Record<string, unknown>, now: Date | string) => ({ input: (k: string) => input[k], step: () => undefined, now })

/** Fills the computed fields into a copy of the input; a field whose expression gives no value is left out. */
export function applyComputed(schema: FormSchema, ui: FormUi, input: Record<string, unknown>, now: Date | string = new Date(),
  visible: (key: string, values: Record<string, unknown>) => boolean = () => true): Record<string, unknown> {
  const out = { ...input }
  const computed = ui.computed ?? {}
  if (Object.keys(computed).length === 0) return out
  const scope = formScope(schema)
  const compiled = new Map<string, Expression>()
  for (const [key, source] of Object.entries(computed)) {
    const result = check(source, scope)
    if (result.ok) compiled.set(key, result.expression)
  }
  const order = computeOrder(computed, key => compiled.get(key)?.fieldRefs ?? [])
  if (!order) return out
  for (const key of order) {
    delete out[key]
    const e = compiled.get(key)
    if (!e) continue
    try {
      const value = toJson(e.evaluate(valuesOf(out, now)))
      if (value !== null && value !== undefined) out[key] = value
    } catch {
      // Bounded work exceeded: the field stays empty and required-field validation reports it.
    }
  }
  for (const key of order) if (key in out && !visible(key, out)) delete out[key]
  return out
}

export interface BrokenRule { id: string; message: string; fields: string[] }

/** @returns the rules whose check is false; a rule without fields is shown at the first field its check reads */
export function brokenRules(schema: FormSchema, ui: FormUi, input: Record<string, unknown>, now: Date | string = new Date()): BrokenRule[] {
  const scope = formScope(schema)
  const out: BrokenRule[] = []
  for (const rule of ui.rules ?? []) {
    try {
      const e = compile(rule.check, scope)
      if (e.evaluate(valuesOf(input, now)) === false) {
        const fields = rule.fields?.length ? rule.fields : e.fieldRefs.filter(r => r.startsWith('/input/')).slice(0, 1).map(r => r.slice(7))
        out.push({ id: rule.id, message: rule.message, fields })
      }
    } catch {
      // A rule that cannot be checked is refused by the compiler before publishing.
    }
  }
  return out
}

/** @returns whether an expression condition holds (true only) over the request values */
export function expressionHolds(schema: FormSchema, source: string, input: Record<string, unknown>, now: Date | string = new Date()): boolean {
  try {
    return compile(source, formScope(schema)).evaluate(valuesOf(input, now)) === true
  } catch {
    return false
  }
}

export type { FormRule }
