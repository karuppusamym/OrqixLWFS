/**
 * Decision tables whose rows use Orqix expressions as input or output entries (CG-D04, docs/build/S146): the browser
 * twin of backend/compiler/src/main/java/com/orqix/compiler/internal/eval/ExpressionTable.java, used by the mock server,
 * the mock engine and the mock simulator. Both pass examples/fixtures/decision-expression-cases.json; the server stays
 * authoritative. Tables without expressions keep the literal evaluation (decisionLogic.evaluateTable).
 */
import {
  decisionTerms, isExpressionTerm, isOutputExpression, type DecisionRow, type DecisionTable, type Literal,
} from '@/features/studio/model/types'
import { inWindow, matchValue, priorityRank } from '@/features/studio/model/decisionLogic'
import { Dec, add, cmp, fromNumber } from './decimal'
import { type Value, type Values } from './evaluator'
import { compile, toJson } from './index'
import { type Ast, parse } from './parser'
import { type ExprType, type Scope, describeType, readType, scopeOf, typeEquals } from './types'

/** Reserved value keys: the instant today() uses (ISO text) and the day effective windows are judged by (YYYY-MM-DD). */
export const NOW = '$now'
export const AS_OF = '$asOf'

/** One evaluated expression entry; term is -1 for an output entry. */
export interface DecisionEntry {
  row: number
  term: number
  kind: 'input' | 'output'
  /** The column (a field reference) of an input entry, the result name of an output entry. */
  target: string
  expression: string
  /** true, false or null for an input entry; the computed value (JSON form) for an output entry. */
  value: unknown
}

export interface ExpressionTableResult {
  matchedRow: number | null
  matchedRows: number[]
  usedDefault: boolean
  output: Record<string, unknown>
  expressions: DecisionEntry[]
  /** Rows whose condition holds and that apply on $asOf, before the hit policy chooses (for the evidence). */
  applying: number[]
}

/** The evaluation stops (a business message, like the server's IllegalArgumentException). */
export class DecisionEvaluationError extends Error {}

/** Pinned types in the compiler notation: {"input": {k: T}, "steps": {id: {k: T}}}. */
export interface PinnedTypes {
  input?: Record<string, unknown>
  steps?: Record<string, Record<string, unknown>>
}

/** Field references (/input/a, /steps/s/b) an expression names; throws for text that does not parse. */
export function expressionReferences(source: string): string[] {
  const out = new Set<string>()
  const walk = (n: Ast): void => {
    switch (n.t) {
      case 'ref':
        if (n.kind === 'input') out.add(`/input/${n.key}`)
        else if (n.kind === 'step') out.add(`/steps/${n.step}/${n.key}`)
        return
      case 'list': n.items.forEach(walk); return
      case 'member': walk(n.target); return
      case 'neg': walk(n.operand); return
      case 'binary': walk(n.left); walk(n.right); return
      case 'if': walk(n.condition); walk(n.then); walk(n.otherwise); return
      case 'call': n.args.forEach(walk); return
      default:
    }
  }
  walk(parse(source))
  return [...out]
}

/** Field references named inside an expression, or none when the text does not parse (for editors and summaries). */
export function safeReferences(source: string): string[] {
  try {
    return expressionReferences(source)
  } catch {
    return []
  }
}

/** Every field the table reads: the tested columns and the fields named inside expressions, sorted. */
export function decisionFields(table: Pick<DecisionTable, 'rows'>): string[] {
  const out = new Set<string>()
  for (const row of table.rows) {
    for (const term of decisionTerms(row.when)) {
      out.add(term.fieldRef)
      if (isExpressionTerm(term)) safeReferences(term.expression).forEach(r => out.add(r))
    }
    for (const v of Object.values(row.then ?? {})) {
      if (isOutputExpression(v)) safeReferences(v.expression).forEach(r => out.add(r))
    }
  }
  return [...out].sort()
}

/** The expression type of a literal result: the type every computed value of that result must have. */
export function literalType(literal: Literal | undefined): ExprType | null {
  if (typeof literal === 'number') return 'number'
  if (typeof literal === 'boolean') return 'boolean'
  if (typeof literal === 'string') return 'string'
  return null
}

function pinnedScope(types: PinnedTypes): Scope {
  const read = (o: Record<string, unknown> | undefined) => Object.fromEntries(Object.entries(o ?? {}).map(([k, v]) => [k, readType(v)])) as Record<string, ExprType>
  return scopeOf(read(types.input), Object.fromEntries(Object.entries(types.steps ?? {}).map(([id, o]) => [id, read(o)])))
}

const typeOfValue = (v: unknown): ExprType => (typeof v === 'number' ? 'number' : typeof v === 'boolean' ? 'boolean' : typeof v === 'string' ? 'string' : 'any')

function inferredScope(table: Pick<DecisionTable, 'rows'>, values: Record<string, unknown>): Scope {
  const input: Record<string, ExprType> = {}
  const steps: Record<string, Record<string, ExprType>> = {}
  for (const row of table.rows) {
    for (const term of decisionTerms(row.when)) if (isExpressionTerm(term)) expressionReferences(term.expression)
    for (const v of Object.values(row.then ?? {})) if (isOutputExpression(v)) expressionReferences(v.expression)
  }
  for (const ref of decisionFields(table)) {
    const parts = ref.split('/')
    const t = typeOfValue(values[ref])
    if (ref.startsWith('/input/') && parts.length === 3) input[parts[2]!] = t
    else if (ref.startsWith('/steps/') && parts.length === 4) (steps[parts[2]!] ??= {})[parts[3]!] = t
  }
  return scopeOf(input, steps)
}

function reader(values: Record<string, unknown>): Values {
  return {
    input: key => values[`/input/${key}`],
    step: (id, key) => values[`/steps/${id}/${key}`],
    get now(): string {
      const now = values[NOW]
      if (now !== undefined && now !== null) return String(now)
      const asOf = values[AS_OF]
      if (asOf !== undefined && asOf !== null) return `${String(asOf)}T00:00:00Z`
      throw new DecisionEvaluationError(`No value for ${NOW}`)
    },
  }
}

const fits = (value: Value, wanted: ExprType) =>
  wanted === 'number' ? value instanceof Dec : wanted === 'boolean' ? typeof value === 'boolean' : wanted !== 'string' || typeof value === 'string'

function run(source: string, scope: Scope, values: Values, wanted: ExprType | null, row: number, output: string | null): Value {
  const where = output === null ? `a test of rule ${row + 1}` : `the result ${output} of rule ${row + 1}`
  let e
  let value: Value
  try {
    e = compile(source, scope)
    if (wanted !== null && !(e.type === 'null' || e.type === 'any' || typeEquals(e.type, wanted))) {
      throw new DecisionEvaluationError(`The expression of ${where} must give ${describeType(wanted)}, but it gives ${describeType(e.type)}.`)
    }
    value = e.evaluate(values)
  } catch (problem) {
    if (problem instanceof DecisionEvaluationError) throw problem
    throw new DecisionEvaluationError(`The expression of ${where} cannot be evaluated: ${problem instanceof Error ? problem.message : String(problem)}`)
  }
  if (value !== null && wanted !== null && e.type === 'any' && !fits(value, wanted)) {
    throw new DecisionEvaluationError(`The expression of ${where} must give ${describeType(wanted)}.`)
  }
  return value
}

/** A literal result as an expression value, so literal and computed results compare and combine alike. */
const literalValue = (v: Literal): Value => (typeof v === 'number' ? fromNumber(v) : v)

function same(a: Map<string, Value>, b: Map<string, Value>): boolean {
  if (a.size !== b.size) return false
  for (const [k, x] of a) {
    if (!b.has(k)) return false
    const y = b.get(k) ?? null
    if (x instanceof Dec && y instanceof Dec) {
      if (cmp(x, y) !== 0) return false
    } else if (x !== y) {
      return false
    }
  }
  return true
}

function complete(out: Map<string, Value>, row: number, key: string): Value {
  const value = out.get(key) ?? null
  if (value === null) throw new DecisionEvaluationError(`Rule ${row + 1} gives no value for ${key} (a missing field or a division by zero in its expression).`)
  return value
}

const rules = (indexes: number[]) => `${indexes.slice(0, -1).map(i => i + 1).join(', ')} and ${indexes[indexes.length - 1]! + 1}`

/**
 * Evaluates a table that uses expressions with the server semantics (ExpressionTable.evaluate).
 * @param values by field reference (numbers, texts, true/false, dates as YYYY-MM-DD), plus $now and $asOf
 * @param types pinned types, or absent to infer them from the values (a missing value may be anything)
 * @throws DecisionEvaluationError when the evaluation stops
 */
export function evaluateExpressionTable(table: Pick<DecisionTable, 'rows' | 'default' | 'hitPolicy' | 'aggregation' | 'priority'>,
  values: Record<string, unknown>, types?: PinnedTypes | null): ExpressionTableResult {
  let scope: Scope
  try {
    scope = types ? pinnedScope(types) : inferredScope(table, values)
  } catch (problem) {
    throw new DecisionEvaluationError(problem instanceof Error ? problem.message : String(problem))
  }
  const read = reader(values)
  const dated = table.rows.some(r => r.effective)
  const asOf = dated ? values[AS_OF] : undefined
  if (dated && (asOf === undefined || asOf === null)) throw new DecisionEvaluationError(`No value for ${AS_OF}`)
  const entries: DecisionEntry[] = []
  const matching: number[] = []
  table.rows.forEach((row: DecisionRow, index) => {
    const any = 'any' in row.when && Array.isArray(row.when.any)
    let matched = !any
    decisionTerms(row.when).forEach((term, i) => {
      let hit: boolean
      if (isExpressionTerm(term)) {
        const result = run(term.expression, scope, read, 'boolean', index, null)
        entries.push({ row: index, term: i, kind: 'input', target: term.fieldRef, expression: term.expression, value: result })
        hit = result === true
      } else {
        const actual = values[term.fieldRef]
        if (actual === undefined || actual === null) throw new DecisionEvaluationError(`No value for ${term.fieldRef}`)
        try {
          hit = matchValue(term.operator, actual, term.value)
        } catch (problem) {
          throw new DecisionEvaluationError(problem instanceof Error ? problem.message : String(problem))
        }
      }
      matched = any ? matched || hit : matched && hit
    })
    if (matched && inWindow(row, asOf === undefined ? undefined : String(asOf))) matching.push(index)
  })
  if (matching.length === 0) {
    return { matchedRow: null, matchedRows: [], usedDefault: true, output: { ...table.default }, expressions: sorted(entries), applying: [] }
  }
  const outputs = new Map<number, Map<string, Value>>()
  for (const index of matching) {
    const row = table.rows[index]!
    const out = new Map<string, Value>()
    for (const key of Object.keys(row.then).sort()) {
      const v = row.then[key]!
      if (!isOutputExpression(v)) {
        out.set(key, literalValue(v))
        continue
      }
      const value = run(v.expression, scope, read, literalType(table.default[key]), index, key)
      entries.push({ row: index, term: -1, kind: 'output', target: key, expression: v.expression, value: toJson(value) })
      out.set(key, value)
    }
    outputs.set(index, out)
  }
  const expressions = sorted(entries)
  if (table.hitPolicy === 'UNIQUE' && matching.length > 1) {
    throw new DecisionEvaluationError(`Rules ${rules(matching)} all apply, but with "Only one row" at most one rule may apply. Make the conditions exclusive or choose "First matching row".`)
  }
  if (table.hitPolicy === 'ANY') {
    const first = outputs.get(matching[0]!)!
    for (const i of matching) {
      if (!same(first, outputs.get(i)!)) {
        throw new DecisionEvaluationError(`Rules ${matching[0]! + 1} and ${i + 1} both apply but give different results, which "Any matching row" does not allow.`)
      }
    }
  }
  if (table.hitPolicy === 'COLLECT') {
    const name = Object.keys(table.default)[0] ?? 'result'
    const numbers = matching.map(i => {
      const value = complete(outputs.get(i)!, i, name)
      if (!(value instanceof Dec)) throw new DecisionEvaluationError(`Rule ${i + 1} must give a number for ${name}.`)
      return value
    })
    return { matchedRow: matching[0]!, matchedRows: matching, usedDefault: false, output: { [name]: toJson(aggregate(table.aggregation, numbers)) }, expressions, applying: matching }
  }
  let chosen = matching[0]!
  if (table.hitPolicy === 'PRIORITY' && table.priority) {
    const p = table.priority.output
    for (const i of matching) {
      const v = table.rows[i]!.then[p]
      if (isOutputExpression(v)) throw new DecisionEvaluationError(`The priority result ${p} of rule ${i + 1} cannot be an expression.`)
      if (priorityRank(table as DecisionTable, v) < priorityRank(table as DecisionTable, table.rows[chosen]!.then[p] as Literal | undefined)) chosen = i
    }
  }
  const out = outputs.get(chosen)!
  const output: Record<string, unknown> = {}
  for (const key of out.keys()) output[key] = toJson(complete(out, chosen, key))
  return { matchedRow: chosen, matchedRows: [chosen], usedDefault: false, output, expressions, applying: matching }
}

/**
 * The decision evidence of a table with formulas (S21, S146): the scalar fields it reads that have a value, and each
 * candidate row with its formula tests and, when selected, its computed results.
 */
export function expressionEvidence(table: Pick<DecisionTable, 'rows'>, values: Record<string, unknown>, result: ExpressionTableResult, asOf?: string) {
  const scalar = (v: unknown) => typeof v === 'string' || typeof v === 'boolean' || (typeof v === 'number' && Number.isFinite(v))
  return {
    inputs: decisionFields(table).filter(ref => scalar(values[ref])).map(fieldRef => ({ fieldRef, value: values[fieldRef] })),
    rows: table.rows.map((row, index) => {
      const own = result.expressions.filter(e => e.row === index)
      const tests = own.filter(e => e.kind === 'input').map(e => ({ term: e.term, fieldRef: e.target, expression: e.expression, result: e.value as boolean | null }))
      const selected = result.matchedRows.includes(index)
      const outputs = selected ? own.filter(e => e.kind === 'output').map(e => ({ output: e.target, expression: e.expression, value: e.value })) : []
      return {
        rowId: `r${index + 1}`, condition: row.when, matched: result.applying.includes(index), selected,
        ...(row.effective ? { inWindow: inWindow(row, asOf), effective: row.effective } : {}),
        ...(tests.length ? { expressions: tests } : {}),
        ...(outputs.length ? { outputExpressions: outputs } : {}),
      }
    }),
  }
}

function aggregate(aggregation: string | undefined, numbers: Dec[]): Dec {
  switch (aggregation) {
    case 'SUM': return numbers.reduce((s, n) => add(s, n), new Dec(0n, 0))
    case 'MIN': return numbers.reduce((m, n) => (cmp(n, m) < 0 ? n : m))
    case 'MAX': return numbers.reduce((m, n) => (cmp(n, m) > 0 ? n : m))
    case 'COUNT': return new Dec(BigInt(numbers.length), 0)
    default: throw new DecisionEvaluationError(aggregation ? `Unknown aggregation ${aggregation}` : 'COLLECT needs an aggregation')
  }
}

/** Canonical order: by row, the row's tests by term, then its results by name. */
function sorted(entries: DecisionEntry[]): DecisionEntry[] {
  return [...entries].sort((a, b) => a.row - b.row || Number(a.term < 0) - Number(b.term < 0) || a.term - b.term || (a.target < b.target ? -1 : a.target > b.target ? 1 : 0))
}
