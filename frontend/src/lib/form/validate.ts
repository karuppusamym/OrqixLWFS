import type { FormFieldSchema, FormSchema } from './schema'

/**
 * Interpretive validator for the approved form-schema subset.
 *
 * Why not ajv at runtime? ajv compiles schemas with `new Function(...)`, which the platform Content-Security-Policy
 * (`script-src 'self'`, docs/build/S6-security.md section 6) forbids. This validator walks the schema instead of compiling
 * it, so it is CSP-safe. It reproduces ajv (draft 2020-12, `multipleOfPrecision: 2`, `allErrors: true`) for exactly the
 * keywords the subset allows; `validate.parity.test.ts` runs both against the shared fixtures and generated cases.
 */

export type IssueKeyword =
  | 'type'
  | 'required'
  | 'additionalProperties'
  | 'enum'
  | 'minLength'
  | 'maxLength'
  | 'minimum'
  | 'maximum'
  | 'multipleOf'

export interface JsonIssue {
  /** JSON Pointer relative to the input object, e.g. "/amount". */
  pointer: string
  keyword: IssueKeyword
}

/** ajv's `multipleOfPrecision: 2` check: |round(d) - d| > 1e-2 means "not a multiple". */
export function isMultipleOf(value: number, multipleOf: number): boolean {
  if (multipleOf === 0) return true
  const quotient = value / multipleOf
  return !(Math.abs(Math.round(quotient) - quotient) > 1e-2)
}

/** Unicode code point length, as ajv counts string length. */
function codePointLength(text: string): number {
  let count = 0
  for (const _ of text) count++
  return count
}

function typeMatches(type: FormFieldSchema['type'], value: unknown): boolean {
  switch (type) {
    case 'string':
      return typeof value === 'string'
    case 'boolean':
      return typeof value === 'boolean'
    case 'number':
      return typeof value === 'number' && Number.isFinite(value)
    case 'integer':
      return typeof value === 'number' && Number.isInteger(value)
  }
}

function escapePointer(key: string): string {
  return key.replace(/~/g, '~0').replace(/\//g, '~1')
}

export function validateJsonInput(schema: FormSchema, data: unknown): JsonIssue[] {
  const issues: JsonIssue[] = []
  if (typeof data !== 'object' || data === null || Array.isArray(data)) {
    return [{ pointer: '', keyword: 'type' }]
  }
  const record = data as Record<string, unknown>

  for (const key of Object.keys(record)) {
    if (!(key in schema.properties)) {
      issues.push({ pointer: `/${escapePointer(key)}`, keyword: 'additionalProperties' })
    }
  }
  for (const key of schema.required) {
    if (!(key in record) || record[key] === undefined) {
      issues.push({ pointer: `/${escapePointer(key)}`, keyword: 'required' })
    }
  }
  for (const [key, field] of Object.entries(schema.properties)) {
    if (!(key in record) || record[key] === undefined) continue
    const value = record[key]
    const pointer = `/${escapePointer(key)}`
    const typeOk = typeMatches(field.type, value)
    if (!typeOk) issues.push({ pointer, keyword: 'type' })
    // ajv evaluates enum for any value; the keywords below only apply to strings and numbers respectively.
    if (field.enum && !field.enum.includes(value as string)) issues.push({ pointer, keyword: 'enum' })
    if (!typeOk) continue
    if (typeof value === 'string') {
      const length = codePointLength(value)
      if (field.minLength !== undefined && length < field.minLength) issues.push({ pointer, keyword: 'minLength' })
      if (field.maxLength !== undefined && length > field.maxLength) issues.push({ pointer, keyword: 'maxLength' })
    }
    if (typeof value === 'number') {
      if (field.minimum !== undefined && value < field.minimum) issues.push({ pointer, keyword: 'minimum' })
      if (field.maximum !== undefined && value > field.maximum) issues.push({ pointer, keyword: 'maximum' })
      if (field.multipleOf !== undefined && !isMultipleOf(value, field.multipleOf)) {
        issues.push({ pointer, keyword: 'multipleOf' })
      }
    }
  }
  return issues
}

/** The JSON Schema document ajv would receive for the same subset (used by parity tests and docs). */
export function toJsonSchema(schema: FormSchema): Record<string, unknown> {
  const properties: Record<string, unknown> = {}
  for (const [key, field] of Object.entries(schema.properties)) {
    properties[key] = { ...field }
  }
  return { type: 'object', additionalProperties: false, required: schema.required, properties }
}
