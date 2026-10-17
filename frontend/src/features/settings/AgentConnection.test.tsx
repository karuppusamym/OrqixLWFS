import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { aiMock } from '@/features/ai/mocks'
import { renderApp, signInAs, WS } from '@/test/utils'

async function openConnections(user: ReturnType<typeof userEvent.setup>) {
  renderApp(`/w/${WS}/settings`)
  await user.click(await screen.findByRole('tab', { name: /Connections/ }))
}

describe('AI agent connections in Settings (S13 section 8)', () => {
  beforeEach(() => {
    aiMock.enabled = true
  })
  afterEach(() => {
    aiMock.enabled = true
  })

  it('lists the agent with a badge, its instructions and its allowed tools, read-only', async () => {
    signInAs('wade.admin')
    await openConnections(userEvent.setup())
    const heading = await screen.findByRole('heading', { name: /Vendor risk assistant/ })
    const card = heading.closest('section')!
    expect(within(card).getAllByTestId('ai-agent-badge')[0]).toHaveTextContent('AI agent')
    expect(within(card).getAllByText(/Uses AI \(cost varies, read-only\)/).length).toBeGreaterThan(0)
    expect(await within(card).findByTestId('agent-instructions')).toHaveTextContent(/call the vendor_risk_lookup tool once/)
    expect(within(card).getByTestId('agent-tools')).toHaveTextContent('Vendor registry')
    expect(within(card).getByTestId('agent-tools')).toHaveTextContent('vendor_risk_lookup')
    expect(within(card).queryByRole('textbox', { name: /instructions/i })).not.toBeInTheDocument()
    // The ordinary HTTP connection has no badge.
    const procurement = screen.getByRole('heading', { name: /Procurement service/ }).closest('section')!
    expect(within(procurement).queryByTestId('ai-agent-badge')).not.toBeInTheDocument()
  })

  it('tests the agent with a sample input and shows the structured result and the timeline sentence', async () => {
    signInAs('wade.admin')
    const user = userEvent.setup()
    await openConnections(user)
    const test = await screen.findByTestId('agent-test-vendor-risk-assistant')
    const run = within(test).getByRole('button', { name: 'Test the agent' })
    await within(test).findByLabelText(/Vendor name/)
    expect(run).toBeDisabled()
    await user.type(within(test).getByLabelText(/Vendor name/), 'Acme Ltd')
    await user.type(within(test).getByLabelText(/Country/), 'United Kingdom')
    await user.type(within(test).getByLabelText(/Annual spend/), '1200')
    await user.click(run)
    expect(await within(test).findByTestId('agent-test-summary')).toHaveTextContent(
      "Vendor risk assistant checked 'Acme Ltd' using Vendor registry: risk 72 (high)",
    )
    expect(within(test).getByTestId('agent-test-output')).toHaveTextContent('72')
    expect(within(test).getByTestId('agent-test-output')).toHaveTextContent('high')
  })

  it('does not offer the test while AI is not configured', async () => {
    aiMock.enabled = false
    signInAs('wade.admin')
    await openConnections(userEvent.setup())
    const test = await screen.findByTestId('agent-test-vendor-risk-assistant')
    expect(await within(test).findByText(/AI is not available/)).toBeInTheDocument()
    expect(within(test).getByRole('button', { name: 'Test the agent' })).toBeDisabled()
  })
})
