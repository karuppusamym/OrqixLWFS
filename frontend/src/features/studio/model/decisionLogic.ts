/**
 * Client copy of the decision semantics (backend ConditionMatcher and DefaultDecisionEvaluator, docs/build/S40, S121):
 * comparisons, lists (`in`), ranges (`between`) and the five hit policies. Used by the mock server, the mock simulator
 * and wording in the studio; the server stays authoritative.
 */
import { conditionTerms, isRangeValue, type AtomicCondition, type ConditionValue, type DecisionRow, type DecisionTable, type Literal } from './types'

const SCALAR = new Set(['eq', 'ne', 'gte', 'gt', 'lt', 'lte'])

/** Same semantics as ConditionMatcher.matches; throws for unknown operators or mismatched types. */
export function matchValue(operator: string, actual: unknown, value: ConditionValue): boolean {
  if (operator === 'in') {
    if (!Array.isArray(value) || value.length === 0) throw new Error('Operator in needs a list of values')
    return value.some(v => matchValue('eq', actual, v))
  }
  if (operator === 'between') {
    if (!isRangeValue(value)) throw new Error('Operator between needs a range')
    if (typeof actual !== 'number') throw new Error('Operator between compares numbers only')
    const lo = value.includeFrom === false ? actual > value.from : actual >= value.from
    const hi = value.includeTo === false ? actual < value.to : actual <= value.to
    return lo && hi
  }
  if (!SCALAR.has(operator)) throw new Error(`Unknown operator: ${operator}`)
  if (Array.isArray(value) || isRangeValue(value)) throw new Error(`Operator ${operator} compares one value`)
  if (typeof actual === 'number' && typeof value === 'number') {
    switch (operator) {
      case 'eq': return actual === value
      case 'ne': return actual !== value
      case 'gte': return actual >= value
      case 'gt': return actual > value
      case 'lt': return actual < value
      default: return actual <= value
    }
  }
  if (typeof actual !== typeof value) throw new Error(`Cannot compare ${typeof actual} with ${typeof value}`)
  if (operator !== 'eq' && operator !== 'ne') throw new Error(`Operator ${operator} compares numbers only`)
  return operator === 'eq' ? actual === value : actual !== value
}

/** "EU, UK" for a list, "1,000 and 5,000 (excluding 5,000)" for a range, the value otherwise. */
export function conditionValueText(value: ConditionValue, format: (v: Literal) => string = String): string {
  if (Array.isArray(value)) return value.map(format).join(', ')
  if (isRangeValue(value)) {
    const excluded = value.includeFrom === false && value.includeTo === false ? ' (both excluded)'
      : value.includeFrom === false ? ` (excluding ${format(value.from)})`
      : value.includeTo === false ? ` (excluding ${format(value.to)})` : ''
    return `${format(value.from)} and ${format(value.to)}${excluded}`
  }
  return format(value)
}

/** True when the row applies on `asOf` (YYYY-MM-DD); a row without a window always applies. */
export function inWindow(row: DecisionRow, asOf: string | undefined): boolean {
  const w = row.effective
  if (!w || !asOf) return true
  return (!w.from || asOf >= w.from) && (!w.to || asOf <= w.to)
}

function termMatches(term: AtomicCondition, values: Record<string, Literal | undefined>): boolean {
  const actual = values[term.fieldRef]
  if (actual === undefined) return false
  try {
    return matchValue(term.operator, actual, term.value)
  } catch {
    return false
  }
}

export function rowMatches(row: DecisionRow, values: Record<string, Literal | undefined>, asOf?: string): boolean {
  const terms = conditionTerms(row.when)
  const hit = 'any' in row.when ? terms.some(t => termMatches(t, values)) : terms.every(t => termMatches(t, values))
  return hit && inWindow(row, asOf)
}

/** Position of a value in the priority order (lower wins); the order length when absent. */
export function priorityRank(table: DecisionTable, value: Literal | undefined): number {
  const order = table.priority?.order ?? []
  const i = order.findIndex(v => v === value)
  return i < 0 ? order.length : i
}

export interface TableResult {
  matchedRow: number | null
  usedDefault: boolean
  output: Record<string, Literal>
  matchedRows: number[]
}

/** Decimal-safe combination of the COLLECT numbers (scaled integers, enough for money-like values). */
function combine(aggregation: string | undefined, numbers: number[]): number {
  switch (aggregation) {
    case 'MIN': return Math.min(...numbers)
    case 'MAX': return Math.max(...numbers)
    case 'COUNT': return numbers.length
    default: {
      const scale = 10 ** Math.min(12, Math.max(0, ...numbers.map(n => (String(n).split('.')[1] ?? '').length)))
      return numbers.reduce((sum, n) => sum + Math.round(n * scale), 0) / scale
    }
  }
}

/** The canonical evaluation: UNIQUE, FIRST and ANY take the first match, PRIORITY the highest ranked, COLLECT combines all. */
export function evaluateTable(table: DecisionTable, values: Record<string, Literal | undefined>, asOf?: string): TableResult {
  const matching = table.rows.flatMap((row, i) => (rowMatches(row, values, asOf) ? [i] : []))
  if (matching.length === 0) return { matchedRow: null, usedDefault: true, output: table.default, matchedRows: [] }
  if (table.hitPolicy === 'COLLECT') {
    const key = Object.keys(table.default)[0] ?? 'result'
    const numbers = matching.map(i => Number(table.rows[i]?.then[key] ?? 0))
    return { matchedRow: matching[0] ?? null, usedDefault: false, output: { [key]: combine(table.aggregation, numbers) }, matchedRows: matching }
  }
  let chosen = matching[0] as number
  if (table.hitPolicy === 'PRIORITY' && table.priority) {
    const output = table.priority.output
    for (const i of matching) {
      if (priorityRank(table, table.rows[i]?.then[output]) < priorityRank(table, table.rows[chosen]?.then[output])) chosen = i
    }
  }
  return { matchedRow: chosen, usedDefault: false, output: (table.rows[chosen] as DecisionRow).then, matchedRows: [chosen] }
}

/** Values a result field takes in the rows and the default, in first-seen order (to seed a priority order). */
export function outputValues(table: DecisionTable, output: string): Literal[] {
  const seen: Literal[] = []
  for (const v of [...table.rows.map(r => r.then[output]), table.default[output]]) {
    if (v !== undefined && !seen.includes(v)) seen.push(v)
  }
  return seen
}
