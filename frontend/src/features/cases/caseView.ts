import type { ViewDefinition } from '@/features/views/model'
import { validateView } from '@/features/views/model'
import type { DataField } from './CaseDataFields'

export function validateCaseView(fields: DataField[], view: ViewDefinition): void {
  const issues = validateView(view)
  if (issues.length) throw new Error(issues[0])
  if (view.kind !== 'case') throw new Error('Choose a case view.')
  const types = view.actions.map(action => action.type)
  if (!types.includes('saveData') || new Set(types).size !== types.length || types.some(type => !['saveData', 'navigate'].includes(type))) throw new Error('Include one Save data action and at most one navigation action.')
  const controls = view.sections.flatMap(section => section.fields)
  if (controls.length !== fields.length || new Set(controls.map(control => control.binding)).size !== fields.length) throw new Error('Every case field must appear exactly once in the view.')
  for (const control of controls) {
    const field = fields.find(item => `case.${item.key}` === control.binding)
    if (!field || (field.type === 'integer' ? 'number' : field.type) !== control.type || !!field.required !== !!control.required) throw new Error(`The binding, type or required flag for ${control.label} does not match the case fields.`)
    if (field.type === 'choice' && (field.options?.length !== control.options?.length || control.options?.some((option, index) => option.value !== field.options?.[index] || option.label !== option.value))) throw new Error(`Choices for ${control.label} must match the case definition in order.`)
  }
}
