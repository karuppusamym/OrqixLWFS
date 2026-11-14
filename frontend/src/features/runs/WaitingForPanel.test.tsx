import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { components } from '@/api/schema'
import { server } from '@/test/server'
import { Providers } from '@/app/providers'
import { testQueryClient } from '@/test/utils'
import { WaitingForPanel } from './WaitingForPanel'

const renderWithProviders = (element: React.ReactElement) => render(<Providers client={testQueryClient()}>{element}</Providers>)

type S = components['schemas']

const check: S['PollCheck'] = {
  waitId: '00000000-0000-7000-8000-00000000a001', kind: 'check', state: 'waiting', checksDone: 3, maxChecks: 52, consecutiveErrors: 0,
  startedAt: '2026-11-05T10:00:00Z', lastCheckedAt: '2026-11-05T10:15:00Z', lastHttpStatus: 200, lastOutcome: 'pending',
  deadlineAt: '2026-11-07T10:00:00Z', authoredDeadlineAt: '2026-11-07T10:00:00Z', extensionLimit: '2026-11-09T10:00:00Z',
  deadlineExtensions: 0, shown: { state: 'In progress' }, canOperate: true, checkNowAvailableAt: null, endedAt: null, endReason: null,
}
const poll: S['RunWait'] = {
  nodeId: 'track', kind: 'poll', label: 'Track the order', subject: 'Order tracking to report that “Track the order” is done (state: In progress)',
  since: '2026-11-05T10:00:00Z', nextCheckAt: '2026-11-05T10:35:00Z', dueAt: null, deadlineAt: '2026-11-07T10:00:00Z', attempts: 3,
  correlationRef: null, connectionKey: 'order-status', check,
}

describe('Waiting for panel (docs/build/S129, CG-W08)', () => {
  it('shows what the request waits for and lets an operator check now with a reason', async () => {
    let body: unknown = null
    let key: string | null = null
    server.use(
      http.get('/api/v1/workspaces/:ws/runs/:runId/waits', () => HttpResponse.json({ items: [poll], endedChecks: [] } satisfies S['RunWaitList'])),
      http.post('/api/v1/workspaces/:ws/runs/:runId/waits/:waitId/check-now', async ({ request }) => {
        body = await request.json()
        key = request.headers.get('Idempotency-Key')
        return HttpResponse.json({ ...check, nextCheckAt: undefined } as unknown as S['PollCheck'])
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(<WaitingForPanel ws="ws1" runId="run1" />)
    const item = await screen.findByTestId('wait-track')
    expect(item).toHaveTextContent('Checking another system')
    expect(item).toHaveTextContent('Order tracking to report that')
    expect(item).toHaveTextContent('checked 3 of at most 52 times')
    expect(screen.getByTestId('wait-check')).toHaveTextContent('last answer state: In progress')
    await user.click(screen.getByRole('button', { name: 'Check now: Track the order' }))
    await user.click(screen.getByRole('button', { name: 'Check now' }))
    expect(await screen.findByText('Add a reason. It is recorded in the audit log.')).toBeInTheDocument()
    await user.type(screen.getByLabelText(/Reason/), 'Vendor says it is done')
    await user.click(screen.getByRole('button', { name: 'Check now' }))
    await waitFor(() => expect(body).toEqual({ reason: 'Vendor says it is done' }))
    expect(key).toBeTruthy()
  })

  it('offers only the extensions that fit the limit', async () => {
    server.use(http.get('/api/v1/workspaces/:ws/runs/:runId/waits', () => HttpResponse.json({ items: [poll], endedChecks: [] } satisfies S['RunWaitList'])))
    const user = userEvent.setup()
    renderWithProviders(<WaitingForPanel ws="ws1" runId="run1" />)
    await user.click(await screen.findByRole('button', { name: 'Extend the deadline: Track the order' }))
    const options = Array.from((screen.getByLabelText('Extend by') as HTMLSelectElement).options).map((o) => o.textContent)
    expect(options).toEqual(['1 hour', '6 hours', '1 day', '2 days'])
  })

  it('renders nothing when the request waits for nothing', async () => {
    server.use(http.get('/api/v1/workspaces/:ws/runs/:runId/waits', () => HttpResponse.json({ items: [], endedChecks: [] } satisfies S['RunWaitList'])))
    renderWithProviders(<WaitingForPanel ws="ws1" runId="run1" />)
    await waitFor(() => expect(screen.queryByTestId('waiting-for')).not.toBeInTheDocument())
  })
})
