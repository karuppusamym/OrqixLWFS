import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import { aiMock } from '@/features/ai/mocks'
import { db } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'

async function open() {
  signInAs('dana.designer')
  const user = userEvent.setup()
  const { router } = renderApp(`/w/${WS}/workflows`)
  await user.click(await screen.findByRole('button', { name: /New workflow/ }))
  const dialog = await screen.findByRole('dialog', { name: 'New workflow' })
  return { user, router, dialog }
}

function draftOf(name: string) {
  const workflow = [...db.workflows.values()].find((w) => w.name === name)
  expect(workflow, `workflow ${name}`).toBeDefined()
  return workflow!.drafts[workflow!.drafts.length - 1]!.definition
}

describe('New workflow: choose how to start', () => {
  afterEach(() => { aiMock.enabled = true })

  it('offers the deterministic ways first and AI as an option, each with a plain explanation', async () => {
    const { dialog } = await open()
    const names = within(dialog).getAllByRole('button').map((b) => b.getAttribute('aria-labelledby') ? b.textContent : null).filter(Boolean)
    expect(names).toHaveLength(4)
    expect(within(dialog).getByRole('button', { name: 'Start from a template' })).toHaveAccessibleDescription(/Ready-made workflows/)
    expect(within(dialog).getByRole('button', { name: 'Answer a few questions' })).toHaveAccessibleDescription(/No AI involved/)
    expect(within(dialog).getByRole('button', { name: 'Describe it to AI' })).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Start empty' })).toHaveAccessibleDescription(/open Ask AI in the studio/)
    expect(within(dialog).getByTestId('new-workflow-ai-note')).toHaveTextContent(/AI is optional/)
    expect(within(dialog).getByRole('button', { name: 'Use the governed questionnaire' })).toBeInTheDocument()
  })

  it('without AI, the AI choice is gone and the dialog says why', async () => {
    aiMock.enabled = false
    const { dialog } = await open()
    await within(dialog).findByRole('button', { name: 'Start empty' })
    await waitFor(() => expect(within(dialog).getByTestId('new-workflow-ai-note')).toHaveTextContent(/AI drafting is turned off/))
    expect(within(dialog).queryByRole('button', { name: 'Describe it to AI' })).not.toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Start empty' })).not.toHaveAccessibleDescription(/Ask AI/)
  })

  it('template: pick, name and create; Back returns to the choices', async () => {
    const { user, router, dialog } = await open()
    await user.click(within(dialog).getByRole('button', { name: 'Start from a template' }))
    let details = await screen.findByRole('dialog', { name: 'Start from a template' })
    await user.click(within(details).getByRole('button', { name: /Other ways to start/ }))
    const again = await screen.findByRole('dialog', { name: 'New workflow' })
    await user.click(within(again).getByRole('button', { name: 'Start from a template' }))
    details = await screen.findByRole('dialog', { name: 'Start from a template' })
    await user.click(await within(details).findByLabelText(/New hire onboarding/))
    await user.type(within(details).getByLabelText(/^Name/), 'Onboarding copy')
    expect(within(details).getByLabelText(/^Key/)).toHaveValue('onboarding-copy')
    await user.click(within(details).getByRole('button', { name: 'Create workflow' }))
    await waitFor(() => expect(router.state.location.pathname).toMatch(/\/workflows\/[^/]+\/edit$/))
    expect(draftOf('Onboarding copy').nodes.length).toBeGreaterThan(2)
  })

  it('empty: only a name is needed', async () => {
    const { user, router, dialog } = await open()
    await user.click(within(dialog).getByRole('button', { name: 'Start empty' }))
    const details = await screen.findByRole('dialog', { name: 'Start empty' })
    expect(within(details).queryByRole('radio')).not.toBeInTheDocument()
    await user.click(within(details).getByRole('button', { name: 'Create workflow' }))
    expect(within(details).getByText('Give the workflow a name.')).toBeInTheDocument()
    await user.type(within(details).getByLabelText(/^Name/), 'Blank start')
    await user.click(within(details).getByRole('button', { name: 'Create workflow' }))
    await waitFor(() => expect(router.state.location.pathname).toMatch(/\/workflows\/[^/]+\/edit$/))
    expect(draftOf('Blank start').nodes.map((n) => n.type)).toEqual(['start', 'end'])
  })

  it('a few questions: name, what to fill in, who approves, then a draft opens in the studio', async () => {
    const { user, router, dialog } = await open()
    await user.click(within(dialog).getByRole('button', { name: 'Answer a few questions' }))
    const guide = await screen.findByRole('dialog', { name: 'Answer a few questions' })

    await user.click(within(guide).getByRole('button', { name: 'Next' }))
    expect(within(guide).getByRole('alert')).toHaveTextContent('Give the workflow a name.')
    await user.type(within(guide).getByLabelText(/What is the workflow called/), 'Laptop request')
    await user.click(within(guide).getByRole('button', { name: 'Next' }))

    expect(within(guide).getByLabelText('Question 1')).toHaveValue('Request title')
    await user.click(within(guide).getByRole('button', { name: /Add a question/ }))
    await user.type(within(guide).getByLabelText('Question 2'), 'Amount in USD')
    await user.selectOptions(within(guide).getAllByLabelText('Answer type')[1]!, 'number')
    await user.click(within(guide).getByRole('button', { name: 'Next' }))

    await user.click(await within(guide).findByRole('button', { name: /Add an approval/ }))
    await user.click(within(guide).getByRole('button', { name: 'Next' }))
    expect(within(guide).getByRole('alert')).toHaveTextContent('Every approval needs a name.')
    await user.type(within(guide).getByLabelText('Approval 1'), 'Finance review')
    await user.selectOptions(within(guide).getByLabelText('Who approves?'), 'finance-approvers')
    await user.selectOptions(within(guide).getByLabelText('Needed'), '1')
    await user.clear(within(guide).getByLabelText('At least'))
    await user.type(within(guide).getByLabelText('At least'), '5000')
    await user.click(within(guide).getByRole('button', { name: 'Next' }))

    const summary = within(guide).getByTestId('quick-summary')
    expect(summary).toHaveTextContent('Amount in USD (number, required)')
    expect(summary).toHaveTextContent('Finance review: Finance approvers (only when Amount in USD is at least 5000)')
    expect(summary).toHaveTextContent('Nobody can start it until you publish')
    await user.click(within(guide).getByRole('button', { name: 'Create draft' }))

    await waitFor(() => expect(router.state.location.pathname).toMatch(/\/workflows\/[^/]+\/edit$/))
    const definition = draftOf('Laptop request')
    expect(definition.nodes.map((n) => n.id)).toEqual(['start', 'check1', 'approval1', 'approved', 'rejected'])
    expect(Object.keys(definition.inputSchema.properties)).toEqual(['requestTitle', 'amountInUsd'])
  })

  it('a few questions: Back on the first step returns to the choices', async () => {
    const { user, dialog } = await open()
    await user.click(within(dialog).getByRole('button', { name: 'Answer a few questions' }))
    const guide = await screen.findByRole('dialog', { name: 'Answer a few questions' })
    await user.click(within(guide).getByRole('button', { name: /Other ways to start/ }))
    expect(await screen.findByRole('dialog', { name: 'New workflow' })).toBeInTheDocument()
  })
})
