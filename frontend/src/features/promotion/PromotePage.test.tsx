import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { mockPromotionRequests, setMockStage } from '@/mocks/promotion'
import { db, PURCHASE_WORKFLOW_ID, WS_SANDBOX } from '@/mocks/state'
import { renderAppReady, signInAs, WS } from '@/test/utils'

function releaseId() {
  return db.workflows.get(PURCHASE_WORKFLOW_ID)!.releases[0]!.id
}

/** The purchasing workspace is the early stage, the sandbox the later one, and the sandbox has the connection bound. */
function stages(options: { gate?: boolean; bound?: boolean } = {}) {
  const { gate = false, bound = true } = options
  setMockStage(WS, 'development')
  setMockStage(WS_SANDBOX, 'production', gate)
  if (bound) {
    const binding = db.bindings.get(`${WS}:procurement`)!
    db.bindings.set(`${WS_SANDBOX}:procurement`, { ...binding })
  }
}

const path = () => `/w/${WS}/workflows/${PURCHASE_WORKFLOW_ID}/releases/${releaseId()}/promote`

async function chooseTarget(user: ReturnType<typeof userEvent.setup>) {
  const select = await screen.findByLabelText('Promote to')
  await waitFor(() => expect(within(select).getByRole('option', { name: /Purchasing sandbox \(Production\)/ })).toBeEnabled())
  await user.selectOptions(select, WS_SANDBOX)
  await user.click(screen.getByRole('button', { name: 'Run compatibility check' }))
}

// These flows render the whole application and several screens; allow for a loaded machine.
vi.setConfig({ testTimeout: 40_000 })

describe('promotion screen (PL02)', () => {
  it('shows what blocks a promotion in the target and offers no promote button until it is fixed', async () => {
    signInAs('dana.designer', { published: true, sampleRuns: false })
    stages({ bound: false })
    const user = userEvent.setup()
    await renderAppReady(path())

    await chooseTarget(user)
    expect(await screen.findByTestId('compatibility')).toHaveTextContent('Not compatible')
    const connections = screen.getByRole('table', { name: /Connections used by this version/ })
    expect(within(connections).getByText('procurement')).toBeInTheDocument()
    expect(within(connections).getByText('No address configured here')).toBeInTheDocument()
    const groups = screen.getByRole('table', { name: /Approval groups used by this version/ })
    expect(within(groups).getByText('managers')).toBeInTheDocument()
    expect(within(groups).getAllByText('Exists').length).toBeGreaterThan(0)
    const findings = screen.getByRole('list', { name: 'Compiler findings' })
    expect(within(findings).getByText('Blocks promotion')).toBeInTheDocument()
    expect(within(findings).getByText(/no address configured in the target/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Promote as draft' })).not.toBeInTheDocument()
    expect([...db.workflows.values()].filter((w) => w.wsId === WS_SANDBOX)).toHaveLength(0)
  })

  it('promotes as a draft, then adds the next draft revision with the revision from the check, and lists the history', async () => {
    signInAs('dana.designer', { published: true, sampleRuns: false })
    stages()
    const user = userEvent.setup()
    await renderAppReady(path())

    await chooseTarget(user)
    expect(await screen.findByTestId('compatibility')).toHaveTextContent('Compatible')
    expect(screen.getByText(/does not have this workflow yet/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Promote as draft' }))
    expect(await screen.findByTestId('promotion-outcome')).toHaveTextContent('Created the workflow in Purchasing sandbox at draft revision 1. It is not published.')
    const created = [...db.workflows.values()].filter((w) => w.wsId === WS_SANDBOX)
    expect(created).toHaveLength(1)
    expect(created[0]!.releases).toHaveLength(0)
    expect(created[0]!.activeReleaseId).toBeNull()

    // The target now has the workflow: the check says so, and the promotion names the revision it saw.
    await user.click(screen.getByRole('button', { name: 'Run compatibility check' }))
    expect(await screen.findByText(/already has this workflow.*current draft revision 1/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Promote as draft' }))
    expect(await screen.findByTestId('promotion-outcome')).toHaveTextContent('Added a new draft in Purchasing sandbox at draft revision 2')
    expect(created[0]!.drafts.map((d) => d.revision)).toEqual([1, 2])

    const history = await screen.findByRole('table', { name: 'Promotions of this workflow' })
    await waitFor(() => expect(within(history).getAllByRole('row')).toHaveLength(3))
  })

  it('tells the person to check again when the target draft changed after the check', async () => {
    signInAs('dana.designer', { published: true, sampleRuns: false })
    stages()
    const user = userEvent.setup()
    await renderAppReady(path())

    await chooseTarget(user)
    await user.click(await screen.findByRole('button', { name: 'Promote as draft' }))
    await screen.findByTestId('promotion-outcome')
    await user.click(screen.getByRole('button', { name: 'Run compatibility check' }))
    const promote = await screen.findByRole('button', { name: 'Promote as draft' })
    const target = [...db.workflows.values()].find((w) => w.wsId === WS_SANDBOX)!
    target.drafts.push({ ...target.drafts[0]!, revision: 2 })
    await user.click(promote)
    expect(await screen.findByText('The target changed')).toBeInTheDocument()
    expect(screen.getByText(/Run the compatibility check again/)).toBeInTheDocument()
    expect(target.drafts).toHaveLength(2)
  })

  it('asks for approval instead of promoting when the target is a gated production workspace', async () => {
    signInAs('dana.designer', { published: true, sampleRuns: false })
    stages({ gate: true })
    const user = userEvent.setup()
    await renderAppReady(path())

    const select = await screen.findByLabelText('Promote to')
    await waitFor(() => expect(within(select).getByRole('option', { name: /Purchasing sandbox/ })).toBeEnabled())
    await user.selectOptions(select, WS_SANDBOX)
    expect(await screen.findByTestId('approval-gate-note')).toHaveTextContent('Approval required')
    await user.click(screen.getByRole('button', { name: 'Run compatibility check' }))
    expect(await screen.findByText('Approval needed')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Promote as draft' })).not.toBeInTheDocument()
    await user.type(screen.getByLabelText(/Note for the approver/), 'Quarter close')
    await user.click(screen.getByRole('button', { name: 'Request approval' }))
    expect(await screen.findByTestId('promotion-outcome')).toHaveTextContent('waiting for approval. Nothing has been created there yet.')
    expect(mockPromotionRequests()).toHaveLength(1)
    expect(mockPromotionRequests()[0]).toMatchObject({ status: 'pending', note: 'Quarter close', requestedBy: 'Dana Designer' })
    expect([...db.workflows.values()].filter((w) => w.wsId === WS_SANDBOX)).toHaveLength(0)
  })

  it('does not offer a workspace at an earlier or equal stage', async () => {
    signInAs('dana.designer', { published: true, sampleRuns: false })
    setMockStage(WS, 'production')
    setMockStage(WS_SANDBOX, 'test')
    await renderAppReady(path())
    const select = await screen.findByLabelText('Promote to')
    const option = await within(select).findByRole('option', { name: /Purchasing sandbox \(Test\) - not a later stage/ })
    expect(option).toBeDisabled()
  })

  it('is closed to someone who may not export releases from this workspace', async () => {
    signInAs('olga.operator', { published: true, sampleRuns: false })
    await renderAppReady(path())
    expect(await screen.findByText(/You need permission to export releases/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Promote to')).not.toBeInTheDocument()
  })
})
