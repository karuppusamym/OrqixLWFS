import type { Definition, FieldSchemaDoc } from '@/features/studio/model/types'
import { validateView, type ViewDefinition, type ViewField } from './model'

export function compatible(field: ViewField, schema: FieldSchemaDoc): boolean {
  switch (field.type) {
    case 'text': return schema.type === 'string' && !schema.format && !schema.enum
    case 'number': return schema.type === 'number' || schema.type === 'integer'
    case 'date': return schema.type === 'string' && schema.format === 'date'
    case 'file': return schema.type === 'string' && schema.format === 'attachment'
    case 'row': return schema.type === 'array' && schema.items?.type === 'object'
    case 'choice': return schema.type === 'string' && !!schema.enum && schema.enum.length === field.options?.length && field.options.every((option, index) => option.value === schema.enum![index] && option.label === option.value)
  }
}

/** Copy the presentation only. Input types, bounds, conditions, required flags and execution stay authoritative. */
export function bindRequestView(workflow: Definition, id: string, version: number, view: ViewDefinition): Definition {
  const invalid = validateView(view)
  if (invalid.length) throw new Error(invalid[0])
  if (view.kind !== 'request' || view.sections.length > 10) throw new Error('Choose a request view with at most ten sections.')
  if (view.actions.length > 1 || view.actions.some(action => action.type !== 'submit')) throw new Error('This form supports a single submit action. Choose a view without other actions.')
  const fields = view.sections.flatMap(section => section.fields)
  const keys = fields.map(field => field.binding.slice(8))
  if (keys.length !== Object.keys(workflow.inputSchema.properties).length || new Set(keys).size !== keys.length) throw new Error('The view must bind every workflow question exactly once.')
  for (const field of fields) {
    const key = field.binding.slice(8), schema = workflow.inputSchema.properties[key]
    if (!field.binding.startsWith('request.') || !schema || !compatible(field, schema) || (workflow.inputSchema.required ?? []).includes(key) !== (field.required === true)) throw new Error(`The binding, type or required flag for ${field.label} does not match this workflow.`)
  }
  const next = structuredClone(workflow)
  const widgets: Record<string, string> = {}
  for (const field of fields) {
    const key = field.binding.slice(8)
    next.inputSchema.properties[key]!.title = field.label
    if (field.type === 'text' || field.type === 'number' || field.type === 'choice') widgets[key] = field.type === 'choice' ? 'select' : field.type
  }
  next.inputUi = { ...next.inputUi, viewRef: { id, version }, order: keys, widgets, layout: { ...next.inputUi?.layout, sections: view.sections.map(section => ({ id: section.id, title: section.label, columns: section.columns, fields: section.fields.map(field => field.binding.slice(8)) })) } }
  if (view.actions[0]) next.inputUi.submitLabel = view.actions[0].label
  else delete next.inputUi.submitLabel
  return next
}
