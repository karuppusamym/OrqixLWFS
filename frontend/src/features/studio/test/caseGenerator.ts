import type { SimulationResult } from '@/api/types'
import { patternMatches } from '@/lib/form/pattern'
import type { FieldDef, FormSchema, FormUi } from '@/lib/form/schema'
import { isMultipleOf } from '@/lib/form/validate'
import { emptyValues, validateForm, type RawValue, type RawValues } from '@/lib/form/values'
import { visibleFields } from '@/lib/form/visibility'
import { MONEY_PATTERN } from '@/lib/money'
import { conditionTerms, isAtomicCondition, type AtomicCondition, type Condition, type Definition } from '../model/types'
import { CASE_VERSION, MAX_CASES, type SavedCase } from './savedCases'
import { sampleValues } from './sampleValues'
import { emptyAssertions, emptySettings, NEEDS_ATTENTION, stepControls, type TestSettings } from './scenarioModel'

/**
 * "Generate cases for every path" (docs/build/S122): synthetic test cases that reach each route of a workflow, instead
 * of the one fixed sample. Deterministic and local: the answers come from the conditions in the definition, the
 * expected outcome and path from a dry run. Nothing is sent anywhere but the simulator, which starts and calls nothing.
 */

/** Where a condition on the request form's answers comes from. */
export interface ConditionSource {
  kind: 'route' | 'rule' | 'form'
  condition: Condition
}

/** An answer set on purpose, which names the case. */
export interface Target {
  key: string
  value: RawValue
}

export interface Candidate {
  values: RawValues
  targets: Target[]
}

/** The most answer sets tried before the reject cases; each costs one dry run. */
export const MAX_CANDIDATES = 40
/** The most generated cases: what one "Run all" (simulateWorkflowCases) and the saved list hold. */
export const MAX_GENERATED = Math.min(20, MAX_CASES)

const inputKey = (ref: string): string | null => /^\/input\/([a-z][A-Za-z0-9]{0,63})$/.exec(ref)?.[1] ?? null

/** Every condition that reads a request-form answer: route conditions, decision rules of used tables, form rules. */
export function inputConditions(def: Definition, fields: FieldDef[]): ConditionSource[] {
  const keys = new Set(fields.filter((f) => f.schema.type !== 'array' && f.schema.type !== 'object').map((f) => f.key))
  const readsInput = (c: Condition) => conditionTerms(c).some((t) => keys.has(inputKey(t.fieldRef) ?? ''))
  const out: ConditionSource[] = []
  const seen = new Set<string>()
  const add = (kind: ConditionSource['kind'], condition: Condition | undefined) => {
    if (!condition || !readsInput(condition)) return
    const id = JSON.stringify(condition)
    if (seen.has(id)) return
    seen.add(id)
    out.push({ kind, condition })
  }
  for (const t of def.transitions) if (t.on === 'condition') add('route', t.condition)
  const used = new Set(def.nodes.filter((n) => n.type === 'decision').map((n) => String(n.config.decisionRef ?? '')))
  for (const table of def.decisions) if (used.has(table.key)) for (const row of table.rows) add('rule', row.when)
  for (const f of fields) {
    for (const c of f.visibleWhen ?? []) add('form', c)
    add('form', f.requiredWhen)
  }
  return out
}

const decimals = (n: number): number => {
  const text = String(n)
  if (text.includes('e-')) return Number(text.split('e-')[1])
  return text.includes('.') ? (text.split('.')[1] ?? '').length : 0
}

/** The smallest meaningful difference for a number question: a cent for money, one for whole numbers. */
export function numericStep(field: FieldDef): number {
  if (field.schema.type === 'integer') return Math.max(1, Math.ceil(field.schema.multipleOf ?? 1))
  if (field.schema.multipleOf !== undefined && field.schema.multipleOf > 0) return field.schema.multipleOf
  return 0.01
}

function numberFits(field: FieldDef, n: number): boolean {
  const s = field.schema
  if (!Number.isFinite(n)) return false
  if (s.minimum !== undefined && n < s.minimum) return false
  if (s.maximum !== undefined && n > s.maximum) return false
  if (s.type === 'integer' && !Number.isInteger(n)) return false
  if (s.multipleOf !== undefined && !isMultipleOf(n, s.multipleOf)) return false
  if (field.isMoney && !MONEY_PATTERN.test(String(n))) return false
  return true
}

function textFits(field: FieldDef, text: string): boolean {
  const s = field.schema
  const length = [...text].length
  if (s.enum && s.enum.length > 0 && !s.enum.includes(text)) return false
  if (s.minLength !== undefined && length < s.minLength) return false
  if (s.maxLength !== undefined && length > s.maxLength) return false
  if (s.pattern && !patternMatches(s.pattern, text)) return false
  return true
}

/**
 * The answer that makes one comparison come out `holds`: the boundary itself or the next value past it (1999.99 and
 * 2000 for "at least 2000"), the named choice or another one, Yes or No. Undefined when the question cannot give it
 * (outside its minimum and maximum, not a listed choice, not matching its pattern, or a different type).
 */
export function answerFor(field: FieldDef, term: AtomicCondition, holds: boolean, fallback?: RawValue): RawValue | undefined {
  const { operator: op, value: v } = term
  switch (field.schema.type) {
    case 'boolean':
      if (typeof v !== 'boolean') return undefined
      return (op === 'eq') === holds ? v : !v
    case 'number':
    case 'integer': {
      if (typeof v !== 'number') return undefined
      const step = numericStep(field)
      const round = (n: number) => Number(n.toFixed(Math.min(10, Math.max(decimals(step), decimals(v)))))
      const options: number[] =
        op === 'gte' ? [holds ? v : v - step]
        : op === 'gt' ? [holds ? v + step : v]
        : op === 'lte' ? [holds ? v : v + step]
        : op === 'lt' ? [holds ? v - step : v]
        : (op === 'eq') === holds ? [v]
        : [v + step, v - step]
      const fit = options.map(round).find((n) => numberFits(field, n))
      return fit === undefined ? undefined : String(fit)
    }
    case 'string': {
      if (typeof v !== 'string' || (op !== 'eq' && op !== 'ne')) return undefined
      if ((op === 'eq') === holds) return textFits(field, v) ? v : undefined
      const choices = field.schema.enum ?? []
      if (choices.length > 0) return choices.find((c) => c !== v)
      // Any other valid answer: the sample one, unless the rule names exactly that.
      return typeof fallback === 'string' && fallback !== v && textFits(field, fallback) ? fallback : undefined
    }
    default:
      return undefined
  }
}

/**
 * Alternative answer sets that make a condition come out `holds`. "All of" holds with every term; it fails with any
 * one term failing. "Any of" holds with any one term; it fails with every term failing. Terms that read something
 * other than an answer are left to the dry run.
 */
export function assignmentsFor(condition: Condition, holds: boolean, fields: FieldDef[], base: RawValues): Target[][] {
  const one = (term: AtomicCondition, h: boolean): Target | null | undefined => {
    const key = inputKey(term.fieldRef)
    const field = key ? fields.find((f) => f.key === key) : undefined
    if (!field) return null
    const value = answerFor(field, term, h, base[field.key])
    return value === undefined ? undefined : { key: field.key, value }
  }
  const terms = conditionTerms(condition)
  const together = (h: boolean): Target[][] => {
    const set: Target[] = []
    for (const term of terms) {
      const t = one(term, h)
      if (t === undefined) return []
      if (t && !set.some((x) => x.key === t.key)) set.push(t)
    }
    return set.length > 0 ? [set] : []
  }
  const each = (h: boolean): Target[][] => terms.map((term) => one(term, h)).filter((t): t is Target => !!t).map((t) => [t])
  if (isAtomicCondition(condition)) return each(holds)
  const all = 'all' in condition
  return all === holds ? together(holds) : each(holds)
}

/** Clears the answers of hidden questions, so the case reads as what a requester would send. */
export function tidyValues(fields: FieldDef[], values: RawValues): RawValues {
  let current = { ...values }
  const empty = emptyValues(fields)
  for (let i = 0; i <= fields.length; i++) {
    const shown = new Set(visibleFields(fields, current).map((f) => f.key))
    const next = { ...current }
    for (const f of fields) if (!shown.has(f.key)) next[f.key] = empty[f.key] as RawValue
    if (JSON.stringify(next) === JSON.stringify(current)) return current
    current = next
  }
  return current
}

/** Applies the targets on top of the sample answers, first making each target's question visible. */
function withTargets(fields: FieldDef[], base: RawValues, targets: Target[]): RawValues {
  const values = { ...base }
  const targetKeys = new Set(targets.map((t) => t.key))
  const reveal = (key: string, depth: number) => {
    const field = fields.find((f) => f.key === key)
    if (!field || depth > 3) return
    for (const c of field.visibleWhen ?? []) {
      const set = assignmentsFor(c, true, fields, base)[0] ?? []
      for (const t of set) {
        if (targetKeys.has(t.key)) continue
        reveal(t.key, depth + 1)
        values[t.key] = t.value
      }
    }
  }
  for (const t of targets) reveal(t.key, 0)
  for (const t of targets) values[t.key] = t.value
  return tidyValues(fields, values)
}

/**
 * The answer sets to try, in a fixed order: each condition's two sides (routes first, then decision rules, then form
 * rules), then the sample answers. Sets that do not pass the request form, or repeat another set, are left out.
 */
export function candidateInputs(def: Definition, fields: FieldDef[], schema: FormSchema, ui: FormUi, today: Date = new Date()): Candidate[] {
  const base = sampleValues(fields, today)
  const out: Candidate[] = []
  const seen = new Set<string>()
  const push = (targets: Target[]) => {
    const values = withTargets(fields, base, targets)
    const check = validateForm(schema, ui, values)
    if (!check.valid) return
    const id = JSON.stringify(check.payload)
    if (seen.has(id)) return
    seen.add(id)
    out.push({ values, targets })
  }
  for (const source of inputConditions(def, fields)) for (const holds of [true, false]) for (const set of assignmentsFor(source.condition, holds, fields, base)) push(set)
  push([])
  return out.slice(0, MAX_CANDIDATES)
}

// ------------------------------------------------------------------------------------------- dry runs and selection

/** One dry run; null when the simulator refused these answers (they are left out). Throws to stop generating. */
export type DryRun = (input: Record<string, unknown>, settings: TestSettings) => Promise<SimulationResult | null>

export interface GeneratedCase {
  case: SavedCase
  /** Step labels of the path, in order of first visit. */
  pathLabels: string[]
  /** Answers set on purpose, in words. */
  answers: string[]
  /** Takes a route or rule, or ends in a way, that no earlier case did. */
  newPath: boolean
}

export interface GenerationReport {
  cases: GeneratedCase[]
  /** Answer sets and reject choices dry-run. */
  tried: number
  routes: { taken: number; total: number; notTaken: string[] }
  rules: { matched: number; total: number }
}

function answerText(field: FieldDef | undefined, value: RawValue): string {
  if (!field) return String(value)
  if (typeof value === 'boolean') return `${field.label} is ${value ? 'Yes' : 'No'}`
  if (field.schema.type === 'number' || field.schema.type === 'integer') return `${field.label} is ${String(value)}`
  return `${field.label} is “${field.optionLabels?.[String(value)] ?? String(value)}”`
}

const visitedSteps = (r: SimulationResult): string[] => [...new Set(r.trace.map((e) => e.stepId))]

function signatures(r: SimulationResult, fields: FieldDef[], values: RawValues): { route: string; full: string } {
  const routes = [...new Set(r.trace.map((e) => e.transitionId).filter((t): t is string => typeof t === 'string'))].sort()
  const rules = (r.coverage.rules.notMatched ?? []).map((x) => `${String(x.decision)}#${String(x.row)}`).sort()
  const route = JSON.stringify([routes, rules, r.outcome.status, r.outcome.businessOutcome ?? null, r.outcome.attention?.stepId ?? null])
  const shown = visibleFields(fields, values).map((f) => f.key)
  return { route, full: JSON.stringify([route, shown]) }
}

/** A name unique among `taken` (case-insensitive), at most 80 characters. */
function uniqueName(name: string, taken: Set<string>): string {
  const base = name.length > 80 ? `${name.slice(0, 79)}…` : name
  let candidate = base
  for (let i = 2; taken.has(candidate.toLowerCase()); i++) candidate = `${base.slice(0, 76)} ${i}`
  taken.add(candidate.toLowerCase())
  return candidate
}

async function inPool<T, R>(items: T[], size: number, work: (item: T) => Promise<R>): Promise<R[]> {
  const results: R[] = new Array<R>(items.length)
  let next = 0
  const worker = async () => {
    while (next < items.length) {
      const i = next++
      results[i] = await work(items[i] as T)
    }
  }
  await Promise.all(Array.from({ length: Math.min(size, items.length) }, worker))
  return results
}

/**
 * Generates up to {@link MAX_GENERATED} cases: one per distinct path (routes, decision rules and ending), one per
 * further set of questions shown, and one reject case per approval step on the first path that reaches it. Each
 * expects the outcome and the exact path of its dry run.
 */
export async function generateCases(opts: { def: Definition; fields: FieldDef[]; schema: FormSchema; ui: FormUi; dryRun: DryRun; today?: Date; newId?: (i: number) => string }): Promise<GenerationReport> {
  const { def, fields, schema, ui, dryRun } = opts
  const newId = opts.newId ?? ((i: number) => `case-gen-${i + 1}`)
  const label = (id: string) => def.nodes.find((n) => n.id === id)?.label ?? id
  const candidates = candidateInputs(def, fields, schema, ui, opts.today)
  const payloadOf = (values: RawValues) => validateForm(schema, ui, values).payload

  type Kept = { values: RawValues; settings: TestSettings; name: string; answers: string[]; result: SimulationResult; newPath: boolean; order: number }
  const kept: Kept[] = []
  const routeSeen = new Set<string>()
  const fullSeen = new Set<string>()
  const names = new Set<string>()
  const consider = (k: Omit<Kept, 'newPath' | 'name' | 'order'> & { name: string }, order: number): Kept | null => {
    const sig = signatures(k.result, fields, k.values)
    if (fullSeen.has(sig.full)) return null
    fullSeen.add(sig.full)
    const newPath = !routeSeen.has(sig.route)
    routeSeen.add(sig.route)
    const entry = { ...k, name: uniqueName(k.name, names), newPath, order }
    kept.push(entry)
    return entry
  }

  const approveAll = emptySettings()
  const results = await inPool(candidates, 4, (c) => dryRun(payloadOf(c.values), approveAll))
  candidates.forEach((c, i) => {
    const result = results[i]
    if (!result) return
    const answers = c.targets.map((t) => answerText(fields.find((f) => f.key === t.key), t.value))
    consider({ values: c.values, settings: approveAll, answers, result, name: answers.length > 0 ? answers.join(', ') : 'Sample answers' }, i)
  })

  // One reject per approval step, on the first kept path that reaches it.
  const rejects = stepControls(def).approvals.flatMap((a) => {
    const on = kept.find((k) => visitedSteps(k.result).includes(a.id))
    if (!on) return []
    const settings: TestSettings = { ...emptySettings(), approvals: { [a.id]: { outcome: 'reject', reason: 'Rejected in a generated test case.' } } }
    return [{ on, approval: a, settings }]
  })
  const rejectResults = await inPool(rejects, 4, (r) => dryRun(payloadOf(r.on.values), r.settings))
  rejects.forEach((r, i) => {
    const result = rejectResults[i]
    if (!result) return
    const answers = [...r.on.answers, `${r.approval.label} rejects`]
    consider({ values: r.on.values, settings: r.settings, answers, result, name: r.on.answers.length > 0 ? `${r.approval.label} rejects (${r.on.answers.join(', ')})` : `${r.approval.label} rejects` }, candidates.length + i)
  })

  // New paths first when there are more than fit; the order shown is the order generated.
  const byPath = [...kept.filter((k) => k.newPath), ...kept.filter((k) => !k.newPath)].slice(0, MAX_GENERATED)
  const selected = kept.filter((k) => byPath.includes(k))

  const cases: GeneratedCase[] = selected.map((k, i) => {
    const steps = visitedSteps(k.result)
    const o = k.result.outcome
    return {
      case: {
        version: CASE_VERSION,
        id: newId(i),
        name: k.name,
        values: k.values,
        settings: k.settings,
        expect: o.status === 'finished' ? (o.businessOutcome ?? null) : NEEDS_ATTENTION,
        assertions: { ...emptyAssertions(), path: { steps, match: 'exact' } },
      },
      pathLabels: steps.map(label),
      answers: k.answers,
      newPath: k.newPath,
    }
  })

  // Coverage of the cases together: a route or rule counts when any case took it.
  const chosen = selected.map((k) => k.result)
  const first = chosen[0]
  const notTakenEverywhere = (first?.coverage.routes.notTaken ?? []).filter((r) => chosen.every((x) => (x.coverage.routes.notTaken ?? []).some((y) => y.transitionId === r.transitionId)))
  const notMatchedEverywhere = (first?.coverage.rules.notMatched ?? []).filter((r) => chosen.every((x) => (x.coverage.rules.notMatched ?? []).some((y) => y.decision === r.decision && y.row === r.row)))
  const routesTotal = first?.coverage.routes.total ?? 0
  const rulesTotal = first?.coverage.rules.total ?? 0
  return {
    cases,
    tried: candidates.length + rejects.length,
    routes: { taken: routesTotal - notTakenEverywhere.length, total: routesTotal, notTaken: notTakenEverywhere.map((r) => String(r.description ?? r.transitionId)) },
    rules: { matched: rulesTotal - notMatchedEverywhere.length, total: rulesTotal },
  }
}
