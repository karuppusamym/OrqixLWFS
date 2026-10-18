import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { makeRelease } from '@/mocks/seed'
import { db, PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import { server } from '@/test/server'
import { renderApp, signInAs, WS } from '@/test/utils'

const START = `/w/${WS}/start/${PURCHASE_WORKFLOW_ID}`

it('uses the submit label copied into the published request form', async () => {
  signInAs('rhea.requester')
  const workflow = db.workflows.get(PURCHASE_WORKFLOW_ID)!
  workflow.releases[0]!.definition.inputUi = { ...workflow.releases[0]!.definition.inputUi, submitLabel: 'Send for review' }
  const user = userEvent.setup()
  renderApp(START)
  await fill(user, { title: 'Shared view', purpose: 'Pinned form', costCenter: 'OPS', amount: '100' })
  await user.click(screen.getByRole('button', { name: 'Review request' }))
  expect(await screen.findByRole('button', { name: 'Send for review' })).toBeInTheDocument()
})

async function fill(user: ReturnType<typeof userEvent.setup>, values: { title?: string; purpose?: string; costCenter?: string; amount?: string }) {
  if (values.title !== undefined) await user.type(await screen.findByLabelText(/What are you buying\?/), values.title)
  if (values.purpose !== undefined) await user.type(screen.getByLabelText(/Business purpose/), values.purpose)
  if (values.costCenter !== undefined) await user.selectOptions(screen.getByLabelText(/Cost center/), values.costCenter)
  if (values.amount !== undefined) await user.type(screen.getByLabelText(/Amount \(USD\)/), values.amount)
}

const problem = (status: number, code: string, extra: Record<string, unknown> = {}) =>
  HttpResponse.json({ type: 'https://orqix.example/problems/x', title: 'x', status, code, correlationId: 'c0a8017e-6f1b-4b7e-9d0c-2a4f5e0d9b11', ...extra }, { status, headers: { 'Content-Type': 'application/problem+json' } })

describe('request form (T-UI-01 at component level, B06.2)', () => {
  it('shows four labelled fields in order, required marked in text, money as USD', async () => {
    signInAs('rhea.requester')
    renderApp(START)
    const title = await screen.findByLabelText(/What are you buying\?/)
    const fields = screen.getAllByRole('textbox').concat(screen.getAllByRole('combobox'))
    expect(fields.length).toBeGreaterThanOrEqual(4)
    const labels = ['What are you buying?', 'Business purpose', 'Cost center', 'Amount (USD)']
    const order = labels.map((l) => screen.getByLabelText(new RegExp(l.replace(/[()?]/g, '\\$&'))))
    expect(order[0]).toBe(title)
    for (let i = 1; i < order.length; i++) expect(order[i - 1]!.compareDocumentPosition(order[i]!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.getAllByText('(required)')).toHaveLength(4)
    expect(screen.getByText('USD')).toBeInTheDocument() // prefix of the money input
    expect(screen.getByLabelText(/Business purpose/).tagName).toBe('TEXTAREA')
    expect(screen.getByLabelText(/Cost center/).tagName).toBe('SELECT')
    // Help text is linked with aria-describedby.
    expect(screen.getByLabelText(/Amount \(USD\)/)).toHaveAccessibleDescription(/Total including tax and shipping/)
  })

  it('reports each problem on its own field, with an error summary, and focuses the first invalid field', async () => {
    signInAs('rhea.requester')
    const user = userEvent.setup()
    renderApp(START)
    await fill(user, { title: 'Monitor', purpose: 'Second screen', costCenter: 'IT', amount: '1800.071' })
    await user.click(screen.getByRole('button', { name: 'Review request' }))
    const amount = screen.getByLabelText(/Amount \(USD\)/)
    expect(amount).toHaveAttribute('aria-invalid', 'true')
    expect(amount).toHaveAccessibleDescription(/two decimal places/)
    expect(amount).toHaveFocus()
    const summary = screen.getByTestId('error-summary')
    expect(summary).toHaveTextContent('There is 1 problem to fix')
    expect(within(summary).getByRole('link', { name: /Amount \(USD\)/ })).toBeInTheDocument()
    // Nothing else is flagged.
    expect(screen.getByLabelText(/What are you buying\?/)).not.toHaveAttribute('aria-invalid')
  })

  it('missing and zero values read in plain words', async () => {
    signInAs('rhea.requester')
    const user = userEvent.setup()
    renderApp(START)
    await fill(user, { title: 'Free item', costCenter: 'HR', amount: '0' })
    await user.click(screen.getByRole('button', { name: 'Review request' }))
    expect(screen.getByLabelText(/Business purpose/)).toHaveAccessibleDescription(/required/)
    expect(screen.getByLabelText(/Amount \(USD\)/)).toHaveAccessibleDescription(/at least \$0\.01/)
    expect(screen.getByTestId('error-summary')).toHaveTextContent('There are 2 problems to fix')
  })

  it('confirms with a summary that reuses the typed values, and going back loses nothing', async () => {
    signInAs('rhea.requester')
    const user = userEvent.setup()
    renderApp(START)
    await fill(user, { title: 'Laptop', purpose: 'Replacement', costCenter: 'OPS', amount: '1800.07' })
    await user.click(screen.getByRole('button', { name: 'Review request' }))
    const summary = await screen.findByLabelText('Request summary')
    expect(summary).toHaveTextContent('Laptop')
    expect(summary).toHaveTextContent('$1,800.07')
    expect(screen.getByRole('heading', { level: 1, name: 'Check your request' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Back to edit' }))
    expect(await screen.findByLabelText(/What are you buying\?/)).toHaveValue('Laptop')
    expect(screen.getByLabelText(/Amount \(USD\)/)).toHaveValue('1800.07')
  })

  it('submits, then shows the request id, the current step and its owner, and a link to the request', async () => {
    signInAs('rhea.requester')
    const user = userEvent.setup()
    renderApp(START)
    await fill(user, { title: 'Laptop', purpose: 'Replacement', costCenter: 'OPS', amount: '1800' })
    await user.click(screen.getByRole('button', { name: 'Review request' }))
    await user.click(await screen.findByRole('button', { name: 'Submit request' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Request submitted' })).toBeInTheDocument()
    expect(screen.getByTestId('request-id')).toHaveTextContent(/^ORQ-\d{4,}$/)
    expect(screen.getByText(/Waiting for Managers: Review request/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View your request' })).toHaveAttribute('href', expect.stringMatching(/\/runs\//))
    const run = [...db.runs.values()].find((r) => r.input.title === 'Laptop')
    expect(run?.input.amount).toBe(1800) // typed text became a JSON number only here
  })

  it('shows server field errors on the same fields and keeps what was entered', async () => {
    signInAs('rhea.requester')
    const user = userEvent.setup()
    server.use(http.post('*/api/v1/workspaces/:ws/runs', () => problem(422, 'VALIDATION_FAILED', { fieldErrors: [{ pointer: '/input/costCenter', code: 'enum', message: 'x' }] }), { once: true }))
    renderApp(START)
    await fill(user, { title: 'Laptop', purpose: 'Replacement', costCenter: 'OPS', amount: '1800' })
    await user.click(screen.getByRole('button', { name: 'Review request' }))
    await user.click(await screen.findByRole('button', { name: 'Submit request' }))
    const cost = await screen.findByLabelText(/Cost center/)
    await waitFor(() => expect(cost).toHaveAttribute('aria-invalid', 'true'))
    expect(screen.getByLabelText(/What are you buying\?/)).toHaveValue('Laptop')
    expect(screen.getByLabelText(/Amount \(USD\)/)).toHaveValue('1800')
  })

  it('reloads the form after RELEASE_NOT_ACTIVE and keeps values whose keys still exist', async () => {
    signInAs('rhea.requester')
    const user = userEvent.setup()
    renderApp(START)
    await fill(user, { title: 'Laptop', purpose: 'Replacement', costCenter: 'OPS', amount: '1800' })
    await user.click(screen.getByRole('button', { name: 'Review request' }))
    await screen.findByRole('button', { name: 'Submit request' })

    // A new version becomes active: it drops "purpose" and adds a question.
    const wf = db.workflows.get(PURCHASE_WORKFLOW_ID)!
    const next = structuredClone(wf.releases[0]!.definition)
    delete next.inputSchema.properties.purpose
    next.inputSchema.required = next.inputSchema.required!.filter((k) => k !== 'purpose')
    next.inputUi!.order = next.inputUi!.order!.filter((k) => k !== 'purpose')
    next.inputSchema.properties.reference = { type: 'string', title: 'Reference', maxLength: 50 }
    const release = makeRelease(wf.id, 2, 1, next, wf.ownerId)
    wf.releases.push(release)
    wf.activeReleaseId = release.id
    server.use(http.post('*/api/v1/workspaces/:ws/runs', () => problem(409, 'RELEASE_NOT_ACTIVE'), { once: true }))

    await user.click(screen.getByRole('button', { name: 'Submit request' }))
    expect(await screen.findByText('This form was updated')).toBeInTheDocument()
    expect(await screen.findByLabelText(/Reference/)).toHaveValue('')
    expect(screen.queryByLabelText(/Business purpose/)).not.toBeInTheDocument()
    expect(screen.getByLabelText(/What are you buying\?/)).toHaveValue('Laptop')
    expect(screen.getByLabelText(/Amount \(USD\)/)).toHaveValue('1800')
    expect(screen.getByText('Version 2')).toBeInTheDocument()
  })

  it('offers a retry after DEPENDENCY_UNAVAILABLE and retries with the same idempotency key', async () => {
    signInAs('rhea.requester')
    const user = userEvent.setup()
    const keys: Array<string | null> = []
    server.use(
      http.post('*/api/v1/workspaces/:ws/runs', ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'))
        return problem(503, 'DEPENDENCY_UNAVAILABLE', {}) // first attempt only; the default handler serves the retry
      }, { once: true }),
    )
    renderApp(START)
    await fill(user, { title: 'Laptop', purpose: 'Replacement', costCenter: 'OPS', amount: '1800' })
    await user.click(screen.getByRole('button', { name: 'Review request' }))
    await user.click(await screen.findByRole('button', { name: 'Submit request' }))
    expect(await screen.findByText('Service temporarily unavailable. Nothing was submitted.')).toBeInTheDocument()
    expect(screen.getByLabelText('Request summary')).toHaveTextContent('Laptop') // entered data is kept
    // The retry reuses the key; the server answers with the original result.
    server.use(
      http.post('*/api/v1/workspaces/:ws/runs', ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'))
        return undefined
      }),
    )
    await user.click(screen.getByRole('button', { name: /Try again/ }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Request submitted' })).toBeInTheDocument()
    expect(keys).toHaveLength(2)
    expect(keys[0]).toBeTruthy()
    expect(keys[1]).toBe(keys[0])
  })

  it('answers "not published yet" instead of an empty form', async () => {
    signInAs('rhea.requester', { published: false })
    renderApp(START)
    expect(await screen.findByText("This workflow isn't published yet.")).toBeInTheDocument()
  })
})
