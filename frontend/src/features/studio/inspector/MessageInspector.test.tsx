import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { db, PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'

const EDIT = `/w/${WS}/workflows/${PURCHASE_WORKFLOW_ID}/edit`

function stepButton(id: string): HTMLElement {
  const button = screen.getByTestId(`step-row-${id}`).querySelector<HTMLElement>('button[aria-pressed]')
  if (!button) throw new Error(`No step button for ${id}`)
  return button
}

const draft = () => db.workflows.get(PURCHASE_WORKFLOW_ID)!.drafts.at(-1)!.definition
const savedConfig = () => draft().nodes.find((n) => n.id === 'manager')!.config

async function openStep(user: ReturnType<typeof userEvent.setup>) {
  await screen.findByText(/Draft revision 1/, undefined, { timeout: 15000 })
  await user.click(screen.getByRole('tab', { name: 'Steps' }))
  await screen.findByRole('list', { name: 'Workflow steps' }, { timeout: 15000 })
  await user.click(stepButton('manager'))
}

describe('inspector: Wait for a message', () => {
  it('edits the message, the identifying field, the time limit and the values, and reads them back in plain language', async () => {
    signInAs('dana.designer')
    const node = draft().nodes.find((n) => n.id === 'manager')!
    node.type = 'waitForMessage'
    node.config = { message: 'payment-received', correlationField: '', payload: [] }
    const user = userEvent.setup()
    renderApp(EDIT)
    await openStep(user)

    expect(await screen.findByTestId('message-wait-preview', undefined, { timeout: 15000 })).toHaveTextContent('choose which field identifies it')
    const field = screen.getByLabelText(/^Which field identifies the request/)
    expect((field as HTMLSelectElement).value).toBe('')
    await user.selectOptions(field, '/input/costCenter')
    expect(screen.getByTestId('message-wait-preview')).toHaveTextContent("Waits until a message named payment-received arrives for this request's Cost center.")

    const name = screen.getByLabelText(/^Message name/)
    await user.clear(name)
    await user.type(name, 'Payment Received')
    await user.tab()
    expect(await screen.findByText(/Use lowercase letters and digits joined with dashes/, undefined, { timeout: 10000 })).toBeInTheDocument()
    await user.clear(name)
    await user.type(name, 'docs-arrived')
    await user.tab()
    expect(screen.getByTestId('message-wait-preview')).toHaveTextContent('named docs-arrived')

    const limit = screen.getByLabelText(/^Give up after/)
    await user.type(limit, 'P2D')
    await user.tab()
    expect(screen.getByTestId('duration-preview')).toHaveTextContent('Waits 2 days.')
    expect(screen.getByTestId('message-wait-preview')).toHaveTextContent('follows Timed out')

    await user.click(screen.getByRole('button', { name: 'Add a value' }))
    const valueName = await screen.findByLabelText(/^Value name/, undefined, { timeout: 10000 })
    await user.clear(valueName)
    await user.type(valueName, 'paymentReference')
    await user.tab()
    await user.click(screen.getByLabelText('The message must carry it'))

    await waitFor(() => expect(screen.getByTestId('save-state')).toHaveTextContent('Saved'), { timeout: 12000 })
    expect(savedConfig()).toEqual({
      message: 'docs-arrived',
      correlationField: '/input/costCenter',
      timeoutAfter: 'P2D',
      payload: [{ name: 'paymentReference', type: 'string', required: true }],
    })
    // The step row offers a Timed out route and the value becomes an output other steps can use.
    expect(screen.getByTestId('step-row-manager')).toHaveTextContent('docs-arrived')
    expect(screen.getByTestId('step-row-manager')).toHaveTextContent(/Timed out|no message arrives in time/)
  }, 90000)

  it('removes the timed out route when the time limit is cleared', async () => {
    signInAs('dana.designer')
    const d = draft()
    const node = d.nodes.find((n) => n.id === 'manager')!
    node.type = 'waitForMessage'
    node.config = { message: 'payment-received', correlationField: '/input/costCenter', timeoutAfter: 'PT12H', payload: [] }
    d.transitions = d.transitions.filter((t) => t.from !== 'manager')
    d.transitions.push({ id: 'tm1', from: 'manager', to: 'finance', on: 'next' }, { id: 'tm2', from: 'manager', to: 'notifyRejected', on: 'timedOut' })
    const user = userEvent.setup()
    renderApp(EDIT)
    await openStep(user)
    const limit = await screen.findByLabelText(/^Give up after/, undefined, { timeout: 15000 })
    await user.clear(limit)
    await user.tab()
    await waitFor(() => expect(savedConfig().timeoutAfter).toBeUndefined(), { timeout: 12000 })
    await waitFor(() => expect(draft().transitions.some((t) => t.from === 'manager' && t.on === 'timedOut')).toBe(false), { timeout: 12000 })
  }, 90000)

  it('marks the wait as answered by an external system, shows the contract and removes the option again', async () => {
    signInAs('dana.designer')
    const node = draft().nodes.find((n) => n.id === 'manager')!
    node.type = 'waitForMessage'
    node.config = { message: 'payment-received', correlationField: '/input/costCenter', payload: [{ name: 'paymentReference', type: 'string', required: true }] }
    const user = userEvent.setup()
    renderApp(EDIT)
    await openStep(user)

    const toggle = await screen.findByRole('checkbox', { name: 'Answered by an external system' }, { timeout: 15000 })
    expect(screen.queryByTestId('callback-contract')).toBeNull()
    await user.click(toggle)
    await waitFor(() => expect(savedConfig().externalCallback).toEqual({ tokenValidFor: 'P7D' }), { timeout: 12000 })
    const contract = screen.getByTestId('callback-contract')
    expect(contract).toHaveTextContent('X-Orqix-Callback-Token')
    expect(contract).toHaveTextContent('paymentReference (Text, required)')
    const ttl = screen.getByLabelText(/^Token is valid for/)
    await user.clear(ttl)
    await user.type(ttl, 'PT12H')
    await user.tab()
    await waitFor(() => expect(savedConfig().externalCallback).toEqual({ tokenValidFor: 'PT12H' }), { timeout: 12000 })
    await user.click(screen.getByRole('checkbox', { name: 'Answered by an external system' }))
    await waitFor(() => expect(savedConfig().externalCallback).toBeUndefined(), { timeout: 12000 })
  }, 60000)
})
