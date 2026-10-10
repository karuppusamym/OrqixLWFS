import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { db, PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import { server } from '@/test/server'
import { renderApp, signInAs, WS } from '@/test/utils'

const EDIT = `/w/${WS}/workflows/${PURCHASE_WORKFLOW_ID}/edit`

/** The select button of a step row (its name also appears in the row's Add and Delete buttons). */
function stepButton(id: string): HTMLElement {
  const button = screen.getByTestId(`step-row-${id}`).querySelector<HTMLElement>('button[aria-pressed]')
  if (!button) throw new Error(`No step button for ${id}`)
  return button
}

async function openSteps(user: ReturnType<typeof userEvent.setup>) {
  await screen.findByText(/Draft revision 1/)
  await user.click(screen.getByRole('tab', { name: 'Steps' }))
  return screen.findByRole('list', { name: 'Workflow steps' })
}

describe('studio (Steps tab, inspector, autosave, check, conflicts)', () => {
  it('edits a wait duration and saves the timer configuration', async () => {
    signInAs('dana.designer')
    const draft = db.workflows.get(PURCHASE_WORKFLOW_ID)!.drafts.at(-1)!.definition
    const node = draft.nodes.find((n) => n.id === 'manager')!
    node.type = 'wait'
    node.config = { duration: 'PT1H' }
    const user = userEvent.setup()
    renderApp(EDIT)
    await openSteps(user)
    await user.click(stepButton('manager'))
    const duration = await screen.findByLabelText(/^Wait duration/)
    await user.clear(duration)
    await user.type(duration, 'PT30M')
    await user.tab()
    await waitFor(() => expect(screen.getByTestId('save-state')).toHaveTextContent('Saved'), { timeout: 12000 })
    expect(db.workflows.get(PURCHASE_WORKFLOW_ID)!.drafts.at(-1)!.definition.nodes.find((n) => n.id === 'manager')!.config.duration).toBe('PT30M')
  }, 30000)

  it('removes the timeout route when an approval time limit is cleared', async () => {
    signInAs('dana.designer')
    const draft = db.workflows.get(PURCHASE_WORKFLOW_ID)!.drafts.at(-1)!.definition
    draft.nodes.find((n) => n.id === 'manager')!.config.timeoutAfter = 'PT2H'
    draft.transitions.push({ id: 'timeout-route', from: 'manager', to: 'rejected', on: 'timedOut' })
    const user = userEvent.setup()
    renderApp(EDIT)
    await openSteps(user)
    expect(within(screen.getByTestId('step-row-manager')).getByLabelText('Timed out')).toHaveValue('rejected')
    await user.click(stepButton('manager'))
    await user.clear(await screen.findByLabelText(/^Time limit/))
    await user.tab()
    await waitFor(() => expect(screen.getByTestId('save-state')).toHaveTextContent('Saved'), { timeout: 5000 })
    const saved = db.workflows.get(PURCHASE_WORKFLOW_ID)!.drafts.at(-1)!.definition
    expect(saved.nodes.find((n) => n.id === 'manager')!.config.timeoutAfter).toBeUndefined()
    expect(saved.transitions.some((t) => t.id === 'timeout-route')).toBe(false)
  })

  it('shows an unavailable pinned connector version without displaying another version’s mappings', async () => {
    signInAs('dana.designer')
    const draft = db.workflows.get(PURCHASE_WORKFLOW_ID)!.drafts.at(-1)!.definition
    draft.dependencies.connections[0]!.definitionVersion = 2
    const user = userEvent.setup()
    renderApp(EDIT)
    await openSteps(user)
    await user.click(stepButton('procure'))
    await screen.findByText(/Connection version 2 is unavailable/)
    expect(screen.getByLabelText(/^Operation/)).toBeDisabled()
    expect(screen.queryByLabelText(/Amount \(number\)/)).not.toBeInTheDocument()
  })
  it('loads the draft with its header, steps in order, and Canvas and Steps tabs', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    renderApp(EDIT)
    const steps = await openSteps(user)
    expect(screen.getByTestId('save-state')).toHaveTextContent('Saved')
    expect(screen.getByRole('tab', { name: 'Canvas' })).toBeInTheDocument()
    const rows = within(steps).getAllByRole('listitem').filter((li) => li.dataset.testid?.startsWith('step-row-'))
    const order = rows.map((r) => r.dataset.testid?.replace('step-row-', ''))
    expect(order).toHaveLength(10)
    expect(order[0]).toBe('start')
    // Topological order: every step comes after the steps that lead to it.
    const before = (a: string, b: string) => expect(order.indexOf(a), `${a} before ${b}`).toBeLessThan(order.indexOf(b))
    before('manager', 'amountPolicy')
    before('amountPolicy', 'route')
    before('route', 'finance')
    before('finance', 'procure')
    before('procure', 'notify')
    before('notify', 'fulfilled')
    before('manager', 'notifyRejected')
    before('notifyRejected', 'rejected')
    // The approval's two routes are selects with business labels.
    const manager = screen.getByTestId('step-row-manager')
    expect(within(manager).getByLabelText('When approved')).toHaveValue('amountPolicy')
    expect(within(manager).getByLabelText('When rejected')).toHaveValue('notifyRejected')
  })

  it('edits a route from the Steps tab, autosaves after 1.5 s with If-Match, and shows honest save state', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    let ifMatch: string | null = null
    server.use(
      http.put('*/api/v1/workspaces/:ws/workflows/:id/draft', async ({ request }) => {
        ifMatch = request.headers.get('If-Match')
        return undefined
      }),
    )
    renderApp(EDIT)
    await openSteps(user)
    await user.selectOptions(within(screen.getByTestId('step-row-manager')).getByLabelText('When rejected'), 'rejected')
    expect(screen.getByTestId('save-state')).toHaveTextContent('Unsaved changes')
    await waitFor(() => expect(screen.getByTestId('save-state')).toHaveTextContent('Saved'), { timeout: 5000 })
    expect(ifMatch).toBe('"r1"')
    expect(screen.getByText('Draft revision 2')).toBeInTheDocument()
    const stored = db.workflows.get(PURCHASE_WORKFLOW_ID)!.drafts.at(-1)!.definition
    expect(stored.transitions.find((t) => t.id === 't3')?.to).toBe('rejected')
  })

  it('undo and redo work with the keyboard and return to the saved state', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    renderApp(EDIT)
    await openSteps(user)
    const select = within(screen.getByTestId('step-row-manager')).getByLabelText('When rejected')
    await user.selectOptions(select, 'rejected')
    expect(select).toHaveValue('rejected')
    await user.keyboard('{Control>}z{/Control}')
    expect(select).toHaveValue('notifyRejected')
    expect(screen.getByTestId('save-state')).toHaveTextContent('Saved') // back at the saved document
    await user.keyboard('{Control>}{Shift>}z{/Shift}{/Control}')
    expect(select).toHaveValue('rejected')
    await user.keyboard('{Control>}z{/Control}')
    await user.keyboard('{Control>}y{/Control}')
    expect(select).toHaveValue('rejected')
  })

  it('shows the 412 conflict dialog with Reload latest and Download my version', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    // Someone else saved revision 2 in the meantime.
    const wf = db.workflows.get(PURCHASE_WORKFLOW_ID)!
    renderApp(EDIT)
    await openSteps(user)
    wf.drafts.push({ revision: 2, savedAt: new Date().toISOString(), savedById: wf.ownerId, definition: structuredClone(wf.drafts[0]!.definition) })
    await user.selectOptions(within(screen.getByTestId('step-row-manager')).getByLabelText('When rejected'), 'rejected')
    const dialog = await screen.findByRole('dialog', { name: 'Your draft is out of date' }, { timeout: 5000 })
    expect(within(dialog).getByTestId('conflict-message')).toHaveTextContent('A newer version was saved by Dana Designer')
    expect(within(dialog).getByRole('button', { name: 'Download my version' })).toBeInTheDocument()
    expect(screen.getByTestId('save-state')).toHaveTextContent('Conflict')
    await user.click(within(dialog).getByRole('button', { name: 'Reload latest' }))
    await waitFor(() => expect(screen.getByText('Draft revision 2')).toBeInTheDocument())
    expect(within(screen.getByTestId('step-row-manager')).getByLabelText('When rejected')).toHaveValue('notifyRejected') // latest, not mine
  })

  it('Check workflow lists issues by step and Go to step opens the field the issue names', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    renderApp(EDIT)
    await openSteps(user)
    // Break the approval: no approver group.
    await user.click(stepButton('manager'))
    const group = await screen.findByLabelText(/Assign to/)
    await user.selectOptions(group, '')
    await user.keyboard('{Control>}s{/Control}')
    await waitFor(() => expect(screen.getByTestId('save-state')).toHaveTextContent('Saved'), { timeout: 5000 })

    await user.click(screen.getByRole('button', { name: 'Check workflow' }))
    const panel = await screen.findByTestId('check-panel')
    await waitFor(() => expect(within(panel).getByTestId('check-summary')).toHaveTextContent(/1 blocker/))
    expect(within(panel).getByRole('heading', { name: 'Review request' })).toBeInTheDocument()
    expect(within(panel).getByText(/needs an approver group/)).toBeInTheDocument()

    // Select something else first, then use Go to step: it selects the step and focuses the field.
    await user.click(stepButton('fulfilled'))
    await user.click(within(panel).getByRole('button', { name: /Go to step Review request/ }))
    await waitFor(() => expect(screen.getByLabelText(/Assign to/)).toHaveFocus(), { timeout: 3000 })
    // The step card carries the issue count too.
    expect(within(screen.getByTestId('step-row-manager')).getByText(/1 issue/)).toBeInTheDocument()
  })

  it('deleting a step lists what goes with it, prunes layout and rule table, and can be undone', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    renderApp(EDIT)
    await openSteps(user)
    await user.click(within(screen.getByTestId('step-row-amountPolicy')).getByRole('button', { name: 'Delete Check purchase amount' }))
    const dialog = await screen.findByRole('dialog', { name: 'Delete "Check purchase amount"?' })
    expect(within(dialog).getByText(/The rule table .amount-policy./)).toBeInTheDocument()
    expect(within(dialog).getAllByText(/The connection from/)).toHaveLength(2)
    await user.click(within(dialog).getByRole('button', { name: 'Delete step' }))
    await waitFor(() => expect(screen.queryByTestId('step-row-amountPolicy')).not.toBeInTheDocument())
    await user.keyboard('{Control>}z{/Control}')
    expect(await screen.findByTestId('step-row-amountPolicy')).toBeInTheDocument()
  })

  it('is read-only for a viewer: no palette, no add or delete, disabled routes', async () => {
    signInAs('vic.viewer')
    const user = userEvent.setup()
    renderApp(EDIT)
    await openSteps(user)
    expect(screen.getByText(/You can look at this draft but not change it/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Add step after/ })).not.toBeInTheDocument()
    expect(within(screen.getByTestId('step-row-manager')).getByLabelText('When approved')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Publish' })).toBeDisabled()
  })

  it('tests a rule with a sample value (Test this rule)', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    renderApp(EDIT)
    await openSteps(user)
    await user.click(stepButton('amountPolicy'))
    const sample = await screen.findByLabelText('Sample value')
    await user.clear(sample)
    await user.type(sample, '5000')
    await user.click(screen.getByRole('button', { name: 'Test this rule' }))
    expect(await screen.findByText(/Row 1 matched/, undefined, { timeout: 5000 })).toBeInTheDocument()
    expect(screen.getByTestId('rule-test-result')).toHaveTextContent('Route is financeReview')
    await user.clear(sample)
    await user.type(sample, '4999.99')
    await user.click(screen.getByRole('button', { name: 'Test this rule' }))
    await waitFor(() => expect(screen.getByTestId('rule-test-result')).toHaveTextContent('Otherwise'), { timeout: 5000 })
    expect(screen.getByTestId('rule-test-result')).toHaveTextContent('Route is standard')
  })

  it('call-a-system inspector offers connections and builds the mapping from the operation contract', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    renderApp(EDIT)
    await openSteps(user)
    await user.click(stepButton('procure'))
    expect(await screen.findByLabelText(/^Connection/)).toHaveValue('procurement')
    expect(await screen.findByLabelText(/^Operation/)).toHaveValue('createOrder')
    // Each request field of the operation, with a required badge and a typed source chooser.
    const amount = await screen.findByLabelText(/Amount \(number\)/)
    expect(amount).toHaveValue('/input/amount')
    expect(within(amount).queryByRole('option', { name: /Cost center/ })).not.toBeInTheDocument() // text field cannot feed a number
    expect(screen.getAllByText('required').length).toBeGreaterThanOrEqual(3)
    expect(screen.getByTestId('effect-class')).toHaveTextContent('Safe to retry: the service de-duplicates repeated requests')
  })

  it('call-a-system lists AI agents with a label and shows what the agent may do, read-only (S13)', async () => {
    signInAs('dana.designer')
    const draft = db.workflows.get(PURCHASE_WORKFLOW_ID)!.drafts.at(-1)!.definition
    const node = draft.nodes.find((n) => n.id === 'procure')!
    node.config = {
      connectionRef: 'vendor-risk-assistant',
      operation: 'assessVendor',
      inputMapping: {},
      resultMapping: {},
      timeoutSeconds: 30,
    }
    draft.dependencies.connections = [{ key: 'vendor-risk-assistant', definitionVersion: 1 }]
    const user = userEvent.setup()
    renderApp(EDIT)
    await openSteps(user)
    await user.click(stepButton('procure'))
    const connection = await screen.findByLabelText(/^Connection/)
    expect(await within(connection).findByRole('option', { name: /Vendor risk assistant · AI agent/ })).toBeInTheDocument()
    expect(within(connection).getByRole('option', { name: /Procurement service$/ })).toBeInTheDocument()
    const spec = await screen.findByTestId('agent-spec')
    expect(within(spec).getByText('Uses AI (cost varies, read-only)')).toBeInTheDocument()
    expect(within(spec).getByTestId('agent-instructions')).toHaveTextContent(/vendor risk assistant of the procurement team/)
    expect(within(spec).getByTestId('agent-tools')).toHaveTextContent('Vendor registry')
    expect(within(spec).queryByRole('textbox')).not.toBeInTheDocument()
    // The result fields of the agent can be kept like any other result.
    expect(await screen.findByRole('checkbox', { name: /Keep .riskScore./ })).toBeInTheDocument()
  })
})
