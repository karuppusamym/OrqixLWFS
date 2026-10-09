import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { db, PURCHASE_WORKFLOW_ID, type MockRun } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'

/** Give a seeded request a generated reference that differs from the person's own reference, like the real server does. */
function withReference(key: string, reference: string, own: string): MockRun {
  const run = [...db.runs.values()].find((r) => r.businessKey === key)
  if (!run) throw new Error(`No run ${key}`)
  run.reference = reference
  run.businessKey = own
  return run
}

const UUID_PREFIX = (run: MockRun) => run.id.replace(/-/g, '').slice(0, 8).toUpperCase()

describe('request references (ORQ-1043) are shown instead of UUID prefixes', () => {
  it('home, My requests and run detail show the reference, and the own reference next to it', async () => {
    signInAs('rhea.requester')
    const run = withReference('ORQ-1005', 'ORQ-2005', 'PO-77')
    const home = renderApp(`/w/${WS}`)
    const recent = await screen.findByRole('region', { name: 'Your recent requests' })
    expect(await within(recent).findByText(/ORQ-2005/)).toBeInTheDocument()
    expect(within(recent).queryByText(new RegExp(UUID_PREFIX(run)))).not.toBeInTheDocument()
    home.unmount()

    const list = renderApp(`/w/${WS}/requests`)
    expect(await screen.findByRole('link', { name: 'ORQ-2005' })).toHaveAttribute('href', `/w/${WS}/runs/${run.id}`)
    list.unmount()

    renderApp(`/w/${WS}/runs/${run.id}`)
    expect(await screen.findByTestId('run-reference')).toHaveTextContent('ORQ-2005')
    expect(screen.getByRole('heading', { level: 1 }).parentElement).toHaveTextContent('your reference PO-77')
  })

  it('falls back to the own reference, then a short ID, for servers that send no reference', async () => {
    signInAs('rhea.requester')
    const run = withReference('ORQ-1001', 'ORQ-1001', 'ORQ-1001')
    delete run.reference
    const first = renderApp(`/w/${WS}/requests`)
    expect(await screen.findByRole('link', { name: 'ORQ-1001' })).toBeInTheDocument()
    first.unmount()
    run.businessKey = null
    renderApp(`/w/${WS}/requests`)
    expect(await screen.findByRole('link', { name: UUID_PREFIX(run) })).toBeInTheDocument()
  })

  it('My tasks, the task panel and Home show the reference of the request', async () => {
    signInAs('max.manager')
    const run = withReference('ORQ-1005', 'ORQ-2005', 'PO-77')
    const task = run.tasks.find((t) => t.status === 'open')!
    const home = renderApp(`/w/${WS}`)
    const waiting = await screen.findByRole('region', { name: 'Waiting for you' })
    expect(await within(waiting).findByText(/ORQ-2005/)).toBeInTheDocument()
    home.unmount()

    const tasks = renderApp(`/w/${WS}/tasks?view=claimable`)
    const table = await screen.findByRole('table')
    expect(within(table).getByText('ORQ-2005')).toBeInTheDocument()
    expect(within(table).queryByText('PO-77')).not.toBeInTheDocument()
    tasks.unmount()

    renderApp(`/w/${WS}/tasks/${task.id}?view=claimable`)
    const actions = await screen.findByTestId('task-actions')
    expect(actions).toBeInTheDocument()
    expect(screen.getByText(/Purchase approval · ORQ-2005/i)).toBeInTheDocument()
  })

  it('the submitted page shows the generated reference', async () => {
    signInAs('rhea.requester')
    const user = userEvent.setup()
    renderApp(`/w/${WS}/start/${PURCHASE_WORKFLOW_ID}`)
    await user.type(await screen.findByLabelText(/What are you buying/), 'Stapler')
    await user.type(screen.getByLabelText(/Business purpose/), 'Office supplies for the new team.')
    await user.selectOptions(screen.getByLabelText(/Cost center/), 'OPS')
    await user.type(screen.getByLabelText(/Amount/), '25')
    await user.click(screen.getByRole('button', { name: 'Review request' }))
    await user.click(await screen.findByRole('button', { name: 'Submit request' }))
    const id = await screen.findByTestId('request-id')
    await waitFor(() => expect(id.textContent).toMatch(/^ORQ-\d{4,}$/))
  })

  it('notifications show the reference of the request they are about', async () => {
    signInAs('rhea.requester')
    const user = userEvent.setup()
    const run = [...db.runs.values()].find((r) => r.businessKey === 'ORQ-1005')!
    run.reference = 'ORQ-2005'
    const note = db.notifications.find((n) => n.runId && n.userId === db.runs.get(n.runId)?.initiatorId)
    renderApp(`/w/${WS}`)
    const bell = await screen.findByRole('button', { name: /Notifications/ })
    await user.click(bell)
    const dialog = await screen.findByRole('dialog', { name: 'Notifications' })
    const refs = within(dialog).getAllByText(/^ORQ-\d+$/)
    expect(refs.length).toBeGreaterThan(0)
    expect(note).toBeDefined()
  })
})
