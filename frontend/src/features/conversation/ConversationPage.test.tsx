import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { USERS as users } from '@/mocks/state'
import { server } from '@/test/server'
import { renderApp, signInAs, WS } from '@/test/utils'
import type { Conversation, ConversationMessage } from './api'

const PATH = `/w/${WS}/assistant`
const ID = '00000000-0000-7000-8000-0000000000c1'
const RUN = '00000000-0000-7000-8000-0000000000c2'
const endpoint = '/api/v1/workspaces/:ws/conversations/messages'
const reply = (patch: Partial<Conversation> = {}): Conversation => ({ id: ID, version: 1, stage: 'idle', message: 'Choose a workflow.', choices: [], ...patch })

describe('guided conversation execution', () => {
  beforeEach(() => { window.sessionStorage.clear(); signInAs('rhea.requester') })

  it('brings the newest turn into view after a reply instead of leaving it behind the answer box', async () => {
    server.use(http.post(endpoint, () => HttpResponse.json(reply({ stage: 'completed', message: 'Submitted ORQ-1001.', runId: RUN }))))
    const scrolled: Element[] = []
    const original = Element.prototype.scrollIntoView
    Element.prototype.scrollIntoView = vi.fn(function (this: Element) { scrolled.push(this) })
    try {
      const user = userEvent.setup()
      renderApp(PATH)
      await user.type(await screen.findByLabelText('Message'), 'confirm{Enter}')
      await screen.findByRole('link', { name: 'Open request' })
      await waitFor(() => expect(scrolled.at(-1)?.textContent).toContain('You said'))
      expect(scrolled.at(-1)?.textContent).toContain('confirm')
      expect(document.activeElement).toHaveAccessibleName('Current step')
    } finally { Element.prototype.scrollIntoView = original }
  })

  it('collects a missing field and requires explicit review before creating a request', async () => {
    const requests: ConversationMessage[] = []
    server.use(http.post(endpoint, async ({ request }) => {
      const body = await request.json() as ConversationMessage
      requests.push(body)
      if (requests.length === 1) return HttpResponse.json(reply({ stage: 'collecting', message: 'What is the purchase title?', field: { title: 'Purchase title', type: 'string' } }))
      if (requests.length === 2) return HttpResponse.json(reply({ version: 2, stage: 'review', message: 'Review the request before submitting.', review: { workflowName: 'Purchase approval', input: { title: 'Laptop', amount: 100 } } }))
      return HttpResponse.json(reply({ version: 3, stage: 'completed', message: 'Submitted ORQ-1001.', runId: RUN }))
    }))
    const user = userEvent.setup()
    renderApp(PATH)
    await user.type(await screen.findByLabelText('Message'), 'start Purchase approval')
    await user.click(screen.getByRole('button', { name: new RegExp('^' + 'Send' + '$') }))
    await user.type(await screen.findByLabelText('Your answer: Purchase title'), 'Laptop')
    await user.click(screen.getByRole('button', { name: new RegExp('^' + 'Send' + '$') }))
    await screen.findByRole('heading', { name: 'Review Purchase approval' })
    expect(requests).toHaveLength(2)
    expect(screen.queryByRole('link', { name: 'Open request' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Submit request' }))
    expect(await screen.findByRole('link', { name: 'Open request' })).toHaveAttribute('href', `/w/${WS}/runs/${RUN}`)
    expect(requests.map((r) => r.message)).toEqual(['start Purchase approval', 'Laptop', 'confirm'])
    expect(requests[2]).toMatchObject({ conversationId: ID, expectedVersion: 2 })
  })

  it('resumes the subject-specific conversation and permits discarding a proposed task action', async () => {
    const subject = users.find((u) => u.username === 'rhea.requester')!.id
    sessionStorage.setItem(`orqix.conversation:${WS}:${subject}`, ID)
    const bodies: ConversationMessage[] = []
    server.use(
      http.get('/api/v1/workspaces/:ws/conversations/:id', () => HttpResponse.json(reply({ version: 7, stage: 'confirmingTask', message: 'You are about to approve ORQ-1001.', taskId: 'task-one', taskAction: 'approve' }))),
      http.post(endpoint, async ({ request }) => {
        bodies.push(await request.json() as ConversationMessage)
        return HttpResponse.json(reply({ version: 8, message: 'Proposed action discarded.' }))
      }),
    )
    const user = userEvent.setup()
    renderApp(PATH)
    expect(await screen.findByRole('button', { name: 'Confirm approval' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Leave this task alone' }))
    await screen.findByText('Proposed action discarded.')
    expect(bodies).toEqual([{ message: 'cancel', conversationId: ID, expectedVersion: 7 }])
    expect(screen.queryByRole('button', { name: 'Confirm approval' })).not.toBeInTheDocument()
  })

  it('retries a failed message with the same idempotency key and preserves the typed text', async () => {
    const keys: (string | null)[] = []
    server.use(http.post(endpoint, ({ request }) => {
      keys.push(request.headers.get('Idempotency-Key'))
      return keys.length === 1
        ? HttpResponse.json({ type: 'about:blank', title: 'Unavailable', status: 503, code: 'DEPENDENCY_UNAVAILABLE', detail: 'Try again.' }, { status: 503 })
        : HttpResponse.json(reply({ message: 'Your requests are ready.' }))
    }))
    const user = userEvent.setup()
    renderApp(PATH)
    await user.type(await screen.findByLabelText('Message'), 'my requests')
    await user.click(screen.getByRole('button', { name: new RegExp('^' + 'Send' + '$') }))
    await screen.findByRole('button', { name: 'Retry message' })
    expect(screen.getByLabelText('Message')).toHaveValue('my requests')
    await user.click(screen.getByRole('button', { name: 'Retry message' }))
    await screen.findByText('Your requests are ready.')
    expect(keys).toHaveLength(2)
    expect(keys[0]).toBeTruthy()
    expect(keys[1]).toBe(keys[0])
  })

  it('starts fresh after a resume failure without retaining the old conversation identifier', async () => {
    const subject = users.find((u) => u.username === 'rhea.requester')!.id
    sessionStorage.setItem(`orqix.conversation:${WS}:${subject}`, ID)
    const bodies: ConversationMessage[] = []
    server.use(
      http.get('/api/v1/workspaces/:ws/conversations/:id', () => HttpResponse.json({ type: 'about:blank', title: 'Not found', status: 404, code: 'NOT_FOUND' }, { status: 404 })),
      http.post(endpoint, async ({ request }) => { bodies.push(await request.json() as ConversationMessage); return HttpResponse.json(reply({ message: 'A fresh conversation.' })) }),
    )
    const user = userEvent.setup()
    renderApp(PATH)
    await screen.findByText(/could not reopen your last conversation/)
    await user.click(screen.getByRole('button', { name: 'New conversation' }))
    await user.click(screen.getByRole('button', { name: 'Start a request' }))
    await waitFor(() => expect(bodies).toEqual([{ message: 'workflows' }]))
  })

  it('asks for a date plainly and shows the reviewed date in a readable form', async () => {
    let calls = 0
    server.use(http.post(endpoint, () => {
      calls += 1
      return HttpResponse.json(calls === 1
        ? reply({ stage: 'collecting', message: 'Needed by? Enter the date as year-month-day, for example 2026-10-14.', field: { name: 'needBy', title: 'Needed by', type: 'string', format: 'date', required: true } })
        : reply({ version: 2, stage: 'review', message: 'Review.', review: { workflowName: 'Purchase approval', input: { needBy: '2026-10-14' }, fields: [{ name: 'needBy', label: 'Needed by', value: '2026-10-14', display: '14 October 2026' }] } }))
    }))
    const user = userEvent.setup()
    renderApp(PATH)
    await user.type(await screen.findByLabelText('Message'), 'start Purchase approval')
    await user.click(screen.getByRole('button', { name: 'Send' }))
    const box = await screen.findByLabelText('Your answer: Needed by')
    expect(screen.getAllByText(/year-month-day, for example 2026-10-14/).length).toBeGreaterThan(0)
    await user.type(box, '2026-10-14{Enter}')
    expect(await screen.findByText('14 October 2026')).toBeInTheDocument()
  })
})

