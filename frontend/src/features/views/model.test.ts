import { describe, expect, it } from 'vitest'
import { blankView, moveItem, uniqueId, validateView } from './model'

describe('reusable view model', () => {
  it('starts a request draft with an editable section', () => {
    expect(blankView()).toEqual({ schemaVersion: 1, kind: 'request', sections: [{ id: 'section1', label: 'Details', columns: 1, fields: [{ id: 'field1', label: 'New question', type: 'text', binding: 'request.field1' }] }], actions: [] })
  })

  it('orders existing items without mutating the source', () => {
    const source = ['first', 'second', 'third']
    expect(moveItem(source, 1, -1)).toEqual(['second', 'first', 'third'])
    expect(source).toEqual(['first', 'second', 'third'])
    expect(moveItem(source, 0, -1)).toBe(source)
    expect(uniqueId('field', ['field1', 'field2'])).toBe('field3')
  })

  it('surfaces invalid bindings, choices, duplicate IDs, and off-site navigation before save', () => {
    const definition = blankView()
    definition.sections[0]!.fields.push({ id: 'section1', label: '', type: 'choice', binding: 'other.key', options: [] })
    definition.actions.push({ id: 'action1', label: 'Leave', type: 'navigate', target: '//external.example' })
    expect(validateView(definition)).toEqual(expect.arrayContaining([
      'Field ID section1 must be unique.',
      'Field section1 needs a label.',
      'Field section1 needs a binding matching this view kind.',
      'Choice field section1 needs labeled values.',
      'Navigation action action1 needs a same-app path.',
    ]))
  })
})
