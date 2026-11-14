import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { server } from '@/test/server'
import { setCurrentUser } from '@/mocks/handlers'
import { resetConnectionHealthMocks, seedConnectionHealth } from '@/mocks/connectionHealth'
import { ConnectionHealthNotices, ConnectionHealthSection } from './ConnectionHealthPanel'
import { formatLatency, formatRate } from './connectionHealthApi'

const permission = vi.hoisted(() => ({ manage: true }))
vi.mock('@/app/workspace', async () => {
  const state = await import('@/mocks/state')
  return { useWorkspace: () => ({ workspace: { id: state.WS_PURCHASING }, can: (p: string) => (p === 'connection.manage' ? permission.manage : true) }) }
})

function mount(node: React.ReactNode) {
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>{node}</QueryClientProvider>)
}

describe('connection health (CG-C09, S131)', () => {
  beforeEach(() => {
    permission.manage = true
    setCurrentUser('wade.admin')
    resetConnectionHealthMocks()
  })

  it('shows success rate, latency, 429s and the last error of a binding', async () => {
    mount(<ConnectionHealthSection connectionKey="procurement" name="Procurement system" />)
    expect(await screen.findByTestId('connection-health-procurement')).toBeInTheDocument()
    expect(screen.getByTestId('circuit-procurement')).toHaveTextContent('Calls flowing')
    expect(screen.getByRole('row', { name: /Last hour 42 97.6 % 180 ms 640 ms/ })).toBeInTheDocument()
    expect(screen.getByTestId('connection-last-error-procurement')).toHaveTextContent('RATE_LIMITED (HTTP 429)')
    expect(screen.getByText('No limit')).toBeInTheDocument()
  })

  it('raises an operator notice for an open circuit, in the card and above the list', async () => {
    seedConnectionHealth('procurement', {
      circuit: { state: 'open', consecutiveFailures: 10, failureThreshold: 10, openedAt: '2026-11-05T10:00:00Z', openUntil: '2026-11-05T10:01:00Z', reason: '10 consecutive failures (last http_503)', timesOpened: 1 },
      notices: [{ code: 'CIRCUIT_OPEN', severity: 'critical', message: 'Calls to Procurement system are paused until 2026-11-05T10:01:00Z.' }],
    })
    mount(<><ConnectionHealthNotices /><ConnectionHealthSection connectionKey="procurement" name="Procurement system" /></>)
    expect(await screen.findByTestId('connection-notice-procurement-CIRCUIT_OPEN')).toHaveTextContent('are paused')
    expect(await screen.findByTestId('circuit-procurement')).toHaveTextContent('Calls paused')
    expect(screen.getByTestId('circuit-procurement')).toHaveTextContent('until')
  })

  it('sets and clears the request limit, and checks the number first', async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    server.events.on('request:start', ({ request }) => {
      if (request.method === 'PUT' && request.url.includes('/rate-limit')) void request.clone().json().then((b) => sent.push(b))
    })
    mount(<ConnectionHealthSection connectionKey="procurement" name="Procurement system" />)
    const input = await screen.findByLabelText('Requests a minute')
    await user.type(input, '0')
    await user.click(screen.getByRole('button', { name: 'Save limit' }))
    expect(await screen.findByText(/Enter a whole number from 1 to 100000/)).toBeInTheDocument()
    await user.clear(input)
    await user.type(input, '120')
    await user.click(screen.getByRole('button', { name: 'Save limit' }))
    expect(await screen.findByText('120 a minute (120 on each of 1 server)')).toBeInTheDocument()
    await user.clear(screen.getByLabelText('Requests a minute'))
    await user.click(screen.getByRole('button', { name: 'Save limit' }))
    expect(await screen.findByText('No limit')).toBeInTheDocument()
    await waitFor(() => expect(sent).toEqual([{ maxRequestsPerMinute: 120 }, { maxRequestsPerMinute: null }]))
    server.events.removeAllListeners()
  })

  it('hides the limit form without connection.manage and reports a failed read', async () => {
    permission.manage = false
    mount(<ConnectionHealthSection connectionKey="procurement" name="Procurement system" />)
    expect(await screen.findByTestId('connection-health-procurement')).toBeInTheDocument()
    expect(screen.queryByLabelText('Requests a minute')).not.toBeInTheDocument()
    server.use(http.get('*/api/v1/workspaces/:ws/connections/:key/health', () => HttpResponse.json({ type: 'about:blank', title: 'Down', status: 503, code: 'DEPENDENCY_UNAVAILABLE' }, { status: 503, headers: { 'Content-Type': 'application/problem+json' } })))
    mount(<ConnectionHealthSection connectionKey="vendor-risk-assistant" name="Vendor risk assistant" />)
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.queryByTestId('connection-health-vendor-risk-assistant')).not.toBeInTheDocument()
  })

  it('formats rates and latency', () => {
    expect(formatRate(null)).toBe('No calls')
    expect(formatRate(1)).toBe('100 %')
    expect(formatRate(0.976)).toBe('97.6 %')
    expect(formatLatency(null)).toBe('–')
    expect(formatLatency(180)).toBe('180 ms')
    expect(formatLatency(1400)).toBe('1.4 s')
  })
})
