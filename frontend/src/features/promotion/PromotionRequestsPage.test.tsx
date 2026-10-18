import { cleanup, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { setCurrentUser } from '@/mocks/handlers'
import { grantMockApprover, mockPromotionRequests, setMockStage } from '@/mocks/promotion'
import { db, PURCHASE_WORKFLOW_ID, userByName, WS_SANDBOX } from '@/mocks/state'
import { renderAppReady, signInAs, WS } from '@/test/utils'

/** Dana asks, through the real promote screen, to promote into the gated sandbox (production-stage here). */
async function danaAsks() {
  setMockStage(WS, 'development')
  setMockStage(WS_SANDBOX, 'production', true)
  db.bindings.set(`${WS_SANDBOX}:procurement`, { ...db.bindings.get(`${WS}:procurement`)! })
  const release = db.workflows.get(PURCHASE_WORKFLOW_ID)!.releases[0]!
  const user = userEvent.setup()
  await renderAppReady(`/w/${WS}/workflows/${PURCHASE_WORKFLOW_ID}/releases/${release.id}/promote`)
  const select = await screen.findByLabelText('Promote to')
  await waitFor(() => expect(within(select).getByRole('option', { name: /Purchasing sandbox/ })).toBeEnabled())
  await user.selectOptions(select, WS_SANDBOX)
  await user.click(screen.getByRole('button', { name: 'Run compatibility check' }))
  await user.click(await screen.findByRole('button', { name: 'Request approval' }))
  await screen.findByTestId('promotion-outcome')
  cleanup()
}

// These flows render the whole application and several screens; allow for a loaded machine.
vi.setConfig({ testTimeout: 40_000 })

describe('promotion approvals (PL02)', () => {
  it('lets a second person with the permission approve, which saves the draft in the target', async () => {
    signInAs('dana.designer', { published: true, sampleRuns: false })
    await danaAsks()
    expect(mockPromotionRequests()).toHaveLength(1)

    setCurrentUser('paul.publisher')
    grantMockApprover(WS_SANDBOX, userByName('paul.publisher').id)
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS_SANDBOX}/promotions`)
    const row = (await screen.findByText('purchase-approval')).closest('tr')!
    expect(within(row).getByText('Waiting')).toBeInTheDocument()
    expect(within(row).getByText('Dana Designer')).toBeInTheDocument()
    await user.click(within(row).getByRole('button', { name: /Approve purchase-approval version 1/ }))
    await waitFor(() => expect(screen.getByText(/No request is waiting for approval/)).toBeInTheDocument())
    expect(mockPromotionRequests()[0]).toMatchObject({ status: 'approved', decidedBy: 'Paul Publisher' })
    const created = [...db.workflows.values()].filter((w) => w.wsId === WS_SANDBOX)
    expect(created).toHaveLength(1)
    expect(created[0]!.activeReleaseId).toBeNull()
    expect(created[0]!.ownerId).toBe(userByName('dana.designer').id)
    await user.click(screen.getByLabelText('Show decided requests'))
    expect(await screen.findByText('Approved')).toBeInTheDocument()
  })

  it('never offers the requester a decision, even with the permission, only to withdraw', async () => {
    signInAs('dana.designer', { published: true, sampleRuns: false })
    await danaAsks()
    grantMockApprover(WS_SANDBOX, userByName('dana.designer').id)
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS_SANDBOX}/promotions`)
    const row = (await screen.findByText('purchase-approval')).closest('tr')!
    expect(within(row).queryByRole('button', { name: /^Approve/ })).not.toBeInTheDocument()
    expect(within(row).queryByRole('button', { name: /^Reject/ })).not.toBeInTheDocument()
    expect(within(row).getByText(/You asked for this, so someone else must decide/)).toBeInTheDocument()
    await user.click(within(row).getByRole('button', { name: /Withdraw/ }))
    await waitFor(() => expect(mockPromotionRequests()[0]!.status).toBe('cancelled'))
    expect([...db.workflows.values()].filter((w) => w.wsId === WS_SANDBOX)).toHaveLength(0)
  })

  it('refuses to reject without a reason, then records it', async () => {
    signInAs('dana.designer', { published: true, sampleRuns: false })
    await danaAsks()
    setCurrentUser('paul.publisher')
    grantMockApprover(WS_SANDBOX, userByName('paul.publisher').id)
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS_SANDBOX}/promotions`)
    await user.click(await screen.findByRole('button', { name: /Reject purchase-approval/ }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Reject request' }))
    expect(await within(dialog).findByText('Say why the promotion is rejected.')).toBeInTheDocument()
    expect(mockPromotionRequests()[0]!.status).toBe('pending')
    await user.type(within(dialog).getByLabelText('Reason'), 'Wait for the freeze to end')
    await user.click(within(dialog).getByRole('button', { name: 'Reject request' }))
    await waitFor(() => expect(mockPromotionRequests()[0]).toMatchObject({ status: 'rejected', decisionNote: 'Wait for the freeze to end' }))
  })

  it('shows requests to a workspace member without the approval permission but no decision buttons', async () => {
    signInAs('dana.designer', { published: true, sampleRuns: false })
    await danaAsks()
    setCurrentUser('olga.operator')
    await renderAppReady(`/w/${WS_SANDBOX}/promotions`)
    const row = (await screen.findByText('purchase-approval')).closest('tr')!
    expect(within(row).queryByRole('button')).not.toBeInTheDocument()
  })

  it('lets a workspace administrator require approval for production only', async () => {
    signInAs('wade.admin', { published: true, sampleRuns: false })
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS}/promotions`)
    expect(await screen.findByTestId('stage-summary')).toHaveTextContent('This workspace is Production')
    expect(screen.getByTestId('stage-summary')).toHaveTextContent('without approval')
    const gate = screen.getByRole('checkbox', { name: /Require approval/ })
    await user.click(gate)
    await user.click(screen.getByRole('button', { name: 'Save stage' }))
    await waitFor(() => expect(screen.getByTestId('stage-summary')).toHaveTextContent('needs a second person to approve it'))

    await user.selectOptions(screen.getByRole('combobox', { name: 'Stage' }), 'staging')
    expect(screen.getByRole('checkbox', { name: /Require approval/ })).toBeDisabled()
    expect(screen.getByText(/Approval can only be required for a production workspace/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Save stage' }))
    await waitFor(() => expect(screen.getByTestId('stage-summary')).toHaveTextContent('This workspace is Staging'))
    expect(screen.getByTestId('stage-summary')).toHaveTextContent('without approval')
  })
})
