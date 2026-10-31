import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { db } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'

const isPrimary = (el: HTMLElement) => /(^|\s)bg-primary(\s|$)/.test(el.className)

/** The demo seed (nine requests, tools/demo/seed_demo.py) is what the walkthrough shows; these tests keep it reading well. */
describe('demo walkthrough: wording, hierarchy and deep links', () => {
  it('operator: a definite failure leads with Retry, an unknown outcome with Reconcile, and Cancel is always the destructive outline', async () => {
    signInAs('olga.operator', { demo: true })
    const printer = [...db.runs.values()].find((r) => r.input.title === 'Label printer')!
    const first = renderApp(`/w/${WS}/runs/${printer.id}`)
    expect(await screen.findByText(/Recommended next step: review the failed step and retry/)).toBeInTheDocument()
    expect(isPrimary(screen.getByRole('button', { name: 'Retry step' }))).toBe(true)
    expect(isPrimary(screen.getByRole('button', { name: 'Reconcile outcome' }))).toBe(false)
    expect(isPrimary(screen.getByRole('button', { name: 'Reassign task' }))).toBe(false)
    expect(screen.getByRole('button', { name: /Cancel request/ }).className).toMatch(/border-danger/)
    expect(screen.getByText('Create purchase order is delayed')).toBeInTheDocument()
    first.unmount()

    signInAs('olga.operator')
    const stuck = [...db.runs.values()].find((r) => r.businessKey === 'ORQ-1004')! // unknown outcome
    renderApp(`/w/${WS}/runs/${stuck.id}`)
    await screen.findByText(/Recommended next step: review what happened/)
    expect(isPrimary(screen.getByRole('button', { name: 'Reconcile outcome' }))).toBe(true)
    expect(isPrimary(screen.getByRole('button', { name: 'Retry step' }))).toBe(false)
  })

  it('operator: a request that is only waiting has no recommended action and no primary button', async () => {
    signInAs('olga.operator', { demo: true })
    const waiting = [...db.runs.values()].find((r) => r.input.title === 'Ergonomic chairs (12)')!
    renderApp(`/w/${WS}/runs/${waiting.id}`)
    await screen.findByRole('heading', { name: 'Operator actions' })
    expect(screen.queryByText(/Recommended next step/)).not.toBeInTheDocument()
    expect(isPrimary(screen.getByRole('button', { name: 'Reassign task' }))).toBe(false)
  })

  it('runs: "Needs attention" is a toggle (aria-pressed, check mark) and the row has one Review recovery action', async () => {
    signInAs('olga.operator', { demo: true })
    const user = userEvent.setup()
    renderApp(`/w/${WS}/runs?scope=all`)
    const toggle = await screen.findByRole('button', { name: 'Needs attention' })
    expect(toggle).toHaveAttribute('aria-pressed', 'false')
    await user.click(toggle)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Needs attention' })).toHaveAttribute('aria-pressed', 'true'))
    expect(isPrimary(screen.getByRole('button', { name: 'Needs attention' }))).toBe(false) // not a second primary next to the row action
    const recovery = await screen.findAllByRole('link', { name: /Review recovery for ORQ-1009/ })
    expect(recovery).toHaveLength(1)
    expect(recovery[0]).toHaveAttribute('href', expect.stringMatching(/#operator-actions$/))
  })

  it('tasks: a link straight to a task opens the tab the task is in, and a claimed task moves to "Assigned to me"', async () => {
    signInAs('max.manager', { demo: true })
    const user = userEvent.setup()
    const laptop = [...db.runs.values()].find((r) => r.input.title === 'Replacement laptop for onboarding')!
    const task = laptop.tasks.find((t) => t.status === 'open')!
    const { router } = renderApp(`/w/${WS}/tasks/${task.id}`)
    await waitFor(() => expect(screen.getByRole('tab', { name: /Available to claim/ })).toHaveAttribute('aria-selected', 'true'))
    expect(router.state.location.search).toBe('?view=claimable')
    expect(await screen.findByTestId('task-actions')).toBeInTheDocument()
    // The open row is selected but is not a second primary button beside "Claim task".
    expect(isPrimary(screen.getByRole('button', { name: /^Open Replacement laptop/ }))).toBe(false)
    await user.click(screen.getByRole('button', { name: 'Claim task' }))
    await waitFor(() => expect(screen.getByRole('tab', { name: /Assigned to me/ })).toHaveAttribute('aria-selected', 'true'))
  })

  it('workflows: state is an icon plus text (Active v1 or Draft only), Start is primary only when startable', async () => {
    signInAs('dana.designer')
    renderApp(`/w/${WS}/workflows`)
    const row = (await screen.findByText('Purchase approval', { selector: 'a' })).closest('tr')!
    expect(within(row).getByText('Active v1')).toBeInTheDocument()
    expect(within(row).getByRole('link', { name: 'Edit draft of Purchase approval' }).className).not.toMatch(/(^|\s)bg-primary(\s|$)/)
    expect(isPrimary(within(row).getByRole('link', { name: 'Start Purchase approval' }))).toBe(true)
  })

  it('workflows: an unpublished workflow reads "Draft only"', async () => {
    signInAs('dana.designer', { published: false })
    renderApp(`/w/${WS}/workflows`)
    expect(await screen.findByText('Draft only')).toBeInTheDocument()
  })

  it('home: people who cannot start requests are not offered a Start section, requesters are', async () => {
    signInAs('olga.operator', { demo: true })
    const first = renderApp(`/w/${WS}`)
    await screen.findByRole('heading', { level: 1, name: /Welcome, Olga/ })
    expect(screen.queryByRole('heading', { name: 'Start a request' })).not.toBeInTheDocument()
    expect(screen.queryByText('Nothing to start yet')).not.toBeInTheDocument()
    first.unmount()
    signInAs('rhea.requester', { demo: true })
    renderApp(`/w/${WS}`)
    expect(await screen.findByRole('heading', { name: 'Start a request' })).toBeInTheDocument()
  })

  it('timeline and lists use business words, not workflow identifiers', async () => {
    signInAs('rhea.requester', { demo: true })
    const desks = [...db.runs.values()].find((r) => r.input.title === 'Standing desks for the analytics team')!
    renderApp(`/w/${WS}/runs/${desks.id}`)
    const timeline = await screen.findByTestId('timeline')
    expect(timeline).toHaveTextContent('Check purchase amount: Route is finance review')
    expect(timeline).not.toHaveTextContent('financeReview')
  })

  it('lists say what each request is about when the server sends the request title, and never fall back to a UUID', async () => {
    signInAs('rhea.requester', { demo: true })
    const home = renderApp(`/w/${WS}`)
    const recent = await screen.findByRole('region', { name: 'Your recent requests' })
    expect(await within(recent).findByText('Annual design software licences')).toBeInTheDocument()
    expect(within(recent).getByText(/ORQ-1008/)).toBeInTheDocument()
    home.unmount()
    renderApp(`/w/${WS}/requests`)
    const table = await screen.findByRole('table')
    expect(within(table).getByText('Premium coffee machine')).toBeInTheDocument()
    expect(within(table).getByRole('link', { name: 'ORQ-1007' })).toBeInTheDocument()
  })

  it('settings: where a role came from is plain words', async () => {
    signInAs('wade.admin')
    renderApp(`/w/${WS}/settings`)
    expect((await screen.findAllByText('Initial setup')).length).toBeGreaterThan(0)
    expect(screen.queryByText('Seed')).not.toBeInTheDocument()
  })
})
