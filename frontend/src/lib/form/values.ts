import { MONEY_PATTERN, formatUsd } from '../money'
import type { FieldDef } from './schema'
import { buildFields, type FormSchema, type FormUi } from './schema'
import { validateJsonInput, type IssueKeyword } from './validate'

/** What the user typed. Money and numbers stay strings until submission. */
export type RawValue = string | boolean
export type RawValues = Record<string, RawValue>
export type FieldErrors = Record<string, string>

export const NUMBER_PATTERN = /^-?\d+(\.\d+)?$/
export const INTEGER_PATTERN = /^-?\d+$/

export function emptyValues(fields: FieldDef[]): RawValues {
  const values: RawValues = {}
  for (const f of fields) values[f.key] = f.schema.type === 'boolean' ? false : ''
  return values
}

/** JSON input -> typed text, e.g. for restoring values or showing an existing request in a form. */
export function valuesFromJson(fields: FieldDef[], input: Record<string, unknown> | null | undefined): RawValues {
  const values = emptyValues(fields)
  if (!input) return values
  for (const f of fields) {
    const v = input[f.key]
    if (v === undefined || v === null) continue
    if (f.schema.type === 'boolean') values[f.key] = v === true
    else values[f.key] = typeof v === 'string' ? v : String(v)
  }
  return values
}

/** Keep the values of keys that still exist with a compatible type (RELEASE_NOT_ACTIVE reload). */
export function carryOverValues(previous: RawValues, fields: FieldDef[]): RawValues {
  const next = emptyValues(fields)
  for (const f of fields) {
    const prev = previous[f.key]
    if (prev === undefined) continue
    if (f.schema.type === 'boolean') {
      if (typeof prev === 'boolean') next[f.key] = prev
    } else if (typeof prev === 'string') {
      if (f.schema.enum && prev !== '' && !f.schema.enum.includes(prev)) continue
      next[f.key] = prev
    }
  }
  return next
}

function formatMessage(f: FieldDef, typed: string): string {
  if (f.isMoney) {
    if (/^\d+\.\d{3,}$/.test(typed)) return 'Use at most two decimal places (cents).'
    if (/^\d{10,}/.test(typed)) return 'Enter at most 9 digits before the decimal point.'
    return 'Enter an amount in US dollars, for example 1800 or 1800.50. Do not use commas or symbols.'
  }
  if (f.schema.type === 'integer') return 'Enter a whole number, for example 12.'
  return 'Enter a number, for example 12.5.'
}

export interface BuildResult {
  /** JSON-typed request body built from the typed text. */
  payload: Record<string, unknown>
  /** Errors for values that could not even be converted (wrong text shape). */
  parseErrors: FieldErrors
}

/**
 * Convert typed text to JSON values. Money is converted to a JSON number here and only here:
 * Number(text) of a string already proven to match ^\d{1,9}(\.\d{1,2})?$ - no arithmetic involved.
 */
export function buildPayload(fields: FieldDef[], raw: RawValues): BuildResult {
  const payload: Record<string, unknown> = {}
  const parseErrors: FieldErrors = {}
  for (const f of fields) {
    const value = raw[f.key]
    switch (f.schema.type) {
      case 'boolean':
        payload[f.key] = value === true
        break
      case 'string': {
        const text = typeof value === 'string' ? value : ''
        if (text !== '') payload[f.key] = text
        break
      }
      case 'number':
      case 'integer': {
        const text = (typeof value === 'string' ? value : '').trim()
        if (text === '') break
        const pattern = f.isMoney ? MONEY_PATTERN : f.schema.type === 'integer' ? INTEGER_PATTERN : NUMBER_PATTERN
        if (!pattern.test(text)) {
          parseErrors[f.key] = formatMessage(f, text)
          break
        }
        payload[f.key] = Number(text)
        break
      }
    }
  }
  return { payload, parseErrors }
}

function messageFor(f: FieldDef, keyword: IssueKeyword, typedLength: number): string {
  const s = f.schema
  switch (keyword) {
    case 'required':
      if (f.widget === 'select' || f.widget === 'radio') return 'Choose an option.'
      if (f.widget === 'checkbox') return 'Confirm this to continue.'
      return 'This field is required.'
    case 'type':
      return formatMessage(f, '')
    case 'enum':
      return 'Choose one of the listed options.'
    case 'minLength':
      return `Use at least ${s.minLength} character${s.minLength === 1 ? '' : 's'}.`
    case 'maxLength':
      return `Use at most ${s.maxLength} characters (you used ${typedLength}).`
    case 'minimum':
      return f.isMoney ? `Enter at least ${formatUsd(s.minimum)}.` : `Enter ${s.minimum} or more.`
    case 'maximum':
      return f.isMoney ? `Enter ${formatUsd(s.maximum)} or less.` : `Enter ${s.maximum} or less.`
    case 'multipleOf':
      return s.multipleOf === 1 ? 'Enter a whole number.' : 'Use at most two decimal places (cents).'
    case 'additionalProperties':
      return 'This field is not part of the form.'
  }
}

export interface ValidationResult {
  payload: Record<string, unknown>
  errors: FieldErrors
  valid: boolean
}

/** Client-side validation. It only assists: the server answers with its own fieldErrors on submit. */
export function validateForm(schema: FormSchema, ui: FormUi, raw: RawValues): ValidationResult {
  const fields = buildFields(schema, ui)
  const { payload, parseErrors } = buildPayload(fields, raw)
  const errors: FieldErrors = { ...parseErrors }
  // Do not validate values that failed to parse: they are already reported, and absent keys would add "required".
  const checked = { ...payload }
  const issues = validateJsonInput(schema, checked)
  for (const issue of issues) {
    const key = issue.pointer.split('/').filter(Boolean)[0]
    if (!key || key in errors) continue
    const field = fields.find((f) => f.key === key)
    if (!field) continue
    if (issue.keyword === 'required' && key in parseErrors) continue
    const raw0 = raw[key]
    errors[key] = messageFor(field, issue.keyword, typeof raw0 === 'string' ? [...raw0].length : 0)
  }
  return { payload, errors, valid: Object.keys(errors).length === 0 }
}
