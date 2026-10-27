import { describe, expect, it } from 'vitest'
import purchase from '../../../../examples/purchase-approval.json'
import { createStudioStore } from '@/stores/studioStore'
import { normalizeDefinition } from '@/features/studio/model/types'
import type { AiDraftEditChange } from './api'
import { applyDraftChanges, applyRegeneration } from './draftEdits'

const def = () => normalizeDefinition(purchase)

function changes(): AiDraftEditChange[] {
  const d = def()
  const manager = d.nodes.find((n) => n.id === 'manager')!
  const t7 = d.transitions.find((t) => t.id === 't7')!
  return [
    {
      id: 'c1', summary: 'Legal after finance', dependsOn: [], operations: [
        { op: 'disconnect', transitionId: 't7', before: structuredClone(t7) as never },
        { op: 'addStep', id: 'legal', kind: 'approval', label: 'Legal review', config: { candidateGroupRef: 'legal', rejectReasonRequired: true } },
        { op: 'connect', transitionId: 't12', from: 'finance', to: 'legal', on: 'approved' },
        { op: 'connect', transitionId: 't13', from: 'legal', to: 'procure', on: 'approved' },
        { op: 'connect', transitionId: 't14', from: 'legal', to: 'notifyRejected', on: 'rejected' },
      ],
    },
    { id: 'c2', summary: 'Rename', dependsOn: [], operations: [{ op: 'renameStep', id: 'manager', label: 'Manager review', before: structuredClone(manager) as never }] },
    { id: 'c3', summary: 'Longer deadline', dependsOn: ['c2'], operations: [{ op: 'updateStep', id: 'manager', config: { dueAfterHours: 48, rejectReasonRequired: null }, before: { ...structuredClone(manager), label: 'Manager review' } as never }] },
    { id: 'c4', summary: 'Cost code', dependsOn: [], operations: [{ op: 'changeFormField', action: 'add', key: 'costCode', field: { type: 'string', title: 'Cost code' }, required: true }] },
  ]
}

describe('applying conversational draft edits (STU02)', () => {
  it('applies the chosen changes in order, places new steps and keeps everything else', () => {
    const outcome = applyDraftChanges(def(), changes(), new Set(['c1', 'c2', 'c3', 'c4']))
    expect(outcome.applied).toEqual(['c1', 'c2', 'c3', 'c4'])
    expect(outcome.skipped).toEqual([])
    const next = outcome.def
    expect(next.nodes.find((n) => n.id === 'legal')).toMatchObject({ type: 'approval', label: 'Legal review' })
    expect(next.transitions.some((t) => t.id === 't7')).toBe(false)
    expect(next.transitions.filter((t) => t.from === 'legal').map((t) => t.on).sort()).toEqual(['approved', 'rejected'])
    const finance = next.uiLayout!.positions.finance!
    expect(next.uiLayout!.positions.legal!.x).toBeGreaterThan(finance.x)
    const manager = next.nodes.find((n) => n.id === 'manager')!
    expect(manager.label).toBe('Manager review')
    expect(manager.config.dueAfterHours).toBe(48)
    expect('rejectReasonRequired' in manager.config).toBe(false)
    expect(next.inputSchema.required).toContain('costCode')
  })

  it('skips a change whose dependency was not chosen or whose target changed since', () => {
    const outcome = applyDraftChanges(def(), changes(), new Set(['c3', 'c4']))
    expect(outcome.applied).toEqual(['c4'])
    expect(outcome.skipped).toEqual([{ id: 'c3', reason: 'it needs change c2, which was not applied' }])
    const edited = def()
    edited.nodes.find((n) => n.id === 'manager')!.label = 'Edited by hand'
    const stale = applyDraftChanges(edited, changes(), new Set(['c2']))
    expect(stale.applied).toEqual([])
    expect(stale.def).toBe(edited)
    expect(stale.skipped[0]!.reason).toMatch(/changed since the proposal/)
    // A half-applicable change is skipped whole: the connection id is taken now.
    const taken = def()
    taken.transitions.push({ id: 't13', from: 'manager', to: 'finance', on: 'approved' })
    const partial = applyDraftChanges(taken, changes(), new Set(['c1']))
    expect(partial.applied).toEqual([])
    expect(partial.def.nodes.some((n) => n.id === 'legal')).toBe(false)
  })

  it('applies several changes as one undoable edit', () => {
    const store = createStudioStore()
    store.getState().load({ workflowId: 'w', revision: 1, definition: def() })
    const before = store.getState().definition
    store.getState().apply((d) => applyDraftChanges(d, changes(), new Set(['c1', 'c2', 'c4'])).def)
    expect(store.getState().definition.nodes.some((n) => n.id === 'legal')).toBe(true)
    store.getState().undo()
    expect(store.getState().definition).toBe(before)
  })

  it('replaces the draft with a regeneration only when the draft is unchanged since the request', () => {
    const baseline = def()
    const proposed = { ...structuredClone(baseline), workflowKey: 'other', name: 'Other', nodes: [baseline.nodes[0]], transitions: [] }
    const replaced = applyRegeneration(baseline, baseline, proposed)!
    expect(replaced.nodes).toHaveLength(1)
    expect(replaced.workflowKey).toBe('purchase-approval')
    expect(replaced.name).toBe('Purchase approval')
    const edited = def()
    edited.name = 'Changed'
    expect(applyRegeneration(edited, baseline, proposed)).toBeNull()
  })
})
