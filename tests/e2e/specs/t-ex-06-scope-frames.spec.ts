import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { decideOpenTask, selectStep, submitPurchase, workspaceId } from '../support/app'
import { createWorkflow, editDraft, openTaskOfRun, publish, stubFault, uniq } from '../support/api'
import { expectNoSeriousViolations } from '../support/axe'

/**
 * EX06/EX08 (docs/build/S71): a section and a handler are drawn as frames on the studio canvas; the call inspector
 * offers "When it fails" inside a section with an error route; after publishing, a rejected purchase order (stub 400)
 * raises the error, the section is left by its error route, and the run diagram shows the frame as passed.
 * Real stack only (the mock validator and engine do not model scopes).
 */
test.skip(MOCK, 'The mock validator and engine do not model sections, handlers or call failures (docs/build/S71).')

test('section and handler frames, the opt-in call failure, and the run diagram after a rejected call', async ({ sessions }) => {
  const designer = await sessions.as('dana.designer')
  const ws = await workspaceId(designer)
  const wf = await createWorkflow(designer, ws, 'Purchase approval')
  await editDraft(designer, ws, wf.id, (d) => {
    d.nodes.push(
      { id: 'withdrawnNote', type: 'notification', label: 'Tell requester it was withdrawn', config: { channel: 'inApp', recipient: 'initiator', message: 'Your request was withdrawn.' } },
      { id: 'withdrawnEnd', type: 'end', label: 'Withdrawn', config: { businessOutcome: 'Withdrawn', outcomeKind: 'rejected' } },
    )
    d.transitions.push({ id: 'w1', from: 'withdrawnNote', to: 'withdrawnEnd', on: 'next' })
    d.subprocesses = [
      { id: 'order', label: 'Order', type: 'embedded', nodes: ['procure', 'notify'], onError: { to: 'notifyRejected', errorCode: 'ORDER_REJECTED' } },
      { id: 'withdraw', label: 'Withdraw', type: 'event', nodes: ['withdrawnNote', 'withdrawnEnd'], trigger: { type: 'signal', signal: 'withdraw' }, interrupting: true },
    ]
    delete d.uiLayout
  })

  await designer.goto(`/w/${ws}/workflows/${wf.id}/edit`)
  await designer.getByRole('tab', { name: 'Canvas' }).click()
  const order = designer.getByTestId('scope-frame-order')
  await expect(order).toBeVisible()
  await expect(order).toHaveAttribute('data-kind', 'section')
  await expect(order).toContainText('Section: Order')
  await expect(order).toContainText('error ORDER_REJECTED')
  await expect(designer.getByTestId('scope-frame-withdraw')).toHaveAttribute('data-kind', 'handler')
  await expect(designer.getByTestId('scope-frame-withdraw')).toContainText('on signal withdraw')
  await expectNoSeriousViolations(designer, 'Studio canvas with section and handler frames')

  await designer.getByRole('tab', { name: 'Steps' }).click()
  await selectStep(designer, 'procure')
  const inspector = designer.getByTestId('inspector')
  const optIn = inspector.getByLabel(/Raise an error for the section/)
  await expect(optIn).toBeVisible()
  await optIn.check()
  await expect(inspector.getByLabel('Error code')).toHaveValue('ORDER_REJECTED')
  await expectNoSeriousViolations(designer, 'Call inspector, When it fails')
  await expect(designer.getByText(/^Saved/).first()).toBeVisible({ timeout: 20_000 })
  await designer.getByRole('button', { name: 'Check workflow' }).click()
  await expect(designer.getByText(/no problems|ready to publish|Everything looks right|passes/i).first()).toBeVisible({ timeout: 20_000 })

  const publisher = await sessions.as('paul.publisher')
  await publish(publisher, ws, wf.id)

  const requester = await sessions.as('rhea.requester')
  const title = uniq('Rejected order')
  await stubFault('status400', 1)
  const { runUrl } = await submitPurchase(requester, ws, wf.id, { title, purpose: 'Section error route', costCenter: 'OPS', amount: '200' })
  const manager = await sessions.as('mani.manager')
  await openTaskOfRun(manager, ws, /\/runs\/([^/?#]+)/.exec(runUrl)![1])
  await decideOpenTask(manager, 'approve', 'Go ahead')

  await requester.goto(runUrl)
  await expect(requester.getByRole('main')).toContainText(/Rejected/i, { timeout: 60_000 })
  await requester.getByRole('button', { name: 'Diagram' }).click()
  await expect(requester.getByTestId('run-diagram-canvas')).toBeVisible()
  const frame = requester.getByTestId('scope-frame-order')
  await expect(frame).toHaveAttribute('data-state', 'passed')
  await expect(frame).toContainText('left because a step raised an error')
  await expect(requester.getByTestId('scope-frame-withdraw')).toHaveAttribute('data-state', 'idle')
  await expect(requester.getByTestId('run-scopes')).toContainText('Section Order')
  await expectNoSeriousViolations(requester, 'Run diagram with section and handler frames')
  await stubFault('none', 0).catch(() => undefined)
})
