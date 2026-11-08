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

const TARGET = '00000000-0000-7000-8000-00000000f002'

describe('guarded move (CG-O03, docs/build/S143)', () => {
  it('shows the plan, says a second operator must approve, and sends kind, steps, reason, version and a key', async () => {
    signInAs('olga.operator')
    const user = userEvent.setup()
    const run = runByKey('ORQ-1005')
    let sent: { body: Record<string, unknown>; key: string | null } | undefined
    let planned: Record<string, unknown> | undefined
    server.use(
      http.get('*/api/v1/workspaces/:ws/runs/:id/moves', () => HttpResponse.json({ items: [] })),
      http.post('*/api/v1/workspaces/:ws/runs/:id/move-plan', async ({ request }) => {
        planned = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({
          kind: planned.kind, fromStepId: planned.fromStepId, fromLabel: 'Manager approval', toStepId: planned.toStepId,
          toLabel: 'Next', allowed: true, runVersion: 7, approvalRequired: true,
          approvalReason: 'This is a production workspace: a second operator must approve the move.',
          skipped: [{ nodeId: String(planned.fromStepId), label: 'Manager approval', kind: 'approval', handling: 'notRun' }],
          rerun: [], findings: [],
        })
      }),
      http.post('*/api/v1/workspaces/:ws/runs/:id/moves', async ({ request }) => {
        sent = { body: (await request.json()) as Record<string, unknown>, key: request.headers.get('Idempotency-Key') }
        return HttpResponse.json({
          id: '00000000-0000-7000-8000-00000000f010', runId: run.id, kind: 'skip', fromStepId: 'manager', toStepId: 'x',
          reason: 'Manager is away', state: 'pendingApproval', approvalRequired: true, expectedVersion: 7,
          requestedBy: '00000000-0000-7000-8000-000000000001', requestedAt: new Date().toISOString(), skipped: [], rerun: [],
        })
      }),
    )
    renderApp(`/w/${WS}/runs/${run.id}`)
    await user.click(await screen.findByRole('button', { name: 'Move to another step' }))
    const dialog = await screen.findByRole('dialog', { name: 'Move, skip or re-run a step' })
    const to = within(dialog).getByLabelText(/To the step/) as HTMLSelectElement
    await waitFor(() => expect(to.options.length).toBeGreaterThan(1))
    await user.selectOptions(to, to.options[1]!.value)
    await user.click(within(dialog).getByRole('button', { name: 'Check plan' }))
    expect(await within(dialog).findByText(/a second operator must approve/)).toBeInTheDocument()
    expect(within(dialog).getByText(/Manager approval is not run/)).toBeInTheDocument()
    expect(planned?.kind).toBe('skip')
    await user.click(within(dialog).getByRole('button', { name: 'Ask a second operator' }))
    expect(within(dialog).getByText(/Add a reason/)).toBeInTheDocument()
    expect(sent).toBeUndefined()
    await user.type(within(dialog).getByLabelText(/^Reason/), 'Manager is away')
    await user.click(within(dialog).getByRole('button', { name: 'Ask a second operator' }))
    await waitFor(() => expect(sent).toBeDefined())
    expect(sent?.body).toMatchObject({ kind: 'skip', toStepId: to.options[1]!.value, reason: 'Manager is away', expectedVersion: 7 })
    expect(sent?.key).toBeTruthy()
  })

  it('lists a move that waits for approval with approve and reject', async () => {
    signInAs('olga.operator')
    const user = userEvent.setup()
    const run = runByKey('ORQ-1005')
    let decided: string | undefined
    server.use(
      http.get('*/api/v1/workspaces/:ws/runs/:id/moves', () => HttpResponse.json({
        items: [{
          id: '00000000-0000-7000-8000-00000000f011', runId: run.id, kind: 'rerun', fromStepId: 'finance', fromLabel: 'Finance',
          toStepId: 'manager', toLabel: 'Manager', reason: 'Wrong amount', state: 'pendingApproval', approvalRequired: true,
          expectedVersion: 3, requestedBy: '00000000-0000-7000-8000-00000000ffff', requestedAt: new Date().toISOString(),
          skipped: [], rerun: [],
        }],
      })),
      http.post('*/api/v1/workspaces/:ws/runs/:id/moves/:move/reject', ({ params }) => {
        decided = String(params.move)
        return HttpResponse.json({ type: 'about:blank', title: 'Conflict', status: 409, code: 'MOVE_NOT_PENDING' }, { status: 409, headers: { 'Content-Type': 'application/problem+json' } })
      }),
    )
    renderApp(`/w/${WS}/runs/${run.id}`)
    await user.click(await screen.findByRole('button', { name: 'Move to another step' }))
    const dialog = await screen.findByRole('dialog', { name: 'Move, skip or re-run a step' })
    expect(await within(dialog).findByText(/A move waits for a second operator/)).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Approve and run' })).toBeEnabled()
    await user.click(within(dialog).getByRole('button', { name: 'Reject' }))
    await waitFor(() => expect(decided).toBe('00000000-0000-7000-8000-00000000f011'))
    expect(await within(dialog).findByText(/no longer waiting for approval/)).toBeInTheDocument()
  })
})

describe('migration with a step mapping (CG-O02, docs/build/S143)', () => {
  it('shows the refusal reasons of the dry run and keeps the move disabled', async () => {
    signInAs('olga.operator')
    const user = userEvent.setup()
    const run = runByKey('ORQ-1005')
    let planned: Record<string, unknown> | undefined
    server.use(
      http.get('*/api/v1/workspaces/:ws/runs/:id/migration-options', () => HttpResponse.json({
        current: { id: '00000000-0000-7000-8000-00000000f001', releaseNumber: 1 }, ready: false,
        notReadyReason: 'Suspend the request first.',
        options: [{ release: { id: TARGET, releaseNumber: 2 }, active: true, publishedAt: new Date().toISOString(), compatible: false, findings: [] }],
      })),
      http.post('*/api/v1/workspaces/:ws/runs/:id/migration-plan', async ({ request }) => {
        planned = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({
          runId: run.id, current: { id: '00000000-0000-7000-8000-00000000f001', releaseNumber: 1 },
          target: { id: TARGET, releaseNumber: 2 }, ready: true, hold: 'suspended', allowed: false, runVersion: 4,
          steps: [{ from: 'manager', to: 'manager', kind: 'approval', action: 'keep', label: 'Manager' }], dataCopies: [],
          findings: [{ code: 'DATA_MISSING', message: "The step 'Route' of the target release reads data of 'Size', which this request does not have.", nodeId: 'route' }],
          suggestedMappings: [],
        })
      }),
    )
    renderApp(`/w/${WS}/runs/${run.id}`)
    await user.click(await screen.findByRole('button', { name: 'Move with step mapping' }))
    const dialog = await screen.findByRole('dialog', { name: 'Move with a step mapping' })
    const version = within(dialog).getByLabelText(/Version to move to/) as HTMLSelectElement
    await waitFor(() => expect(version.options.length).toBe(2))
    await user.selectOptions(version, TARGET)
    await user.click(within(dialog).getByRole('button', { name: 'Check plan' }))
    expect(await within(dialog).findByTestId('migration-findings')).toHaveTextContent(/reads data of 'Size'/)
    expect(planned).toMatchObject({ targetReleaseId: TARGET, mappings: [] })
    expect(within(dialog).getByRole('button', { name: 'Move this request' })).toBeDisabled()
  })
})
