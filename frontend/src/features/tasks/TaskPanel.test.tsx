import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { claim, decide } from '@/mocks/engine'
import { db, userByName, type MockRun, type MockTask } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'

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

describe('task panel: states and conflict UX (T-UI-03)', () => {
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
