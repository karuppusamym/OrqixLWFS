import { render, screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { components } from '@/api/schema'
import { Providers } from '@/app/providers'
import { server } from '@/test/server'
import { testQueryClient } from '@/test/utils'
import { OutsideWaits } from './OutsideWaits'

type S = components['schemas']

describe('time spent waiting on outside systems (docs/build/S129, CG-W08)', () => {
  it('lists open and finished waits per system', async () => {
    server.use(http.get('/api/v1/workspaces/:ws/insights/outside-waits', () => HttpResponse.json({
      range: { from: '2026-11-01', to: '2026-11-05', days: 5, generatedAt: '2026-11-05T12:00:00Z' },
      totalOpenSeconds: 7200,
      items: [{ kind: 'poll', source: 'order-status', openNow: 2, longestOpenSeconds: 5400, totalOpenSeconds: 7200, finished: 4,
        averageFinishedSeconds: 1800, p90FinishedSeconds: 3000 }],
    } satisfies S['InsightsOutsideWaits'])))
    render(<Providers client={testQueryClient()}><OutsideWaits ws="ws1" query={{ from: '2026-11-01', to: '2026-11-05' }} enabled /></Providers>)
    expect(await screen.findByText('order-status')).toBeInTheDocument()
    expect(screen.getByText('Checking a system')).toBeInTheDocument()
    expect(screen.getByTestId('outside-waits')).toHaveTextContent('Waiting now in total')
  })
})
