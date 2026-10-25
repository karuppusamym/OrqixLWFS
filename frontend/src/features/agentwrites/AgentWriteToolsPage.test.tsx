import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { components } from '@/api/schema'
import { server } from '@/test/server'
import { AgentWriteToolsPage } from './AgentWriteToolsPage'

type S = components['schemas']

const perms = vi.hoisted(() => ({ granted: new Set<string>() }))
vi.mock('@/app/workspace', () => ({
  useWorkspace: () => ({ workspace: { id: 'ws1' }, can: (...p: string[]) => p.some((x) => perms.granted.has(x)) }),
}))

const mani = { id: '00000000-0000-7000-8000-000000000011', displayName: 'Mani Manager' }
const rhea = { id: '00000000-0000-7000-8000-000000000012', displayName: 'Rhea Requester' }
const tool: S['AgentWriteToolDeclared'] = {
  connectionKey: 'vendor-writer', connectionVersion: 1, connectionName: 'Vendor writer', operation: 'assessVendor',
  operationLabel: 'Assess vendor risk', toolName: 'notify_procurement', label: 'Tell procurement',
  description: 'Emails procurement.', adapterKey: 'procurement-mail', kind: 'email', to: ['procurement@example.com'], maxCalls: 1,
}
const approval: S['AgentWriteToolApproval'] = {
  id: '00000000-0000-7000-8000-0000000000a1', workflowKey: 'vendor-onboarding-check', connectionKey: 'vendor-writer',
  connectionVersion: 1, operation: 'assessVendor', toolName: 'notify_procurement', adapterKey: 'procurement-mail',
  status: 'active', approvedBy: mani, approvedAt: '2026-10-18T10:00:00Z', approvalReason: 'Asked by procurement',
  revokedBy: null, revokedAt: null, revokeReason: null, expiresAt: null, version: 3,
}
const unknownWrite: S['AgentWriteCall'] = {
  id: '00000000-0000-7000-8000-0000000000c1', runId: '00000000-0000-7000-8000-0000000000b1',
  nodeId: 'assess:write:notify_procurement:1', connectionKey: 'vendor-writer', connectionVersion: 1, operation: 'assessVendor',
  toolName: 'notify_procurement', callIndex: 1, adapterKey: 'procurement-mail', kind: 'email', approvalId: approval.id,
  approvedBy: mani, requestedBy: rhea, argumentChars: 57, effectId: '00000000-0000-7000-8000-0000000000e1', state: 'unknown',
  attempts: 1, lastErrorCode: 'LEASE_EXPIRED', reconciledOutcome: null, reconciledBy: null, reconciledAt: null,
  reconcileReason: null, version: 2, createdAt: '2026-10-18T11:00:00Z', updatedAt: '2026-10-18T11:00:00Z',
}

function mount() {
  return render(
    <MemoryRouter>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>
        <AgentWriteToolsPage />
      </QueryClientProvider>
    </MemoryRouter>,
  )
}

describe('agent write tools page (IN04)', () => {
  beforeEach(() => {
    perms.granted = new Set()
  })

  it('tells people without permission that they cannot see it, and asks nothing of the server', async () => {
    const seen: string[] = []
    server.use(http.get('*/api/v1/workspaces/:ws/agent-write-tools', ({ request }) => (seen.push(request.url), HttpResponse.json({}))))
    mount()
    expect(screen.getByText(/You need permission to view agent write tools/)).toBeVisible()
    expect(seen).toEqual([])
  })

  it('lets an approver approve a tool for a workflow only with a key and a reason, and revoke with the version seen', async () => {
    perms.granted = new Set(['agent.approve-write-tool'])
    const approved: unknown[] = []
    const revoked: unknown[] = []
    server.use(
      http.get('*/api/v1/workspaces/:ws/agent-write-tools', () => HttpResponse.json({ tools: [tool], approvals: [approval], canApprove: true, canRevoke: true })),
      http.get('*/api/v1/workspaces/:ws/agent-writes', () => HttpResponse.json({ items: [], canReconcile: false })),
      http.post('*/api/v1/workspaces/:ws/agent-write-tools/approvals', async ({ request }) => {
        approved.push(await request.json())
        return HttpResponse.json({ ...approval, id: '00000000-0000-7000-8000-0000000000a2', workflowKey: 'supplier-review', version: 1 }, { status: 201 })
      }),
      http.post('*/api/v1/workspaces/:ws/agent-write-tools/approvals/:id/revoke', async ({ request }) => {
        revoked.push(await request.json())
        return HttpResponse.json({ ...approval, status: 'revoked', revokedBy: mani, revokedAt: '2026-10-18T12:00:00Z', revokeReason: 'Paused', version: 4 })
      }),
    )
    const user = userEvent.setup()
    mount()
    const row = (await screen.findByText('Tell procurement')).closest('tr')!
    expect(within(row).getByText('procurement@example.com')).toBeVisible()
    expect(within(row).getByText('vendor-onboarding-check')).toBeVisible()

    await user.click(within(row).getByRole('button', { name: 'Approve Tell procurement for a workflow' }))
    let dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/not be able to send through it for requests you start yourself/)).toBeVisible()
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }))
    expect(within(dialog).getByText(/Enter the key of the workflow/)).toBeVisible()
    await user.type(within(dialog).getByLabelText('Workflow key'), 'supplier-review')
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }))
    // The missing reason is announced and tied to its field.
    const reasonError = within(dialog).getByRole('alert')
    expect(reasonError).toHaveTextContent('Say why this agent may send these messages.')
    const reasonField = within(dialog).getByLabelText('Reason')
    expect(reasonField).toHaveAttribute('aria-invalid', 'true')
    expect(reasonField).toHaveAccessibleDescription('Say why this agent may send these messages.')
    expect(within(dialog).getByLabelText('Workflow key')).not.toHaveAttribute('aria-invalid')
    expect(approved).toEqual([])
    await user.type(within(dialog).getByLabelText('Reason'), 'Supplier team asked for it')
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }))
    await waitFor(() => expect(approved).toHaveLength(1))
    expect(approved[0]).toEqual({ workflowKey: 'supplier-review', connectionKey: 'vendor-writer', connectionVersion: 1,
      operation: 'assessVendor', toolName: 'notify_procurement', reason: 'Supplier team asked for it' })

    await user.click(await screen.findByRole('button', { name: 'Revoke Tell procurement for vendor-onboarding-check' }))
    dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Revoke' }))
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Say why the approval is revoked.')
    expect(within(dialog).getByLabelText('Reason')).toHaveAttribute('aria-invalid', 'true')
    expect(within(dialog).getByLabelText('Reason')).toHaveAccessibleDescription('Say why the approval is revoked.')
    await user.type(within(dialog).getByLabelText('Reason'), 'Paused')
    await user.click(within(dialog).getByRole('button', { name: 'Revoke' }))
    await waitFor(() => expect(revoked).toEqual([{ reason: 'Paused', expectedVersion: 3 }]))
  })

  it('lets an operator record what they found for an unknown write, with a reason and the version seen', async () => {
    perms.granted = new Set(['run.operate', 'connection.read'])
    const recorded: unknown[] = []
    let state: S['AgentWriteCall'] = unknownWrite
    server.use(
      http.get('*/api/v1/workspaces/:ws/agent-write-tools', () => HttpResponse.json({ tools: [tool], approvals: [approval], canApprove: false, canRevoke: false })),
      http.get('*/api/v1/workspaces/:ws/agent-writes', () => HttpResponse.json({ items: state.state === 'unknown' ? [state] : [], canReconcile: true })),
      http.post('*/api/v1/workspaces/:ws/agent-writes/:id/reconcile', async ({ request }) => {
        recorded.push(await request.json())
        state = { ...unknownWrite, state: 'succeeded', reconciledOutcome: 'succeeded', reconciledBy: { id: rhea.id, displayName: 'Olga Operator' }, reconcileReason: 'In the mailbox', version: 3 }
        return HttpResponse.json(state)
      }),
    )
    const user = userEvent.setup()
    mount()
    const row = (await screen.findByText('Outcome unknown')).closest('tr')!
    expect(within(row).getByText(/57 characters written by the agent/)).toBeVisible()
    // Approvers' controls are not offered to an operator without the grant.
    expect(screen.queryByRole('button', { name: /Approve Tell procurement/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Revoke/ })).not.toBeInTheDocument()

    await user.click(within(row).getByRole('button', { name: 'Record the outcome of notify_procurement use 1' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Record outcome' }))
    expect(within(dialog).getByText('Choose what you found at the target.')).toHaveAttribute('role', 'alert')
    const group = within(dialog).getByRole('radiogroup', { name: 'What did you find?' })
    expect(group).toHaveAttribute('aria-invalid', 'true')
    expect(group).toHaveAccessibleDescription('Choose what you found at the target.')
    expect(within(dialog).getByLabelText('What you checked')).toHaveAccessibleDescription('Say what you checked.')
    await user.click(within(dialog).getByLabelText(/It was delivered/))
    await user.click(within(dialog).getByRole('button', { name: 'Record outcome' }))
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Say what you checked.')
    expect(within(dialog).getByRole('radiogroup', { name: 'What did you find?' })).not.toHaveAttribute('aria-invalid')
    expect(within(dialog).getByLabelText('What you checked')).toHaveAttribute('aria-invalid', 'true')
    expect(recorded).toEqual([])
    await user.type(within(dialog).getByLabelText('What you checked'), 'In the mailbox')
    await user.type(within(dialog).getByLabelText('Reference at the target (optional)'), 'MSG-1')
    await user.click(within(dialog).getByRole('button', { name: 'Record outcome' }))
    await waitFor(() => expect(recorded).toEqual([{ outcome: 'succeeded', reason: 'In the mailbox', remoteReference: 'MSG-1', expectedVersion: 2 }]))
    expect(await screen.findByText('No write needs checking.')).toBeVisible()
  })

  it('lets an approver set an optional expiry of 1 to 90 days, and shows expiry dates and expired approvals as Expired', async () => {
    perms.granted = new Set(['agent.approve-write-tool'])
    const approved: unknown[] = []
    const limited: S['AgentWriteToolApproval'] = { ...approval, expiresAt: '2026-11-17T10:00:00Z' }
    const expired: S['AgentWriteToolApproval'] = {
      ...approval, id: '00000000-0000-7000-8000-0000000000a3', workflowKey: 'supplier-review', status: 'expired',
      approvedAt: '2026-09-01T10:00:00Z', expiresAt: '2026-09-02T10:00:00Z', version: 1,
    }
    // An older expired approval of a workflow that was approved again is history, not an Expired state.
    const replaced: S['AgentWriteToolApproval'] = {
      ...approval, id: '00000000-0000-7000-8000-0000000000a4', status: 'expired', approvedAt: '2026-08-01T10:00:00Z',
      expiresAt: '2026-08-02T10:00:00Z', version: 2,
    }
    server.use(
      http.get('*/api/v1/workspaces/:ws/agent-write-tools', () => HttpResponse.json({ tools: [tool], approvals: [limited, expired, replaced], canApprove: true, canRevoke: true })),
      http.get('*/api/v1/workspaces/:ws/agent-writes', () => HttpResponse.json({ items: [], canReconcile: false })),
      http.post('*/api/v1/workspaces/:ws/agent-write-tools/approvals', async ({ request }) => {
        approved.push(await request.json())
        return HttpResponse.json({ ...expired, id: '00000000-0000-7000-8000-0000000000a5', status: 'active', expiresAt: '2026-11-24T10:00:00Z', version: 1 }, { status: 201 })
      }),
    )
    const user = userEvent.setup()
    mount()
    const row = (await screen.findByText('Tell procurement')).closest('tr')!
    expect(within(row).getByText(/by Mani Manager; expires 17 Nov 2026/)).toBeVisible()
    // The expired approval is a state with its own text, not a colour, and offers nothing to revoke.
    const expiredItem = within(row).getByTestId('expired-approval')
    expect(within(expiredItem).getByText('supplier-review')).toBeVisible()
    expect(within(expiredItem).getByText('Expired')).toBeVisible()
    expect(within(expiredItem).getByText(/allows nothing until approved again/)).toHaveTextContent(/2026/)
    expect(within(row).getAllByTestId('expired-approval')).toHaveLength(1)
    expect(within(row).queryByRole('button', { name: 'Revoke Tell procurement for supplier-review' })).not.toBeInTheDocument()

    await user.click(within(row).getByRole('button', { name: 'Approve Tell procurement for a workflow' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Workflow key'), 'supplier-review')
    await user.type(within(dialog).getByLabelText('Reason'), 'Renewed for the pilot')
    const expiry = within(dialog).getByLabelText('Expires after (days, optional)')
    expect(expiry).toHaveAccessibleDescription(/From 1 to 90 days/)
    for (const bad of ['0', '91', '2.5', 'soon']) {
      await user.clear(expiry)
      await user.type(expiry, bad)
      await user.click(within(dialog).getByRole('button', { name: 'Approve' }))
      expect(within(dialog).getByRole('alert')).toHaveTextContent('Enter a whole number of days from 1 to 90, or leave it empty for no end date.')
      expect(expiry).toHaveAttribute('aria-invalid', 'true')
      expect(expiry).toHaveAccessibleDescription(/Enter a whole number of days from 1 to 90/)
    }
    expect(approved).toEqual([])
    await user.clear(expiry)
    await user.type(expiry, '7')
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }))
    await waitFor(() => expect(approved).toEqual([{ workflowKey: 'supplier-review', connectionKey: 'vendor-writer', connectionVersion: 1,
      operation: 'assessVendor', toolName: 'notify_procurement', reason: 'Renewed for the pilot', expiresInDays: 7 }]))
  })
})
