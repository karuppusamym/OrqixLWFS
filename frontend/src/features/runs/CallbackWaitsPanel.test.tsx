import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { mockCallbackWaits, resetCallbackMocks, seedCallbackMocks } from '@/mocks/callbacks'
import { CallbackWaitsPanel } from './CallbackWaitsPanel'

const RUN = '00000000-0000-7000-8000-00000000e001'
const wait = {
  id: '00000000-0000-7000-8000-00000000e101', runId: RUN, runReference: 'REQ-1', nodeId: 'waitPayment', stepLabel: 'Wait for the payment',
  messageName: 'payment-received', state: 'waiting' as const, reason: null, suspended: false, tokenIssued: false, tokenExpiresAt: null,
  tokensIssued: 0, tokenValidFor: 'P7D', resumedAt: null, createdAt: '2026-10-26T09:00:00Z',
  fields: [{ name: 'paymentReference', type: 'string', required: true }, { name: 'amountPaid', type: 'number', required: false }],
}

function mount() {
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}><CallbackWaitsPanel ws="workspace" runId={RUN} /></QueryClientProvider>)
}

describe('external system waits on the request page', () => {
  beforeEach(() => resetCallbackMocks())

  it('renders nothing when the request has no such wait', async () => {
    const { container } = mount()
    await new Promise((r) => setTimeout(r, 50))
    expect(container).toBeEmptyDOMElement()
  })

  it('issues a token shown once, then revokes it while the step keeps waiting', async () => {
    seedCallbackMocks({ waits: [wait] })
    const user = userEvent.setup()
    mount()
    const row = await screen.findByTestId('callback-wait-waitPayment')
    expect(row).toHaveTextContent('Waiting for the external system')
    expect(row).toHaveTextContent('No token issued')
    expect(row).toHaveTextContent('expects paymentReference, amountPaid (optional)')
    await user.click(within(row).getByRole('button', { name: 'Issue a token for Wait for the payment' }))
    const once = await screen.findByTestId('callback-token-once')
    expect(once).toHaveTextContent('It will not be shown again.')
    expect(within(once).getByLabelText('Callback token').textContent).toMatch(/^ocb_[A-Za-z0-9_-]{43}$/)
    expect(once).toHaveTextContent('X-Orqix-Callback-Token')
    await waitFor(() => expect(within(screen.getByTestId('callback-wait-waitPayment')).getByRole('button', { name: 'Revoke the token for Wait for the payment' })).toBeVisible())
    await user.click(screen.getByRole('button', { name: 'Revoke the token for Wait for the payment' }))
    expect(await screen.findByText(/no longer works\. The step still waits/)).toBeVisible()
    expect(screen.queryByTestId('callback-token-once')).toBeNull()
    expect(mockCallbackWaits()[0]?.tokenIssued).toBe(false)
  })

  it('offers no token actions once the wait is answered or closed', async () => {
    seedCallbackMocks({ waits: [{ ...wait, state: 'cancelled', reason: 'timedOut' }] })
    mount()
    const row = await screen.findByTestId('callback-wait-waitPayment')
    expect(row).toHaveTextContent('No longer waiting (the time limit passed)')
    expect(within(row).queryByRole('button')).toBeNull()
  })
})
