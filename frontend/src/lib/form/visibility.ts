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
  return fieldsForInput(fields, Object.fromEntries(fields.map(field => [field.key, actual(`/input/${field.key}`)])))
}

/** Server-shaped input validation keeps wrong JSON types wrong instead of coercing them into controllers. */
export function fieldsForInput(fields: FieldDef[], input: Record<string, unknown>): FieldDef[] {
  const actual = (ref: string): string | number | boolean | undefined => {
    const field = fields.find(f => `/input/${f.key}` === ref)
    if (!field || field.schema.type === 'array' || field.schema.type === 'object') return
    const value = input[field.key]
    if (field.schema.type === 'boolean') return typeof value === 'boolean' ? value : undefined
    if (field.schema.type === 'string') return typeof value === 'string' ? value : undefined
    return typeof value === 'number' && Number.isFinite(value) && (field.schema.type !== 'integer' || Number.isInteger(value)) ? value : undefined
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
