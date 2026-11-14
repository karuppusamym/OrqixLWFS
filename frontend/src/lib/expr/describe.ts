/**
 * Plain-language reading of an Orqix expression (docs/build/S137) for the guided editor: "Amount times 1.2, rounded to
 * 2 decimal places". Business words only; it never changes what the expression means.
 */
import { plain } from './decimal'
import { type Ast, type Op, parse } from './parser'

export interface Labels {
  /** The title of a request field, e.g. "Amount (USD)". */
  input?(key: string): string | undefined
  /** The label of a step output, e.g. "Check credit: score". */
  step?(stepId: string, key: string): string | undefined
}

const OPS: Record<Op, string> = {
  '+': 'plus', '-': 'minus', '*': 'times', '/': 'divided by', '=': 'is', '!=': 'is not', '<': 'is less than',
  '<=': 'is at most', '>': 'is more than', '>=': 'is at least', and: 'and', or: 'or', in: 'is one of',
}

const RANK: Record<Op, number> = { or: 1, and: 2, '=': 3, '!=': 3, '<': 3, '<=': 3, '>': 3, '>=': 3, in: 3, '+': 4, '-': 4, '*': 5, '/': 5 }

const humanize = (key: string) => key.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/^./, c => c.toUpperCase())

function words(node: Ast, labels: Labels, item: string | null): string {
  const w = (n: Ast) => words(n, labels, item)
  const nested = (n: Ast, rank: number) => (n.t === 'binary' && RANK[n.op] < rank ? `(${w(n)})` : w(n))
  switch (node.t) {
    case 'num': return plain(node.value)
    case 'str': return `“${node.value}”`
    case 'bool': return node.value ? 'yes' : 'no'
    case 'null': return 'nothing'
    case 'list': return node.items.length === 0 ? 'an empty list' : node.items.map(w).join(', ')
    case 'ref':
      if (node.kind === 'input') return labels.input?.(node.key!) ?? humanize(node.key!)
      if (node.kind === 'step') return labels.step?.(node.step!, node.key!) ?? `${node.step}: ${humanize(node.key!)}`
      if (node.kind === 'item') return item ?? 'the item'
      return humanize(node.key!)
    case 'member': return node.target.t === 'ref' && node.target.kind === 'item' ? `its ${humanize(node.name).toLowerCase()}` : `${w(node.target)}: ${humanize(node.name).toLowerCase()}`
    case 'neg': return `minus ${nested(node.operand, 6)}`
    case 'binary': {
      const rank = RANK[node.op]
      if (node.op === 'in') return `${nested(node.left, 4)} is one of ${w(node.right)}`
      return `${nested(node.left, rank)} ${OPS[node.op]} ${nested(node.right, rank + 1)}`
    }
    case 'if': return `if ${w(node.condition)} then ${w(node.then)}, otherwise ${w(node.otherwise)}`
    case 'call': return call(node, labels, item)
  }
}

function call(c: Extract<Ast, { t: 'call' }>, labels: Labels, item: string | null): string {
  const a = c.args
  const w = (n: Ast | undefined) => (n ? words(n, labels, item) : '')
  const each = (n: Ast | undefined) => (n ? words(n, labels, 'the item') : '')
  switch (c.name) {
    case 'round': {
      const mode = a[2]?.t === 'str' ? ({ halfUp: '', halfEven: ', halves to even', down: ', always down', up: ', always up', floor: ', down', ceiling: ', up' } as Record<string, string>)[a[2].value] ?? '' : ''
      return `${w(a[0])} rounded to ${w(a[1])} decimal places${mode}`
    }
    case 'abs': return `${w(a[0])} without its sign`
    case 'floor': return `${w(a[0])} rounded down`
    case 'ceiling': return `${w(a[0])} rounded up`
    case 'sum': return a.length === 2 ? `the total of ${each(a[1])} over ${w(a[0])}` : `the total of ${w(a[0])}`
    case 'average': return a.length === 2 ? `the average of ${each(a[1])} over ${w(a[0])}` : `the average of ${w(a[0])}`
    case 'count': return a.length === 2 ? `the number of ${w(a[0])} where ${each(a[1])}` : `the number of ${w(a[0])}`
    case 'min': case 'max': {
      const which = c.name === 'min' ? 'smallest' : 'largest'
      if (a.length === 2 && a[0]?.t === 'ref' && a[0].kind !== 'local') return `the ${which} ${each(a[1])} of ${w(a[0])}`
      return `the ${which} of ${a.map(w).join(', ')}`
    }
    case 'filter': return `the ${w(a[0])} where ${each(a[1])}`
    case 'any': return `whether any of ${w(a[0])} has ${each(a[1])}`
    case 'all': return `whether all of ${w(a[0])} have ${each(a[1])}`
    case 'today': return 'today'
    case 'date': return a.length === 1 ? `the date ${w(a[0])}` : `the date ${w(a[0])}-${w(a[1])}-${w(a[2])}`
    case 'addDays': return `${w(a[1])} days after ${w(a[0])}`
    case 'addMonths': return `${w(a[1])} months after ${w(a[0])}`
    case 'addYears': return `${w(a[1])} years after ${w(a[0])}`
    case 'addWorkingDays': return `${w(a[1])} working days after ${w(a[0])}`
    case 'daysBetween': return `the days from ${w(a[0])} to ${w(a[1])}`
    case 'workingDaysBetween': return `the working days from ${w(a[0])} to ${w(a[1])}`
    case 'isWorkingDay': return `whether ${w(a[0])} is a working day`
    case 'year': case 'month': case 'day': return `the ${c.name} of ${w(a[0])}`
    case 'weekday': return `the weekday of ${w(a[0])} (Monday is 1)`
    case 'coalesce': return a.map(w).join(', or when that is missing ')
    case 'isEmpty': return `whether ${w(a[0])} is empty`
    case 'not': return `not ${w(a[0])}`
    case 'length': return `the length of ${w(a[0])}`
    case 'upper': return `${w(a[0])} in capitals`
    case 'lower': return `${w(a[0])} in small letters`
    case 'trim': return `${w(a[0])} without surrounding spaces`
    case 'contains': return `whether ${w(a[0])} contains ${w(a[1])}`
    case 'startsWith': return `whether ${w(a[0])} starts with ${w(a[1])}`
    case 'endsWith': return `whether ${w(a[0])} ends with ${w(a[1])}`
    case 'substring': return a.length === 3 ? `${w(a[2])} characters of ${w(a[0])} from position ${w(a[1])}` : `${w(a[0])} from position ${w(a[1])}`
    case 'replace': return `${w(a[0])} with ${w(a[1])} replaced by ${w(a[2])}`
    case 'string': return `${w(a[0])} as text`
    case 'number': return `${w(a[0])} as a number`
    default: return `${c.name} of ${a.map(w).join(', ')}`
  }
}

/** @returns the plain-language reading, or null when the expression does not parse */
export function describe(source: string, labels: Labels = {}): string | null {
  try {
    const text = words(parse(source), labels, null)
    return text.charAt(0).toUpperCase() + text.slice(1)
  } catch {
    return null
  }
}
