import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockAiStore } from '@/mocks/aiSkills'
import { renderApp, signInAs, WS } from '@/test/utils'

describe('AI skills list and authoring (S146 part D)', () => {
  it('lists the skills with their state, and readers cannot author', async () => {
    signInAs('vic.viewer')
    renderApp(`/w/${WS}/ai-skills`)
    expect(await screen.findByRole('heading', { level: 1, name: 'AI skills' })).toBeInTheDocument()
    const table = await screen.findByRole('table', { name: 'AI skills' })
    expect(within(table).getByRole('link', { name: 'Dispute triage' })).toHaveAttribute('href', `/w/${WS}/ai-skills/dispute-triage`)
    expect(within(table).getByText('Waiting for approval')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /New skill/ })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'AI skills' })).toHaveAttribute('aria-current', 'page')
  })

  it('checks a new draft against the contract before saving, then creates version 1', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    const { router } = renderApp(`/w/${WS}/ai-skills`)
    await user.click(await screen.findByRole('button', { name: /New skill/ }))
    const editor = screen.getByRole('region', { name: 'Skill editor' })
    await user.click(within(editor).getByRole('button', { name: 'Create draft' }))
    const problems = await within(editor).findByTestId('skill-problems')
    expect(problems).toHaveTextContent('/key')
    expect(problems).toHaveTextContent('/instructions')
    expect(problems).toHaveTextContent('/labels/0/key')
    expect(within(editor).getByLabelText(/^Key/)).toHaveAttribute('aria-invalid', 'true')

    await user.type(within(editor).getByLabelText(/^Key/), 'chargeback-type')
    await user.type(within(editor).getByLabelText(/^Name/), 'Chargeback type')
    await user.type(within(editor).getByLabelText(/^Instructions/), 'Pick the chargeback type of the case.')
    const keys = within(editor).getAllByLabelText('Label key')
    const meanings = within(editor).getAllByLabelText('Meaning')
    await user.type(keys[0]!, 'goods')
    await user.type(meanings[0]!, 'Goods not received')
    await user.type(keys[1]!, 'service')
    await user.type(meanings[1]!, 'Service not provided')
    await user.click(within(editor).getByRole('button', { name: 'Create draft' }))
    await waitFor(() => expect(router.state.location.pathname).toBe(`/w/${WS}/ai-skills/chargeback-type`))
    expect(await screen.findByRole('heading', { level: 1, name: 'Chargeback type' })).toBeInTheDocument()
    expect(mockAiStore().skills.find((s) => s.key === 'chargeback-type')?.versions[0]?.status).toBe('draft')
  })

  it('offers a JSON view that is checked like the form', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    renderApp(`/w/${WS}/ai-skills?new=1`)
    const editor = await screen.findByRole('region', { name: 'Skill editor' })
    await user.click(within(editor).getByRole('button', { name: 'JSON' }))
    const area = within(editor).getByLabelText(/Skill document \(JSON\)/)
    await user.clear(area)
    await user.click(area)
    await user.paste('{"schemaVersion":"1.0","kind":"skill","key":"x-y","name":"N","mode":"summarize","instructions":"Summarize the case.","modelProfile":"economy","input":{"text":{"type":"string"}},"labels":[],"bogus":true}')
    await user.click(within(editor).getByRole('button', { name: 'Create draft' }))
    const problems = await within(editor).findByTestId('skill-problems')
    expect(problems).toHaveTextContent('The skill contract has no member bogus.')
    expect(problems).toHaveTextContent('A summarize skill has no labels.')
    await user.clear(area)
    await user.click(area)
    await user.paste('{not json')
    await user.click(within(editor).getByRole('button', { name: 'Form' }))
    expect(within(editor).getByText(/The JSON does not parse/)).toBeInTheDocument()
  })
})
