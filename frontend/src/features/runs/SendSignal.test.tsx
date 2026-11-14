import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { RunEvent } from '@/api/types'
import { normalizeDefinition } from '@/features/studio/model/types'
import { describeEvent } from '@/lib/events'
import { stepOwnerText } from '@/lib/runText'
import { createRun } from '@/mocks/engine'
import { makeRelease } from '@/mocks/seed'
import { blankDefinition, clock, db, userByName, WS_PURCHASING, type MockRun, type MockWorkflow } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'
import { isSignalName, signalChoices } from './signals'

const WF_ID = 'cccccccc-0000-4000-8000-0000000000f1'

/** A workflow that waits for the signal payment-confirmed, with a signal handler for escalate. */
function signalDefinition() {
  return normalizeDefinition({
    ...blankDefinition('payment-signal', 'Payment by signal'),
    nodes: [
      { id: 'start', type: 'start', label: 'Request submitted', config: {} },
      { id: 'waitPaid', type: 'waitForSignal', label: 'Wait for the payment', config: { signal: 'payment-confirmed' } },
      { id: 'finished', type: 'end', label: 'Finished', config: { businessOutcome: 'Paid', outcomeKind: 'success' } },
    ],
    transitions: [
      { id: 't1', from: 'start', to: 'waitPaid', on: 'next' },
      { id: 't2', from: 'waitPaid', to: 'finished', on: 'next' },
    ],
  })
}

function startWaitingRun(): MockRun {
  const designer = userByName('dana.designer')
  const wf: MockWorkflow = {
    id: WF_ID, wsId: WS_PURCHASING, key: 'payment-signal', name: 'Payment by signal', description: null, ownerId: designer.id,
    drafts: [], releases: [], activeReleaseId: null, version: 1, createdAt: clock.iso(), updatedAt: clock.iso(),
  }
  const release = makeRelease(wf.id, 1, 1, signalDefinition(), designer.id)
  wf.releases.push(release)
  wf.activeReleaseId = release.id
  db.workflows.set(wf.id, wf)
  return createRun({ wsId: WS_PURCHASING, workflow: wf, release, initiator: userByName('rhea.requester'), businessKey: null, input: {} })
}

const operatorActions = () => screen.getByRole('region', { name: 'Operator actions' })

describe('send a signal to a request (S59)', () => {
  it('shows a signal wait as such, and an operator releases it with Send signal', async () => {
    signInAs('olga.operator', { sampleRuns: false })
    const run = startWaitingRun()
    expect(run.lifecycle).toBe('waiting')
    const user = userEvent.setup()
    renderApp(`/w/${WS}/runs/${run.id}`)

    expect(await screen.findByText('Waiting for a signal: Wait for the payment')).toBeVisible()
    expect(await screen.findByTestId('signal-hint')).toHaveTextContent('payment-confirmed')
    await user.click(within(operatorActions()).getByRole('button', { name: /Send signal/ }))

    const dialog = await screen.findByRole('dialog', { name: 'Send a signal to this request' })
    expect(within(dialog).getByRole('radio', { name: /payment-confirmed/ })).toBeChecked()
    expect(within(dialog).getByText('Reaches Wait for the payment (waiting now)')).toBeVisible()
    await user.click(within(dialog).getByRole('button', { name: 'Send signal' }))
    expect(within(dialog).getByText('Add a reason. It is recorded in the audit log.')).toBeVisible()

    await user.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Customer paid by phone')
    await user.click(within(dialog).getByRole('button', { name: 'Send signal' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(run.lifecycle).toBe('completed')
    expect(run.events.find((e) => e.type === 'SIGNAL_SENT')).toMatchObject({ actorId: userByName('olga.operator').id, data: { signal: 'payment-confirmed', reason: 'Customer paid by phone' } })
    expect(await screen.findByText('Olga Operator sent the signal payment-confirmed: Customer paid by phone')).toBeVisible()
    expect(screen.getByText('Wait for the payment: the signal payment-confirmed arrived')).toBeVisible()
  })

  it('reports a signal nothing waits for, and checks the name before sending', async () => {
    signInAs('olga.operator', { sampleRuns: false })
    const run = startWaitingRun()
    const user = userEvent.setup()
    renderApp(`/w/${WS}/runs/${run.id}`)
    await user.click(within(await screen.findByRole('region', { name: 'Operator actions' })).getByRole('button', { name: /Send signal/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Send a signal to this request' })
    await user.click(within(dialog).getByRole('radio', { name: 'Another signal' }))
    await user.type(within(dialog).getByRole('textbox', { name: 'Signal name' }), 'Not A Signal')
    await user.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Testing')
    await user.click(within(dialog).getByRole('button', { name: 'Send signal' }))
    expect(within(dialog).getByText(/Use the signal name from the workflow/)).toBeVisible()

    await user.clear(within(dialog).getByRole('textbox', { name: 'Signal name' }))
    await user.type(within(dialog).getByRole('textbox', { name: 'Signal name' }), 'order-shipped')
    await user.click(within(dialog).getByRole('button', { name: 'Send signal' }))
    expect(await within(dialog).findByText('Nothing in this request is waiting for that signal now.')).toBeVisible()
    expect(run.lifecycle).toBe('waiting')
  })

  it('is not offered while suspended, without signals in the workflow, or to people who cannot operate requests', async () => {
    signInAs('olga.operator', { sampleRuns: false })
    const run = startWaitingRun()
    run.suspension = { since: clock.iso(), reason: 'Checking', byId: userByName('olga.operator').id }
    const { unmount } = renderApp(`/w/${WS}/runs/${run.id}`)
    expect(within(await screen.findByRole('region', { name: 'Operator actions' })).getByRole('button', { name: /Send signal/ })).toBeDisabled()
    unmount()

    // A request whose workflow never uses signals does not offer the button.
    signInAs('olga.operator')
    const plain = [...db.runs.values()].find((r) => r.lifecycle === 'waiting' || r.lifecycle === 'running')!
    const second = renderApp(`/w/${WS}/runs/${plain.id}`)
    expect(await screen.findByRole('region', { name: 'Operator actions' })).toBeVisible()
    expect(screen.queryByRole('button', { name: /Send signal/ })).not.toBeInTheDocument()
    second.unmount()

    signInAs('rhea.requester', { sampleRuns: false })
    const own = startWaitingRun()
    renderApp(`/w/${WS}/runs/${own.id}`)
    expect(await screen.findByText('Waiting for a signal: Wait for the payment')).toBeVisible()
    expect(screen.queryByRole('button', { name: /Send signal/ })).not.toBeInTheDocument()
  })
})

describe('signal choices', () => {
  const waitStep = { nodeId: 'waitPaid', label: 'Wait for the payment', kind: 'waitForSignal' as const, since: '2026-11-01T10:00:00Z', candidateGroup: null, assignee: null, dueAt: null }
  const event = (data: Record<string, unknown>): RunEvent => ({ seq: 1, type: 'SIGNAL_WAIT_STARTED', nodeId: 'waitPaid', actor: null, occurredAt: '2026-11-01T10:00:00Z', data } as unknown as RunEvent)

  it('lists the open signal waits and the signal handlers of the workflow', () => {
    const definition = {
      nodes: [{ id: 'waitPaid', type: 'waitForSignal', config: { signal: 'payment-confirmed' } }],
      subprocesses: [
        { id: 'esc', label: 'Escalation', type: 'event', trigger: { type: 'signal', signal: 'escalate' } },
        { id: 'late', label: 'Too late', type: 'event', trigger: { type: 'timer', after: 'P1D' } },
      ],
    }
    expect(signalChoices({ currentSteps: [waitStep] }, [], definition)).toEqual([
      { signal: 'payment-confirmed', receivers: ['Wait for the payment (waiting now)'] },
      { signal: 'escalate', receivers: ['the handler Escalation'] },
    ])
  })

  it('reads the signal name from the wait event when the workflow cannot be read', () => {
    expect(signalChoices({ currentSteps: [waitStep] }, [event({ signal: 'payment-confirmed' })])).toEqual([
      { signal: 'payment-confirmed', receivers: ['Wait for the payment (waiting now)'] },
    ])
    expect(signalChoices({ currentSteps: [] }, [event({ signal: 'payment-confirmed' })])).toEqual([])
  })

  it('accepts only workflow signal names', () => {
    expect(isSignalName('payment-confirmed')).toBe(true)
    expect(isSignalName('paymentConfirmed')).toBe(true)
    expect(isSignalName('a')).toBe(false)
    expect(isSignalName('Payment')).toBe(false)
    expect(isSignalName('pay--ment')).toBe(false)
  })

  it('describes signal steps and events in words', () => {
    expect(stepOwnerText(waitStep)).toBe('Waiting for a signal: Wait for the payment')
    expect(stepOwnerText({ ...waitStep, kind: 'generateDocument', label: 'Create the contract' })).toBe('Create the contract is in progress')
    const ctx = { labels: { waitPaid: 'Wait for the payment' }, groupNames: {}, initiatorName: 'Rhea' }
    expect(describeEvent(event({ label: 'Wait for the payment', signal: 'payment-confirmed' }), ctx)).toBe('Wait for the payment: waiting for the signal payment-confirmed')
    expect(describeEvent({ ...event({ signal: 'go', label: 'Send go' }), type: 'SIGNAL_SENT', nodeId: 'sendGo' }, ctx)).toBe('Send go: sent the signal go')
  })
})
