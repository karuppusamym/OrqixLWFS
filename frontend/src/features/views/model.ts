export type ViewKind = 'request' | 'approval'
export type ViewFieldType = 'text' | 'number' | 'date' | 'choice' | 'file' | 'row'
export type ViewActionType = 'submit' | 'saveDraft' | 'approve' | 'reject' | 'requestCorrection' | 'navigate'

export interface ViewField {
  id: string
  label: string
  type: ViewFieldType
  binding: string
  required?: boolean
  options?: Array<{ value: string; label: string }>
}

export interface ViewSection {
  id: string
  label: string
  columns: 1 | 2
  fields: ViewField[]
}

export interface ViewAction {
  id: string
  label: string
  type: ViewActionType
  target?: string
}

export interface ViewDefinition {
  schemaVersion: 1
  kind: ViewKind
  sections: ViewSection[]
  actions: ViewAction[]
}

export const FIELD_TYPES: ViewFieldType[] = ['text', 'number', 'date', 'choice', 'file', 'row']
export const ACTION_TYPES: ViewActionType[] = ['submit', 'saveDraft', 'approve', 'reject', 'requestCorrection', 'navigate']

export function blankView(kind: ViewKind = 'request'): ViewDefinition {
  return { schemaVersion: 1, kind, sections: [{ id: 'section1', label: 'Details', columns: 1, fields: [{ id: 'field1', label: 'New question', type: 'text', binding: kind === 'request' ? 'request.field1' : 'task.field1' }] }], actions: [] }
}

export function uniqueId(base: string, existing: string[]): string {
  let index = 1
  while (existing.includes(`${base}${index}`)) index++
  return `${base}${index}`
}

export function moveItem<T>(items: T[], index: number, delta: -1 | 1): T[] {
  const target = index + delta
  if (target < 0 || target >= items.length) return items
  const next = [...items]
  ;[next[index], next[target]] = [next[target]!, next[index]!]
  return next
}

export function validateView(definition: ViewDefinition): string[] {
  const problems: string[] = []
  const ids = new Set<string>()
  const bindings = new Set<string>()
  if (!definition.sections.length) problems.push('Add at least one section.')
  if (definition.sections.length > 20) problems.push('Use at most 20 sections.')
  if (definition.sections.reduce((count, section) => count + section.fields.length, 0) > 200) problems.push('Use at most 200 fields.')
  for (const section of definition.sections) {
    if (!/^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(section.id) || ids.has(section.id)) problems.push(`Section ID ${section.id || '(empty)'} must be unique.`)
    ids.add(section.id)
    if (!section.label.trim() || section.label !== section.label.trim() || section.label.length > 120) problems.push(`Section ${section.id} needs a label.`)
    if (section.fields.length < 1 || section.fields.length > 50) problems.push(`Section ${section.id} needs 1 to 50 fields.`)
    for (const field of section.fields) {
      if (!/^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(field.id) || ids.has(field.id)) problems.push(`Field ID ${field.id || '(empty)'} must be unique.`)
      ids.add(field.id)
      if (!field.label.trim() || field.label !== field.label.trim() || field.label.length > 120) problems.push(`Field ${field.id} needs a label.`)
      if (!/^(request|task)\.[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(field.binding) || (definition.kind === 'request' && field.binding.startsWith('task.'))) problems.push(`Field ${field.id} needs a request or task binding.`)
      if (bindings.has(field.binding)) problems.push(`Field ${field.id} duplicates a binding.`)
      bindings.add(field.binding)
      if (field.type === 'choice' && (!field.options?.length || field.options.some(option => !option.value.trim() || !option.label.trim()))) {
        problems.push(`Choice field ${field.id} needs labeled values.`)
      }
      if (field.type === 'choice' && field.options && new Set(field.options.map(option => option.value)).size !== field.options.length) problems.push(`Choice field ${field.id} repeats an option value.`)
    }
  }
  for (const action of definition.actions) {
    if (!/^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(action.id) || ids.has(action.id)) problems.push(`Action ID ${action.id || '(empty)'} must be unique.`)
    ids.add(action.id)
    if (!action.label.trim() || action.label !== action.label.trim() || action.label.length > 80) problems.push(`Action ${action.id} needs a label.`)
    if (!(definition.kind === 'request' ? ['submit', 'saveDraft', 'navigate'] : ['approve', 'reject', 'requestCorrection', 'navigate']).includes(action.type)) problems.push(`Action ${action.id} is not allowed on a ${definition.kind} view.`)
    if (action.type === 'navigate' && (!action.target || !/^\/(?!\/)[A-Za-z0-9/_-]{1,199}$/.test(action.target) || action.target.includes('..'))) problems.push(`Navigation action ${action.id} needs a same-app path.`)
  }
  if (definition.actions.length > 10) problems.push('Use at most 10 actions.')
  return problems
}
