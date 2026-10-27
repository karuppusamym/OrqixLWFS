import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it } from 'vitest'
import { db, PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import { server } from '@/test/server'
import { renderAppReady, signInAs, WS } from '@/test/utils'
import { aiMock } from './mocks'

const EDIT = `/w/${WS}/workflows/${PURCHASE_WORKFLOW_ID}/edit`
const latest = () => db.workflows.get(PURCHASE_WORKFLOW_ID)!.drafts.at(-1)!.definition

async function openPanel() {
  await screen.findByText(/Draft revision 1/)
  const panel = await screen.findByTestId('authoring-panel')
  return panel
}

describe('conversational authoring panel (STU02, docs/build/S95)', () => {
  afterEach(() => { aiMock.enabled = true })

  it('proposes reviewable changes, applies only the ticked ones as one edit, and saves them', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(EDIT)
    const panel = await openPanel()
    const before = structuredClone(latest())
    const send = within(panel).getByRole('button', { name: /Send/ })
    expect(send).toBeDisabled()
    await user.type(within(panel).getByLabelText('Your instruction'), 'Add a finance review and rename the first one.')
    await user.click(send)
    expect(await within(panel).findByText('2 proposed changes to review. Nothing has been applied.')).toBeInTheDocument()
    const conversation = within(panel).getByRole('list', { name: 'Conversation' })
    expect(conversation).toHaveTextContent('Add a finance review and rename the first one.')
    const first = within(panel).getByTestId('draft-change-c1')
    expect(within(first).getByRole('list', { name: /Operations of/ })).toHaveTextContent('Add approval step “Second review”')
    expect(within(first).getByRole('list', { name: /Operations of/ })).toHaveTextContent('Connect “Review request” to “Second review” (Approved)')
    // Nothing changes by asking; checkboxes start unticked.
    expect(latest()).toEqual(before)
    const apply = within(panel).getByRole('button', { name: 'Apply 0 selected changes' })
    expect(apply).toBeDisabled()
    await user.click(within(first).getByRole('checkbox'))
    await user.click(within(panel).getByRole('button', { name: 'Apply 1 selected change' }))
    expect(within(panel).getAllByText(/Applied 1 change as one edit\. Use Undo to revert\./).length).toBeGreaterThan(0)
    await waitFor(() => expect(latest().nodes.map((n) => n.id)).toContain('secondReview'))
    const saved = latest()
    expect(saved.nodes.find((n) => n.id === 'manager')?.label).toBe('Review request')
    expect(saved.transitions).toEqual(expect.arrayContaining([expect.objectContaining({ from: 'manager', to: 'secondReview', on: 'approved' })]))
    expect(saved.uiLayout?.positions.secondReview).toBeDefined()
  })

  it('sends with Ctrl+Enter, includes the earlier turns, and shows a stale draft as an alert', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    const bodies: Array<Record<string, unknown>> = []
    await renderAppReady(EDIT)
    const panel = await openPanel()
    const input = within(panel).getByLabelText('Your instruction')
    await user.type(input, 'Add a cost code question.')
    await user.keyboard('{Control>}{Enter}{/Control}')
    expect(await within(panel).findByText('1 proposed change to review. Nothing has been applied.')).toBeInTheDocument()
    server.use(http.post('*/api/v1/workspaces/:ws/workflows/:id/ai/draft-edits', async ({ request }) => {
      bodies.push((await request.json()) as Record<string, unknown>)
      return HttpResponse.json({ type: 'x', title: 'The workflow changed since you loaded it', status: 409, code: 'WORKFLOW_VERSION_CONFLICT', detail: 'The draft was saved again (revision 2). Reload the studio, then ask again.', correlationId: 'c1', currentRevision: 2 }, { status: 409, headers: { 'Content-Type': 'application/problem+json' } })
    }))
    await user.type(input, 'Make it optional.')
    await user.keyboard('{Control>}{Enter}{/Control}')
    expect(await within(panel).findByRole('alert')).toHaveTextContent(/changed while you were looking at it\. Reload/)
    expect(bodies[0]).toMatchObject({ instruction: 'Make it optional.', revision: 1, mode: 'edit', history: [{ role: 'author', text: 'Add a cost code question.' }, { role: 'assistant' }] })
  })

  it('regenerates from scratch only after two explicit confirmations', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(EDIT)
    const panel = await openPanel()
    await user.type(within(panel).getByLabelText('Your instruction'), 'Start again with a single review.')
    await user.click(within(panel).getByRole('button', { name: /Regenerate from scratch/ }))
    const dialog = await screen.findByRole('dialog', { name: /Regenerate this draft from scratch/ })
    await user.click(within(dialog).getByRole('button', { name: 'Ask for a new draft' }))
    expect(await within(panel).findByText('A new draft is proposed. Nothing has been changed yet.')).toBeInTheDocument()
    expect(latest().nodes.map((n) => n.id)).toContain('procure')
    await user.click(within(panel).getByRole('button', { name: 'Replace the draft…' }))
    const confirm = await screen.findByRole('dialog', { name: /Replace the draft with the proposal/ })
    await user.click(within(confirm).getByRole('button', { name: 'Replace the draft' }))
    await waitFor(() => expect(latest().nodes.map((n) => n.id)).toEqual(['start', 'review', 'approved', 'declined']))
    expect(latest().workflowKey).toBe('purchase-approval')
    expect(latest().inputSchema.properties.amount).toBeDefined()
  })

  it('says why when AI is not configured instead of disappearing', async () => {
    aiMock.enabled = false
    signInAs('dana.designer')
    await renderAppReady(EDIT)
    const panel = await openPanel()
    const notice = await within(panel).findByTestId('authoring-not-configured')
    expect(notice).toHaveTextContent('AI is not configured')
    expect(notice).toHaveTextContent('no provider key is set')
    expect(within(panel).queryByLabelText('Your instruction')).not.toBeInTheDocument()
  })

  it('collapses to a narrow strip and opens again from the keyboard', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(EDIT)
    await openPanel()
    await user.click(screen.getByRole('button', { name: 'Hide the authoring assistant' }))
    expect(screen.queryByTestId('authoring-panel')).not.toBeInTheDocument()
    const show = screen.getByRole('button', { name: 'Show the authoring assistant' })
    expect(show).toHaveAttribute('aria-expanded', 'false')
    show.focus()
    await user.keyboard('{Enter}')
    expect(await screen.findByTestId('authoring-panel')).toBeInTheDocument()
  })
})
