import { beforeEach, describe, expect, it } from 'vitest'
import { signInAs } from '@/test/utils'
import { db, PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import { deriveLanes } from './lanes'
import { normalizeDefinition, type Definition } from './types'

beforeEach(() => signInAs('dana.designer'))

const seeded = (): Definition => normalizeDefinition(structuredClone(db.workflows.get(PURCHASE_WORKFLOW_ID)!.drafts.at(-1)!.definition))

describe('deriveLanes', () => {
  it('puts the request form first and automation last, with handoffs between lanes', () => {
    const def = seeded()
    const model = deriveLanes(def, { managers: 'Managers' })
    const ids = model.lanes.map((l) => l.id)
    expect(ids[0]).toBe('requester')
    expect(ids.at(-1)).toBe('system')
    expect(model.lanes.some((l) => l.kind === 'group')).toBe(true)
    const covered = model.lanes.flatMap((l) => l.steps.map((s) => s.id)).sort()
    expect(covered).toEqual(def.nodes.map((n) => n.id).sort())
    for (const h of model.handoffs) {
      expect(h.fromLane).not.toBe(h.toLane)
      expect(def.transitions.some((t) => t.id === h.transitionId)).toBe(true)
    }
    expect(model.handoffs.length).toBeGreaterThan(0)
    const columns = model.lanes.flatMap((l) => l.steps.map((s) => s.column))
    expect(new Set(columns).size).toBe(columns.length)
  })

  it('names a group lane with its display name, falls back to the key, and flags an approval without a group', () => {
    const def = seeded()
    const approval = def.nodes.find((n) => n.type === 'approval')!
    approval.config.candidateGroupRef = 'managers'
    expect(deriveLanes(def, { managers: 'Line managers' }).lanes.find((l) => l.id === 'group:managers')?.label).toBe('Line managers')
    expect(deriveLanes(def, {}).lanes.find((l) => l.id === 'group:managers')?.label).toBe('managers')
    delete approval.config.candidateGroupRef
    expect(deriveLanes(def, {}).lanes.some((l) => l.kind === 'unassigned')).toBe(true)
  })

  it('still lists every step of a draft with a loop and does not throw on an empty draft', () => {
    const def = seeded()
    const first = def.nodes[1]!
    const last = def.nodes.at(-1)!
    def.transitions.push({ id: 'loop', from: last.id, to: first.id, on: 'next' })
    const model = deriveLanes(def)
    expect(model.lanes.flatMap((l) => l.steps)).toHaveLength(def.nodes.length)
    expect(deriveLanes(normalizeDefinition({})).lanes).toEqual([])
  })
})
