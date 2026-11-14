import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockAiStore } from '@/mocks/aiSkills'
import { renderApp, signInAs, WS } from '@/test/utils'

describe('workspace AI policy (S146 part D)', () => {
  it('administrators save with the version they loaded and reload after a concurrent change', async () => {
    signInAs('wade.admin')
    const user = userEvent.setup()
    renderApp(`/w/${WS}/ai-policy`)
    expect(await screen.findByRole('heading', { level: 1, name: 'AI policy' })).toBeInTheDocument()
    const endpoints = await screen.findByRole('table', { name: 'Model endpoints and their residency tags' })
    expect(within(within(endpoints).getByText('bedrock-eu').closest('tr')!).getByText('eu')).toBeInTheDocument()

    await user.type(screen.getByLabelText(/Residency tags/), 'eu')
    await user.click(screen.getByRole('button', { name: 'Save policy' }))
    expect(await screen.findByText('AI policy saved.')).toBeInTheDocument()
    expect(mockAiStore().policy).toMatchObject({ residency: ['eu'], version: 1 })
    expect(within(screen.getByRole('table', { name: 'Model endpoints and their residency tags' }).querySelector('tbody')!).getAllByText('No')).toHaveLength(2)

    // Someone else saves meanwhile.
    mockAiStore().policy = { ...mockAiStore().policy, version: 2, retentionDays: 90 }
    await user.click(screen.getByLabelText(/Allow automatic decisions with a review sample/))
    await user.click(screen.getByRole('button', { name: 'Save policy' }))
    expect(await screen.findByTestId('ai-problem')).toHaveTextContent('Someone else changed this since you loaded it.')
    await user.click(screen.getByRole('button', { name: 'Reload the policy' }))
    await waitFor(() => expect(screen.getByLabelText(/Keep decision records/)).toHaveValue('90'))
    expect(screen.getByText(/Policy version 2/)).toBeInTheDocument()
  })

  it('checks the policy before saving (regulated workspaces never send sensitive fields)', async () => {
    signInAs('wade.admin')
    const user = userEvent.setup()
    renderApp(`/w/${WS}/ai-policy`)
    await user.click(await screen.findByLabelText('Regulated workspace'))
    await user.selectOptions(screen.getByLabelText('sensitive'), 'send')
    await user.type(screen.getByLabelText(/Low-risk labels/), '{enter}bad label')
    await user.click(screen.getByRole('button', { name: 'Save policy' }))
    expect(await screen.findByText('A regulated workspace never sends sensitive fields.')).toBeInTheDocument()
    expect(screen.getByText(/Write each label as skillKey:label/)).toBeInTheDocument()
    expect(mockAiStore().policy.version).toBe(0)
  })

  it('is read-only for people who cannot manage access', async () => {
    signInAs('dana.designer')
    renderApp(`/w/${WS}/ai-policy`)
    expect(await screen.findByTestId('policy-read-only')).toBeInTheDocument()
    expect(screen.getByLabelText('Regulated workspace')).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Save policy' })).not.toBeInTheDocument()
  })
})

describe('AI review queue and evaluation candidates (S146 part D)', () => {
  it('operators see sampled decisions but not the candidates', async () => {
    signInAs('olga.operator')
    renderApp(`/w/${WS}/ai-review`)
    const queue = await screen.findByRole('table', { name: 'Sampled automatic AI decisions not reviewed yet' })
    expect(within(queue).getByRole('link', { name: 'Open request' })).toHaveAttribute('href', `/w/${WS}/runs/00000000-0000-7000-8000-00000000c101`)
    expect(within(queue).getByText('93 %')).toBeInTheDocument()
    expect(screen.getByTestId('candidates-hint')).toBeInTheDocument()
  })

  it('evaluators accept an override into a suite with a synthetic input', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    renderApp(`/w/${WS}/ai-review`)
    await user.click(await screen.findByRole('button', { name: 'Accept into a suite' }))
    const dialog = await screen.findByRole('dialog')
    await waitFor(() => expect(within(dialog).getByLabelText(/Suite/)).toHaveValue('dispute-triage-suite'))
    const input = within(dialog).getByLabelText(/Case input/)
    await user.clear(input)
    await user.click(input)
    await user.paste('{"description": "Synthetic: I never ordered this", "amount": 40}')
    await user.click(within(dialog).getByRole('button', { name: 'Add case' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(await screen.findByText('No override is waiting to become an evaluation case.')).toBeInTheDocument()
    const suite = mockAiStore().suites[0]!
    expect(suite.caseCount).toBe(61)
    expect((suite.document.cases as Array<Record<string, unknown>>).at(-1)).toMatchObject({ source: 'override', expected: { label: 'fraudSuspected' }, input: { amount: 40 } })
  })
})
