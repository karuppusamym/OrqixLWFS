import { focusManager } from '@tanstack/react-query'
import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { eventsPollInterval } from '@/api/queries'
import { claim, decide } from '@/mocks/engine'
import { db, userByName, type MockRun } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'

function runByKey(key: string): MockRun {
  for (const run of db.runs.values()) if (run.businessKey === key) return run
  throw new Error(`No run ${key}`)
}

/** Simulate the window losing and regaining focus, which refetches polled data. */
async function regainFocus() {
  await act(async () => {
    focusManager.setFocused(false)
    focusManager.setFocused(true)
  })
}

const at = (run: MockRun) => `/w/${WS}/runs/${run.id}`

describe('run detail', () => {
  it('requester view: request values with USD, decisions, a business-language timeline, no operator tools', async () => {
    signInAs('rhea.requester')
    const run = runByKey('ORQ-1001') // completed and fulfilled
    renderApp(at(run))
    expect(await screen.findByRole('heading', { level: 1, name: 'Purchase approval' })).toBeInTheDocument()
    expect(screen.getByTestId('run-status')).toHaveTextContent('Completed · Fulfilled')
    expect(screen.getByTestId('request-values')).toHaveTextContent('$240.00')
    const timeline = screen.getByTestId('timeline')
    expect(timeline).toHaveTextContent('Rhea Requester submitted the request (version 1)')
    expect(timeline).toHaveTextContent('Waiting for Managers: Review request')
    expect(timeline).toHaveTextContent('Mani Manager approved Review request')
    expect(timeline).toHaveTextContent(/Create purchase order completed \(reference PO-\d+\)/)
    expect(timeline).toHaveTextContent('Finished: Fulfilled')
    expect(screen.getByText(/Review request: Approved by Mani Manager/)).toBeInTheDocument()
    expect(screen.queryByText('Operator actions')).not.toBeInTheDocument()
    expect(screen.queryByText('Technical details')).not.toBeInTheDocument()
  })

  it('shows a rejection with its reason', async () => {
    signInAs('rhea.requester')
    renderApp(at(runByKey('ORQ-1002')))
    await screen.findByTestId('run-status')
    expect(screen.getByTestId('run-status')).toHaveTextContent('Completed · Rejected')
    expect(screen.getByText(/Reason: The travel budget for this quarter is spent/)).toBeInTheDocument()
  })

  it('a requester sees a calm message while an operator fixes a stuck request', async () => {
    signInAs('dana.designer') // requested ORQ-1004
    renderApp(at(runByKey('ORQ-1004')))
    expect(await screen.findByText('This request needs attention')).toBeInTheDocument()
    expect(screen.getByText(/An operator has been alerted/)).toBeInTheDocument()
    expect(screen.queryByTestId('attention-message')).not.toBeInTheDocument() // technical wording is for operators
  })

  it('operator view: never "Failed" for an unknown outcome; retry is refused until reconciled', async () => {
    signInAs('olga.operator')
    renderApp(at(runByKey('ORQ-1004')))
    const message = await screen.findByTestId('attention-message')
    expect(message).toHaveTextContent('Procurement service may have created the order. Check it before retrying.')
    expect(screen.getByTestId('run-status')).toHaveTextContent('Needs attention')
    expect(screen.queryByText(/^Failed$/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry step' })).toBeDisabled()
    expect(screen.getByText(/Retry: The outcome of a step is unknown\. Reconcile it first\./)).toBeInTheDocument()
    expect(screen.getByText('Technical details')).toBeInTheDocument()
    expect(screen.getByText(/Outcome unknown/)).toBeInTheDocument()
  })

  it('reconcile needs evidence and the result fields from the node, then resumes the run', async () => {
    signInAs('olga.operator')
    const user = userEvent.setup()
    const run = runByKey('ORQ-1004')
    renderApp(at(run))
    await user.click(await screen.findByRole('button', { name: 'Reconcile outcome' }))
    const dialog = await screen.findByRole('dialog', { name: 'Reconcile the outcome' })
    // Result fields come from the node's resultMapping.
    const field = await within(dialog).findByLabelText(/procurementId/)
    await user.click(within(dialog).getByRole('button', { name: 'Record outcome' }))
    expect(within(dialog).getByText(/at least 10 characters/)).toBeInTheDocument()
    expect(within(dialog).getByText('Enter the value the other system returned.')).toBeInTheDocument()
    expect(run.lifecycle).toBe('needsAttention') // nothing was sent
    await user.type(within(dialog).getByLabelText(/Evidence/), 'Order PO-9000 exists in the procurement system.')
    await user.type(field, 'PO-9000')
    await user.click(within(dialog).getByRole('button', { name: 'Record outcome' }))
    await waitFor(() => expect(screen.getByTestId('run-status')).toHaveTextContent('Completed · Fulfilled'))
    expect(run.steps.procure).toEqual({ procurementId: 'PO-9000' })
    expect(screen.getByTestId('timeline')).toHaveTextContent('Olga Operator confirmed the outcome of Create purchase order')
  })

  it('"not applied" leaves the run waiting for an explicit retry, which then needs a reason', async () => {
    signInAs('olga.operator')
    const user = userEvent.setup()
    const run = runByKey('ORQ-1004')
    renderApp(at(run))
    await user.click(await screen.findByRole('button', { name: 'Reconcile outcome' }))
    const dialog = await screen.findByRole('dialog', { name: 'Reconcile the outcome' })
    await user.click(within(dialog).getByLabelText(/did not do the work/))
    await user.type(within(dialog).getByLabelText(/Evidence/), 'No order exists for this request.')
    await user.click(within(dialog).getByRole('button', { name: 'Record outcome' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(run.lifecycle).toBe('needsAttention')
    const retry = await screen.findByRole('button', { name: 'Retry step' })
    await waitFor(() => expect(retry).toBeEnabled())
    await user.click(retry)
    const retryDialog = await screen.findByRole('dialog', { name: 'Retry this step' })
    await user.click(within(retryDialog).getByRole('button', { name: 'Retry step' }))
    expect(within(retryDialog).getByText(/Add a reason/)).toBeInTheDocument()
    await user.type(within(retryDialog).getByLabelText(/Reason/), 'Service is back')
    db.procurementFault = 'none'
    await user.click(within(retryDialog).getByRole('button', { name: 'Retry step' }))
    await waitFor(() => expect(screen.getByTestId('run-status')).toHaveTextContent('Completed · Fulfilled'))
  })

  it('cancel requires a reason and warns that finished steps are not reversed', async () => {
    signInAs('olga.operator')
    const user = userEvent.setup()
    const run = runByKey('ORQ-1005') // waiting for a manager
    renderApp(at(run))
    await user.click(await screen.findByRole('button', { name: 'Cancel request' }))
    const dialog = await screen.findByRole('dialog', { name: 'Cancel this request' })
    expect(within(dialog).getByText('Cancelling does not undo work in other systems')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Cancel request' }))
    expect(within(dialog).getByText(/Add a reason/)).toBeInTheDocument()
    expect(run.lifecycle).toBe('waiting')
    await user.type(within(dialog).getByLabelText(/Reason/), 'Duplicate request')
    await user.click(within(dialog).getByRole('button', { name: 'Cancel request' }))
    await waitFor(() => expect(screen.getByTestId('run-status')).toHaveTextContent('Cancelled'))
    expect(screen.getByTestId('timeline')).toHaveTextContent('Olga Operator cancelled the request: Duplicate request')
  })

  it('reassigns a waiting task to a person or back to the group, with a reason', async () => {
    signInAs('olga.operator')
    const user = userEvent.setup()
    const run = runByKey('ORQ-1005')
    renderApp(at(run))
    await user.click(await screen.findByRole('button', { name: 'Reassign task' }))
    const dialog = await screen.findByRole('dialog', { name: 'Reassign this task' })
    await user.click(within(dialog).getByRole('button', { name: 'Reassign' }))
    expect(within(dialog).getByText(/Add a reason/)).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText(/Reason/), 'Mani is out of office')
    await user.click(within(dialog).getByRole('button', { name: 'Reassign' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(run.events.at(-1)?.type).toBe('TASK_REASSIGNED')
    expect(run.tasks.find((t) => t.status === 'open')?.assigneeId).toBeNull() // returned to the group
  })

  it('picks up new events when the window regains focus, asking only for events after the last one', async () => {
    signInAs('rhea.requester')
    const run = runByKey('ORQ-1005') // waiting at the manager
    renderApp(at(run))
    await screen.findByTestId('timeline')
    expect(screen.getByTestId('timeline')).not.toHaveTextContent('picked up')
    const task = run.tasks.find((t) => t.status === 'open')!
    claim(run, task, userByName('mani.manager'))
    await regainFocus()
    await waitFor(() => expect(screen.getByTestId('timeline')).toHaveTextContent('Mani Manager picked up Review request'))
    decide(run, task, userByName('mani.manager'), 'approve', null, null)
    await regainFocus()
    await waitFor(() => expect(screen.getByTestId('run-status')).toHaveTextContent('Completed · Fulfilled'))
    expect(screen.getAllByText(/picked up Review request/)).toHaveLength(1) // not duplicated by the second poll
  })

  it('polls every 3 s while running, 15 s while waiting or needing attention, and stops when finished', () => {
    expect(eventsPollInterval('running')).toBe(3000)
    expect(eventsPollInterval('waiting')).toBe(15000)
    expect(eventsPollInterval('needsAttention')).toBe(15000)
    expect(eventsPollInterval('completed')).toBe(false)
    expect(eventsPollInterval('cancelled')).toBe(false)
  })

  it('explains that a request cannot be found without revealing whether it exists', async () => {
    signInAs('fred.facilities')
    renderApp(`/w/${WS}/runs/00000000-0000-4000-8000-000000000000`)
    expect(await screen.findByRole('heading', { level: 1, name: 'Page not found' })).toBeInTheDocument()
  })
})
