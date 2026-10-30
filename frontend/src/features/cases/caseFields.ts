import type { components } from '@/api/schema'
import type { FieldKind, FormFieldModel } from '@/features/studio/model/ops'
import type { ViewDefinition } from '@/features/views/model'
import { isCalendarDate } from '@/lib/dates'
import { compiledPattern, MAX_PATTERN_LENGTH } from '@/lib/form/pattern'
import { buildFields, type FieldDef, type FormFieldSchema, type FormSchema, type FormUi, type Widget } from '@/lib/form/schema'

/**
 * Case data fields as request-form fields (docs/build/S125). A case field is edited with the shared field editor and
 * rendered with the request form renderer; this module converts between the case contract (`CaseDataField`) and the
 * editor model (`FormFieldModel`) and form schema (`FormSchema`). The same rules run in the mock backend.
 */
export type DataField = components['schemas']['CaseDataField']
export type CaseDefinitionWarning = components['schemas']['CaseDefinitionWarning']

/** Case data holds single values only: no collections, rows or files. */
export const CASE_FIELD_KINDS: FieldKind[] = ['text', 'longText', 'number', 'money', 'integer', 'date', 'yesNo', 'choice']
/** New keys: the request-question pattern (workflow-v0 `fieldKey`). */
export const CASE_KEY_PATTERN = /^[a-z][A-Za-z0-9]{0,63}$/
/** Every key the server accepts: the older case-data pattern, kept so existing definitions keep working. */
export const LEGACY_CASE_KEY_PATTERN = /^[A-Za-z][A-Za-z0-9_]{0,63}$/
export const MAX_CASE_FIELDS = 30
const TEXT_DEFAULT_MAX = 2000
const BOUND = 1_000_000_000_000_000

/** customerName for Customer_Name; undefined when no key in the new pattern can be derived (same as the server). */
export function suggestCaseKey(key: string): string | undefined {
  let out = '', upper = false
  for (const c of key) {
    if (c === '_') { upper = out.length > 0; continue }
    if (!out) { if (/[A-Za-z]/.test(c)) out += c.toLowerCase(); continue }
    out += upper ? c.toUpperCase() : c
    upper = false
  }
  out = out.slice(0, 64)
  return CASE_KEY_PATTERN.test(out) ? out : undefined
}

/** The warning for a key in the older pattern, or undefined when the key follows the request-question pattern. */
export function legacyKeyWarning(key: string): string | undefined {
  if (CASE_KEY_PATTERN.test(key)) return undefined
  const suggestion = suggestCaseKey(key)
  return `The key ${key} uses the older case data key pattern. It keeps working, but new keys start with a lowercase letter and use letters and digits only, like request questions${suggestion ? ` (for example ${suggestion})` : ''}.`
}

/** Warnings the server reports for a definition (CASE_DATA_KEY_LEGACY). */
export function caseDefinitionWarnings(fields: DataField[]): CaseDefinitionWarning[] {
  return fields.flatMap(field => {
    const message = legacyKeyWarning(field.key)
    return message ? [{ code: 'CASE_DATA_KEY_LEGACY' as const, field: field.key, message }] : []
  })
}

/** A case field as the shared editor's model. */
export function fieldModel(field: DataField): FormFieldModel {
  const kind: FieldKind = field.type === 'text' ? (field.widget === 'textarea' ? 'longText' : 'text')
    : field.type === 'number' ? (field.widget === 'money' ? 'money' : 'number')
      : field.type === 'boolean' ? 'yesNo' : field.type
  return {
    key: field.key,
    label: field.label,
    kind,
    required: field.required === true,
    help: field.description ?? '',
    choices: field.options ?? [],
    minimum: field.minimum ?? undefined,
    maximum: field.maximum ?? undefined,
    minLength: field.minLength,
    maxLength: field.maxLength ?? undefined,
    pattern: field.pattern,
    patternMessage: field.patternMessage,
    minDate: field.formatMinimum,
    maxDate: field.formatMaximum,
  }
}

/** The editor's model as a case field. Only the settings of the chosen type are kept, as the server requires. */
export function caseField(model: FormFieldModel): DataField {
  const base: DataField = { key: model.key, label: model.label.trim() || model.key, type: 'text', required: model.required }
  const help = model.help.trim()
  if (help) base.description = help
  switch (model.kind) {
    case 'text':
    case 'longText':
      if (model.kind === 'longText') base.widget = 'textarea'
      if (model.maxLength !== undefined) base.maxLength = model.maxLength
      if (model.minLength !== undefined) base.minLength = model.minLength
      if (model.pattern?.trim()) {
        base.pattern = model.pattern.trim()
        if (model.patternMessage?.trim()) base.patternMessage = model.patternMessage.trim()
      }
      return base
    case 'number':
    case 'money':
    case 'integer':
      base.type = model.kind === 'integer' ? 'integer' : 'number'
      if (model.kind === 'money') { base.widget = 'money'; base.multipleOf = 0.01 }
      if (model.minimum !== undefined) base.minimum = model.minimum
      if (model.maximum !== undefined) base.maximum = model.maximum
      return base
    case 'date':
      base.type = 'date'
      if (model.minDate) base.formatMinimum = model.minDate
      if (model.maxDate) base.formatMaximum = model.maxDate
      return base
    case 'yesNo':
      base.type = 'boolean'
      return base
    case 'choice':
      base.type = 'choice'
      base.options = model.choices.filter(choice => choice.trim() !== '')
      return base
    default:
      // Collections, rows and files are not case data; the editor does not offer them.
      return base
  }
}

/** The case fields as a request form schema: the same keywords the server validates case data with. */
export function caseFormSchema(fields: DataField[]): { schema: FormSchema; ui: FormUi } {
  const properties: Record<string, FormFieldSchema> = {}
  const widgets: Record<string, Widget> = {}
  for (const field of fields) {
    const schema: FormFieldSchema = { type: 'string', title: field.label }
    if (field.description) schema.description = field.description
    switch (field.type) {
      case 'text':
        schema.maxLength = field.maxLength ?? TEXT_DEFAULT_MAX
        if (field.minLength !== undefined) schema.minLength = field.minLength
        if (field.pattern && compiledPattern(field.pattern)) {
          schema.pattern = field.pattern
          if (field.patternMessage) schema.patternMessage = field.patternMessage
        }
        if (field.widget === 'textarea') widgets[field.key] = 'textarea'
        break
      case 'number':
      case 'integer':
        schema.type = field.type
        if (field.minimum !== undefined && field.minimum !== null) schema.minimum = field.minimum
        if (field.maximum !== undefined && field.maximum !== null) schema.maximum = field.maximum
        if (field.multipleOf !== undefined) schema.multipleOf = field.multipleOf
        widgets[field.key] = field.type === 'number' && field.widget === 'money' ? 'money' : 'number'
        break
      case 'date':
        schema.format = 'date'
        if (field.formatMinimum) schema.formatMinimum = field.formatMinimum
        if (field.formatMaximum) schema.formatMaximum = field.formatMaximum
        break
      case 'boolean':
        schema.type = 'boolean'
        widgets[field.key] = 'checkbox'
        break
      case 'choice':
        schema.enum = field.options ?? []
        widgets[field.key] = 'select'
        break
    }
    properties[field.key] = schema
  }
  return {
    schema: { type: 'object', additionalProperties: false, required: fields.filter(field => field.required).map(field => field.key), properties },
    ui: { order: fields.map(field => field.key), widgets, currency: 'USD' },
  }
}

/**
 * The fields the form renderer shows. A pinned case view (docs/build/S69) supplies labels, choice labels and sections;
 * the types and checks always come from the case fields.
 */
export function caseFieldDefs(fields: DataField[], view?: ViewDefinition): FieldDef[] {
  const { schema, ui } = caseFormSchema(fields)
  const defs = buildFields(schema, ui)
  if (!view) return defs
  return view.sections.flatMap(section => section.fields.flatMap(control => {
    const def = defs.find(item => `case.${item.key}` === control.binding)
    if (!def) return []
    return [{
      ...def,
      label: control.label,
      optionLabels: control.options ? Object.fromEntries(control.options.map(option => [option.value, option.label])) : undefined,
      section: { id: section.id, title: section.label, columns: section.columns ?? 1 },
    }]
  }))
}

/**
 * The server's structural checks of case fields (`CaseDataModel.fields`), for the mock backend and early feedback.
 * Returns the first problem, or undefined.
 */
export function caseFieldProblem(fields: unknown): string | undefined {
  if (fields === undefined || fields === null) return undefined
  if (!Array.isArray(fields)) return 'Case data fields must be a list.'
  if (fields.length > MAX_CASE_FIELDS) return 'A case supports at most 30 data fields.'
  const keys = new Set<string>()
  for (const raw of fields as DataField[]) {
    if (!raw || typeof raw.key !== 'string' || !LEGACY_CASE_KEY_PATTERN.test(raw.key) || keys.has(raw.key) || typeof raw.label !== 'string' || !raw.label.trim()
      || raw.label !== raw.label.trim() || raw.label.length > 120 || !['text', 'number', 'integer', 'boolean', 'date', 'choice'].includes(raw.type)) return 'Case data fields need unique keys, labels and supported types.'
    keys.add(raw.key)
    const numeric = raw.type === 'number' || raw.type === 'integer'
    if (raw.maxLength != null && (raw.type !== 'text' || raw.maxLength < 1 || raw.maxLength > 4000)) return 'Text limits must be between 1 and 4000 characters.'
    if ((raw.minimum != null || raw.maximum != null) && !numeric) return 'Only numeric fields accept numeric bounds.'
    if ([raw.minimum, raw.maximum].some(bound => bound != null && Math.abs(bound) > BOUND) || (raw.minimum != null && raw.maximum != null && raw.minimum > raw.maximum)) return 'Numeric case-data bounds are invalid.'
    if (raw.type === 'choice') {
      const options = raw.options ?? []
      if (!options.length || options.length > 30 || options.some(option => !option.trim() || option !== option.trim() || option.length > 120) || new Set(options).size !== options.length) return 'Choice fields need 1 to 30 distinct values.'
    } else if (raw.options?.length) return 'Only choice fields accept options.'
    if (raw.description !== undefined && raw.description.length > 500) return `Help text of ${raw.key} must be at most 500 characters.`
    if (raw.minLength !== undefined && (raw.type !== 'text' || raw.minLength < 0 || raw.minLength > (raw.maxLength ?? TEXT_DEFAULT_MAX))) return `The shortest length of ${raw.key} applies to text fields only and must be between 0 and the longest length.`
    if (raw.pattern !== undefined) {
      if (raw.type !== 'text') return 'Only text fields accept an answer pattern.'
      if (raw.pattern.length < 2 || raw.pattern.length > MAX_PATTERN_LENGTH || !compiledPattern(raw.pattern)) return `The answer pattern of ${raw.key} is not supported.`
    }
    if (raw.patternMessage?.trim() && raw.pattern === undefined) return 'A pattern message needs an answer pattern.'
    if (raw.formatMinimum !== undefined || raw.formatMaximum !== undefined) {
      if (raw.type !== 'date') return 'Only date fields accept an earliest or latest date.'
      if ((raw.formatMinimum !== undefined && !isCalendarDate(raw.formatMinimum)) || (raw.formatMaximum !== undefined && !isCalendarDate(raw.formatMaximum))) return `Date bounds of ${raw.key} must be calendar dates written YYYY-MM-DD.`
      if (raw.formatMinimum && raw.formatMaximum && raw.formatMinimum > raw.formatMaximum) return `The earliest date of ${raw.key} is after its latest date.`
    }
    if (raw.multipleOf !== undefined && (!numeric || ![0.01, 1].includes(raw.multipleOf))) return 'Only numeric fields accept a step, and it must be 0.01 or 1.'
    if (raw.widget !== undefined && !((raw.widget === 'textarea' && raw.type === 'text') || (raw.widget === 'money' && raw.type === 'number'))) return 'Long text applies to text fields and money to number fields.'
  }
  return undefined
}
