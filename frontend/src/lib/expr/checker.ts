/**
 * Static type checker of Orqix expressions (docs/build/S137): a line-by-line port of
 * backend/compiler/src/main/java/com/orqix/compiler/expr/Checker.java.
 */
import { type Ast } from './parser'
import { MAX_ARGS } from './parser'
import { type ExprType, type Scope, describeType, ExpressionError, isList, isRow, isScalar, typeEquals } from './types'

export const ROUNDING = ['halfUp', 'halfEven', 'down', 'up', 'floor', 'ceiling'] as const

const loose = (t: ExprType) => t === 'null' || t === 'any'
const fits = (actual: ExprType, expected: ExprType) => loose(actual) || typeEquals(actual, expected) || expected === 'any'
const orderable = (t: ExprType) => t === 'number' || t === 'string' || t === 'date'

function comparable(a: ExprType, b: ExprType): boolean {
  if (loose(a) || loose(b)) return !isRow(a) && !isRow(b)
  if (isList(a) && isList(b)) return comparable(a.list, b.list)
  return isScalar(a) && a === b
}

export function join(a: ExprType, b: ExprType): ExprType | null {
  if (a === 'null' || a === 'any') return b
  if (b === 'null' || b === 'any') return a
  if (typeEquals(a, b)) return a
  if (isList(a) && isList(b)) {
    const e = join(a.list, b.list)
    return e === null ? null : { list: e }
  }
  return null
}

const SYMBOL: Record<string, string> = { '+': '+', '-': '-', '*': '*', '/': '/', '=': '=', '!=': '!=', '<': '<', '<=': '<=', '>': '>', '>=': '>=', and: 'and', or: 'or', in: 'in' }

export class Checker {
  readonly types = new Map<Ast, ExprType>()
  readonly fieldRefs = new Set<string>()
  readonly locals = new Set<string>()
  private readonly items: ExprType[] = []
  private readonly scope: Scope
  constructor(scope: Scope) {
    this.scope = scope
  }

  check(node: Ast): ExprType {
    const t = this.type(node)
    this.types.set(node, t)
    return t
  }

  private type(node: Ast): ExprType {
    switch (node.t) {
      case 'num': return 'number'
      case 'str': return 'string'
      case 'bool': return 'boolean'
      case 'null': return 'null'
      case 'list': {
        let element: ExprType = 'any'
        for (const item of node.items) {
          const t = this.check(item)
          const joined = join(element, t)
          if (joined === null) throw new ExpressionError('EXPR_TYPE', item.pos, `A list holds values of one type: this is ${describeType(t)} after ${describeType(element)}.`)
          element = joined
        }
        return { list: element }
      }
      case 'ref': return this.ref(node)
      case 'member': {
        const target = this.check(node.target)
        if (isRow(target)) {
          if (!Object.hasOwn(target.row, node.name)) throw new ExpressionError('EXPR_UNKNOWN_FIELD', node.pos, `This row has no column named ${node.name}.`)
          return target.row[node.name]!
        }
        if (loose(target)) return target
        if (isList(target) && isRow(target.list)) {
          throw new ExpressionError('EXPR_TYPE', node.pos, `This is a list of rows: use item.${node.name} inside sum, count, filter, any or all.`)
        }
        throw new ExpressionError('EXPR_TYPE', node.pos, `Only a row has columns; this is ${describeType(target)}.`)
      }
      case 'neg': {
        const t = this.check(node.operand)
        if (!fits(t, 'number')) throw new ExpressionError('EXPR_TYPE', node.pos, `Only a number can be negative; this is ${describeType(t)}.`)
        return 'number'
      }
      case 'binary': return this.binary(node)
      case 'if': {
        const c = this.check(node.condition)
        if (!fits(c, 'boolean')) throw new ExpressionError('EXPR_TYPE', node.condition.pos, `The condition after if must be true or false; this is ${describeType(c)}.`)
        const a = this.check(node.then)
        const e = this.check(node.otherwise)
        const joined = join(a, e)
        if (joined === null) throw new ExpressionError('EXPR_TYPE', node.otherwise.pos, `Both results of if must have one type: ${describeType(a)} and ${describeType(e)}.`)
        return joined
      }
      case 'call': return this.call(node)
    }
  }

  private ref(r: Extract<Ast, { t: 'ref' }>): ExprType {
    switch (r.kind) {
      case 'input': {
        const t = this.scope.input(r.key!)
        if (t == null) throw new ExpressionError('EXPR_UNKNOWN_FIELD', r.pos, `There is no request field named ${r.key}.`)
        this.fieldRefs.add(`/input/${r.key}`)
        return t
      }
      case 'step': {
        const t = this.scope.step(r.step!, r.key!)
        if (t == null) throw new ExpressionError('EXPR_UNKNOWN_FIELD', r.pos, `Step ${r.step} has no output named ${r.key} available here.`)
        this.fieldRefs.add(`/steps/${r.step}/${r.key}`)
        return t
      }
      case 'item': {
        if (this.items.length === 0) throw new ExpressionError('EXPR_UNKNOWN_FIELD', r.pos, 'item can only be used inside count, sum, average, min, max, filter, any and all.')
        return this.items[this.items.length - 1]!
      }
      default: {
        const t = this.scope.local?.(r.key!)
        if (t == null) throw new ExpressionError('EXPR_UNKNOWN_FIELD', r.pos, `There is no field or earlier output named ${r.key}; request fields start with input.`)
        this.locals.add(r.key!)
        return t
      }
    }
  }

  private binary(b: Extract<Ast, { t: 'binary' }>): ExprType {
    const l = this.check(b.left)
    const r = this.check(b.right)
    const both = `${describeType(l)} and ${describeType(r)}`
    switch (b.op) {
      case '+':
        if (fits(l, 'number') && fits(r, 'number')) return 'number'
        if (fits(l, 'string') && fits(r, 'string')) return 'string'
        throw new ExpressionError('EXPR_TYPE', b.pos, `+ adds two numbers or joins two texts; here it gets ${both}.`)
      case '-': case '*': case '/':
        if (fits(l, 'number') && fits(r, 'number')) return 'number'
        throw new ExpressionError('EXPR_TYPE', b.pos, `${SYMBOL[b.op]} works on two numbers; here it gets ${both}. Use addDays or daysBetween for dates.`)
      case '=': case '!=':
        if (comparable(l, r)) return 'boolean'
        throw new ExpressionError('EXPR_TYPE', b.pos, `${b.op} compares two values of one type; here it gets ${both}.`)
      case '<': case '<=': case '>': case '>=': {
        const ok = (loose(l) && (loose(r) || orderable(r))) || (orderable(l) && (loose(r) || typeEquals(l, r)))
        if (ok) return 'boolean'
        throw new ExpressionError('EXPR_TYPE', b.pos, `${b.op} compares two numbers, two texts or two dates; here it gets ${both}.`)
      }
      case 'and': case 'or':
        if (fits(l, 'boolean') && fits(r, 'boolean')) return 'boolean'
        throw new ExpressionError('EXPR_TYPE', b.pos, `${b.op} joins two true-or-false values; here it gets ${both}.`)
      case 'in':
        if ((isList(r) && comparable(l, r.list)) || (loose(r) && !isRow(l))) return 'boolean'
        throw new ExpressionError('EXPR_TYPE', b.pos, `in looks for a value in a list of the same type; here it gets ${both}.`)
    }
  }

  private static need(c: Extract<Ast, { t: 'call' }>, index: number, actual: ExprType, expected: ExprType): void {
    if (!fits(actual, expected)) {
      throw new ExpressionError('EXPR_TYPE', c.args[index]!.pos, `${c.name} needs ${describeType(expected)} here, not ${describeType(actual)}.`)
    }
  }

  private static count(c: Extract<Ast, { t: 'call' }>, min: number, max: number, spec: string): void {
    const n = c.args.length
    if (n < min || n > max) throw new ExpressionError('EXPR_ARGUMENTS', c.pos, `${c.name} takes ${spec}.`)
  }

  private static element(c: Extract<Ast, { t: 'call' }>, t: ExprType): ExprType {
    if (isList(t)) return t.list
    if (loose(t)) return 'any'
    throw new ExpressionError('EXPR_TYPE', c.args[0]!.pos, `${c.name} needs a list here, not ${describeType(t)}.`)
  }

  private perItem(c: Extract<Ast, { t: 'call' }>, element: ExprType): ExprType {
    this.items.push(element)
    try {
      return this.check(c.args[1]!)
    } finally {
      this.items.pop()
    }
  }

  private call(c: Extract<Ast, { t: 'call' }>): ExprType {
    const a = c.args
    const n = c.name
    const need = Checker.need
    const count = Checker.count
    const arity = (spec: string) => new ExpressionError('EXPR_ARGUMENTS', c.pos, `${n} takes ${spec}.`)
    switch (n) {
      case 'count': case 'sum': case 'average': case 'filter': case 'any': case 'all': {
        const predicate = n === 'filter' || n === 'any' || n === 'all'
        if (predicate) count(c, 2, 2, 'a list and a condition')
        else count(c, 1, 2, 'a list and, optionally, a value for each item')
        const element = Checker.element(c, this.check(a[0]!))
        if (a.length === 2) {
          const each = this.perItem(c, element)
          need(c, 1, each, predicate || n === 'count' ? 'boolean' : 'number')
        } else if (n !== 'count' && !fits(element, 'number')) {
          throw new ExpressionError('EXPR_TYPE', a[0]!.pos, `${n} needs a list of numbers here, not ${describeType({ list: element })}.`)
        }
        if (n === 'filter') {
          const t = this.types.get(a[0]!)!
          return isList(t) ? t : { list: element }
        }
        return n === 'any' || n === 'all' ? 'boolean' : 'number'
      }
      case 'min': case 'max': {
        count(c, 1, MAX_ARGS, 'a list, a list and a value for each item, or 2 to 10 values')
        const first = this.check(a[0]!)
        if (a.length === 1 || isList(first)) {
          if (a.length > 2) throw arity('a list and, optionally, a value for each item')
          const element = Checker.element(c, first)
          const each = a.length === 2 ? this.perItem(c, element) : element
          if (!loose(each) && !orderable(each)) throw new ExpressionError('EXPR_TYPE', a[a.length - 1]!.pos, `${n} needs numbers, texts or dates, not ${describeType(each)}.`)
          return loose(each) ? 'any' : each
        }
        let result = first
        for (let i = 1; i < a.length; i++) {
          const t = this.check(a[i]!)
          const joined = join(result, t)
          if (joined === null) throw new ExpressionError('EXPR_TYPE', a[i]!.pos, `${n} compares values of one type: ${describeType(result)} and ${describeType(t)}.`)
          result = joined
        }
        if (!loose(result) && !orderable(result)) throw new ExpressionError('EXPR_TYPE', a[0]!.pos, `${n} needs numbers, texts or dates, not ${describeType(result)}.`)
        return result
      }
      case 'abs': case 'floor': case 'ceiling':
        count(c, 1, 1, 'one number')
        need(c, 0, this.check(a[0]!), 'number')
        return 'number'
      case 'round': {
        count(c, 2, 3, 'a number, the decimal places and, optionally, a rounding mode')
        need(c, 0, this.check(a[0]!), 'number')
        need(c, 1, this.check(a[1]!), 'number')
        if (a.length === 3) {
          this.check(a[2]!)
          const mode = a[2]!
          if (mode.t !== 'str' || !(ROUNDING as readonly string[]).includes(mode.value)) {
            throw new ExpressionError('EXPR_ARGUMENTS', mode.pos, `The rounding mode must be one of "${ROUNDING.join('", "')}".`)
          }
        }
        return 'number'
      }
      case 'number':
        count(c, 1, 1, 'one text')
        need(c, 0, this.check(a[0]!), 'string')
        return 'number'
      case 'length':
        count(c, 1, 1, 'one text')
        need(c, 0, this.check(a[0]!), 'string')
        return 'number'
      case 'upper': case 'lower': case 'trim':
        count(c, 1, 1, 'one text')
        need(c, 0, this.check(a[0]!), 'string')
        return 'string'
      case 'substring':
        count(c, 2, 3, 'a text, the first position (from 1) and, optionally, a length')
        need(c, 0, this.check(a[0]!), 'string')
        need(c, 1, this.check(a[1]!), 'number')
        if (a.length === 3) need(c, 2, this.check(a[2]!), 'number')
        return 'string'
      case 'contains': case 'startsWith': case 'endsWith':
        count(c, 2, 2, 'two texts')
        need(c, 0, this.check(a[0]!), 'string')
        need(c, 1, this.check(a[1]!), 'string')
        return 'boolean'
      case 'replace':
        count(c, 3, 3, 'a text, the text to find and its replacement')
        for (let i = 0; i < 3; i++) need(c, i, this.check(a[i]!), 'string')
        return 'string'
      case 'string': {
        count(c, 1, 1, 'one value')
        const t = this.check(a[0]!)
        if (!isScalar(t)) throw new ExpressionError('EXPR_TYPE', a[0]!.pos, `string needs a single value, not ${describeType(t)}.`)
        return 'string'
      }
      case 'date':
        if (a.length === 1) need(c, 0, this.check(a[0]!), 'string')
        else if (a.length === 3) for (let i = 0; i < 3; i++) need(c, i, this.check(a[i]!), 'number')
        else throw arity('a text like "2026-03-31" or a year, month and day')
        return 'date'
      case 'today':
        count(c, 0, 0, 'no values')
        return 'date'
      case 'addDays': case 'addMonths': case 'addYears': case 'addWorkingDays':
        count(c, 2, 2, 'a date and a whole number')
        need(c, 0, this.check(a[0]!), 'date')
        need(c, 1, this.check(a[1]!), 'number')
        return 'date'
      case 'daysBetween': case 'workingDaysBetween':
        count(c, 2, 2, 'two dates')
        need(c, 0, this.check(a[0]!), 'date')
        need(c, 1, this.check(a[1]!), 'date')
        return 'number'
      case 'year': case 'month': case 'day': case 'weekday':
        count(c, 1, 1, 'one date')
        need(c, 0, this.check(a[0]!), 'date')
        return 'number'
      case 'isWorkingDay':
        count(c, 1, 1, 'one date')
        need(c, 0, this.check(a[0]!), 'date')
        return 'boolean'
      case 'coalesce': {
        count(c, 2, MAX_ARGS, '2 to 10 values')
        let result: ExprType = 'null'
        for (const arg of a) {
          const t = this.check(arg)
          const joined = join(result, t)
          if (joined === null) throw new ExpressionError('EXPR_TYPE', arg.pos, `coalesce needs values of one type: ${describeType(result)} and ${describeType(t)}.`)
          result = joined
        }
        return result
      }
      case 'isEmpty':
        count(c, 1, 1, 'one value')
        this.check(a[0]!)
        return 'boolean'
      case 'not':
        count(c, 1, 1, 'one true-or-false value')
        need(c, 0, this.check(a[0]!), 'boolean')
        return 'boolean'
      default:
        throw new ExpressionError('EXPR_UNKNOWN_FUNCTION', c.pos, `There is no function named ${n}.`)
    }
  }
}
