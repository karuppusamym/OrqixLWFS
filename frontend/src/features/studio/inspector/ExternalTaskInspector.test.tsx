import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import purchase from '../../../../../examples/purchase-approval.json'
import { StudioProvider, useStudio } from '../StudioContext'
import { normalizeDefinition } from '../model/types'
import { allFields } from '../model/fieldRefs'
import { ExternalTaskInspector } from './ExternalTaskInspector'

function setup(readOnly = false) {
  const definition = normalizeDefinition(structuredClone(purchase))
  const node = definition.nodes.find((n) => n.id === 'manager')!
  node.type = 'externalTask'
  node.label = 'Check the address'
  node.config = { topic: '', inputMapping: {}, result: [] }
  const store = createStudioStore()
  store.getState().load({ workflowId: 'workflow', revision: 1, definition })
  function Inspector() {
    const current = useStudio((s) => s.definition.nodes.find((n) => n.id === 'manager')!)
    return <ExternalTaskInspector node={current} />
  }
  render(<StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly, connections: { responseType: () => undefined } }}><Inspector /></StudioProvider>)
  return store
}

const config = (store: ReturnType<typeof setup>) => store.getState().definition.nodes.find((n) => n.id === 'manager')!.config

describe('inspector: Work by an external worker', () => {
  it('sets the topic and lease, and rejects a malformed topic with a plain message', async () => {
    const store = setup()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/^Topic/), 'Address Check')
    await user.tab()
    expect(screen.getByText(/lowercase letters and digits joined with dashes/)).toBeInTheDocument()
    await user.clear(screen.getByLabelText(/^Topic/))
    await user.type(screen.getByLabelText(/^Topic/), 'address-check')
    await user.tab()
    expect(config(store).topic).toBe('address-check')
    await user.type(screen.getByLabelText(/^Lease time/), '5')
    await user.tab()
    expect(config(store).leaseSeconds).toBe(30) // clamped to the minimum
  })

  it('maps request values to named inputs and removes them', async () => {
    const store = setup()
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Add a value to send' }))
    expect(Object.keys(config(store).inputMapping as object)).toEqual(['value1'])
    await user.selectOptions(within(screen.getByTestId('external-input-0')).getByRole('combobox'), '/input/purpose')
    expect((config(store).inputMapping as Record<string, string>).value1).toBe('/input/purpose')
    await user.click(screen.getByRole('button', { name: 'Remove value1' }))
    expect(config(store).inputMapping).toEqual({})
  })

  it('declares result values that later steps can use, and flags duplicates', async () => {
    const store = setup()
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Add a result value' }))
    await user.click(screen.getByRole('button', { name: 'Add a result value' }))
    expect((config(store).result as Array<{ name: string }>).map((f) => f.name)).toEqual(['result1', 'result2'])
    await user.clear(screen.getAllByLabelText(/^Value name/)[1]!)
    await user.type(screen.getAllByLabelText(/^Value name/)[1]!, 'result1')
    await user.tab()
    expect(screen.getAllByText('Each value needs its own name.').length).toBeGreaterThan(0)
    expect(allFields(store.getState().definition).map((f) => f.ref)).toContain('/steps/manager/result1')
  })

  it('is read only in a published definition', () => {
    setup(true)
    expect(screen.getByLabelText(/^Topic/)).toBeDisabled()
    expect(screen.queryByRole('button', { name: /^Add a/ })).not.toBeInTheDocument()
  })
})
