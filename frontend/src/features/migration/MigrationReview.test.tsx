import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import { renderApp, signInAs, WS } from '@/test/utils'
import { convertMigration, createMigration, reviewView } from '@/mocks/migration'
import { bpmn } from '@/mocks/migration.fixtures'
import { PURCHASE_WORKFLOW_ID, clock, db, userByName } from '@/mocks/state'

/** A migrated draft in the Purchasing workspace, converted (and so authored) by `mapper`. */
function migratedWorkflow(mapper: string, policy = true): string {
  const { migration } = createMigration(WS, 'orders.bpmn', bpmn('orders-process'))
  const outcome = convertMigration(migration, userByName(mapper), { workflowKey: 'orders-process', name: 'Orders' })
  if (!outcome.ok) throw new Error(outcome.detail)
  if (policy) db.migrationPolicies.set(WS, { required: true, version: 1, reason: 'Four eyes', updatedAt: clock.iso() })
  return outcome.workflow.id
}

const publishPage = (id: string) => `/w/${WS}/workflows/${id}/publish`
const panel = async () => within(await screen.findByTestId('migration-review'))

describe('migration review on the publish page', () => {
  it('lets an independent reviewer approve with the keyboard and then asks for a third person to publish', async () => {
    signInAs('paul.publisher')
    const id = migratedWorkflow('dana.designer')
    const user = userEvent.setup()
    renderApp(publishPage(id))
    const review = await panel()
    expect(review.getByRole('heading', { level: 2, name: 'Migration review' })).toBeInTheDocument()
    expect(review.getByText('Mapped by').nextElementSibling).toHaveTextContent('Dana Designer')
    expect(review.getByText('Reviewed by').nextElementSibling).toHaveTextContent('Nobody yet')
    expect(review.getByText('Review required')).toBeInTheDocument()
    expect(review.getByText('Workspace policy: independent review required')).toBeInTheDocument()
    expect(review.getByRole('list', { name: 'Reviewed hashes' })).toHaveTextContent(/Draft [0-9a-f]{12}…/)
    expect(review.getByText(/Publishing is blocked/)).toBeInTheDocument()
    expect(review.getByTestId('publish-blocked-reason')).toHaveTextContent('Someone other than the mapper and the draft author must approve it.')
    const publish = screen.getByRole('button', { name: /Publish version 1/ })
    expect(publish).toBeDisabled()
    expect(publish).toHaveAccessibleDescription(/blocked by the migration review policy/)

    const approve = review.getByRole('button', { name: 'Approve migration review' })
    expect(approve).toBeDisabled()
    await user.click(review.getByLabelText('Review reason'))
    await user.keyboard('Checked the mappings against the source report')
    expect(approve).toBeEnabled()
    await user.tab()
    expect(approve).toHaveFocus()
    await user.keyboard('{Enter}')

    await waitFor(() => expect(review.getByText('Independently reviewed')).toBeInTheDocument())
    expect(review.getByText('Reviewed by').nextElementSibling).toHaveTextContent('Paul Publisher')
    expect(review.getByText('Reviewed by').nextElementSibling).toHaveTextContent('Checked the mappings against the source report')
    expect(review.queryByRole('form', { name: 'Approve migration review' })).not.toBeInTheDocument()
    // The reviewer cannot also publish.
    expect(await review.findByTestId('publish-blocked-reason')).toHaveTextContent('You reviewed this migration, so a different person must publish it.')
    expect(screen.getByRole('button', { name: /Publish version 1/ })).toBeDisabled()
    expect(db.migrationReviews).toHaveLength(1)
  })

  it('explains why the mapper cannot approve and does not offer the action', async () => {
    signInAs('paul.publisher')
    const id = migratedWorkflow('paul.publisher')
    renderApp(publishPage(id))
    const review = await panel()
    expect(review.getByTestId('approve-disabled-reason')).toHaveTextContent('You mapped this import, so you cannot approve it.')
    expect(review.getByLabelText('Review reason')).toBeDisabled()
    expect(review.getByRole('button', { name: 'Approve migration review' })).toBeDisabled()
    expect(review.getByRole('button', { name: 'Approve migration review' })).toHaveAccessibleDescription(/You mapped this import/)
  })

  it('explains why the author of the latest draft cannot approve it', async () => {
    signInAs('paul.publisher')
    const id = migratedWorkflow('dana.designer')
    const wf = db.workflows.get(id)!
    wf.drafts.push({ ...structuredClone(wf.drafts[0]!), revision: 2, savedById: userByName('paul.publisher').id })
    renderApp(publishPage(id))
    const review = await panel()
    expect(review.getByTestId('approve-disabled-reason')).toHaveTextContent('You saved the latest draft revision, so you cannot approve it.')
    expect(review.getByRole('button', { name: 'Approve migration review' })).toBeDisabled()
  })

  it('explains that approving needs permission to publish', async () => {
    signInAs('dana.designer')
    const id = migratedWorkflow('paul.publisher')
    renderApp(publishPage(id))
    const review = await panel()
    expect(review.getByTestId('approve-disabled-reason')).toHaveTextContent('You need permission to publish workflows')
  })

  it('treats a historical conversion without a recorded mapper as not approvable', async () => {
    signInAs('paul.publisher')
    const id = migratedWorkflow('dana.designer')
    db.workflows.get(id)!.migration!.mapperId = null
    renderApp(publishPage(id))
    const review = await panel()
    expect(review.getByText('Mapped by').nextElementSibling).toHaveTextContent('Unknown (historical conversion)')
    expect(review.getByTestId('approve-disabled-reason')).toHaveTextContent('no recorded mapper')
  })

  it('lets a third person publish an independently reviewed draft', async () => {
    signInAs('pia.publisher')
    const id = migratedWorkflow('dana.designer')
    db.migrationReviews.push({ wsId: WS, workflowId: id, draftRevision: 1, version: 1, ...reviewFor(id), mapperId: userByName('dana.designer').id, reviewerId: userByName('paul.publisher').id, reason: 'Looks right', reviewedAt: clock.iso() })
    const user = userEvent.setup()
    renderApp(publishPage(id))
    const review = await panel()
    await waitFor(() => expect(review.getByText('Independently reviewed')).toBeInTheDocument())
    expect(review.getByText(/The independent review is in place/)).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: /Publish version 1/ })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: /Publish version 1/ }))
    expect(await screen.findByTestId('publish-success')).toHaveTextContent('Version 1 published')
  })

  it('stops counting an approval once the draft changed afterwards', async () => {
    signInAs('pia.publisher')
    const id = migratedWorkflow('dana.designer')
    db.migrationReviews.push({ wsId: WS, workflowId: id, draftRevision: 1, version: 1, ...reviewFor(id), mapperId: userByName('dana.designer').id, reviewerId: userByName('paul.publisher').id, reason: 'Looks right', reviewedAt: clock.iso() })
    const wf = db.workflows.get(id)!
    wf.drafts.push({ ...structuredClone(wf.drafts[0]!), revision: 2, savedById: userByName('dana.designer').id, savedAt: clock.iso() })
    wf.drafts[1]!.definition.name = 'Edited after the review'
    renderApp(publishPage(id))
    const review = await panel()
    expect(review.getByText('Review required')).toBeInTheDocument()
    expect(review.getByTestId('publish-blocked-reason')).toHaveTextContent('Someone other than the mapper and the draft author must approve it.')
    expect(screen.getByRole('button', { name: /Publish version 1/ })).toBeDisabled()
  })

  it('names the reviewer whose approval no longer applies', async () => {
    signInAs('pia.publisher')
    const id = migratedWorkflow('dana.designer')
    // Rita (someone who can no longer publish) approved the exact draft earlier.
    db.migrationReviews.push({ wsId: WS, workflowId: id, draftRevision: 1, version: 1, ...reviewFor(id), mapperId: userByName('dana.designer').id, reviewerId: userByName('olga.operator').id, reason: 'Fine', reviewedAt: clock.iso() })
    renderApp(publishPage(id))
    const review = await panel()
    expect(review.getByTestId('publish-blocked-reason')).toHaveTextContent('The approval by Olga Operator no longer applies')
  })

  it('shows the server’s reason when an approval is refused and keeps the reason text', async () => {
    signInAs('paul.publisher')
    const id = migratedWorkflow('dana.designer')
    server.use(http.post('*/api/v1/workspaces/:ws/workflows/:id/migration-review', () => HttpResponse.json({ code: 'WORKFLOW_VERSION_CONFLICT', status: 409, title: 'Conflict', detail: 'The migration review changed. Reload before approving.' }, { status: 409 })))
    const user = userEvent.setup()
    renderApp(publishPage(id))
    const review = await panel()
    await user.type(review.getByLabelText('Review reason'), 'Reviewed')
    await user.click(review.getByRole('button', { name: 'Approve migration review' }))
    expect(await review.findByText('The migration review changed. Reload before approving.')).toBeInTheDocument()
    expect(review.getByLabelText('Review reason')).toHaveValue('Reviewed')
  })

  it('shows no review panel for a workflow that was not migrated', async () => {
    signInAs('paul.publisher')
    db.migrationPolicies.set(WS, { required: true, version: 1, reason: 'Four eyes', updatedAt: clock.iso() })
    renderApp(publishPage(PURCHASE_WORKFLOW_ID))
    await screen.findByRole('heading', { level: 2, name: 'Publish' })
    expect(screen.queryByTestId('migration-review')).not.toBeInTheDocument()
  })

  it('leaves the review optional, and publishing open, while the workspace policy is off', async () => {
    signInAs('pia.publisher')
    const id = migratedWorkflow('dana.designer', false)
    renderApp(publishPage(id))
    const review = await panel()
    expect(review.getByText('Workspace policy: review optional')).toBeInTheDocument()
    expect(review.getByText(/does not require an independent review/)).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: /Publish version 1/ })).toBeEnabled())
  })
})

/** Hashes of the current draft, source and mappings, exactly as the review endpoint reports them. */
function reviewFor(id: string) {
  const state = reviewView(db.workflows.get(id)!, userByName('pia.publisher'))
  return { definitionHash: state.definitionHash, sourceHash: state.sourceHash!, mappingsHash: state.mappingsHash! }
}
