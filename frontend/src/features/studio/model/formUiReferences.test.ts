import { describe, expect, it } from 'vitest'
import purchase from '../../../../../examples/purchase-approval.json'
import { conditionTerms, normalizeDefinition } from './types'
import { applyFields, fieldsFromDefinition, renameFieldKey } from './ops'

describe('form UI reference preservation', () => {
  it('renames controller and target references together without losing incident opt-in or layout', () => {
    const definition = normalizeDefinition(purchase)
    const condition = { fieldRef: '/input/amount', operator: 'gte' as const, value: 5000 }
    definition.inputSchema.properties.amount!.incidentEditable = true
    definition.inputUi = { ...definition.inputUi, visibleWhen: { purpose: condition }, requiredWhen: { purpose: { all: [condition, { fieldRef: '/input/costCenter', operator: 'eq', value: 'IT' }] } }, layout: { helpText: 'below', sections: [{ id: 'details', title: 'Details', fields: ['purpose'], columns: 2 }] } }
    const controller = renameFieldKey(definition, 'amount', 'total')
    expect(controller.ok).toBe(true)
    if (!controller.ok) return
    expect(controller.def.inputUi!.visibleWhen!.purpose!.fieldRef).toBe('/input/total')
    expect(conditionTerms(controller.def.inputUi!.requiredWhen!.purpose!)[0]!.fieldRef).toBe('/input/total')
    const target = renameFieldKey(controller.def, 'purpose', 'reason')
    expect(target.ok).toBe(true)
    if (!target.ok) return
    const edited = applyFields(target.def, fieldsFromDefinition(target.def).map(field => field.key === 'reason' ? { ...field, label: 'Reason' } : field))
    expect(edited.inputUi?.visibleWhen).toHaveProperty('reason')
    expect(edited.inputUi?.visibleWhen).not.toHaveProperty('purpose')
    expect(edited.inputUi?.requiredWhen).toHaveProperty('reason')
    expect(edited.inputUi?.layout).toEqual({ helpText: 'below', sections: [{ id: 'details', title: 'Details', fields: ['reason'], columns: 2 }] })
    expect(edited.inputSchema.properties.total!.incidentEditable).toBe(true)
    expect(definition.inputUi.visibleWhen!.purpose!.fieldRef).toBe('/input/amount')
  })
})
