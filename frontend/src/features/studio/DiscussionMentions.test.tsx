import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { db, PURCHASE_WORKFLOW_ID, userByName } from '@/mocks/state'
import { renderAppReady, signInAs, WS } from '@/test/utils'

const EDIT = `/w/${WS}/workflows/${PURCHASE_WORKFLOW_ID}/edit`
const draft = () => db.workflows.get(PURCHASE_WORKFLOW_ID)!.drafts.at(-1)!.definition

function stepButton(id: string): HTMLElement {
  const button = screen.getByTestId(`step-row-${id}`).querySelector<HTMLElement>('button[aria-pressed]')
  if (!button) throw new Error(`No step button for ${id}`)
  return button
}

describe('discussion @mentions (CMP01)', () => {
  it('suggests only people who can read the workflow, posts the mention and notifies that person once', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(EDIT)
    await screen.findByText(/Draft revision 1/)
    await user.click(screen.getByRole('tab', { name: 'Steps' }))
    const target = draft().nodes.find((n) => n.type === 'approval')!
    await user.click(stepButton(target.id))
    const panel = await screen.findByTestId('discussion')
    const box = within(panel).getByLabelText('New comment')
    await user.type(box, 'Please check @')
    const list = await within(panel).findByRole('listbox', { name: 'People to mention' })
    const names = within(list).getAllByRole('option').map((o) => o.textContent?.replace('›', '').trim())
    expect(names).toContain('Paul Publisher')
    expect(names).toContain('Vic Viewer')
    // Never the author, a requester without workflow.read, or someone from another workspace.
    expect(names).not.toContain('Dana Designer')
    expect(names).not.toContain('Rhea Requester')
    expect(names).not.toContain('Fred Facilities')
    await user.type(box, 'pau')
    await waitFor(() => expect(within(panel).getAllByRole('option')).toHaveLength(1))
    expect(box).toHaveAttribute('aria-activedescendant')
    await user.keyboard('{Enter}')
    expect(box).toHaveValue('Please check @Paul Publisher ')
    expect(within(panel).queryByRole('listbox')).not.toBeInTheDocument()
    await user.type(box, 'thanks')
    await user.click(within(panel).getByRole('button', { name: 'Comment' }))

    const mention = await within(panel).findByTestId('mention')
    expect(mention).toHaveTextContent('mention of @Paul Publisher')
    expect(within(mention).getByText('mention of')).toHaveClass('sr-only')
    const paul = userByName('paul.publisher')
    const notes = db.notifications.filter((n) => n.userId === paul.id && n.kind === 'discussionMention')
    expect(notes).toHaveLength(1)
    expect(notes[0]!.elementId).toBe(target.id)
    expect(notes[0]!.message).not.toContain('thanks')
    expect(db.workflowComments.at(-1)!.mentions).toEqual([paul.id])
  })

  it('opens the studio on the mentioned step from the bell and marks all read', async () => {
    signInAs('paul.publisher')
    const paul = userByName('paul.publisher')
    const target = draft().nodes.find((n) => n.type === 'approval')!
    db.workflowComments.push({ id: 'c-m', wfId: PURCHASE_WORKFLOW_ID, elementId: target.id, parentId: null, body: 'Look @Paul Publisher', authorId: userByName('dana.designer').id, createdAt: new Date().toISOString(), resolvedAt: null, resolvedById: null, mentions: [paul.id] })
    db.notifications.unshift({ id: 'n-m', wsId: WS, userId: paul.id, runId: null, kind: 'discussionMention', message: 'Dana Designer mentioned you in the discussion.', createdAt: new Date().toISOString(), readAt: null, workflowId: PURCHASE_WORKFLOW_ID, elementId: target.id })
    db.notifications.unshift({ id: 'n-x', wsId: WS, userId: paul.id, runId: null, kind: 'workflowMessage', message: 'Other note', createdAt: new Date().toISOString(), readAt: null })
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS}`)
    await user.click(await screen.findByRole('button', { name: /Notifications, \d+ unread/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Notifications' })
    const link = within(dialog).getByRole('link', { name: /Dana Designer mentioned you/ })
    expect(link).toHaveAttribute('href', `/w/${WS}/workflows/${PURCHASE_WORKFLOW_ID}/edit?element=${target.id}&discussion=1`)
    await user.click(link)
    const panel = await screen.findByTestId('discussion')
    expect(await within(panel).findByTestId('mention')).toHaveTextContent('@Paul Publisher')
    await waitFor(() => expect(document.activeElement?.id).toBe('discussion-heading'))
    expect(db.notifications.find((n) => n.id === 'n-m')!.readAt).not.toBeNull()

    await user.click(await screen.findByRole('button', { name: /Notifications, \d+ unread/ }))
    await user.click(within(await screen.findByRole('dialog', { name: 'Notifications' })).getByRole('button', { name: 'Mark all as read' }))
    await waitFor(() => expect(db.notifications.filter((n) => n.userId === paul.id && !n.readAt)).toHaveLength(0))
    expect(await screen.findByRole('button', { name: 'Notifications' })).toBeInTheDocument()
  })

  it('tells the reader when the mentioned step is no longer in the draft', async () => {
    signInAs('paul.publisher')
    await renderAppReady(`${EDIT}?element=gone-step&discussion=1`)
    expect(await screen.findByTestId('discussion-link-notice')).toHaveTextContent(/no longer in this draft/)
  })
})
