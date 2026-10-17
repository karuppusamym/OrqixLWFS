import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { db, type MockRun } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'
import { server } from '@/test/server'

function runByKey(key: string): MockRun {
  for (const run of db.runs.values()) if (run.businessKey === key) return run
  throw new Error(`No run ${key}`)
}

const diagnosis = (state: string, repairable: boolean, message: string, version: number) =>
  HttpResponse.json({ state, repairable, message, projectedNodeIds: [], engineNodeIds: ['manager'], version })

describe('consistency check (EX09)', () => {
  it('offers a rebuild only for repairable drift and sends the reason, version and an idempotency key', async () => {
    signInAs('olga.operator')
    const user = userEvent.setup()
    const run = runByKey('ORQ-1005')
    let sent: { body: unknown; key: string | null } | undefined
    server.use(
      http.get('*/api/v1/workspaces/:ws/runs/:id/diagnosis', () => diagnosis('projectionDrift', true, 'Open approvals are missing.', run.version)),
      http.post('*/api/v1/workspaces/:ws/runs/:id/repair', async ({ request }) => {
        sent = { body: await request.json(), key: request.headers.get('Idempotency-Key') }
        return HttpResponse.json({ type: 'about:blank', title: 'Conflict', status: 409, code: 'RUN_NOT_REPAIRABLE' }, { status: 409, headers: { 'Content-Type': 'application/problem+json' } })
      }),
    )
    renderApp(`/w/${WS}/runs/${run.id}`)
    await user.click(await screen.findByRole('button', { name: 'Check consistency' }))
    const dialog = await screen.findByRole('dialog', { name: 'Check this request' })
    expect(await within(dialog).findByText('Open approvals are missing from this request')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Rebuild approval steps' }))
    expect(within(dialog).getByText(/Add a reason/)).toBeInTheDocument()
    expect(sent).toBeUndefined()
    await user.type(within(dialog).getByLabelText(/Reason/), 'Projection lost after restore')
    await user.click(within(dialog).getByRole('button', { name: 'Rebuild approval steps' }))
    await waitFor(() => expect(sent).toBeDefined())
    expect(sent?.body).toEqual({ reason: 'Projection lost after restore', expectedVersion: run.version })
    expect(sent?.key).toBeTruthy()
  })

  it('never offers a rebuild for a stranded token, only cancellation', async () => {
    signInAs('olga.operator')
    const user = userEvent.setup()
    const run = runByKey('ORQ-1005')
    server.use(http.get('*/api/v1/workspaces/:ws/runs/:id/diagnosis', () => diagnosis('strandedToken', false, 'Nothing can move the token.', run.version)))
    renderApp(`/w/${WS}/runs/${run.id}`)
    await user.click(await screen.findByRole('button', { name: 'Check consistency' }))
    const dialog = await screen.findByRole('dialog', { name: 'Check this request' })
    expect(await within(dialog).findByText('A step is stuck and nothing can move it')).toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: 'Rebuild approval steps' })).not.toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: /Cancel request/ }))
    expect(await screen.findByRole('dialog', { name: /Cancel/ })).toBeInTheDocument()
  })

  it('does not show the check to requesters', async () => {
    signInAs('rhea.requester')
    const run = runByKey('ORQ-1005')
    renderApp(`/w/${WS}/runs/${run.id}`)
    await screen.findByTestId('run-status')
    expect(screen.queryByRole('button', { name: 'Check consistency' })).not.toBeInTheDocument()
  })
})
