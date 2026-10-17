import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { USERS as users, db } from '@/mocks/state'
import { setCurrentUser } from '@/mocks/handlers'
import { server } from '@/test/server'
import { renderApp, signInAs, WS } from '@/test/utils'

const PATH = `/w/${WS}/assistant`
const ID = '00000000-0000-7000-8000-0000000000c1'
const openTasks = () => [...db.runs.values()].flatMap((run) => run.tasks).filter((task) => task.status === 'open').length

/** The Assistant against the mock-mode API, which follows the backend's guided purchase flow. */
describe('Assistant guided flows (mock API)', () => {
  beforeEach(() => { window.sessionStorage.clear(); signInAs('rhea.requester') })

  it('speaks plainly: no commands, JSON or field-name syntax on the page', async () => {
    renderApp(PATH)
    await screen.findByRole('heading', { name: 'How can I help?' })
    const page = document.body.textContent ?? ''
    expect(page).not.toMatch(/JSON|fieldName|=value|\["Laptop/)
    expect(page).toMatch(/separated by commas|Type what you need/)
    expect(page).toMatch(/Press Enter to send/)
  })

  it('starts a purchase, labels the review, lets a value change, and submits only on request', async () => {
    const user = userEvent.setup()
    renderApp(PATH)
    await user.click(await screen.findByRole('button', { name: 'Start a request' }))
    await user.click(await screen.findByRole('button', { name: 'Purchase approval' }))
    const answer = async (label: string, value: string) => {
      const box = await screen.findByLabelText(`Your answer: ${label}`)
      await waitFor(() => expect(box).toHaveFocus())
      await user.type(box, `${value}{Enter}`)
    }
    await answer('What are you buying?', 'Standing desk')
    await answer('Business purpose', 'Back support')
    await user.click(await screen.findByRole('button', { name: 'IT' }))
    await answer('Amount (USD)', '1,250.50')
    await screen.findByRole('heading', { name: 'Review Purchase approval' })
    expect(screen.getByText('Cost center')).toBeInTheDocument()
    expect(screen.getByText('1250.5')).toBeInTheDocument()
    expect(screen.queryByText('costCenter')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Open request' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Change Amount (USD)' }))
    const field = await screen.findByLabelText('New value for Amount (USD)')
    await user.clear(field)
    await user.type(field, 'lots')
    await user.click(screen.getByRole('button', { name: 'Save change' }))
    await screen.findByText(/Please enter Amount \(USD\) as a number/)
    await user.click(screen.getByRole('button', { name: 'Change Amount (USD)' }))
    const again = await screen.findByLabelText('New value for Amount (USD)')
    await user.clear(again)
    await user.type(again, '999')
    await user.click(screen.getByRole('button', { name: 'Save change' }))
    await screen.findByText('999')

    const before = db.runs.size
    await user.click(screen.getByRole('button', { name: 'Submit request' }))
    expect(await screen.findByRole('link', { name: 'Open request' })).toBeInTheDocument()
    expect(db.runs.size).toBe(before + 1)
    expect([...db.runs.values()].some((run) => run.input.title === 'Standing desk' && run.input.amount === 999)).toBe(true)
  })

  it('shows the request before a manager decides, needs a reason to reject, and confirms separately', async () => {
    signInAs('mani.manager')
    const user = userEvent.setup()
    renderApp(PATH)
    await user.click(await screen.findByRole('button', { name: 'My tasks' }))
    await screen.findByText(/These tasks are waiting for you/)
    const choices = screen.getAllByRole('button').filter((b) => /^ORQ-\d+ - /.test(b.textContent ?? ''))
    expect(choices.length).toBeGreaterThan(0)
    const before = openTasks()
    await user.click(choices[0]!)
    await screen.findByRole('heading', { name: /^Review ORQ-/ })
    expect(screen.queryByRole('button', { name: /^Confirm/ })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Reject…' }))
    const reason = await screen.findByLabelText(/Why are you rejecting this/)
    await waitFor(() => expect(reason).toHaveFocus())
    expect(screen.getByRole('button', { name: 'Continue to confirm' })).toBeDisabled()
    await user.type(reason, 'Please attach a quote')
    await user.click(screen.getByRole('button', { name: 'Continue to confirm' }))
    const confirmReject = await screen.findByRole('button', { name: 'Confirm rejection' })
    expect(openTasks()).toBe(before)
    await user.click(confirmReject)
    await screen.findByText(/You rejected ORQ-/)
    expect(openTasks()).toBe(before - 1)
  })

  it('does not let a requester review a task on their own request', async () => {
    const user = userEvent.setup()
    renderApp(PATH)
    await user.click(await screen.findByRole('button', { name: 'My tasks' }))
    await screen.findByText(/You have no tasks waiting for you|These tasks are waiting for you/)
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
  })

  it('refuses a stale conversation without applying the message and lets the person reload it', async () => {
    const subject = users.find((u) => u.username === 'rhea.requester')!.id
    sessionStorage.setItem(`orqix.conversation:${WS}:${subject}`, ID)
    let gets = 0
    server.use(
      http.post('/api/v1/workspaces/:ws/conversations/messages', () => HttpResponse.json({ type: 'about:blank', title: 'Conflict', status: 409, code: 'TASK_VERSION_CONFLICT' }, { status: 409 })),
      http.get('/api/v1/workspaces/:ws/conversations/:id', () => { gets += 1; return HttpResponse.json({ id: ID, version: 5, stage: 'idle', message: 'Back at version five.', choices: [] }) }),
    )
    const user = userEvent.setup()
    renderApp(PATH)
    await user.type(await screen.findByLabelText('Message'), 'my tasks')
    await user.click(screen.getByRole('button', { name: 'Send' }))
    await screen.findByText(/changed somewhere else/)
    await user.click(screen.getByRole('button', { name: 'Reload conversation' }))
    await screen.findByText('Back at version five.')
    expect(gets).toBeGreaterThan(1)
  })

  it('answers 404 for another person’s conversation, so nothing leaks', async () => {
    const user = userEvent.setup()
    renderApp(PATH)
    await user.click(await screen.findByRole('button', { name: 'My requests' }))
    await screen.findByText(/latest requests|not made any requests/)
    const mine = window.sessionStorage.getItem(`orqix.conversation:${WS}:${users.find((u) => u.username === 'rhea.requester')!.id}`)
    expect(mine).toBeTruthy()
    setCurrentUser('mani.manager')
    const response = await fetch(`/api/v1/workspaces/${WS}/conversations/${mine}`)
    expect(response.status).toBe(404)
  })
})
