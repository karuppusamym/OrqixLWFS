import type { FieldDef } from '@/lib/form/schema'
import type { RawValue, RawValues } from '@/lib/form/values'

function clamp(value: number, min?: number, max?: number): number {
  let v = value
  if (min !== undefined && v < min) v = min
  if (max !== undefined && v > max) v = max
  return v
}

function sampleOne(schema: FieldDef['schema'], isMoney: boolean, key: string, today: Date): RawValue {
  switch (schema.type) {
    case 'boolean':
      return false
    case 'number':
    case 'integer': {
      const v = clamp(isMoney ? 1000 : 10, schema.minimum, schema.maximum)
      return String(schema.type === 'integer' ? Math.ceil(v) : v)
    }
    case 'array':
      return []
    default: {
      if (schema.enum && schema.enum.length > 0) return schema.enum[0] as string
      if (schema.format === 'date') {
        const d = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate() + 14))
        let iso = d.toISOString().slice(0, 10)
        if (schema.formatMinimum && iso < schema.formatMinimum) iso = schema.formatMinimum
        if (schema.formatMaximum && iso > schema.formatMaximum) iso = schema.formatMaximum
        return iso
      }
      let text = `Sample ${key}`
      if (schema.maxLength !== undefined) text = text.slice(0, schema.maxLength)
      if (schema.minLength !== undefined && text.length < schema.minLength) text = text.padEnd(schema.minLength, 'x')
      return text
    }
  }
}

/** Starting answers for the sample request form: plausible values that satisfy the form, so Run test works at once. */
export function sampleValues(fields: FieldDef[], today: Date = new Date()): RawValues {
  const values: RawValues = {}
  for (const f of fields) {
    if (f.schema.type === 'array') {
      const item = f.schema.items
      const wanted = Math.max(f.schema.minItems ?? 0, f.required ? 1 : 0)
      values[f.key] = item ? Array.from({ length: wanted }, (_, i) => sampleOne(item, false, `${f.key} ${i + 1}`, today) as string | boolean) : []
    } else values[f.key] = sampleOne(f.schema, f.isMoney, f.key, today)
  }
  return values
}
