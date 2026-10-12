import { conditionTerms, isAtomicCondition, type Condition } from '@/features/studio/model/types'
import { MONEY_PATTERN } from '../money'
import type { FieldDef } from './schema'
import type { RawValues } from './values'

/** Conditions see the same scalar values submission converts. Empty or malformed controllers never match. */
export function visibleFields(fields: FieldDef[], raw: RawValues): FieldDef[] {
  const actual = (ref: string): string | number | boolean | undefined => {
    const field = fields.find(f => `/input/${f.key}` === ref)
    if (!field || field.schema.type === 'array' || field.schema.type === 'object') return
    const value = raw[field.key]
    if (field.schema.type === 'boolean') return typeof value === 'boolean' ? value : false
    if (typeof value !== 'string' || value === '') return
    if (field.schema.type === 'string') return value
    const text = value.trim()
    const pattern = field.isMoney ? MONEY_PATTERN : field.schema.type === 'integer' ? /^-?\d+$/ : /^-?\d+(\.\d+)?$/
    if (pattern.test(text) && Number.isFinite(Number(text))) return Number(text)
  }
  const matches = (condition: Condition): boolean => {
    if (!isAtomicCondition(condition)) return 'all' in condition ? conditionTerms(condition).every(matches) : conditionTerms(condition).some(matches)
    const value = actual(condition.fieldRef), expected = condition.value
    if (value === undefined || typeof value !== typeof expected) return false
    if (condition.operator === 'eq') return value === expected
    if (condition.operator === 'ne') return value !== expected
    if (typeof value !== 'number' || typeof expected !== 'number') return false
    return condition.operator === 'gt' ? value > expected : condition.operator === 'gte' ? value >= expected : condition.operator === 'lt' ? value < expected : value <= expected
  }
  return fields.filter(f => (f.visibleWhen ?? []).every(matches)).map(f => ({ ...f, required: f.required || !!(f.requiredWhen && matches(f.requiredWhen)) }))
}
