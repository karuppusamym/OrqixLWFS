/**
 * The approved form-schema subset (contracts/workflow-v0.schema.json `formSchema` / `formField`)
 * and the presentation hints (`inputUi`). Server data is untrusted, so parsing is defensive.
 */

import { isCalendarDate } from '../dates'
import type { Condition } from '@/features/studio/model/types'

export type FieldType = 'string' | 'number' | 'integer' | 'boolean' | 'array' | 'object'
export type Widget = 'text' | 'date' | 'file' | 'textarea' | 'number' | 'money' | 'select' | 'radio' | 'checkbox' | 'collection'

export interface FormFieldSchema {
  incidentEditable?: true
  type: FieldType
  title: string
  description?: string
  enum?: string[]
  /** `date`: a calendar date written YYYY-MM-DD, without time or time zone (type is string). */
  format?: 'date' | 'attachment'
  /** Only with format attachment: accepted file types (keys of docs/build/S17 section 2) and the largest file in bytes. */
  acceptTypes?: string[]
  maxFileBytes?: number
  /** Earliest and latest allowed date (YYYY-MM-DD, inclusive), only with format date. */
  formatMinimum?: string
  formatMaximum?: string
  minLength?: number
  maxLength?: number
  minimum?: number
  maximum?: number
  multipleOf?: number
  items?: FormFieldSchema
  minItems?: number
  maxItems?: number
  properties?: Record<string, FormFieldSchema>
  required?: string[]
  additionalProperties?: false
}

export interface FormSchema {
  type: 'object'
  additionalProperties: false
  required: string[]
  properties: Record<string, FormFieldSchema>
}

export interface FormUi {
  order?: string[]
  widgets?: Record<string, Widget>
  currency?: 'USD'
  visibleWhen?: Record<string, Condition>
  requiredWhen?: Record<string, Condition>
  layout?: { sections?: Array<{ id: string; title: string; fields: string[]; columns?: 1 | 2; visibleWhen?: Condition }>; helpText?: 'above' | 'below' }
}

const FIELD_TYPES: readonly string[] = ['string', 'number', 'integer', 'boolean', 'array']
const WIDGETS: readonly string[] = ['text', 'textarea', 'number', 'money', 'select', 'radio', 'checkbox']

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function num(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

/** Parse an untrusted `inputSchema` into the supported subset. Unsupported keywords are ignored. */
export function parseFormSchema(raw: unknown): FormSchema {
  const properties: Record<string, FormFieldSchema> = {}
  const rawProps = isRecord(raw) && isRecord(raw.properties) ? raw.properties : {}
  for (const [key, value] of Object.entries(rawProps)) {
    if (!isRecord(value) || typeof value.type !== 'string' || !FIELD_TYPES.includes(value.type)) continue
    const field: FormFieldSchema = {
      type: value.type as FieldType,
      title: typeof value.title === 'string' && value.title ? value.title : key,
    }
    if (typeof value.description === 'string') field.description = value.description
    if (value.incidentEditable === true) field.incidentEditable = true
    if (field.type === 'string' && value.format === 'date') {
      field.format = 'date'
      if (isCalendarDate(value.formatMinimum)) field.formatMinimum = value.formatMinimum
      if (isCalendarDate(value.formatMaximum)) field.formatMaximum = value.formatMaximum
    }
    if (field.type === 'string' && value.format === 'attachment') {
      field.format = 'attachment'
      if (Array.isArray(value.acceptTypes)) field.acceptTypes = value.acceptTypes.filter((v): v is string => typeof v === 'string')
      const maxFileBytes = num(value.maxFileBytes)
      if (maxFileBytes !== undefined) field.maxFileBytes = maxFileBytes
    }
    if (Array.isArray(value.enum)) field.enum = value.enum.filter((v): v is string => typeof v === 'string')
    const minLength = num(value.minLength)
    const maxLength = num(value.maxLength)
    const minimum = num(value.minimum)
    const maximum = num(value.maximum)
    const multipleOf = num(value.multipleOf)
    if (minLength !== undefined) field.minLength = minLength
    if (maxLength !== undefined) field.maxLength = maxLength
    if (minimum !== undefined) field.minimum = minimum
    if (maximum !== undefined) field.maximum = maximum
    if (multipleOf !== undefined) field.multipleOf = multipleOf
    if (field.type === 'array') {
      if (!isRecord(value.items) || value.items.type === 'array') continue
      const columns = value.items.type === 'object' && isRecord(value.items.properties) ? parseFormSchema(value.items).properties : undefined
      const item = columns && Object.keys(columns).length > 0 && Object.keys(columns).length <= 10 && Object.values(columns).every(c => c.type !== 'array' && c.format !== 'attachment')
        ? { type: 'object' as const, title: typeof value.items.title === 'string' ? value.items.title : 'Row', additionalProperties: false as const, properties: columns, required: Array.isArray(value.items.required) ? value.items.required.filter((k): k is string => typeof k === 'string' && k in columns) : [] }
        : parseFormSchema({ properties: { item: value.items } }).properties.item
      const min = num(value.minItems), max = num(value.maxItems)
      if (!item || min === undefined || max === undefined || !Number.isInteger(min) || !Number.isInteger(max) || min < 1 || max > 25 || max < min) continue
      field.items = item; field.minItems = min; field.maxItems = max
    }
    properties[key] = field
  }
  const required =
    isRecord(raw) && Array.isArray(raw.required) ? raw.required.filter((v): v is string => typeof v === 'string') : []
  return { type: 'object', additionalProperties: false, required, properties }
}

export function parseFormUi(raw: unknown): FormUi {
  if (!isRecord(raw)) return {}
  const ui: FormUi = {}
  if (Array.isArray(raw.order)) ui.order = raw.order.filter((v): v is string => typeof v === 'string')
  if (isRecord(raw.widgets)) {
    const widgets: Record<string, Widget> = {}
    for (const [key, value] of Object.entries(raw.widgets)) {
      if (typeof value === 'string' && WIDGETS.includes(value)) widgets[key] = value as Widget
    }
    ui.widgets = widgets
  }
  if (raw.currency === 'USD') ui.currency = 'USD'
  for (const name of ['visibleWhen', 'requiredWhen'] as const) if (isRecord(raw[name])) {
    const parsed: Record<string, Condition> = {}
    for (const [key, value] of Object.entries(raw[name]).slice(0, 30)) {
      const condition = parseCondition(value)
      if (condition) parsed[key] = condition
    }
    ui[name] = parsed
  }
  if (isRecord(raw.layout)) {
    const sections: NonNullable<FormUi['layout']>['sections'] = []
    if (Array.isArray(raw.layout.sections)) for (const item of raw.layout.sections.slice(0, 10)) {
      if (!isRecord(item) || typeof item.id !== 'string' || typeof item.title !== 'string' || !Array.isArray(item.fields)) continue
      const condition = parseCondition(item.visibleWhen)
      sections.push({ id: item.id, title: item.title, fields: item.fields.filter((f): f is string => typeof f === 'string'), columns: item.columns === 2 ? 2 : 1, ...(condition ? { visibleWhen: condition } : {}) })
    }
    ui.layout = { sections, helpText: raw.layout.helpText === 'above' ? 'above' : 'below' }
  }
  return ui
}

function parseCondition(value: unknown): Condition | undefined {
  if (!isRecord(value)) return
  const group = 'all' in value ? 'all' : 'any' in value ? 'any' : undefined
  if (group) {
    if (!Array.isArray(value[group]) || value[group].length < 2 || value[group].length > 8) return
    const terms = value[group].map(parseCondition)
    if (terms.some(t => !t || !('fieldRef' in t) || !t.fieldRef)) return
    return { [group]: terms } as Condition
  }
  if (typeof value.fieldRef !== 'string' || !/^\/input\/[a-z][A-Za-z0-9]{0,63}$/.test(value.fieldRef) || !['eq','ne','gt','gte','lt','lte'].includes(String(value.operator)) || !['string','number','boolean'].includes(typeof value.value)) return
  return { fieldRef: value.fieldRef, operator: value.operator, value: value.value } as Condition
}

export interface FieldDef {
  key: string
  label: string
  description: string | undefined
  required: boolean
  schema: FormFieldSchema
  widget: Widget
  /** True when the value is a currency amount typed as text (widget money). */
  isMoney: boolean
  visibleWhen?: Condition[]
  requiredWhen?: Condition
  section?: { id: string; title: string; columns: 1 | 2 }
  helpAbove?: boolean
}

/** Pick the widget: explicit hint when compatible with the type, otherwise inferred from the schema. */
export function widgetFor(schema: FormFieldSchema, hint: Widget | undefined): Widget {
  switch (schema.type) {
    case 'array': return 'collection'
    case 'object': return 'collection'
    case 'boolean':
      return 'checkbox'
    case 'string':
      if (schema.format === 'date') return 'date'
      if (schema.format === 'attachment') return 'file'
      if (schema.enum && schema.enum.length > 0) return hint === 'radio' ? 'radio' : 'select'
      return hint === 'textarea' ? 'textarea' : 'text'
    case 'integer':
      return 'number'
    case 'number':
      return hint === 'money' ? 'money' : 'number'
  }
}

/** Ordered field definitions for rendering. Fields missing from `ui.order` follow in schema order. */
export function buildFields(schema: FormSchema, ui: FormUi): FieldDef[] {
  const keys = Object.keys(schema.properties)
  const ordered = [...new Set([...(ui.layout?.sections?.flatMap(s => s.fields) ?? []), ...(ui.order ?? [])].filter((k) => k in schema.properties))]
  for (const key of keys) if (!ordered.includes(key)) ordered.push(key)
  return ordered.map((key) => {
    const fieldSchema = schema.properties[key] as FormFieldSchema
    const widget = widgetFor(fieldSchema, ui.widgets?.[key])
    const section = ui.layout?.sections?.find(s => s.fields.includes(key))
    return {
      key,
      label: fieldSchema.title,
      description: fieldSchema.description,
      required: schema.required.includes(key),
      schema: fieldSchema,
      widget,
      isMoney: widget === 'money',
      visibleWhen: [ui.visibleWhen?.[key], section?.visibleWhen].filter((c): c is Condition => !!c),
      requiredWhen: ui.requiredWhen?.[key],
      section: section ? { id: section.id, title: section.title, columns: section.columns ?? 1 } : undefined,
      helpAbove: ui.layout ? ui.layout.helpText === 'above' : undefined,
    }
  })
}
