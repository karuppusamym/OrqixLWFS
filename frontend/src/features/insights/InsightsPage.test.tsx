import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { renderApp, signInAs, WS } from '@/test/utils'
import { formatSeconds, rangeFor, stepsCsv } from './format'
import type { InsightsStep } from './api'

describe('insights formatting', () => {
  it('writes durations with two units at most', () => {
    expect(formatSeconds(45)).toBe('45 s')
    expect(formatSeconds(12 * 60)).toBe('12 min')
    expect(formatSeconds(3 * 3600 + 20 * 60)).toBe('3 h 20 min')
    expect(formatSeconds(2 * 86400 + 4 * 3600)).toBe('2 d 4 h')
    expect(formatSeconds(null)).toBe('—')
  })

  it('builds the date range ending today (UTC)', () => {
    expect(rangeFor(7, new Date('2026-10-11T03:00:00Z'))).toEqual({ from: '2026-10-05', to: '2026-10-11' })
  })

  it('keeps designer-written names from becoming spreadsheet formulas', () => {
    const step = {
      workflow: { id: 'w', key: 'k', name: '=HYPERLINK("http://x")' },
      nodeId: 'a',
      label: '+cmd|calc',
      kind: 'approval',
      count: 2,
      openNow: 0,
      averageWaitSeconds: 60,
      p90WaitSeconds: 90,
      timeouts: 1,
      timeoutShare: 0.5,
    } as InsightsStep
    const csv = stepsCsv([step])
    expect(csv).toContain(`"'=HYPERLINK(""http://x"")"`)
    expect(csv).toContain(`"'+cmd|calc"`)
    expect(csv.split('\r\n')[0]).toContain('Average wait (seconds)')
  })
})

describe('Insights page', () => {
  it('shows cards, chart with a table alternative, bottlenecks and lists to an operator, without request data', async () => {
    signInAs('olga.operator', { demo: true })
    renderApp(`/w/${WS}/insights`)
    expect(await screen.findByRole('heading', { name: 'Insights', level: 1 })).toBeInTheDocument()
    const cards = await screen.findByTestId('insights-cards')
    expect(within(screen.getByTestId('card-started')).getByText(/Requests started/)).toBeInTheDocument()
    expect(cards.textContent).toMatch(/Needing attention/)
    expect(await screen.findByTestId('throughput-chart')).toBeInTheDocument()
    expect(screen.getByText('Show the numbers as a table')).toBeInTheDocument()
    const table = await screen.findByTestId('bottleneck-table')
    expect(within(table).getByText('Review request')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Download CSV/ })).toBeEnabled()
    expect(screen.getByTestId('attention-list').textContent).toMatch(/ORQ-1009/)
    expect(screen.getByTestId('oldest-open').textContent).toMatch(/ORQ-100\d/)
    // Aggregates only: request titles never appear.
    expect(document.body.textContent).not.toMatch(/Replacement laptop|Ergonomic chairs|Label printer/)
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Last 7 days' }))
    expect(screen.getByRole('button', { name: 'Last 7 days' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('is closed to a requester', async () => {
    signInAs('rhea.requester', { demo: true })
    renderApp(`/w/${WS}/insights`)
    expect(await screen.findByText('No access')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Insights' })).not.toBeInTheDocument()
  })
})
