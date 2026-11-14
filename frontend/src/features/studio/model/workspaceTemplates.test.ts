import { describe, expect, it } from 'vitest'
import purchase from '../../../../../examples/purchase-approval.json'
import { normalizeDefinition } from './types'
import { addTemplateStep, suggestKey, templateKind, templateOf } from './workspaceTemplates'

describe('workspace step templates in Studio (CG-S03, S141)', () => {
  it('adds a template as a new step with a copy of its configuration', () => {
    const def = normalizeDefinition(purchase)
    const config = { candidateGroupRef: 'managers', rejectReasonRequired: true, dueAfterHours: 8 }
    const added = addTemplateStep(def, { type: 'approval', label: 'Manager review', config })!
    const node = added.def.nodes.find((n) => n.id === added.id)!
    expect(node.type).toBe('approval')
    expect(node.label).toBe('Manager review')
    expect(node.config).toEqual(config)
    expect(node.config).not.toBe(config)
    expect(def.nodes.some((n) => n.id === added.id)).toBe(false)
  })

  it('refuses start, end and unknown step types', () => {
    const def = normalizeDefinition(purchase)
    expect(addTemplateStep(def, { type: 'start' })).toBeNull()
    expect(addTemplateStep(def, { type: 'end' })).toBeNull()
    expect(templateKind('somethingNew')).toBeNull()
    expect(templateKind('http')).toBe('http')
  })

  it('keeps only type, label and config of a configured step and suggests a key', () => {
    const def = normalizeDefinition(purchase)
    const manager = def.nodes.find((n) => n.id === 'manager')!
    expect(Object.keys(templateOf(manager)).sort()).toEqual(['config', 'label', 'type'])
    expect(suggestKey('Review request')).toBe('review-request')
    expect(suggestKey('1st check!')).toBe('step-1st-check')
  })
})
