/**
 * A structured condition written as the equivalent Orqix expression (docs/build/S137), so a designer who switches a
 * path to a formula starts from what the path already tests.
 */
import { refToExpression } from './expressionScope'
import { conditionTerms, isExpressionCondition, isRangeValue, type AtomicCondition, type Condition, type Literal } from './types'

const OPERATOR: Record<string, string> = { eq: '=', ne: '!=', gt: '>', gte: '>=', lt: '<', lte: '<=' }

const literal = (v: Literal) => (typeof v === 'string' ? JSON.stringify(v) : String(v))

function atom(c: AtomicCondition): string {
  if (!c.fieldRef) return 'true'
  const left = refToExpression(c.fieldRef)
  if (c.operator === 'in' && Array.isArray(c.value)) return `${left} in [${c.value.map(literal).join(', ')}]`
  if (c.operator === 'between' && isRangeValue(c.value)) {
    const r = c.value
    return `${left} ${r.includeFrom === false ? '>' : '>='} ${r.from} and ${left} ${r.includeTo === false ? '<' : '<='} ${r.to}`
  }
  return `${left} ${OPERATOR[c.operator] ?? '='} ${literal(c.value as Literal)}`
}

export function conditionToExpression(c: Condition): string {
  if (isExpressionCondition(c)) return c.expression
  const terms = conditionTerms(c)
  if ('all' in c || 'any' in c) return terms.map(atom).join('all' in c ? ' and ' : ' or ')
  return atom(c as AtomicCondition)
}
