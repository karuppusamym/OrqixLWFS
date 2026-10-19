import { describe, expect, it } from 'vitest'
import purchase from '../../../../examples/purchase-approval.json'
import { normalizeDefinition } from '@/features/studio/model/types'
import type { ViewDefinition } from './model'
import { bindApprovalView } from './approvalBinding'

export function approvalView(): ViewDefinition {
  const workflow = normalizeDefinition(purchase)
  return { schemaVersion: 1, kind: 'approval', sections: [{ id: 'review', label: 'Purchase review', columns: 2, fields: Object.entries(workflow.inputSchema.properties).map(([key, property]) => ({ id: key, binding: `request.${key}`, label: `Review ${key}`, required: workflow.inputSchema.required?.includes(key), type: property.enum ? 'choice' : property.type === 'number' ? 'number' : 'text', ...(property.enum ? { options: property.enum.map(value => ({ value, label: value })) } : {}) })) }], actions: [{ id: 'yes', label: 'Authorize purchase', type: 'approve' }, { id: 'no', label: 'Decline purchase', type: 'reject' }] }
}

describe('pinned approval views', () => {
  it('copies presentation without changing request schema, execution or the source view', () => {
    const source = normalizeDefinition(purchase), view = approvalView()
    const next = bindApprovalView(source, 'manager', 'view-1', 2, view)
    expect(next.nodes.find(node => node.id === 'manager')!.config.viewBinding).toEqual({ id: 'view-1', version: 2, definition: view })
    expect(source.nodes.find(node => node.id === 'manager')!.config.viewBinding).toBeUndefined()
    expect(next.inputSchema).toEqual(source.inputSchema)
    expect(next.inputUi).toEqual(source.inputUi)
    expect(next.transitions).toEqual(source.transitions)
    view.sections[0]!.label = 'Later edit'
    expect((next.nodes.find(node => node.id === 'manager')!.config.viewBinding as { definition: ViewDefinition }).definition.sections[0]!.label).toBe('Purchase review')
  })

  it('refuses missing fields, task bindings, incompatible types, flags and unsupported actions', () => {
    const workflow = normalizeDefinition(purchase)
    const changes: Array<(view: ViewDefinition) => void> = [
      view => { view.kind = 'request' },
      view => { view.sections[0]!.fields.pop() },
      view => { view.sections[0]!.fields[0]!.binding = 'task.comment' },
      view => { view.sections[0]!.fields[0]!.type = 'date' },
      view => { view.sections[0]!.fields[0]!.required = false },
      view => { view.actions.pop() },
      view => { view.actions[0]!.type = 'requestCorrection' },
      view => { view.actions.push({ id: 'again', type: 'approve', label: 'Again' }) },
    ]
    for (const change of changes) {
      const view = approvalView(); change(view)
      expect(() => bindApprovalView(workflow, 'manager', 'view', 1, view)).toThrow()
    }
    expect(() => bindApprovalView(workflow, 'start', 'view', 1, approvalView())).toThrow('no longer exists')
  })
})
