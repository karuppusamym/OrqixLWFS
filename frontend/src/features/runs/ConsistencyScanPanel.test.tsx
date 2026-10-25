import { screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { db, type MockRun } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'
import { server } from '@/test/server'

function runByKey(key: string): MockRun {
  for (const run of db.runs.values()) if (run.businessKey === key) return run
  throw new Error(`No run ${key}`)
}

const finding = (runId: string, over: Record<string, unknown> = {}) => ({
  runId,
  reference: 'ORQ-1005',
  state: 'projectionDrift',
  repairable: true,
  message: 'The projection does not show the steps the engine holds.',
  version: 3,
  firstSeenAt: '2026-10-18T10:00:00Z',
  checkedAt: '2026-10-18T10:05:00Z',
  ...over,
})

describe('consistency scan findings (EX09, S72)', () => {
  it('lists drifted requests for operators with a link to review each, and says the scan only reports', async () => {
    signInAs('olga.operator')
    const run = runByKey('ORQ-1005')
    server.use(
      http.get('*/api/v1/workspaces/:ws/consistency-findings', () =>
        HttpResponse.json({
          enabled: true,
          scannedRuns: 12,
          lastCheckedAt: '2026-10-18T10:05:00Z',
          total: 2,
          items: [finding(run.id), finding('00000000-0000-7000-8000-0000000000aa', { reference: 'ORQ-1009', state: 'strandedToken', repairable: false, message: 'Nothing can move the token.' })],
        }),
      ),
    )
    renderApp(`/w/${WS}/runs`)
    const panel = await screen.findByRole('region', { name: 'Consistency scan' })
    expect(within(panel).getByText(/Checked 12 active requests/)).toBeInTheDocument()
    expect(within(panel).getByText(/The scan only reports/)).toBeInTheDocument()
    const list = within(panel).getByRole('list', { name: /do not match the workflow engine/ })
    expect(within(list).getAllByRole('listitem')).toHaveLength(2)
    expect(within(list).getByText('Open steps are missing from the request')).toBeInTheDocument()
    expect(within(list).getByText('Can be rebuilt', { exact: false })).toBeInTheDocument()
    expect(within(list).getByText('Needs a decision', { exact: false })).toBeInTheDocument()
    expect(within(list).getByRole('link', { name: 'Review ORQ-1005' })).toHaveAttribute('href', `/w/${WS}/runs/${run.id}#operator-actions`)
    // No repair control here: repair stays on the request, behind a reason.
    expect(within(panel).queryByRole('button', { name: /Rebuild|Repair/ })).not.toBeInTheDocument()
  })

  it('is silent when the scan is off and nothing was found', async () => {
    signInAs('olga.operator')
    renderApp(`/w/${WS}/runs`)
    await screen.findByRole('heading', { name: 'Runs' })
    expect(screen.queryByRole('region', { name: 'Consistency scan' })).not.toBeInTheDocument()
  })

  it('says everything matches when the scan is on and clean', async () => {
    signInAs('olga.operator')
    server.use(http.get('*/api/v1/workspaces/:ws/consistency-findings', () => HttpResponse.json({ enabled: true, scannedRuns: 1, lastCheckedAt: null, total: 0, items: [] })))
    renderApp(`/w/${WS}/runs`)
    const panel = await screen.findByRole('region', { name: 'Consistency scan' })
    expect(within(panel).getByText(/Checked 1 active request\./)).toBeInTheDocument()
    expect(within(panel).getByText(/Nothing found/)).toBeInTheDocument()
  })

  it('shows the finding on the request itself for operators', async () => {
    signInAs('olga.operator')
    const run = runByKey('ORQ-1005')
    server.use(
      http.get('*/api/v1/workspaces/:ws/consistency-findings', () =>
        HttpResponse.json({ enabled: true, scannedRuns: 3, lastCheckedAt: null, total: 1, items: [finding(run.id)] }),
      ),
    )
    renderApp(`/w/${WS}/runs/${run.id}`)
    expect(await screen.findByText('The periodic scan found a mismatch with the workflow engine')).toBeInTheDocument()
    expect(screen.getByText(/Use Check consistency to rebuild it/)).toBeInTheDocument()
  })

  it('never asks requesters for findings', async () => {
    signInAs('rhea.requester')
    let asked = false
    server.use(http.get('*/api/v1/workspaces/:ws/consistency-findings', () => { asked = true; return HttpResponse.json({}, { status: 403 }) }))
    const run = runByKey('ORQ-1005')
    renderApp(`/w/${WS}/runs/${run.id}`)
    await screen.findByTestId('run-status')
    expect(asked).toBe(false)
    expect(screen.queryByText('The periodic scan found a mismatch with the workflow engine')).not.toBeInTheDocument()
  })
})
