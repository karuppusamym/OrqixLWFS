import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { db, PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import { renderAppReady, signInAs, WS } from '@/test/utils'

const EDIT = `/w/${WS}/workflows/${PURCHASE_WORKFLOW_ID}/edit`
const draft = () => db.workflows.get(PURCHASE_WORKFLOW_ID)!.drafts.at(-1)!.definition

async function open(user: ReturnType<typeof userEvent.setup>, tab: 'Steps' | 'Lanes' | 'Source') {
  await screen.findByText(/Draft revision 1/)
  await user.click(screen.getByRole('tab', { name: tab }))
}

function stepButton(id: string): HTMLElement {
  const button = screen.getByTestId(`step-row-${id}`).querySelector<HTMLElement>('button[aria-pressed]')
  if (!button) throw new Error(`No step button for ${id}`)
  return button
}

describe('lanes tab', () => {
  it('groups steps by responsibility, lists handoffs and selects a step for the inspector', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(EDIT)
    await open(user, 'Lanes')
    const lanes = await screen.findByRole('list', { name: 'Swimlanes' })
    expect(within(lanes).getByRole('listitem', { name: 'Lane Requester' })).toBeInTheDocument()
    expect(within(lanes).getByRole('listitem', { name: 'Lane Automation' })).toBeInTheDocument()
    const handoffs = screen.getByTestId('handoffs')
    expect(within(handoffs).getAllByRole('listitem').length).toBeGreaterThan(0)
    const first = draft().nodes.find((n) => n.type === 'approval')!
    await user.click(within(lanes).getByRole('button', { name: new RegExp(first.label) }))
    expect(within(lanes).getByRole('button', { name: new RegExp(first.label) })).toHaveAttribute('aria-pressed', 'true')
    expect(await screen.findByLabelText('Discussion')).toBeInTheDocument()
  })
})

describe('source tab', () => {
  it('checks edited YAML, shows the changes, applies it to the draft and autosaves', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(EDIT)
    await open(user, 'Source')
    const box = (await screen.findByTestId('source-text')) as HTMLTextAreaElement
    expect(box.value).toContain(`workflowKey: ${draft().workflowKey}`)
    expect(screen.getByTestId('draft-roundtrip')).toHaveTextContent(/without any difference/)
    const original = draft().name
    fireEvent.change(box, { target: { value: box.value.replace(/^name: .*$/m, 'name: Renamed in source') } })
    await user.click(screen.getByRole('button', { name: 'Check source' }))
    const ok = await screen.findByTestId('source-ok')
    expect(ok).toHaveTextContent('It reads back as the same document')
    expect(ok).toHaveTextContent('Changes: Section name')
    // Checking alone does not touch the draft.
    expect(draft().name).toBe(original)
    await user.click(screen.getByRole('button', { name: 'Apply to draft' }))
    await waitFor(() => expect(draft().name).toBe('Renamed in source'))
    await waitFor(() => expect(screen.getByTestId('save-state')).toHaveTextContent('Saved'))
  })

  it('refuses text with problems and leaves the draft untouched', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(EDIT)
    await open(user, 'Source')
    const box = (await screen.findByTestId('source-text')) as HTMLTextAreaElement
    const before = JSON.stringify(draft())
    fireEvent.change(box, { target: { value: box.value.replace(/^workflowKey: .*$/m, 'workflowKey: other') } })
    await user.click(screen.getByRole('button', { name: 'Apply to draft' }))
    expect(await screen.findByTestId('source-problems')).toHaveTextContent(/workflowKey cannot be changed/)
    fireEvent.change(box, { target: { value: `${box.value}\nextra: &a 1\nother: *a\n` } })
    await user.click(screen.getByRole('button', { name: 'Check source' }))
    expect(await screen.findByTestId('source-problems')).toHaveTextContent(/Anchors, aliases/)
    fireEvent.change(box, { target: { value: 'nodes: [' } })
    await user.click(screen.getByRole('button', { name: 'Apply to draft' }))
    expect(await screen.findByTestId('source-problems')).toBeInTheDocument()
    expect(JSON.stringify(draft())).toBe(before)
    await user.click(screen.getByRole('button', { name: 'Discard edits' }))
    expect(screen.queryByTestId('source-problems')).not.toBeInTheDocument()
    expect((screen.getByTestId('source-text') as HTMLTextAreaElement).value).toContain('workflowKey:')
  })

  it('is read-only for people who cannot edit workflows', async () => {
    signInAs('vic.viewer')
    const user = userEvent.setup()
    await renderAppReady(EDIT)
    await open(user, 'Source')
    expect(await screen.findByTestId('source-text')).toHaveAttribute('readonly')
    expect(screen.queryByRole('button', { name: 'Apply to draft' })).not.toBeInTheDocument()
  })
})

describe('threaded discussion', () => {
  it('lets a designer start, reply to, resolve and reopen a thread on a step', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(EDIT)
    await open(user, 'Steps')
    const target = draft().nodes.find((n) => n.type === 'approval')!
    await user.click(stepButton(target.id))
    const panel = await screen.findByTestId('discussion')
    expect(await within(panel).findByText('No comments yet.')).toBeInTheDocument()
    await user.type(within(panel).getByLabelText('New comment'), 'Is the limit right?')
    await user.click(within(panel).getByRole('button', { name: 'Comment' }))
    const thread = await within(panel).findByTestId('thread')
    expect(thread).toHaveTextContent('Is the limit right?')
    expect(db.workflowComments).toHaveLength(1)
    expect(db.workflowComments[0]!.elementId).toBe(target.id)

    await user.click(within(thread).getByRole('button', { name: 'Reply' }))
    await user.type(within(thread).getByLabelText('Your reply'), 'Checked with finance.')
    await user.click(within(thread).getByRole('button', { name: 'Post reply' }))
    await waitFor(() => expect(within(panel).getByTestId('thread')).toHaveTextContent('Checked with finance.'))
    expect(db.workflowComments.filter((c) => c.parentId)).toHaveLength(1)

    await user.click(within(panel).getByRole('button', { name: 'Resolve' }))
    expect(await within(panel).findByText(/Resolved by Dana/)).toBeInTheDocument()
    await user.click(within(panel).getByRole('button', { name: 'Reopen' }))
    expect(await within(panel).findByText('Open')).toBeInTheDocument()
    // Another step has its own, empty discussion.
    const other = draft().nodes.find((n) => n.id !== target.id && n.type === 'approval') ?? draft().nodes.find((n) => n.id !== target.id)!
    await user.click(stepButton(other.id))
    expect(await within(await screen.findByTestId('discussion')).findByText('No comments yet.')).toBeInTheDocument()
  })

  it('shows existing threads to a viewer without any way to add to them, while a publisher can reply', async () => {
    signInAs('vic.viewer')
    const target = draft().nodes.find((n) => n.type === 'approval')!
    db.workflowComments.push({ id: 'c-1', wfId: PURCHASE_WORKFLOW_ID, elementId: target.id, parentId: null, body: 'Please review the threshold', authorId: 'someone', createdAt: new Date().toISOString(), resolvedAt: null, resolvedById: null })
    const user = userEvent.setup()
    const view = await renderAppReady(EDIT)
    await open(user, 'Steps')
    await user.click(stepButton(target.id))
    const panel = await screen.findByTestId('discussion')
    expect(await within(panel).findByText('Please review the threshold')).toBeInTheDocument()
    expect(within(panel).queryByLabelText('New comment')).not.toBeInTheDocument()
    expect(within(panel).queryByRole('button', { name: 'Resolve' })).not.toBeInTheDocument()
    expect(within(panel).getByText(/read the discussion but not add/)).toBeInTheDocument()
    view.unmount()

    signInAs('paul.publisher')
    db.workflowComments.push({ id: 'c-2', wfId: PURCHASE_WORKFLOW_ID, elementId: target.id, parentId: null, body: 'Reviewer note', authorId: 'someone', createdAt: new Date().toISOString(), resolvedAt: null, resolvedById: null })
    await renderAppReady(EDIT)
    await open(user, 'Steps')
    await user.click(stepButton(target.id))
    const publisherPanel = await screen.findByTestId('discussion')
    expect(await within(publisherPanel).findByLabelText('New comment')).toBeInTheDocument()
    expect(within(publisherPanel).getAllByRole('button', { name: 'Resolve' }).length).toBeGreaterThan(0)
  })
})
