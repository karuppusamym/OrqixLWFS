import { describe, expect, it } from 'vitest'
import type { ViewDefinition } from '@/features/views/model'
import type { DataField } from './CaseDataFields'
import { validateCaseView } from './caseView'

const fields: DataField[] = [{ key: 'summary', label: 'Summary', type: 'text', required: true, maxLength: 80 }, { key: 'checked', label: 'Checked', type: 'boolean', required: false }]
function view(): ViewDefinition { return { schemaVersion: 1, kind: 'case', sections: [{ id: 'details', label: 'Shared case details', columns: 1, fields: [{ id: 'checked', label: 'Review checked', binding: 'case.checked', type: 'boolean' }, { id: 'summary', label: 'Review summary', binding: 'case.summary', type: 'text', required: true }] }], actions: [{ id: 'save', type: 'saveData', label: 'Save reviewed details' }] } }

describe('case view compatibility', () => {
  it('accepts reordered presentation without changing pinned constraints', () => {
    expect(() => validateCaseView(fields, view())).not.toThrow()
    expect(fields[0]!.maxLength).toBe(80)
    expect(fields[0]!.label).toBe('Summary')
  })
  it('refuses incomplete bindings, required/type drift, other scopes and unsupported actions', () => {
    for (const change of [
      (v: ViewDefinition) => { v.sections[0]!.fields.pop() },
      (v: ViewDefinition) => { v.sections[0]!.fields[1]!.required = false },
      (v: ViewDefinition) => { v.sections[0]!.fields[1]!.type = 'number' },
      (v: ViewDefinition) => { v.sections[0]!.fields[1]!.binding = 'request.summary' },
      (v: ViewDefinition) => { v.actions[0]!.type = 'approve' },
      (v: ViewDefinition) => { v.actions = [] },
      (v: ViewDefinition) => { v.actions.push({ id: 'again', type: 'saveData', label: 'Again' }) },
    ]) { const candidate = view(); change(candidate); expect(() => validateCaseView(fields, candidate)).toThrow() }
  })
  it('requires exact ordered choices and refuses unsafe navigation', () => {
    const choices: DataField[] = [{ key: 'priority', label: 'Priority', type: 'choice', required: false, options: ['normal', 'urgent'] }]
    const candidate = view(); candidate.sections[0]!.fields = [{ id: 'priority', label: 'Review priority', binding: 'case.priority', type: 'choice', options: [{ value: 'normal', label: 'normal' }, { value: 'urgent', label: 'urgent' }] }]
    expect(() => validateCaseView(choices, candidate)).not.toThrow()
    candidate.sections[0]!.fields[0]!.options!.reverse()
    expect(() => validateCaseView(choices, candidate)).toThrow('match the case definition')
    candidate.actions.push({ id: 'nav', type: 'navigate', label: 'Leave', target: '//outside.example' })
    expect(() => validateCaseView(choices, candidate)).toThrow('same-app')
  })
})
