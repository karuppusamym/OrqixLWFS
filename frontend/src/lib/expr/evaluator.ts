/**
 * Evaluates a checked Orqix expression (docs/build/S137): a line-by-line port of
 * backend/compiler/src/main/java/com/orqix/compiler/expr/Evaluator.java. Total and side-effect free; every node
 * evaluation costs one unit and a run stops with EXPR_COST_LIMIT after MAX_COST.
 */
import {
  Dec, type RoundingMode, absDec, add, cmp, div, fromNumber, isZero, mul, neg, parseDec, plain, round34, setScale, sub, whole,
  ZERO,
} from './decimal'
import {
  DEFAULT_CALENDAR, ExprDate, type WorkingCalendar, dateIn, isWorkingDay, makeDate, parseDate, plusDays, plusMonths, plusWorkingDays,
  plusYears, workingDaysBetween,
} from './dates'
import { type Ast } from './parser'
import { type ExprType, ExpressionError, isList, isRow } from './types'

export const MAX_COST = 100_000
export const MAX_TEXT = 10_000
export const MAX_LIST_ITEMS = 1_000
const MAX_ABS = new Dec(1n, -24)
const NUMBER_TEXT = /^-?[0-9]{1,30}(\.[0-9]{1,30})?$/

export type Value = Dec | string | boolean | ExprDate | Value[] | Map<string, Value> | null

/** What an expression reads while it is evaluated. Raw values are JSON-like; they are converted by the checked type. */
export interface Values {
  input(key: string): unknown
  step(stepId: string, key: string): unknown
  local?(name: string): unknown
  /** The evaluation instant today() uses (an ISO instant or a Date). */
  now: string | Date
  /** Null or absent for the default Monday-Friday UTC calendar. */
  calendar?: WorkingCalendar | null
}

const isRowValue = (v: unknown): v is Map<string, Value> => v instanceof Map

function fromRaw(v: unknown): Value {
  if (v === null || v === undefined) return null
  if (v instanceof Dec || v instanceof ExprDate || typeof v === 'string' || typeof v === 'boolean') return v
  if (typeof v === 'number') return fromNumber(v)
  if (typeof v === 'bigint') return new Dec(v, 0)
  if (Array.isArray(v)) return v.map(fromRaw)
  if (v instanceof Map) return new Map([...v.entries()].map(([k, x]) => [String(k), fromRaw(x)]))
  if (typeof v === 'object') return new Map(Object.entries(v as Record<string, unknown>).map(([k, x]) => [k, fromRaw(x)]))
  return null
}

export function convert(raw: unknown, type: ExprType, pos: number): Value {
  const v = fromRaw(raw)
  if (v === null) return null
  if (type === 'number') return v instanceof Dec ? v : null
  if (type === 'string') return typeof v === 'string' ? v : null
  if (type === 'boolean') return typeof v === 'boolean' ? v : null
  if (type === 'date') return v instanceof ExprDate ? v : typeof v === 'string' ? parseDate(v) : null
  if (isList(type)) {
    if (!Array.isArray(v)) return null
    if (v.length > MAX_LIST_ITEMS) throw new ExpressionError('EXPR_LIST_LIMIT', pos, `A list can have at most ${MAX_LIST_ITEMS} items.`)
    return v.map(item => convert(item, type.list, pos))
  }
  if (isRow(type)) {
    if (!isRowValue(v)) return null
    const out = new Map<string, Value>()
    for (const [k, t] of Object.entries(type.row)) out.set(k, convert(v.has(k) ? v.get(k) : null, t, pos))
    return out
  }
  return normalize(v, pos)
}

function normalize(v: Value, pos: number): Value {
  if (Array.isArray(v)) {
    if (v.length > MAX_LIST_ITEMS) throw new ExpressionError('EXPR_LIST_LIMIT', pos, `A list can have at most ${MAX_LIST_ITEMS} items.`)
    return v.map(item => normalize(item, pos))
  }
  return v
}

function num(x: Dec, pos: number): Dec {
  const r = round34(x)
  if (cmp(absDec(r), MAX_ABS) >= 0) throw new ExpressionError('EXPR_NUMBER_RANGE', pos, 'A result must stay below 10^24.')
  return r
}

const codePoints = (s: string) => Array.from(s)

function text(s: string, pos: number): string {
  if (s.length > MAX_TEXT && codePoints(s).length > MAX_TEXT) {
    throw new ExpressionError('EXPR_TEXT_LIMIT', pos, `A text result can be at most ${MAX_TEXT} characters long.`)
  }
  return s
}

export function equal(a: Value, b: Value): boolean {
  if (a === null || b === null) return a === b
  if (a instanceof Dec && b instanceof Dec) return cmp(a, b) === 0
  if (a instanceof ExprDate && b instanceof ExprDate) return a.epoch === b.epoch
  if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((x, i) => equal(x, b[i]!))
  if (isRowValue(a) && isRowValue(b)) {
    if (a.size !== b.size) return false
    for (const [k, x] of a) if (!b.has(k) || !equal(x, b.get(k)!)) return false
    return true
  }
  return (typeof a === 'string' || typeof a === 'boolean') && a === b
}

/** The sign of a - b for two numbers, texts (UTF-16 order) or dates; null otherwise. */
export function compare(a: Value, b: Value): number | null {
  if (a instanceof Dec && b instanceof Dec) return cmp(a, b)
  if (typeof a === 'string' && typeof b === 'string') return a < b ? -1 : a > b ? 1 : 0
  if (a instanceof ExprDate && b instanceof ExprDate) return Math.sign(a.epoch - b.epoch)
  return null
}

const bool = (v: Value): boolean | null => (typeof v === 'boolean' ? v : null)
const listOf = (v: Value): Value[] | null => (v === null ? [] : Array.isArray(v) ? v : null)
const decOfInt = (n: number) => new Dec(BigInt(n), 0)

/** Removes spaces, tabs and line breaks (only these, as in Java) from both ends. */
export function trimText(s: string): string {
  let from = 0
  let to = s.length
  while (from < to && ' \t\n\r'.includes(s[from]!)) from++
  while (to > from && ' \t\n\r'.includes(s[to - 1]!)) to--
  return s.slice(from, to)
}

const lower = (s: string) => s.toLowerCase()
const upper = (s: string) => s.toUpperCase()

export class Evaluator {
  private readonly items: Value[] = []
  private cost = 0
  private readonly types: Map<Ast, ExprType>
  private readonly values: Values
  constructor(types: Map<Ast, ExprType>, values: Values) {
    this.types = types
    this.values = values
  }

  private calendar(): WorkingCalendar {
    return this.values.calendar ?? DEFAULT_CALENDAR
  }

  eval(node: Ast): Value {
    if (++this.cost > MAX_COST) throw new ExpressionError('EXPR_COST_LIMIT', node.pos, 'This expression does too much work for the data it was given.')
    switch (node.t) {
      case 'num': return node.value
      case 'str': return node.value
      case 'bool': return node.value
      case 'null': return null
      case 'list': return node.items.map(item => this.eval(item))
      case 'ref':
        switch (node.kind) {
          case 'input': return convert(this.values.input(node.key!), this.types.get(node)!, node.pos)
          case 'step': return convert(this.values.step(node.step!, node.key!), this.types.get(node)!, node.pos)
          case 'local': return convert(this.values.local?.(node.key!), this.types.get(node)!, node.pos)
          default: return this.items[this.items.length - 1] ?? null
        }
      case 'member': {
        const target = this.eval(node.target)
        return isRowValue(target) ? (target.get(node.name) ?? null) : null
      }
      case 'neg': {
        const x = this.eval(node.operand)
        return x instanceof Dec ? num(neg(x), node.pos) : null
      }
      case 'binary': return this.binary(node)
      case 'if': return this.eval(node.condition) === true ? this.eval(node.then) : this.eval(node.otherwise)
      case 'call': return this.call(node)
    }
  }

  private binary(b: Extract<Ast, { t: 'binary' }>): Value {
    if (b.op === 'and') {
      const l = bool(this.eval(b.left))
      if (l === false) return false
      const r = bool(this.eval(b.right))
      if (r === false) return false
      return l === null || r === null ? null : true
    }
    if (b.op === 'or') {
      const l = bool(this.eval(b.left))
      if (l === true) return true
      const r = bool(this.eval(b.right))
      if (r === true) return true
      return l === null || r === null ? null : false
    }
    const l = this.eval(b.left)
    const r = this.eval(b.right)
    switch (b.op) {
      case '+':
        if (l instanceof Dec && r instanceof Dec) return num(add(l, r), b.pos)
        if (typeof l === 'string' && typeof r === 'string') return text(l + r, b.pos)
        return null
      case '-': return l instanceof Dec && r instanceof Dec ? num(sub(l, r), b.pos) : null
      case '*': return l instanceof Dec && r instanceof Dec ? num(mul(l, r), b.pos) : null
      case '/': {
        if (!(l instanceof Dec) || !(r instanceof Dec) || isZero(r)) return null
        return num(div(l, r)!, b.pos)
      }
      case '=': return equal(l, r)
      case '!=': return !equal(l, r)
      case '<': case '<=': case '>': case '>=': {
        const c = compare(l, r)
        if (c === null) return null
        return b.op === '<' ? c < 0 : b.op === '<=' ? c <= 0 : b.op === '>' ? c > 0 : c >= 0
      }
      case 'in': {
        if (l === null || !Array.isArray(r)) return null
        return r.some(item => equal(l, item))
      }
    }
  }

  private each(expression: Ast, item: Value): Value {
    this.items.push(item)
    try {
      return this.eval(expression)
    } finally {
      this.items.pop()
    }
  }

  private call(c: Extract<Ast, { t: 'call' }>): Value {
    const a = c.args
    const n = c.name
    switch (n) {
      case 'count': case 'sum': case 'average': case 'filter': case 'any': case 'all': {
        const list = listOf(this.eval(a[0]!))
        if (list === null) return null
        const per = a.length === 2 ? a[1]! : null
        let total = ZERO
        let counted = 0
        const kept: Value[] = []
        for (const item of list) {
          const v = per === null ? item : this.each(per, item)
          if (n === 'count') {
            if (per === null || v === true) counted++
          } else if (n === 'sum' || n === 'average') {
            if (v instanceof Dec) {
              total = num(add(total, v), c.pos)
              counted++
            }
          } else if (n === 'filter') {
            if (v === true) kept.push(item)
          } else if (n === 'any') {
            if (v === true) return true
          } else if (v !== true) {
            return false
          }
        }
        if (n === 'count') return decOfInt(counted)
        if (n === 'sum') return total
        if (n === 'average') return counted === 0 ? null : num(div(total, decOfInt(counted))!, c.pos)
        if (n === 'filter') return kept
        return n !== 'any'
      }
      case 'min': case 'max': {
        const listMode = a.length === 1 || isList(this.types.get(a[0]!)!)
        const candidates: Value[] = []
        if (listMode) {
          const list = listOf(this.eval(a[0]!))
          if (list === null) return null
          for (const item of list) candidates.push(a.length === 2 ? this.each(a[1]!, item) : item)
        } else {
          for (const arg of a) candidates.push(this.eval(arg))
        }
        let best: Value = null
        for (const v of candidates) {
          if (v === null) continue
          if (best === null) {
            best = v
            continue
          }
          const k = compare(v, best)
          if (k !== null && (n === 'min' ? k < 0 : k > 0)) best = v
        }
        return best
      }
      case 'coalesce': {
        for (const arg of a) {
          const v = this.eval(arg)
          if (v !== null) return v
        }
        return null
      }
      default:
        break
    }
    const v = a.map(arg => this.eval(arg))
    const x = v.length === 0 ? null : v[0]!
    switch (n) {
      case 'abs': return x instanceof Dec ? num(absDec(x), c.pos) : null
      case 'floor': return x instanceof Dec ? num(setScale(x, 0, 'floor'), c.pos) : null
      case 'ceiling': return x instanceof Dec ? num(setScale(x, 0, 'ceiling'), c.pos) : null
      case 'round': {
        const places = whole(v[1])
        if (!(x instanceof Dec) || places === null || places < 0 || places > 20) return null
        const mode = (v.length === 3 ? v[2] : 'halfUp') as RoundingMode
        return num(setScale(x, places, mode), c.pos)
      }
      case 'number': return typeof x === 'string' && NUMBER_TEXT.test(x) ? num(parseDec(x)!, c.pos) : null
      case 'length': return typeof x === 'string' ? decOfInt(codePoints(x).length) : null
      case 'upper': return typeof x === 'string' ? text(upper(x), c.pos) : null
      case 'lower': return typeof x === 'string' ? text(lower(x), c.pos) : null
      case 'trim': return typeof x === 'string' ? trimText(x) : null
      case 'substring': {
        const start = whole(v[1])
        const length = v.length === 3 ? whole(v[2]) : null
        if (typeof x !== 'string' || start === null || start < 1 || (v.length === 3 && (length === null || length < 0))) return null
        const cps = codePoints(x)
        const from = Math.min(start - 1, cps.length)
        const to = length === null ? cps.length : Math.min(from + length, cps.length)
        return cps.slice(from, to).join('')
      }
      case 'contains': case 'startsWith': case 'endsWith': {
        const t = v[1]
        if (typeof x !== 'string' || typeof t !== 'string') return null
        return n === 'contains' ? x.includes(t) : n === 'startsWith' ? x.startsWith(t) : x.endsWith(t)
      }
      case 'replace': {
        const find = v[1]
        const by = v[2]
        if (typeof x !== 'string' || typeof find !== 'string' || typeof by !== 'string') return null
        return find === '' ? x : text(x.split(find).join(by), c.pos)
      }
      case 'string':
        if (x instanceof Dec) return plain(x)
        if (x instanceof ExprDate) return x.toString()
        if (typeof x === 'boolean') return String(x)
        return typeof x === 'string' ? x : null
      case 'date': {
        if (v.length === 1) return typeof x === 'string' ? parseDate(x) : null
        const y = whole(v[0])
        const m = whole(v[1])
        const d = whole(v[2])
        return y === null || m === null || d === null ? null : makeDate(y, m, d)
      }
      case 'today': {
        const now = this.values.now instanceof Date ? this.values.now : new Date(this.values.now)
        return dateIn(now, this.calendar().zone)
      }
      case 'addDays': case 'addMonths': case 'addYears': case 'addWorkingDays': {
        const k = whole(v[1])
        if (!(x instanceof ExprDate) || k === null) return null
        const bound = n === 'addWorkingDays' ? 1000 : 1_000_000
        if (Math.abs(k) > bound) return null
        if (n === 'addDays') return plusDays(x, k)
        if (n === 'addMonths') return plusMonths(x, k)
        if (n === 'addYears') return plusYears(x, k)
        return plusWorkingDays(this.calendar(), x, k)
      }
      case 'daysBetween': case 'workingDaysBetween': {
        const to = v[1]
        if (!(x instanceof ExprDate) || !(to instanceof ExprDate)) return null
        if (n === 'daysBetween') return decOfInt(to.epoch - x.epoch)
        const days = workingDaysBetween(this.calendar(), x, to)
        return days === null ? null : decOfInt(days)
      }
      case 'year': case 'month': case 'day': case 'weekday': {
        if (!(x instanceof ExprDate)) return null
        return decOfInt(n === 'year' ? x.y : n === 'month' ? x.m : n === 'day' ? x.d : x.weekday)
      }
      case 'isWorkingDay': return x instanceof ExprDate ? isWorkingDay(this.calendar(), x) : null
      case 'isEmpty': return x === null || (typeof x === 'string' && trimText(x) === '') || (Array.isArray(x) && x.length === 0)
      case 'not': {
        const b = bool(x)
        return b === null ? null : !b
      }
      default:
        throw new Error(`Unchecked function ${n}`)
    }
  }
}
