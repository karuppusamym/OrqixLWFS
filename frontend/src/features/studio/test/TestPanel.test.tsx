import { configure, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { PURCHASE_WORKFLOW_ID, USERS } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'

const EDIT = `/w/${WS}/workflows/${PURCHASE_WORKFLOW_ID}/edit`

async function openPanel(user: ReturnType<typeof userEvent.setup>) {
  await screen.findByText(/Draft revision 1/)
  await user.click(screen.getByRole('button', { name: /Test workflow/ }))
  return screen.findByTestId('test-panel')
}

async function setAmount(user: ReturnType<typeof userEvent.setup>, amount: string) {
  const field = await screen.findByLabelText(/Amount/)
  await user.clear(field)
  await user.type(field, amount)
}

// The whole app is rendered here, so a loaded machine needs more than the default one second to find things.
configure({ asyncUtilTimeout: 15000 })

describe('Test this workflow (panel)', () => {
  beforeEach(() => window.localStorage.clear())

  it('shares valid expected cases and records an exact-draft publication result', async () => {
    let saved: unknown
    let tested: unknown
    const path = '*/api/v1/workspaces/:ws/workflows/:wf/test-suite'
    server.use(
      http.get(path, () => HttpResponse.json({ version: 2, cases: [] })),
      http.put(path, async ({ request }) => { saved = await request.json(); return HttpResponse.json({ version: 3, cases: [] }) }),
      http.post(`${path}/run`, async ({ request }) => { tested = await request.json(); return HttpResponse.json({ suiteVersion: 3, revision: 1, passed: true, results: [{ id: 'saved', passed: true, status: 'finished' }] }) }),
    )
    signInAs('dana.designer')
    const user = userEvent.setup()
    renderApp(EDIT)
    const panel = await openPanel(user)
    await user.click(within(panel).getByTestId('run-test'))
    await within(panel).findByTestId('test-outcome')
    await user.type(within(panel).getByLabelText(/Keep this case/), 'Shared purchase')
    await user.click(within(panel).getByRole('button', { name: 'Save case' }))
    await user.click(within(panel).getByRole('button', { name: 'Share and test for publication' }))
    await waitFor(() => expect(within(panel).getByTestId('case-notice')).toHaveTextContent('This exact draft satisfies'))
    expect(saved).toMatchObject({ expectedVersion: 2, cases: [{ name: 'Shared purchase', expect: 'Fulfilled', input: { amount: 1000 } }] })
    expect(tested).toEqual({ revision: 1, suiteVersion: 3 })
  }, 90000)

  it('runs the sample request, explains each step in business words and says what was covered', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    renderApp(EDIT)
    const panel = await openPanel(user)
    expect(within(panel).getByText(/not the workflow engine/i, { selector: 'p' })).toBeInTheDocument()
    await setAmount(user, '5000')
    await user.click(within(panel).getByTestId('run-test'))
    expect(await within(panel).findByTestId('test-outcome', undefined, { timeout: 20000 })).toHaveTextContent('Fulfilled')
    expect(within(panel).getByTestId('routes-tested')).toHaveTextContent('3 of 6 routes tested')
    // Step through to the decision: the sentence says why Finance is involved.
    const user2 = userEvent.setup()
    screen.getByRole('button', { name: /Next step/ }).focus()
    await user2.keyboard('{ArrowRight}{ArrowRight}')
    expect(screen.getByTestId('current-step')).toHaveTextContent('rule 1 applies because Amount (USD 5,000) is at least USD 5,000')
    expect(screen.getByTestId('playback-live')).toHaveTextContent(/Step 3 of/)
    await user.click(within(panel).getByTestId('try-another'))
    expect(await within(panel).findByTestId('run-test')).toBeInTheDocument()
  }, 90000)

  it('shows the route of a rejection and of a failing system, and refuses an invalid sample request', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    renderApp(EDIT)
    const panel = await openPanel(user)
    await user.selectOptions(within(panel).getByLabelText(/^Review request/), 'reject')
    await user.click(within(panel).getByTestId('run-test'))
    expect(await within(panel).findByTestId('test-outcome', undefined, { timeout: 20000 })).toHaveTextContent('Rejected')
    await user.click(within(panel).getByTestId('try-another'))
    await user.selectOptions(await within(panel).findByLabelText(/^Review request/), 'approve')
    await user.selectOptions(within(panel).getByLabelText(/^Create purchase order/), 'unavailable')
    await user.click(within(panel).getByTestId('run-test'))
    expect(await within(panel).findByTestId('test-outcome', undefined, { timeout: 20000 })).toHaveTextContent('Needs attention')
    expect(within(panel).getByTestId('test-outcome')).toHaveTextContent(/Create purchase order/)
    await user.click(within(panel).getByTestId('try-another'))
    await setAmount(user, '0')
    await user.click(await within(panel).findByTestId('run-test'))
    await waitFor(() => expect(screen.getByLabelText(/Amount/)).toHaveAttribute('aria-invalid', 'true'))
    expect(within(panel).queryByTestId('test-outcome')).not.toBeInTheDocument()
  }, 90000)

  it('is a Test tab beside the other views; the header button opens it', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    renderApp(EDIT)
    await screen.findByText(/Draft revision 1/)
    const tabs = screen.getByRole('tablist', { name: 'Studio views' })
    expect(within(tabs).getAllByRole('tab').map((t) => t.textContent)).toEqual(['Canvas', 'Steps', 'Form', 'Lanes', 'Source', 'Test'])
    await user.click(screen.getByRole('button', { name: /Test workflow/ }))
    expect(within(tabs).getByRole('tab', { name: 'Test' })).toHaveAttribute('aria-selected', 'true')
    const panel = await screen.findByTestId('test-panel')
    // Leaving the tab and coming back keeps what was typed.
    await setAmount(user, '4321')
    await user.click(within(tabs).getByRole('tab', { name: 'Steps' }))
    await user.click(within(tabs).getByRole('tab', { name: 'Test' }))
    expect(within(panel).getByLabelText(/Amount/)).toHaveValue('4321')
  }, 90000)

  it('lets a reader run and look, without saving or sharing', async () => {
    signInAs('vic.viewer')
    const user = userEvent.setup()
    renderApp(EDIT)
    await screen.findByText(/Draft revision 1/)
    await user.click(screen.getByRole('tab', { name: 'Test' }))
    const panel = await screen.findByTestId('test-panel')
    expect(within(panel).getByTestId('test-read-only')).toBeInTheDocument()
    await user.click(within(panel).getByTestId('run-test'))
    expect(await within(panel).findByTestId('test-outcome', undefined, { timeout: 20000 })).toHaveTextContent('Fulfilled')
    expect(within(panel).queryByLabelText(/Keep this case/)).not.toBeInTheDocument()
    expect(within(panel).queryByRole('button', { name: 'Share and test for publication' })).not.toBeInTheDocument()
    expect(within(panel).queryByRole('button', { name: 'Import' })).not.toBeInTheDocument()
  }, 90000)

  it('mocks the answer of a call: an HTTP status stops for attention, a body is checked against the response schema', async () => {
    let sent: Record<string, unknown> | undefined
    server.use(http.post('*/api/v1/workspaces/:ws/workflows/:wf/simulate', async ({ request }) => {
      sent = (await request.clone().json()) as Record<string, unknown>
      return undefined
    }))
    signInAs('dana.designer')
    const user = userEvent.setup()
    renderApp(EDIT)
    const panel = await openPanel(user)
    const call = within(panel).getByTestId('mock-call-procure')
    await user.click(within(call).getByText(/Mock the whole answer/))
    await user.type(within(call).getByLabelText(/HTTP status/), '503')
    await user.click(within(panel).getByTestId('run-test'))
    expect(await within(panel).findByTestId('test-outcome', undefined, { timeout: 20000 })).toHaveTextContent('Needs attention')
    expect(sent?.scenario).toEqual({ calls: { procure: { status: 503 } } })
    await user.click(within(panel).getByTestId('try-another'))
    const again = await within(panel).findByTestId('mock-call-procure')
    await user.clear(within(again).getByLabelText(/HTTP status/))
    await user.type(within(again).getByLabelText(/HTTP status/), '201')
    const body = within(again).getByLabelText(/Answer body/)
    await user.click(body)
    await user.paste('{"status":"PENDING"}')
    expect(within(again).getByText(/Does not match the response schema/)).toBeInTheDocument()
    expect(within(again).getByText('/id is required but missing')).toBeInTheDocument()
    await user.click(within(panel).getByTestId('run-test'))
    await within(panel).findByTestId('test-outcome', undefined, { timeout: 20000 })
    const outcome = within(panel).getByTestId('test-outcome')
    expect(outcome).toHaveTextContent('Needs attention')
    await user.click(within(panel).getByRole('button', { name: /"Create purchase order" calls/ }))
    expect(within(panel).getByTestId('mocked-answer')).toHaveTextContent('HTTP 201')
  }, 90000)

  it('checks values, the path and the rule of a saved case in one run of all cases, with a readable difference', async () => {
    let batch: Record<string, unknown> | undefined
    server.use(http.post('*/api/v1/workspaces/:ws/workflows/:wf/simulate-cases', async ({ request }) => {
      batch = (await request.clone().json()) as Record<string, unknown>
      return undefined
    }))
    signInAs('dana.designer')
    const user = userEvent.setup()
    renderApp(EDIT)
    const panel = await openPanel(user)
    await user.click(within(panel).getByTestId('run-test'))
    await within(panel).findByTestId('test-outcome', undefined, { timeout: 20000 })
    await user.type(within(panel).getByLabelText(/Keep this case/), 'Small purchase')
    await user.click(within(panel).getByRole('button', { name: 'Save case' }))
    await user.click(within(panel).getByText(/^Checks \(1\)/))
    const checks = within(panel).getByTestId(/-assertions$/)
    await user.click(within(checks).getByRole('button', { name: 'Expect the path of the last run' }))
    await user.click(within(checks).getByRole('button', { name: 'Check a decision' }))
    await user.selectOptions(within(checks).getByLabelText('Rule that fires'), '1')
    await user.click(within(panel).getByRole('button', { name: 'Run all saved cases' }))
    await waitFor(() => expect(within(panel).getByTestId('case-summary')).toHaveTextContent('0 of 1 passed'), { timeout: 20000 })
    expect(batch?.cases).toMatchObject([{ assertions: { outcome: 'Fulfilled', rules: [{ stepId: 'amountPolicy', rule: 1 }], path: { match: 'exact' } } }])
    const failed = within(panel).getAllByTestId('assertion-failed')
    expect(failed).toHaveLength(1)
    expect(failed[0]).toHaveTextContent(/Decision "Check purchase amount".*Failed.*rule 1.*otherwise/)
    await user.selectOptions(within(checks).getByLabelText('Rule that fires'), 'otherwise')
    await user.click(within(panel).getByRole('button', { name: 'Run all saved cases' }))
    await waitFor(() => expect(within(panel).getByTestId('case-summary')).toHaveTextContent('1 of 1 passed'), { timeout: 20000 })
  }, 90000)

  it('tests a decision table with a grid pasted from CSV', async () => {
    signInAs('vic.viewer')
    const user = userEvent.setup()
    renderApp(EDIT)
    await screen.findByText(/Draft revision 1/)
    await user.click(screen.getByRole('tab', { name: 'Test' }))
    const grid = await screen.findByTestId('decision-grid')
    await user.click(within(grid).getByText(/Paste cases from a spreadsheet/))
    await user.click(within(grid).getByLabelText('CSV text'))
    await user.paste('in:number:/input/amount,out:text:route\n100,standard\n5000,financeReview\n6000,standard')
    await user.click(within(grid).getByRole('button', { name: 'Use these cases' }))
    await user.click(within(grid).getByTestId('run-grid'))
    await waitFor(() => expect(within(grid).getByTestId('grid-summary')).toHaveTextContent('2 of 3 table cases passed'), { timeout: 20000 })
    expect(within(grid).getByTestId('grid-result-2')).toHaveTextContent('route: expected standard, got financeReview')
    await user.clear(within(grid).getByLabelText('CSV text'))
    await user.paste('in:number:/input/other,out:text:route\n1,x')
    await user.click(within(grid).getByRole('button', { name: 'Use these cases' }))
    expect(within(grid).getByRole('alert')).toHaveTextContent('this table has no input /input/other')
  }, 90000)

  it('keeps named cases in this browser, runs them all against their expected outcome and exports them', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    renderApp(EDIT)
    const panel = await openPanel(user)
    await user.click(within(panel).getByTestId('run-test'))
    await within(panel).findByTestId('test-outcome', undefined, { timeout: 20000 })
    await user.type(within(panel).getByLabelText(/Keep this case/), 'Small purchase')
    await user.click(within(panel).getByRole('button', { name: 'Save case' }))
    expect(within(panel).getByTestId('case-notice')).toHaveTextContent('Saved')
    expect(JSON.parse(window.localStorage.getItem(`orqix.testCases.v2.${USERS.find((u) => u.username === 'dana.designer')!.id}.${WS}.${PURCHASE_WORKFLOW_ID}`) ?? '[]')[0]).toMatchObject({ name: 'Small purchase', expect: 'Fulfilled' })
    await user.selectOptions(within(panel).getByLabelText('Expect'), 'Rejected')
    await user.click(within(panel).getByRole('button', { name: 'Run all saved cases' }))
    await waitFor(() => expect(within(panel).getByTestId('case-summary')).toHaveTextContent('0 of 1 passed'), { timeout: 20000 })
    expect(within(panel).getByTestId('case-result-Small purchase')).toHaveTextContent(/Failed: Expected Rejected but it ended with Fulfilled/)
    await user.selectOptions(within(panel).getByLabelText('Expect'), 'Fulfilled')
    await user.click(within(panel).getByRole('button', { name: 'Run all saved cases' }))
    await waitFor(() => expect(within(panel).getByTestId('case-summary')).toHaveTextContent('1 of 1 passed'), { timeout: 20000 })
  }, 90000)

  it('generates a case for every path to keep or delete, and the kept cases all pass with their path checks', async () => {
    const simulated: Array<Record<string, unknown>> = []
    server.use(http.post('*/api/v1/workspaces/:ws/workflows/:wf/simulate', async ({ request }) => {
      simulated.push((await request.clone().json()) as Record<string, unknown>)
      return undefined
    }))
    signInAs('dana.designer')
    const user = userEvent.setup()
    renderApp(EDIT)
    const panel = await openPanel(user)
    await user.click(within(panel).getByTestId('generate-cases'))
    expect(await within(panel).findByTestId('generated-summary', undefined, { timeout: 30000 })).toHaveTextContent('4 cases to review. Together they take 6 of 6 routes and match 2 of 2 decision rules.')
    const proposed = within(panel).getAllByTestId('generated-case')
    expect(proposed.map((li) => li.querySelector('span')?.textContent)).toEqual(['Amount (USD) is 5000', 'Amount (USD) is 4999.99', 'Review request rejects (Amount (USD) is 5000)', 'Finance review rejects (Amount (USD) is 5000)'])
    expect(proposed[0]).toHaveTextContent('Expect: Fulfilled')
    expect(proposed[0]).toHaveTextContent('Steps: Request submitted → Review request → Check purchase amount → Finance review needed? → Finance review → Create purchase order → Tell requester it is ordered → Fulfilled')
    expect(proposed[3]).toHaveTextContent('Expect: Rejected')
    // Every dry run is the draft on screen; the boundary answers come from the decision rule "at least USD 5,000".
    expect(simulated.map((b) => (b.input as Record<string, unknown>).amount)).toEqual(expect.arrayContaining([5000, 4999.99]))
    expect(simulated.every((b) => b.revision !== undefined)).toBe(true)
    // Nothing is kept until the designer says so.
    expect(within(panel).getByText(/Saved test cases \(0 of 20\)/)).toBeInTheDocument()

    await user.click(within(panel).getByRole('button', { name: 'Delete the generated case Amount (USD) is 4999.99' }))
    await user.click(within(panel).getByRole('button', { name: 'Keep the generated case Amount (USD) is 5000' }))
    expect(within(panel).getByTestId('case-notice')).toHaveTextContent('Kept “Amount (USD) is 5000”.')
    expect(within(panel).getAllByTestId('generated-case')).toHaveLength(2)
    await user.click(within(panel).getByRole('button', { name: 'Keep all 2' }))
    expect(within(panel).queryByTestId('generated-cases')).not.toBeInTheDocument()
    expect(within(panel).getByText(/Saved test cases \(3 of 20\)/)).toBeInTheDocument()
    const stored = JSON.parse(window.localStorage.getItem(`orqix.testCases.v2.${USERS.find((u) => u.username === 'dana.designer')!.id}.${WS}.${PURCHASE_WORKFLOW_ID}`) ?? '[]')
    expect(stored[0]).toMatchObject({ version: 3, name: 'Amount (USD) is 5000', expect: 'Fulfilled', values: { amount: '5000' }, assertions: { path: { match: 'exact', steps: ['start', 'manager', 'amountPolicy', 'route', 'finance', 'procure', 'notify', 'fulfilled'] } } })
    expect(stored[1]).toMatchObject({ expect: 'Rejected', settings: { approvals: { manager: { outcome: 'reject' } } } })

    await user.click(within(panel).getByRole('button', { name: 'Run all saved cases' }))
    await waitFor(() => expect(within(panel).getByTestId('case-summary')).toHaveTextContent('3 of 3 passed'), { timeout: 20000 })
  }, 90000)
})
