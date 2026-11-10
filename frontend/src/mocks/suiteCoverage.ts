/**
 * CG-Q03 (docs/build/S148): what several dry runs cover together. The twin of SuiteCoverage.java, held identical by
 * the shared fixture examples/fixtures/simulation/suite-coverage.json. A step, route or decision rule is covered when
 * one case reached it; a form rule (visibleWhen, requiredWhen, a section's visibleWhen) when the cases hold both sides.
 */

type Node = Record<string, unknown>
export interface CoverageRun { input: Record<string, unknown>; coverage: Node }
export interface CoverageKind { covered: number; total: number; percent: number; notCovered: Node[] }
export interface SuiteCoverage { cases: number; steps: CoverageKind; routes: CoverageKind; rules: CoverageKind; formRules: CoverageKind; summary: string }
export type CoverageMinimum = Partial<Record<'steps' | 'routes' | 'rules' | 'formRules', number>>
export interface Shortfall { kind: 'steps' | 'routes' | 'rules' | 'formRules'; percent: number; minimum: number }

export const KINDS = ['steps', 'routes', 'rules', 'formRules'] as const
const MAX_LISTED = 100

const isRecord = (v: unknown): v is Node => typeof v === 'object' && v !== null && !Array.isArray(v)
const rec = (v: unknown): Node => (isRecord(v) ? v : {})
const list = (v: unknown): Node[] => (Array.isArray(v) ? v.filter(isRecord) : [])
export const percent = (covered: number, total: number) => (total === 0 ? 100 : Math.floor((covered * 100) / total))

function missed(runs: CoverageRun[], member: string, missedList: string, key: (n: Node) => string): CoverageKind {
  const first = rec(runs[0]?.coverage[member])
  const total = Number(first.total ?? 0)
  const notCovered = new Map<string, Node>()
  for (const n of list(first[missedList])) notCovered.set(key(n), n)
  for (const run of runs.slice(1)) {
    const here = new Set(list(rec(run.coverage[member])[missedList]).map(key))
    for (const k of [...notCovered.keys()]) if (!here.has(k)) notCovered.delete(k)
  }
  const covered = Math.max(0, total - notCovered.size)
  return { covered, total, percent: percent(covered, total), notCovered: [...notCovered.values()].slice(0, MAX_LISTED).map((n) => structuredClone(n)) }
}

function scalar(v: unknown): string | number | boolean | null {
  return typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean' ? v : null
}

/** FormVisibility.matches for the condition shapes form rules use (atomic, all, any). Expressions never hold here. */
export function conditionHolds(condition: unknown, input: Record<string, unknown>): boolean {
  if (!isRecord(condition)) return false
  if ('expression' in condition) return false
  if ('all' in condition) return Array.isArray(condition.all) && condition.all.length > 0 && condition.all.every((t) => conditionHolds(t, input))
  if ('any' in condition) return Array.isArray(condition.any) && condition.any.some((t) => conditionHolds(t, input))
  const ref = typeof condition.fieldRef === 'string' ? condition.fieldRef : ''
  if (!/^\/input\/[a-z][A-Za-z0-9]{0,63}$/.test(ref)) return false
  const actual = scalar(input[ref.slice(7)])
  const expected = scalar(condition.value)
  if (actual === null || expected === null) return false
  const op = condition.operator
  if (typeof actual === 'number' && typeof expected === 'number') {
    return op === 'eq' ? actual === expected : op === 'ne' ? actual !== expected : op === 'gt' ? actual > expected
      : op === 'gte' ? actual >= expected : op === 'lt' ? actual < expected : op === 'lte' ? actual <= expected : false
  }
  if (typeof actual !== typeof expected) return false
  return op === 'eq' ? actual === expected : op === 'ne' ? actual !== expected : false
}

function formRules(definition: Node, runs: CoverageRun[]): CoverageKind {
  const ui = rec(definition.inputUi)
  const properties = rec(rec(definition.inputSchema).properties)
  const rules: Array<{ rule: string; field?: string; section?: string; condition: unknown }> = []
  for (const [field, condition] of Object.entries(rec(ui.visibleWhen))) rules.push({ rule: 'visibleWhen', field, condition })
  for (const [field, condition] of Object.entries(rec(ui.requiredWhen))) rules.push({ rule: 'requiredWhen', field, condition })
  const sections = list(rec(ui.layout).sections)
  for (const s of sections) if ('visibleWhen' in s) rules.push({ rule: 'visibleWhen', section: String(s.id), condition: s.visibleWhen })
  let covered = 0
  const notCovered: Node[] = []
  for (const r of rules) {
    const sides = runs.map((run) => conditionHolds(r.condition, run.input))
    const holds = sides.includes(true)
    const fails = sides.includes(false)
    if (holds && fails) covered++
    else if (notCovered.length < MAX_LISTED) {
      const label = r.field !== undefined
        ? String(rec(properties[r.field]).title ?? r.field)
        : String(sections.find((s) => s.id === r.section)?.title ?? r.section)
      notCovered.push({ rule: r.rule, ...(r.field !== undefined ? { field: r.field } : { section: r.section }), label, missing: holds ? 'fails' : 'holds' })
    }
  }
  return { covered, total: rules.length, percent: percent(covered, rules.length), notCovered }
}

export function suiteCoverage(definition: Node, runs: CoverageRun[]): SuiteCoverage | null {
  if (runs.length === 0) return null
  const steps = missed(runs, 'steps', 'notVisited', (n) => String(n.id))
  const routes = missed(runs, 'routes', 'notTaken', (n) => String(n.transitionId))
  const rules = missed(runs, 'rules', 'notMatched', (n) => `${String(n.decision)}#${String(n.row)}`)
  const form = formRules(definition, runs)
  const phrase = (k: CoverageKind, noun: string) => `${k.covered} of ${k.total} ${noun}`
  return {
    cases: runs.length, steps, routes, rules, formRules: form,
    summary: `Together the cases cover ${phrase(steps, 'steps')}, ${phrase(routes, 'routes')}, ${phrase(rules, 'decision rules')} and ${phrase(form, 'form rules')}.`,
  }
}

export function shortfalls(coverage: Pick<SuiteCoverage, (typeof KINDS)[number]> | null, minimum: CoverageMinimum | null | undefined): Shortfall[] {
  if (!minimum) return []
  const out: Shortfall[] = []
  for (const kind of KINDS) {
    const least = minimum[kind]
    if (least === undefined) continue
    const p = coverage ? percent(coverage[kind].covered, coverage[kind].total) : 0
    if (p < least) out.push({ kind, percent: p, minimum: least })
  }
  return out
}
