import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import * as downloads from '@/lib/download'
import { server } from '@/test/server'
import { renderAppReady, signInAs, WS } from '@/test/utils'
import type { InsightsThroughput } from './api'
import { rangeFor } from './format'

describe('Insights report downloads', () => {
  it('downloads an aggregate-only report for the selected filters', async () => {
    signInAs('olga.operator', { demo: true })
    const download = vi.spyOn(downloads, 'downloadJson').mockImplementation(() => {})
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS}/insights?days=7`)
    const button = await screen.findByRole('button', { name: 'Download report (JSON)' })
    await waitFor(() => expect(button).toBeEnabled())
    await user.click(button)
    const [report, filename] = download.mock.calls[0]!
    expect(report).toMatchObject({ format: 'orqix.insights', version: 1, workspace: { id: WS }, filters: { ...rangeFor(7), workflowId: null } })
    expect(filename).toMatch(/^orqix-insights-\d{4}-\d{2}-\d{2}-to-\d{4}-\d{2}-\d{2}\.json$/)
    expect(JSON.stringify(report)).not.toMatch(/ORQ-1009|oldestOpen|runId|Replacement laptop/)
    expect(screen.getByRole('button', { name: 'Download daily CSV' })).toBeEnabled()
  })

  it('blocks exports while old charts are retained during a period change', async () => {
    signInAs('olga.operator', { demo: true })
    let release!: () => void
    const held = new Promise<void>(resolve => { release = resolve })
    const from = rangeFor(7).from
    server.use(http.get('/api/v1/workspaces/:ws/insights/throughput', async ({ request }) => {
      const url = new URL(request.url)
      if (url.searchParams.get('from') !== from) return
      await held
      return HttpResponse.json({ range: { ...rangeFor(7), days: 7, generatedAt: new Date().toISOString() }, days: [] } satisfies InsightsThroughput)
    }))
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS}/insights`)
    const button = await screen.findByRole('button', { name: 'Download report (JSON)' })
    await waitFor(() => expect(button).toBeEnabled())
    try {
      await user.click(screen.getByRole('button', { name: 'Last 7 days' }))
      expect(button).toBeDisabled()
      expect(screen.getByRole('button', { name: 'Download daily CSV' })).toBeDisabled()
    } finally { release() }
    await waitFor(() => expect(button).toBeEnabled())
  })

  it('keeps report download unavailable after one section fails', async () => {
    signInAs('olga.operator', { demo: true })
    server.use(http.get('/api/v1/workspaces/:ws/insights/steps', () => HttpResponse.json({ code: 'INTERNAL_ERROR', status: 500, title: 'Unavailable' }, { status: 500 })))
    await renderAppReady(`/w/${WS}/insights`)
    const button = await screen.findByRole('button', { name: 'Download report (JSON)' })
    await screen.findByRole('button', { name: 'Try again' }, { timeout: 5000 })
    expect(button).toBeDisabled()
  })

  it('does not offer report downloads to a requester', async () => {
    signInAs('rhea.requester', { demo: true })
    await renderAppReady(`/w/${WS}/insights`)
    await screen.findByText('No access')
    expect(screen.queryByRole('button', { name: /Download/ })).not.toBeInTheDocument()
  })
})
