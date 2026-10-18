import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { resetSharedAssets } from '@/mocks/sharedAssets'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import { signInAs, testQueryClient, WS } from '@/test/utils'
import purchase from '../../../../../examples/purchase-approval.json'
import { StudioProvider, useStudio } from '../StudioContext'
import { normalizeDefinition } from '../model/types'
import { removeNode, renameDecisionKey } from '../model/ops'
import { SharedDecisionSection } from './SharedDecisionSection'

beforeEach(() => resetSharedAssets())

function setup(readOnly = false) {
  const store = createStudioStore()
  store.getState().load({ workflowId: 'workflow', revision: 1, definition: normalizeDefinition(purchase) })
  function Section() {
    const table = useStudio((s) => s.definition.decisions[0]!)
    return <SharedDecisionSection table={table} />
  }
  render(
    <QueryClientProvider client={testQueryClient()}>
      <StudioProvider value={{ store, saver: {} as DraftSaver, wsId: WS, workflowId: 'workflow', readOnly, connections: { responseType: () => undefined } }}>
        <Section />
      </StudioProvider>
    </QueryClientProvider>,
  )
  return store
}

describe('shared decision link (PL01)', () => {
  it('copies a shared decision into the table and pins its version', async () => {
    signInAs('dana.designer')
    const store = setup()
    const user = userEvent.setup()
    const picker = await screen.findByLabelText('Use a shared decision')
    await waitFor(() => expect(within(picker).getByRole('option', { name: 'Spend limit (version 1)' })).toBeInTheDocument())
    await user.selectOptions(picker, 'spend-limit')
    expect(await screen.findByText(/Copied version 1 of Spend limit/)).toBeInTheDocument()
    expect(store.getState().definition.dependencies.assets).toEqual([{ kind: 'decision', key: 'spend-limit', version: 1, target: 'amount-policy' }])
    expect(await screen.findByTestId('shared-decision-link')).toHaveTextContent('Linked to Spend limit (spend-limit), version 1.')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('reports an edited copy and saves it as a new shared version that the workflow then pins', async () => {
    signInAs('dana.designer')
    const store = setup()
    const user = userEvent.setup()
    await user.selectOptions(await screen.findByLabelText('Use a shared decision'), 'spend-limit')
    await screen.findByTestId('shared-decision-link')
    store.getState().apply((d) => ({ ...d, decisions: d.decisions.map((t) => ({ ...t, default: { route: 'financeReview' } })) }))
    expect(await screen.findByText(/no longer matches version 1/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Save as shared version 2' }))
    await waitFor(() => expect(store.getState().definition.dependencies.assets?.[0]?.version).toBe(2))
    await waitFor(() => expect(screen.queryByText(/no longer matches/)).not.toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Unlink' }))
    expect(store.getState().definition.dependencies.assets).toBeUndefined()
    expect(store.getState().definition.decisions[0]!.default).toEqual({ route: 'financeReview' })
  })

  it('shares the current table as a new shared decision', async () => {
    signInAs('dana.designer')
    const store = setup()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: /Share this rule table/ }))
    const form = screen.getByRole('form', { name: 'Share this rule table' })
    await user.type(within(form).getByLabelText(/Shared name/), 'Finance threshold')
    await user.clear(within(form).getByLabelText(/Shared key/))
    await user.type(within(form).getByLabelText(/Shared key/), 'finance-threshold')
    await user.click(within(form).getByRole('button', { name: 'Share' }))
    expect(await screen.findByText(/Shared as Finance threshold, version 1/)).toBeInTheDocument()
    expect(store.getState().definition.dependencies.assets).toEqual([{ kind: 'decision', key: 'finance-threshold', version: 1, target: 'amount-policy' }])
  })

  it('offers nothing to read-only viewers of an unlinked table', async () => {
    signInAs('vic.viewer')
    setup(true)
    await new Promise((r) => setTimeout(r, 50))
    expect(screen.queryByText('Shared decision')).not.toBeInTheDocument()
  })

  it('keeps links consistent when a table is renamed or its step is deleted', () => {
    const def = normalizeDefinition(purchase)
    def.dependencies.assets = [{ kind: 'decision', key: 'spend-limit', version: 1, target: 'amount-policy' }]
    const renamed = renameDecisionKey(def, 'amount-policy', 'spend-policy')
    expect(renamed.ok && renamed.def.dependencies.assets?.[0]?.target).toBe('spend-policy')
    const removed = removeNode(def, def.nodes.find((n) => n.type === 'decision')!.id)
    expect(removed.dependencies.assets).toBeUndefined()
  })
})
