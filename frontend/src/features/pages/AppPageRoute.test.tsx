import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { describe, expect, it } from 'vitest'
import { claimTask, getTask, listTasks, startRun } from '@/api/endpoints'
import { setCurrentUser } from '@/mocks/handlers'
import { PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import { uuid } from '@/lib/text'
import { renderAppReady, signInAs, WS } from '@/test/utils'

const axeOptions = { iframes: false, rules: { 'color-contrast': { enabled: false } } }

describe('application page runtime with mock API (S111)', () => {
  it('lets a requester save a draft and submit through pinned chains, then follows the navigation step', async () => {
    signInAs('rhea.requester')
    const user = userEvent.setup()
    const { router, container } = await renderAppReady(`/w/${WS}/apps/purchase-desk`)
    expect(await screen.findByRole('heading', { level: 1, name: 'Purchase desk' })).toBeInTheDocument()
    await user.type(screen.getByLabelText(/What are you buying\?/), 'Standing desk')
    await user.type(screen.getByLabelText(/Business purpose/), 'Back pain')
    await user.selectOptions(screen.getByLabelText(/Cost center/), 'IT')
    await user.type(screen.getByLabelText(/Amount \(USD\)/), '420')
    const bar = screen.getByRole('region', { name: 'Send' })
    await user.click(within(bar).getByRole('button', { name: 'Save draft' }))
    expect(await within(bar).findByText('Save draft: completed')).toBeInTheDocument()
    expect((await axe.run(container, axeOptions)).violations).toEqual([])

    await user.click(within(bar).getByRole('button', { name: 'Submit and view my requests' }))
    await waitFor(() => expect(router.state.location.pathname).toBe(`/w/${WS}/requests`))
  }, 30_000)

  it('shows the form read-only to a member who cannot start it, and the server refuses the chain', async () => {
    signInAs('vic.viewer')
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS}/apps/purchase-desk`)
    expect(await screen.findByText('You cannot start this request.')).toBeInTheDocument()
    expect(screen.getByLabelText(/What are you buying\?/)).toBeDisabled()
    const bar = screen.getByRole('region', { name: 'Send' })
    await user.click(within(bar).getByRole('button', { name: 'Submit and view my requests' }))
    const outcome = await within(bar).findByText('Submit and view my requests: stopped')
    expect(outcome.closest('[role="status"]')).toHaveTextContent(/Submit request\s*failed/)
    expect(outcome.closest('[role="status"]')).toHaveTextContent(/Go to page\s*skipped/)
  })

  it('claims and approves a task through the approval desk, and stops at the first failure when the claim is refused', async () => {
    signInAs('rhea.requester')
    for (const title of ['Monitor arm', 'Headset']) await startRun(WS, { workflowId: PURCHASE_WORKFLOW_ID, input: { title, purpose: 'Team kit', costCenter: 'IT', amount: 80 } }, uuid())
    setCurrentUser('mani.manager')
    const user = userEvent.setup()
    const claimable = await listTasks(WS, 'claimable')
    const [first, second] = claimable.items.filter(task => ['Monitor arm', 'Headset'].includes(task.requestTitle ?? ''))
    expect(first && second).toBeTruthy()

    // Another manager claims the second task first; the chain must stop at its claim step.
    setCurrentUser('max.manager')
    await claimTask(WS, second!.id, (await getTask(WS, second!.id)).version)
    setCurrentUser('mani.manager')
    const stopped = await renderAppReady(`/w/${WS}/apps/approval-desk/${second!.id}`)
    expect(await screen.findByRole('heading', { level: 1, name: 'Approval desk' })).toBeInTheDocument()
    const decide = screen.getByRole('region', { name: 'Decide' })
    await user.click(within(decide).getByRole('button', { name: 'Claim and approve' }))
    const outcome = await within(decide).findByText('Claim and approve: stopped')
    expect(outcome.closest('[role="status"]')).toHaveTextContent(/Claim task\s*failed/)
    expect(outcome.closest('[role="status"]')).toHaveTextContent(/Approve\s*skipped/)
    expect(stopped.router.state.location.pathname).toBe(`/w/${WS}/apps/approval-desk/${second!.id}`)
    expect(screen.getByTitle('Request badge')).toHaveAttribute('sandbox', 'allow-scripts')
    expect((await axe.run(stopped.container, axeOptions)).violations).toEqual([])
    stopped.unmount()

    const { router } = await renderAppReady(`/w/${WS}/apps/approval-desk/${first!.id}`)
    const region = await screen.findByRole('region', { name: 'Decide' })
    await user.click(within(region).getByRole('button', { name: 'Claim and approve' }))
    await waitFor(() => expect(router.state.location.pathname).toBe(`/w/${WS}/tasks`))
    expect((await getTask(WS, first!.id)).status).not.toBe('open')
  }, 30_000)

  it('lists routed pages for members', async () => {
    signInAs('rhea.requester')
    await renderAppReady(`/w/${WS}/apps`)
    expect(await screen.findByRole('link', { name: 'Purchase desk' })).toHaveAttribute('href', `/w/${WS}/apps/purchase-desk`)
    expect(screen.getByRole('link', { name: 'Approval desk' })).toBeInTheDocument()
  })
})
