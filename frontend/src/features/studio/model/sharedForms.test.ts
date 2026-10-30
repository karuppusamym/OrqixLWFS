import { describe, expect, it } from 'vitest'
import { purchaseTemplate } from '@/mocks/state'
import { removeNode, renameFieldKey } from './ops'
import {
  choiceProblems, clashingKeys, copyAsContent, copyMatches, defaultChoices, formContentOf, formDiff, formLink, insertSharedForm, mappingFor, unlinkSharedForm,
  updateClashes, updateSharedForm, type FormContent,
} from './sharedForms'
import type { Definition } from './types'

const delivery = (): FormContent => ({
  inputSchema: {
    type: 'object', additionalProperties: false, required: ['deliveryDate'],
    properties: {
      deliveryDate: { type: 'string', format: 'date', title: 'Needed by' },
      urgent: { type: 'boolean', title: 'Urgent delivery' },
      urgentReason: { type: 'string', title: 'Why is it urgent?', maxLength: 500 },
    },
  } as FormContent['inputSchema'],
  inputUi: {
    order: ['deliveryDate', 'urgent', 'urgentReason'],
    widgets: { urgent: 'checkbox', urgentReason: 'textarea' },
    visibleWhen: { urgentReason: { fieldRef: '/input/urgent', operator: 'eq', value: true } },
    requiredWhen: { urgentReason: { fieldRef: '/input/urgent', operator: 'eq', value: true } },
    layout: { sections: [{ id: 'delivery', title: 'Delivery', fields: ['deliveryDate', 'urgent', 'urgentReason'] }] },
  } as FormContent['inputUi'],
})

/** A purchase request that already asks its own "urgent" question. */
function withUrgent(): Definition {
  const def = purchaseTemplate()
  def.inputSchema.properties.urgent = { type: 'string', title: 'How soon?', maxLength: 50 }
  return def
}

describe('shared forms (docs/build/S127)', () => {
  it('saves the request form as content without workflow-level settings', () => {
    const def = purchaseTemplate()
    def.inputUi = { ...def.inputUi, submitLabel: 'Send', visibleWhen: { purpose: { fieldRef: '/input/amount', operator: 'gte', value: 100 } } }
    const content = formContentOf(def)
    expect(Object.keys(content.inputSchema.properties)).toEqual(Object.keys(def.inputSchema.properties))
    expect(content.inputSchema.required).toEqual(def.inputSchema.required)
    expect(content.inputUi).not.toHaveProperty('submitLabel')
    expect(content.inputUi).not.toHaveProperty('currency')
    expect(content.inputUi?.visibleWhen?.purpose).toEqual({ fieldRef: '/input/amount', operator: 'gte', value: 100 })
    expect(formContentOf(def, ['title']).inputSchema.properties).toHaveProperty('title')
    expect(Object.keys(formContentOf(def, ['title']).inputSchema.properties)).toHaveLength(1)
  })

  it('never overwrites a clashing question: insert needs a rename or skip for each', () => {
    const def = withUrgent()
    const content = delivery()
    const clashes = clashingKeys(def, content)
    expect(clashes).toEqual(['urgent'])
    expect(choiceProblems(def, content, clashes, {})).toHaveProperty('urgent')
    expect(choiceProblems(def, content, clashes, { urgent: { action: 'rename', to: 'title' } }).urgent).toMatch(/already has/)
    expect(choiceProblems(def, content, clashes, { urgent: { action: 'rename', to: 'urgentReason' } }).urgent).toMatch(/also has/)
    expect(choiceProblems(def, content, clashes, { urgent: { action: 'rename', to: 'Bad key' } }).urgent).toMatch(/lowercase/)
    const defaults = defaultChoices(def, clashes, content)
    expect(defaults).toEqual({ urgent: { action: 'rename', to: 'urgent2' } })
    expect(choiceProblems(def, content, clashes, defaults)).toEqual({})
    expect(() => insertSharedForm(def, 'delivery', 1, content, {})).toThrow(/already used/)
  })

  it('inserts renamed questions with their rules, groups and a pinned link; the copy matches the version', () => {
    const def = withUrgent()
    const next = insertSharedForm(def, 'delivery', 1, delivery(), { urgent: { action: 'rename', to: 'isUrgent' } })
    expect(next.inputSchema.properties.urgent).toEqual(def.inputSchema.properties.urgent)
    expect(next.inputSchema.properties.isUrgent).toEqual({ type: 'boolean', title: 'Urgent delivery' })
    expect(next.inputSchema.required).toContain('deliveryDate')
    expect(next.inputUi?.visibleWhen?.urgentReason).toEqual({ fieldRef: '/input/isUrgent', operator: 'eq', value: true })
    expect(next.inputUi?.widgets?.isUrgent).toBe('checkbox')
    expect(next.inputUi?.layout?.sections).toEqual([{ id: 'delivery-delivery', title: 'Delivery', fields: ['deliveryDate', 'isUrgent', 'urgentReason'] }])
    expect(next.inputUi?.order?.slice(-3)).toEqual(['deliveryDate', 'isUrgent', 'urgentReason'])
    const link = formLink(next, 'delivery')!
    expect(link).toEqual({ kind: 'form', key: 'delivery', version: 1, target: 'request', fields: { deliveryDate: 'deliveryDate', urgent: 'isUrgent', urgentReason: 'urgentReason' } })
    expect(copyMatches(next, link, delivery())).toBe(true)
    expect(def.dependencies.assets).toBeUndefined()
  })

  it('skipping keeps this form\'s question and leaves the rule pointing at it (the compiler checks the result)', () => {
    const next = insertSharedForm(withUrgent(), 'delivery', 1, delivery(), { urgent: { action: 'skip' } })
    const link = formLink(next, 'delivery')!
    expect(link.fields).toEqual({ deliveryDate: 'deliveryDate', urgentReason: 'urgentReason' })
    expect(next.inputSchema.properties.urgent).toEqual({ type: 'string', title: 'How soon?', maxLength: 50 })
    expect(next.inputUi?.visibleWhen?.urgentReason?.fieldRef).toBe('/input/urgent')
    expect(copyMatches(next, link, delivery())).toBe(true)
  })

  it('reports an edited copy; order and groups are presentation and do not count', () => {
    const next = insertSharedForm(purchaseTemplate(), 'delivery', 1, delivery(), {})
    const link = formLink(next, 'delivery')!
    const moved = structuredClone(next)
    moved.inputUi!.order = [...moved.inputUi!.order!].reverse()
    delete moved.inputUi!.layout
    expect(copyMatches(moved, link, delivery())).toBe(true)
    const edited = structuredClone(next)
    edited.inputSchema.properties.urgentReason!.maxLength = 100
    expect(copyMatches(edited, link, delivery())).toBe(false)
    const ruleMoved = structuredClone(next)
    ruleMoved.inputUi!.visibleWhen!.urgentReason = { fieldRef: '/input/title', operator: 'eq', value: 'x' }
    expect(copyMatches(ruleMoved, link, delivery())).toBe(false)
    const optional = structuredClone(next)
    optional.inputSchema.required = optional.inputSchema.required!.filter((k) => k !== 'deliveryDate')
    expect(copyMatches(optional, link, delivery())).toBe(false)
  })

  it('diffs versions and updates the copy: removed deleted, changed replaced in place, added copied with clash choices', () => {
    const v1 = delivery()
    const v2 = delivery()
    delete (v2.inputSchema.properties as Record<string, unknown>).urgentReason
    delete v2.inputUi!.visibleWhen
    delete v2.inputUi!.requiredWhen
    v2.inputUi!.layout!.sections![0]!.fields = ['deliveryDate', 'urgent', 'title']
    ;(v2.inputSchema.properties as Record<string, unknown>).deliveryDate = { type: 'string', format: 'date', title: 'Deliver by' }
    ;(v2.inputSchema.properties as Record<string, unknown>).title = { type: 'string', title: 'Delivery note', maxLength: 200 }
    expect(formDiff(v1, v2)).toEqual({ added: ['title'], removed: ['urgentReason'], changed: ['deliveryDate'] })

    const def = insertSharedForm(purchaseTemplate(), 'delivery', 1, v1, {})
    const link = formLink(def, 'delivery')!
    const clashes = updateClashes(def, link, v1, v2)
    expect(clashes).toEqual(['title'])
    expect(choiceProblems(def, v2, clashes, {})).toHaveProperty('title')
    const updated = updateSharedForm(def, link, v1, v2, 2, { title: { action: 'rename', to: 'deliveryNote' } })
    expect(updated.inputSchema.properties.urgentReason).toBeUndefined()
    expect(updated.inputUi?.visibleWhen?.urgentReason).toBeUndefined()
    expect(updated.inputSchema.properties.deliveryDate!.title).toBe('Deliver by')
    expect(updated.inputSchema.properties.title).toEqual(purchaseTemplate().inputSchema.properties.title)
    expect(updated.inputSchema.properties.deliveryNote!.title).toBe('Delivery note')
    const after = formLink(updated, 'delivery')!
    expect(after.version).toBe(2)
    expect(after.fields).toEqual({ deliveryDate: 'deliveryDate', urgent: 'urgent', title: 'deliveryNote' })
    expect(copyMatches(updated, after, v2)).toBe(true)
    expect(updated.inputUi?.layout?.sections?.[0]?.fields).toEqual(['deliveryDate', 'urgent', 'deliveryNote'])
  })

  it('a library version never changes the workflow by itself; restoring and saving the copy round-trip', () => {
    const def = insertSharedForm(purchaseTemplate(), 'delivery', 1, delivery(), {})
    const link = formLink(def, 'delivery')!
    const edited = structuredClone(def)
    edited.inputSchema.properties.urgentReason!.title = 'Reason for urgency'
    expect(copyMatches(edited, link, delivery())).toBe(false)
    const restored = updateSharedForm(edited, link, delivery(), delivery(), 1, {})
    expect(copyMatches(restored, link, delivery())).toBe(true)
    const saved = copyAsContent(edited, link, delivery())
    expect((saved.inputSchema.properties as Record<string, { title: string }>).urgentReason!.title).toBe('Reason for urgency')
    expect(copyMatches(edited, { ...link, version: 2 }, saved)).toBe(true)
    expect(saved.inputUi?.layout?.sections?.[0]?.id).toBe('delivery')
  })

  it('renaming a copied question keeps the link; unlinking keeps the questions', () => {
    const def = insertSharedForm(purchaseTemplate(), 'delivery', 1, delivery(), {})
    const renamed = renameFieldKey(def, 'urgent', 'rush')
    expect(renamed.ok).toBe(true)
    if (!renamed.ok) return
    const link = formLink(renamed.def, 'delivery')!
    expect(link.fields.urgent).toBe('rush')
    expect(renamed.def.inputUi?.visibleWhen?.urgentReason?.fieldRef).toBe('/input/rush')
    expect(copyMatches(renamed.def, link, delivery())).toBe(true)
    const unlinked = unlinkSharedForm(renamed.def, 'delivery')
    expect(unlinked.dependencies.assets).toBeUndefined()
    expect(unlinked.inputSchema.properties.rush).toBeDefined()
  })

  it("an update keeps a question the designer moved into one of this form's groups, and removing a step keeps the link", () => {
    const def = insertSharedForm(purchaseTemplate(), 'delivery', 1, delivery(), {})
    def.inputUi!.layout!.sections = [{ id: 'mine', title: 'When', fields: ['deliveryDate'] }, ...def.inputUi!.layout!.sections!.map((s) => ({ ...s, fields: s.fields.filter((k) => k !== 'deliveryDate') }))]
    const link = formLink(def, 'delivery')!
    const v2 = delivery()
    ;(v2.inputSchema.properties as Record<string, { title: string }>).urgent!.title = 'Rush delivery'
    const updated = updateSharedForm(def, link, delivery(), v2, 2, {})
    expect(updated.inputUi?.layout?.sections).toEqual([
      { id: 'mine', title: 'When', fields: ['deliveryDate'] },
      { id: 'delivery-delivery', title: 'Delivery', fields: ['urgent', 'urgentReason'] },
    ])
    const step = updated.nodes.find((n) => n.type === 'approval')!
    const removed = removeNode(updated, step.id)
    expect(formLink(removed, 'delivery')).toBeDefined()
  })

  it('maps chosen keys; skipped questions are left out', () => {
    expect(mappingFor(delivery(), { urgent: { action: 'skip' }, urgentReason: { action: 'rename', to: 'why' } })).toEqual({ deliveryDate: 'deliveryDate', urgentReason: 'why' })
  })
})
