import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import { validateDefinition } from '@/mocks/validate'
import purchase from '../../../../../examples/purchase-approval.json'
import { StudioProvider, useStudio } from '../StudioContext'
import { allFields } from '../model/fieldRefs'
import { normalizeDefinition, type Definition } from '../model/types'
import { ApprovalInspector } from './SimpleInspectors'

/** The purchase template with a required list of line items (rows) and a list of offices (single values). */
function withLists(): Definition {
  const def = normalizeDefinition(structuredClone(purchase))
  const props = def.inputSchema.properties as Record<string, unknown>
  props.lines = {
    type: 'array', title: 'Line items', minItems: 1, maxItems: 5,
    items: { type: 'object', additionalProperties: false, required: ['description'], properties: {
      description: { type: 'string', title: 'Description' }, urgent: { type: 'boolean', title: 'Urgent' }, amount: { type: 'number', title: 'Amount' } } },
  }
  props.offices = { type: 'array', title: 'Branch offices', minItems: 1, maxItems: 30, items: { type: 'string' } }
  def.inputSchema.required = [...(def.inputSchema.required ?? []), 'lines', 'offices']
  return def
}

function renderFinance(definition: Definition) {
  const store = createStudioStore()
  store.getState().load({ workflowId: 'workflow', revision: 1, definition })
  function Inspector() {
    const node = useStudio((s) => s.definition.nodes.find((n) => n.id === 'finance')!)
    return <ApprovalInspector node={node} />
  }
  render(
    <QueryClientProvider client={new QueryClient()}>
      <StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly: false, connections: { responseType: () => undefined } }}>
        <Inspector />
      </StudioProvider>
    </QueryClientProvider>,
  )
  return () => store.getState().definition.nodes.find((n) => n.id === 'finance')!.config
}

describe('inspector: one approval per list item (S88)', () => {
  it('chooses the list and its label column, toggles one person for several items and clears it again', async () => {
    const config = renderFinance(withLists())
    const user = userEvent.setup()
    await user.selectOptions(screen.getByLabelText(/^One approval per list item/), 'lines')
    expect(config().perItem).toEqual({ itemsRef: '/input/lines', itemLabel: 'description' })
    // Only columns that can name an item are offered (not the yes/no column).
    const label = screen.getByLabelText(/^Item label/)
    expect([...label.querySelectorAll('option')].map((o) => o.textContent)).toEqual(['Choose a column', 'Description', 'Amount'])
    await user.selectOptions(label, 'amount')
    expect(config().perItem).toEqual({ itemsRef: '/input/lines', itemLabel: 'amount' })
    await user.click(screen.getByRole('checkbox', { name: 'One person may decide several items' }))
    expect(config().perItem).toEqual({ itemsRef: '/input/lines', itemLabel: 'amount', allowSamePerson: true })
    await user.click(screen.getByRole('checkbox', { name: 'One person may decide several items' }))
    expect(config().perItem).toEqual({ itemsRef: '/input/lines', itemLabel: 'amount' })
    await user.selectOptions(screen.getByLabelText(/^One approval per list item/), '')
    expect(config().perItem).toBeUndefined()
  })

  it('a list and a number of approvers replace each other', async () => {
    const config = renderFinance(withLists())
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/^Number of approvers/), '3')
    await user.tab()
    expect(config().multiInstance).toEqual({ approvers: 3 })
    await user.selectOptions(screen.getByLabelText(/^One approval per list item/), 'offices')
    expect(config().perItem).toEqual({ itemsRef: '/input/offices' })
    expect(config().multiInstance).toBeUndefined()
    expect(screen.queryByLabelText(/^Item label/)).toBeNull()
  })

  it('offers approvedCount and itemDecisions only for a list approval, and the offline checks mirror the compiler', () => {
    const def = withLists()
    expect(allFields(def).map((f) => f.ref)).not.toContain('/steps/finance/approvedCount')
    const finance = def.nodes.find((n) => n.id === 'finance')!
    finance.config.perItem = { itemsRef: '/input/lines', itemLabel: 'description' }
    expect(allFields(def).filter((f) => f.ref.startsWith('/steps/finance/')).map((f) => [f.ref, f.type]))
      .toEqual(expect.arrayContaining([['/steps/finance/approvedCount', 'integer'], ['/steps/finance/itemDecisions', 'array']]))
    const codes = (d: Definition) => validateDefinition(d, { groups: ['managers', 'finance-approvers'], connections: [], isTestWorkspace: false } as never).map((i) => i.code)
    expect(codes(def).filter((c) => c.startsWith('WF_PER_ITEM'))).toEqual([])
    finance.config.perItem = { itemsRef: '/input/offices' }
    expect(codes(def)).toContain('WF_PER_ITEM_BOUND')
    finance.config.perItem = { itemsRef: '/input/lines', itemLabel: 'urgent' }
    finance.config.timeoutAfter = 'PT2H'
    expect(codes(def)).toEqual(expect.arrayContaining(['WF_PER_ITEM_LABEL', 'WF_PER_ITEM_UNSUPPORTED']))
  })

  it('sets one item at a time, a quorum and the proposed approver column, and clears each again (S104)', async () => {
    const def = withLists()
    const lines = (def.inputSchema.properties as Record<string, { items: { properties: Record<string, unknown> } }>).lines!
    lines.items.properties.approver = { type: 'string', title: 'Approver email' }
    const config = renderFinance(def)
    const user = userEvent.setup()
    await user.selectOptions(screen.getByLabelText(/^One approval per list item/), 'lines')
    await user.click(screen.getByRole('checkbox', { name: 'Decide items one at a time, in list order' }))
    expect(config().perItem).toEqual({ itemsRef: '/input/lines', itemLabel: 'description', sequential: true })
    await user.type(screen.getByLabelText(/^Items that must be approved/), '2')
    await user.tab()
    expect(config().perItem).toMatchObject({ quorum: 2, sequential: true })
    // Only plain text columns can hold an email: not the number or the yes/no column.
    const who = screen.getByLabelText(/^Proposed approver/)
    expect([...who.querySelectorAll('option')].map((o) => o.textContent)).toEqual(['Nobody, use the group queue', 'Description', 'Approver email'])
    await user.selectOptions(who, 'approver')
    expect(config().perItem).toEqual({ itemsRef: '/input/lines', itemLabel: 'description', sequential: true, quorum: 2, assigneeFrom: 'approver' })
    await user.selectOptions(who, '')
    await user.clear(screen.getByLabelText(/^Items that must be approved/))
    await user.tab()
    await user.click(screen.getByRole('checkbox', { name: 'Decide items one at a time, in list order' }))
    expect(config().perItem).toEqual({ itemsRef: '/input/lines', itemLabel: 'description' })
  })

  it('the offline checks refuse an unreachable quorum and a proposed approver that is not a text column (S104)', () => {
    const def = withLists()
    const finance = def.nodes.find((n) => n.id === 'finance')!
    const codes = (d: Definition) => validateDefinition(d, { groups: ['managers', 'finance-approvers'], connections: [], isTestWorkspace: false } as never).map((i) => i.code)
    finance.config.perItem = { itemsRef: '/input/lines', itemLabel: 'description', quorum: 2 }
    expect(codes(def)).toContain('WF_PER_ITEM_QUORUM')
    ;(def.inputSchema.properties as Record<string, { minItems: number }>).lines!.minItems = 2
    expect(codes(def)).not.toContain('WF_PER_ITEM_QUORUM')
    finance.config.perItem = { itemsRef: '/input/lines', itemLabel: 'description', assigneeFrom: 'amount' }
    expect(codes(def)).toContain('WF_PER_ITEM_ASSIGNEE')
    finance.config.perItem = { itemsRef: '/input/lines', itemLabel: 'description', assigneeFrom: 'description' }
    expect(codes(def).filter((c) => c.startsWith('WF_PER_ITEM'))).toEqual([])
  })
})
