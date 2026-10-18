import { describe, expect, it } from 'vitest'
import purchase from '../../../../examples/purchase-approval.json'
import { normalizeDefinition } from '@/features/studio/model/types'
import { bindRequestView } from './requestBinding'
import type { ViewDefinition } from './model'

function fixture() {
  const workflow = normalizeDefinition(purchase)
  const view: ViewDefinition = { schemaVersion: 1, kind: 'request', actions: [{ id: 'submit', label: 'Send for review', type: 'submit' }], sections: [{ id: 'details', label: 'Purchase details', columns: 2,
    fields: Object.entries(workflow.inputSchema.properties).reverse().map(([key, field]) => ({ id: key, binding: `request.${key}`, label: `Shared ${key}`, type: field.enum ? 'choice' : field.type === 'number' ? 'number' : 'text', required: true, ...(field.enum ? { options: field.enum.map(value => ({ value, label: value })) } : {}) })) }] }
  return { workflow, view }
}
describe('published request view binding', () => {
  it('copies layout and action label, pins an exact version and preserves validation and execution', () => {
    const { workflow, view } = fixture()
    const bound = bindRequestView(workflow, 'view-id', 2, view)
    expect(bound.inputUi?.viewRef).toEqual({ id: 'view-id', version: 2 })
    expect(bound.inputUi?.submitLabel).toBe('Send for review')
    expect(bound.inputUi?.order).toEqual(Object.keys(workflow.inputSchema.properties).reverse())
    expect(bound.inputUi?.layout?.sections?.[0]).toMatchObject({ title: 'Purchase details', columns: 2 })
    expect(bound.inputSchema.properties.amount).toEqual({ ...workflow.inputSchema.properties.amount, title: 'Shared amount' })
    expect(bound.nodes).toEqual(workflow.nodes)
    expect(bound.inputSchema.required).toEqual(workflow.inputSchema.required)
    expect(workflow.inputUi?.viewRef).toBeUndefined()
    view.sections[0]!.label = 'Later library edit'
    expect(bound.inputUi?.layout?.sections?.[0]?.title).toBe('Purchase details')
  })
  it('rejects missing fields, incompatible types, required changes and unsupported actions', () => {
    for (const change of [(view: ViewDefinition) => { view.sections[0]!.fields.pop() },
      (view: ViewDefinition) => { view.sections[0]!.fields[0]!.type = 'file' },
      (view: ViewDefinition) => { view.sections[0]!.fields[0]!.required = false },
      (view: ViewDefinition) => { view.actions = [{ id: 'nav', label: 'Leave', type: 'navigate', target: '/home' }] }]) {
      const { workflow, view } = fixture(); change(view)
      expect(() => bindRequestView(workflow, 'view-id', 1, view)).toThrow()
    }
  })
  it('removes a previous submit label when the selected version has no action', () => {
    const { workflow, view } = fixture()
    workflow.inputUi = { ...workflow.inputUi, submitLabel: 'Old action' }
    view.actions = []
    expect(bindRequestView(workflow, 'view-id', 1, view).inputUi?.submitLabel).toBeUndefined()
  })
})
