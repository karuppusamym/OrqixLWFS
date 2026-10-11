import { configure, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { PURCHASE_WORKFLOW_ID, USERS } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'

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
})
