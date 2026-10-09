/**
 * The approved form-schema subset (contracts/workflow-v0.schema.json `formSchema` / `formField`)
 * and the presentation hints (`inputUi`). Server data is untrusted, so parsing is defensive.
 */

export type FieldType = 'string' | 'number' | 'integer' | 'boolean'
export type Widget = 'text' | 'textarea' | 'number' | 'money' | 'select' | 'radio' | 'checkbox'

export interface FormFieldSchema {
  type: FieldType
  title: string
  description?: string
  enum?: string[]
  minLength?: number
  maxLength?: number
  minimum?: number
  maximum?: number
  multipleOf?: number
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
}

const FIELD_TYPES: readonly string[] = ['string', 'number', 'integer', 'boolean']
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
  return ui
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
}

/** Pick the widget: explicit hint when compatible with the type, otherwise inferred from the schema. */
export function widgetFor(schema: FormFieldSchema, hint: Widget | undefined): Widget {
  switch (schema.type) {
    case 'boolean':
      return 'checkbox'
    case 'string':
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
  const ordered = [...(ui.order ?? []).filter((k) => k in schema.properties)]
  for (const key of keys) if (!ordered.includes(key)) ordered.push(key)
  return ordered.map((key) => {
    const fieldSchema = schema.properties[key] as FormFieldSchema
    const widget = widgetFor(fieldSchema, ui.widgets?.[key])
    return {
      key,
      label: fieldSchema.title,
      description: fieldSchema.description,
      required: schema.required.includes(key),
      schema: fieldSchema,
      widget,
      isMoney: widget === 'money',
    }
  })
}
