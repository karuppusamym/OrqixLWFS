import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { seedMcpCalls } from '@/mocks/mcpServer'
import { renderApp, signInAs, WS } from '@/test/utils'

describe('Settings, AI assistants (MCP server, S136)', () => {
  it('is off until an administrator switches it on, then shows the connection details and recent calls without values', async () => {
    signInAs('wade.admin')
    seedMcpCalls(WS, [
      {
        occurredAt: '2026-11-05T10:00:00Z', actor: { id: 'u1', displayName: 'Rhea Requester' }, tool: 'start_purchase_request',
        step: 'prepare', outcome: 'confirmationRequired', errorCode: null, runId: null, taskId: null, workflowId: null,
        confirmationId: '01a1aede-0000-7000-8000-000000000001',
      },
      {
        occurredAt: '2026-11-05T10:01:00Z', actor: { id: 'u2', displayName: 'Mani Manager' }, tool: 'get_request', step: 'read',
        outcome: 'denied', errorCode: 'NOT_FOUND', runId: '01a1aede-0000-7000-8000-000000000002', taskId: null,
        workflowId: null, confirmationId: null,
      },
    ])
    const user = userEvent.setup()
    renderApp(`/w/${WS}/settings`)
    await user.click(await screen.findByRole('tab', { name: 'AI assistants' }))
    const card = await screen.findByTestId('mcp-server')
    expect(within(card).getByTestId('mcp-server-state')).toHaveTextContent('Off')
    expect(within(card).getByTestId('mcp-endpoint').textContent).toMatch(/\/mcp$/)
    expect(within(card).getByTestId('mcp-metadata').textContent).toMatch(/\/\.well-known\/oauth-protected-resource\/mcp$/)
    expect(screen.queryByRole('heading', { name: 'Recent calls' })).not.toBeInTheDocument()

    await user.click(within(card).getByRole('button', { name: 'Switch on' }))
    expect(await within(card).findByTestId('mcp-server-state')).toHaveTextContent('On')
    expect(within(card).getByText(/Switched on by Wade Admin/)).toBeInTheDocument()
    const rows = await screen.findAllByTestId('mcp-call')
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('Waiting for confirmation')
    expect(rows[1]).toHaveTextContent(/Refused.*NOT_FOUND/)
    expect(within(rows[1]!).getByRole('link', { name: 'Request' })).toHaveAttribute('href', `/w/${WS}/runs/01a1aede-0000-7000-8000-000000000002`)

    // Switching off asks first and says what happens.
    await user.click(within(card).getByRole('button', { name: 'Switch off' }))
    expect(within(card).getByText(/Assistants lose access to this workspace at once/)).toBeVisible()
    await user.click(within(card).getByRole('button', { name: 'Switch off the MCP server' }))
    expect(await within(card).findByTestId('mcp-server-state')).toHaveTextContent('Off')
  })

  it('is not offered to people who do not administer the workspace', async () => {
    signInAs('rhea.requester')
    renderApp(`/w/${WS}/settings`)
    await screen.findByRole('heading', { level: 1, name: 'Settings' })
    expect(screen.queryByRole('tab', { name: 'AI assistants' })).not.toBeInTheDocument()
  })
})
