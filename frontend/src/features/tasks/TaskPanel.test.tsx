import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { claim, currentRelease, decide } from '@/mocks/engine'
import { db, userByName, type MockRun, type MockTask } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'
import { bindApprovalView } from '@/features/views/approvalBinding'
import type { ViewDefinition } from '@/features/views/model'

/** Find a seeded open task by the request title it shows. */
function taskByTitle(title: string): { run: MockRun; task: MockTask } {
  for (const run of db.runs.values()) {
    if (run.input.title === title) {
      const task = run.tasks.find((t) => t.status === 'open')
      if (task) return { run, task }
    }
  }
  throw new Error(`No open task for ${title}`)
}

async function openPanel(title: string, view: 'claimable' | 'assigned' = 'claimable') {
  const { task } = taskByTitle(title)
  const app = renderApp(`/w/${WS}/tasks/${task.id}?view=${view}`)
  await screen.findByTestId('task-actions')
  return { ...app, taskId: task.id }
}

describe('task panel: correcting declared request details (WM07)', () => {
  it('lets the task holder correct only the declared fields, with a reason, and records it', async () => {
    signInAs('max.manager')
    const { run, task } = taskByTitle('Replacement laptop')
    const manager = currentRelease(run).definition.nodes.find((n) => n.id === task.nodeId)!
    manager.config.correctable = ['amount']
    claim(run, task, userByName('max.manager'))
    const user = userEvent.setup()
    renderApp(`/w/${WS}/tasks/${task.id}?view=assigned`)
    const section = await screen.findByTestId('task-correction')
    expect(within(section).queryByLabelText('Business purpose')).not.toBeInTheDocument()
    const amount = within(section).getByLabelText(/Amount/)
    await user.clear(amount)
    await user.type(amount, '2500')
    await user.click(within(section).getByRole('button', { name: 'Save corrections' }))
    expect(await within(section).findByText(/Add a reason/)).toBeInTheDocument()
    await user.type(within(section).getByLabelText(/Reason for the correction/), 'Quote was updated')
    await user.click(within(section).getByRole('button', { name: 'Save corrections' }))
    await waitFor(() => expect(run.input.amount).toBe(2500))
    expect(run.events.at(-1)?.type).toBe('INPUT_CORRECTED')
    expect((run.events.at(-1)?.data as { reason: string }).reason).toBe('Quote was updated')
    expect(await screen.findByText('$2,500.00')).toBeInTheDocument()
  })
  it('shows no correction section when the step declares nothing', async () => {
    signInAs('max.manager')
    const { task } = taskByTitle('Replacement laptop')
    renderApp(`/w/${WS}/tasks/${task.id}`)
    await screen.findByTestId('task-actions')
    expect(screen.queryByTestId('task-correction')).not.toBeInTheDocument()
  })
})

describe('task panel: states and conflict UX (T-UI-03)', () => {
  it('opens available work when no task is assigned, while preserving an explicit tab choice', async () => {
    signInAs('max.manager')
    for (const run of db.runs.values()) for (const task of run.tasks) task.assigneeId = null
    const user = userEvent.setup()
    renderApp(`/w/${WS}/tasks`)
    await waitFor(() => expect(screen.getByRole('tab', { name: /Available to claim/ })).toHaveAttribute('aria-selected', 'true'))
    await user.click(screen.getByRole('tab', { name: /Assigned to me/ }))
    expect(await screen.findByText('No tasks are assigned to you')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /Assigned to me/ })).toHaveAttribute('aria-selected', 'true')
  })
  it('closes the previous task panel when switching task lists', async () => {
    signInAs('max.manager')
    const user = userEvent.setup()
    const { router } = await openPanel('Replacement laptop')
    await user.click(screen.getByRole('tab', { name: 'Completed' }))
    await waitFor(() => expect(router.state.location.pathname).toBe(`/w/${WS}/tasks`))
    expect(screen.queryByRole('complementary', { name: 'Task details' })).not.toBeInTheDocument()
  })
  it('shows the request with USD formatting, why you see it, and Claim first for a group task', async () => {
    signInAs('max.manager')
    await openPanel('Replacement laptop')
    expect(await screen.findByText('$1,800.00')).toBeInTheDocument()
    expect(screen.getByTestId('why-here')).toHaveTextContent("you're in the Managers group")
    expect(screen.getByRole('button', { name: 'Claim task' })).toBeEnabled()
    // Reject and Approve only appear after the task is claimed.
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
  })

  it('claim, then approve sends the text as a comment and shows the result message', async () => {
    signInAs('max.manager')
    const user = userEvent.setup()
    const { taskId } = await openPanel('Replacement laptop')
    await user.click(screen.getByRole('button', { name: 'Claim task' }))
    const comment = await screen.findByLabelText(/Comment/)
    await waitFor(() => expect(comment).toHaveFocus())
    await user.type(comment, 'Looks fine')
    await user.click(screen.getByRole('button', { name: 'Approve' }))
    const banner = await screen.findByTestId('task-banner')
    expect(banner).toHaveTextContent('You approved "Review request"')
    await waitFor(() => expect(banner).toHaveFocus())
    const { task } = (() => {
      for (const run of db.runs.values()) {
        const t = run.tasks.find((x) => x.id === taskId)
        if (t) return { task: t }
      }
      throw new Error('task vanished')
    })()
    expect(task.decision?.action).toBe('approve')
    expect(task.decision?.comment).toBe('Looks fine')
    expect(task.decision?.reason).toBeNull()
  })

  it('reject requires a reason (inline, not sent) and then sends it as the reason', async () => {
    signInAs('max.manager')
    const user = userEvent.setup()
    const { task } = taskByTitle('Replacement laptop')
    await openPanel('Replacement laptop')
    await user.click(screen.getByRole('button', { name: 'Claim task' }))
    const comment = await screen.findByLabelText(/Comment/)
    await user.click(screen.getByRole('button', { name: 'Reject' }))
    expect(await screen.findByText('Add a reason so the requester knows what to change')).toBeInTheDocument()
    expect(comment).toHaveAttribute('aria-invalid', 'true')
    expect(comment).toHaveFocus()
    expect(task.status).toBe('open') // nothing was sent
    await user.type(comment, 'Use the spare device')
    await user.click(screen.getByRole('button', { name: 'Reject' }))
    await waitFor(() => expect(screen.getByTestId('task-banner')).toHaveTextContent('You rejected'))
    expect(task.decision?.action).toBe('reject')
    expect(task.decision?.reason).toBe('Use the spare device')
  })

  it('"This task was completed by {name}" when another approver decided first', async () => {
    signInAs('max.manager')
    const user = userEvent.setup()
    const { run, task } = taskByTitle('Replacement laptop')
    await openPanel('Replacement laptop')
    // Mani completes the task while Max is still looking at the stale panel.
    const mani = userByName('mani.manager')
    claim(run, task, mani)
    decide(run, task, mani, 'approve', null, null)

    await user.click(screen.getByRole('button', { name: 'Claim task' }))
    const banner = await screen.findByTestId('task-banner')
    expect(banner).toHaveTextContent('This task was completed by Mani Manager.')
    await waitFor(() => expect(banner).toHaveFocus())
    // The panel refreshed: no actions are left, and the decision is in the history.
    expect(screen.queryByRole('button', { name: 'Claim task' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    expect(screen.getByText(/Mani Manager approved Review request/)).toBeInTheDocument()
  })

  it('"Claimed by {name}" when another approver claimed first (TASK_ALREADY_CLAIMED)', async () => {
    signInAs('max.manager')
    const user = userEvent.setup()
    const { run, task } = taskByTitle('Replacement laptop')
    await openPanel('Replacement laptop')
    claim(run, task, userByName('mani.manager'))
    await user.click(screen.getByRole('button', { name: 'Claim task' }))
    expect(await screen.findByTestId('task-banner')).toHaveTextContent('Claimed by Mani Manager.')
    expect(screen.queryByRole('button', { name: 'Claim task' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
  })

  it('refreshes after a stale version (TASK_VERSION_CONFLICT) and lets the user try again', async () => {
    signInAs('max.manager')
    const user = userEvent.setup()
    const { task } = taskByTitle('Replacement laptop')
    await openPanel('Replacement laptop')
    task.version += 1 // changed on the server since the panel loaded
    await user.click(screen.getByRole('button', { name: 'Claim task' }))
    expect(await screen.findByTestId('task-banner')).toHaveTextContent('This task changed since you opened it')
    // The refreshed panel carries the new version, so the second attempt succeeds.
    await user.click(await screen.findByRole('button', { name: 'Claim task' }))
    await screen.findByLabelText(/Comment/)
    expect(task.assigneeId).toBe(userByName('max.manager').id)
  })

  it("hides the actions and says why when it is the person's own request (SELF_APPROVAL_DENIED)", async () => {
    signInAs('mani.manager')
    const { task } = taskByTitle('Training budget') // requested by Mani himself
    const app = renderApp(`/w/${WS}/tasks/${task.id}?view=claimable`)
    await screen.findByTestId('task-actions')
    expect(screen.getByTestId('task-why-not')).toHaveTextContent("You can't approve your own request.")
    expect(screen.queryByRole('button', { name: 'Claim task' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    app.unmount()
  })

  it('cannot be submitted twice: the buttons are disabled while the decision is pending', async () => {
    signInAs('max.manager')
    db.latencyMs = 80
    const user = userEvent.setup()
    const { run, task } = taskByTitle('Replacement laptop')
    claim(run, task, userByName('max.manager'))
    await openPanel('Replacement laptop', 'assigned')
    await user.type(await screen.findByLabelText(/Comment/), 'ok')
    const approve = screen.getByRole('button', { name: 'Approve' })
    await user.click(approve)
    expect(screen.getByRole('button', { name: /Approve/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Reject' })).toBeDisabled()
    await screen.findByTestId('task-banner')
    expect(run.events.filter((e) => e.type === 'TASK_DECIDED')).toHaveLength(1)
  })

  it('lists tasks in tabs with counts and opens a task panel beside the list on desktop', async () => {
    signInAs('mani.manager')
    const user = userEvent.setup()
    renderApp(`/w/${WS}/tasks`)
    const tabs = await screen.findByRole('tablist', { name: 'Task lists' })
    expect(within(tabs).getByRole('tab', { name: /Assigned to me/ })).toBeInTheDocument()
    await user.click(within(tabs).getByRole('tab', { name: /Available to claim/ }))
    await waitFor(() => expect(within(tabs).getByRole('tab', { name: /Available to claim/ })).toHaveTextContent('1'))
    await user.click(await screen.findByRole('button', { name: 'Open Replacement laptop' }))
    expect(await screen.findByTestId('task-actions')).toBeInTheDocument()
    // The own request is not offered to claim (separation of duties).
    expect(screen.queryByText('Training budget')).not.toBeInTheDocument()
  })
})


describe('published reusable approval presentation', () => {
  function applyView(run: MockRun, task: MockTask) {
    const release = currentRelease(run)
    const view: ViewDefinition = { schemaVersion: 1, kind: 'approval', sections: [{ id: 'review', label: 'Shared purchase review', columns: 2,
      fields: Object.entries(release.definition.inputSchema.properties).map(([key, property]) => ({ id: key, label: `Review ${key}`, binding: `request.${key}`, required: release.definition.inputSchema.required?.includes(key), type: property.enum ? 'choice' : property.type === 'number' ? 'number' : 'text', ...(property.enum ? { options: property.enum.map(value => ({ value, label: value })) } : {}) })) }], actions: [{ id: 'yes', type: 'approve', label: 'Authorize purchase' }, { id: 'no', type: 'reject', label: 'Decline purchase' }] }
    release.definition = bindApprovalView(release.definition, task.nodeId, 'shared-view', 1, view)
  }
  it('renders copied labels and layout, then uses normal claim and decision controls', async () => {
    signInAs('max.manager')
    const { run, task } = taskByTitle('Replacement laptop'); applyView(run, task)
    const user = userEvent.setup(); await openPanel('Replacement laptop')
    expect(await screen.findByRole('region', { name: 'Shared purchase review' })).toHaveTextContent('Replacement laptop')
    expect(screen.getByTestId('approval-view')).toHaveTextContent('Review amount')
    expect(screen.queryByRole('button', { name: 'Authorize purchase' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Claim task' }))
    await user.click(await screen.findByRole('button', { name: 'Decline purchase' }))
    expect(await screen.findByText('Add a reason so the requester knows what to change')).toBeInTheDocument()
    await user.type(screen.getByLabelText(/Comment/), 'Over budget')
    await user.click(screen.getByRole('button', { name: 'Decline purchase' }))
    expect(await screen.findByTestId('task-banner')).toHaveTextContent('You rejected')
    expect(task.decision?.reason).toBe('Over budget')
  })
  it('keeps task actions unavailable to an ineligible reader despite custom labels', async () => {
    signInAs('mani.manager')
    const { run, task } = taskByTitle('Training budget'); applyView(run, task)
    await openPanel('Training budget')
    expect(await screen.findByTestId('approval-view')).toHaveTextContent('Shared purchase review')
    expect(screen.queryByRole('button', { name: 'Authorize purchase' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Decline purchase' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Claim task' })).not.toBeInTheDocument()
  })
})
