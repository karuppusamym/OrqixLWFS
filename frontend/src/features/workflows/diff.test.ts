import { describe, expect, it } from 'vitest'
import purchase from '../../../../examples/purchase-approval.json'
import { addNode, connect, moveNodes, removeNode, renameNodeId, updateNodeLabel, upsertDecision } from '@/features/studio/model/ops'
import { normalizeDefinition, type Definition } from '@/features/studio/model/types'
import { diffDefinitions } from './diff'

const base = (): Definition => normalizeDefinition(purchase)

describe('publish review diff', () => {
  it('reports a first version when there is no active release', () => {
    const changes = diffDefinitions(null, base())
    expect(changes).toHaveLength(1)
    expect(changes[0]?.title).toMatch(/First version/)
  })

  it('ignores canvas layout changes', () => {
    expect(diffDefinitions(base(), moveNodes(base(), { manager: { x: 999, y: 999 } }))).toEqual([])
  })

  it('shows plain-text notes and retains them through rename but prunes deleted steps', () => {
    const before = base()
    const after = base()
    after.metadata.stepNotes = { manager: 'Review evidence <script>literal text</script>' }
    expect(diffDefinitions(before, after)[0]?.details).toContain('Step notes: (empty) → Review evidence <script>literal text</script>')
    const renamed = renameNodeId(after, 'manager', 'managerReview')
    expect(renamed.ok).toBe(true)
    if (renamed.ok) expect(renamed.def.metadata.stepNotes).toEqual({ managerReview: after.metadata.stepNotes.manager })
    expect(removeNode(after, 'manager').metadata.stepNotes).toBeUndefined()
    expect(after.metadata.stepNotes.manager).toBeDefined()
  })

  it('describes grouped conditions without undefined placeholders', () => {
    const before = base()
    const after = base()
    after.transitions[0]!.condition = { all: [{ fieldRef: '/input/amount', operator: 'gt', value: 100 }, { fieldRef: '/input/amount', operator: 'lte', value: 1000 }] }
    const detail = diffDefinitions(before, after).flatMap((change) => change.details).find((line) => line.startsWith('Condition:'))
    expect(detail).toContain(' AND ')
    expect(detail).toContain('1000')
    expect(detail).not.toContain('undefined')
  })

  it('makes changed decision input fields and currency hints visible', () => {
    const before = base()
    const after = base()
    after.decisions[0]!.rows[0]!.when.fieldRef = '/input/otherAmount'
    before.inputUi = { ...before.inputUi, currency: 'USD' }
    after.inputUi = { ...after.inputUi }
    delete after.inputUi.currency
    const changes = diffDefinitions(before, after)
    expect(changes.find((change) => change.area === 'rule')?.details[0]).toContain('/input/otherAmount')
    expect(changes.find((change) => change.title === 'Currency display changed')?.details[0]).toContain('USD')
  })

  it('shows a changed finance threshold as a rule table row change', () => {
    const before = base()
    const table = structuredClone(before.decisions[0]!)
    table.rows[0]!.when.value = 10000
    const changes = diffDefinitions(before, upsertDecision(before, table))
    const rule = changes.find((c) => c.area === 'rule')
    expect(rule?.kind).toBe('changed')
    expect(rule?.details[0]).toContain('at least 5000')
    expect(rule?.details[0]).toContain('at least 10000')
  })

  it('shows added, removed and changed steps and routes', () => {
    const before = base()
    const renamed = updateNodeLabel(before, 'manager', 'Manager sign-off')
    expect(diffDefinitions(before, renamed)).toEqual([expect.objectContaining({ kind: 'changed', area: 'step', title: 'Step changed: Manager sign-off' })])

    const added = addNode(before, 'notification')
    const withRoute = connect(added.def, { from: added.id, to: 'fulfilled', on: 'next' })
    expect(withRoute.ok).toBe(true)
    if (withRoute.ok) {
      const titles = diffDefinitions(before, withRoute.def).map((c) => c.title)
      expect(titles).toContain('Step added: Tell the requester (Notification)')
      expect(titles.some((t) => t.startsWith('Route added'))).toBe(true)
    }

    const removed = removeNode(before, 'notify')
    const removedTitles = diffDefinitions(before, removed).map((c) => `${c.kind}:${c.title}`)
    expect(removedTitles).toContain('removed:Step removed: Tell requester it is ordered (Notification)')
    expect(removedTitles.filter((t) => t.startsWith('removed:Route removed'))).toHaveLength(2)
  })
})
