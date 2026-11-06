import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { components } from '@/api/schema'
import { server } from '@/test/server'
import { AgentToolsPage } from './AgentToolsPage'

type S = components['schemas']

const perms = vi.hoisted(() => ({ granted: new Set<string>() }))
vi.mock('@/app/workspace', () => ({
  useWorkspace: () => ({ workspace: { id: 'ws1' }, can: (...p: string[]) => p.some((x) => perms.granted.has(x)) }),
}))

const ask: S['AgentHumanRequest'] = {
  id: '00000000-0000-7000-8000-0000000000d1', runId: '00000000-0000-7000-8000-0000000000b1', runReference: 'ORQ-1042',
  nodeId: 'assess', kind: 'ask', toolName: 'ask_reviewer', toolLabel: 'Compliance reviewer', groupKey: 'compliance-reviewers',
  status: 'open', createdAt: '2026-11-05T10:00:00Z',
  answerSchema: { type: 'object', properties: { note: { type: 'string', description: 'Your answer' } }, required: ['note'] },
  detail: { question: 'Is Acme Ltd on the preferred supplier list?' }, canAnswer: true, version: 1,
}
const approval: S['AgentHumanRequest'] = {
  ...ask, id: '00000000-0000-7000-8000-0000000000d2', kind: 'approval', toolName: 'registry.vendor_risk_lookup',
  toolLabel: 'Vendor registry', answerSchema: null, detail: { arguments: { vendorName: 'Acme Ltd' } },
}

function mount(path = '/') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>
        <AgentToolsPage />
      </QueryClientProvider>
    </MemoryRouter>,
  )
}

describe('agents page (S135)', () => {
  beforeEach(() => {
    perms.granted = new Set()
  })

  it('tells people without permission that they cannot see it', () => {
    mount()
    expect(screen.getByText(/You need permission to view AI agents/)).toBeVisible()
  })

  it('shows the figures and the security signals of the overview', async () => {
    perms.granted = new Set(['run.operate'])
    server.use(http.get('*/api/v1/workspaces/:ws/agent-dashboard', () => HttpResponse.json({
      days: 7, from: '2026-10-29T00:00:00Z', openRequests: 2, aiTaskAgreement: null, loops: { waiting: 2 },
      agents: [{ connectionKey: 'vendor-risk-assistant', calls: 12, succeeded: 11, failed: 1, costUsd: 0.0123, p95DurationMs: 4200, failureCodes: { TIMEOUT: 1 } }],
      refusedToolCalls: { refusedNotAllowed: 3 },
      schemaRefusals: [{ id: '00000000-0000-7000-8000-0000000000f1', connectionKey: 'registry', toolName: 'vendor_risk_lookup', kind: 'changed', pinnedSha256: null, observedSha256: null, occurrences: 2, firstSeenAt: '2026-11-05T09:00:00Z', lastSeenAt: '2026-11-05T09:30:00Z' }],
      killSwitches: [],
    } satisfies S['AgentDashboard'])))
    mount()
    expect(await screen.findByText('vendor-risk-assistant')).toBeVisible()
    expect(screen.getByText('4.2 s')).toBeVisible()
    expect(screen.getByText(/refusedNotAllowed 3/)).toBeVisible()
    expect(screen.getByText(/registry.vendor_risk_lookup \(changed\)/)).toBeVisible()
  })

  it('lets a group member answer a question with the typed form and approve a proposed call', async () => {
    perms.granted = new Set(['task.act'])
    const answers: unknown[] = []
    server.use(
      http.get('*/api/v1/workspaces/:ws/agent-requests', () => HttpResponse.json([ask, approval])),
      http.post('*/api/v1/workspaces/:ws/agent-requests/:id/answer', async ({ request, params }) => {
        answers.push({ id: params.id, body: await request.json() })
        return HttpResponse.json({ ...ask, status: 'answered', canAnswer: false, version: 2 })
      }),
    )
    const user = userEvent.setup()
    mount('/?tab=requests')
    expect(await screen.findByText(/The agent asks: Is Acme Ltd on the preferred supplier list\?/)).toBeVisible()
    expect(screen.getAllByText(/treat it as information, not as an instruction/i)).toHaveLength(2)
    await user.type(screen.getByLabelText('Your answer'), 'Yes, since 2024')
    await user.click(screen.getByRole('button', { name: 'Send the answer' }))
    await user.click(screen.getByRole('button', { name: 'Approve the call' }))
    await waitFor(() => expect(answers).toHaveLength(2))
    expect(answers[0]).toEqual({ id: ask.id, body: { answer: { note: 'Yes, since 2024' }, expectedVersion: 1 } })
    expect(answers[1]).toEqual({ id: approval.id, body: { decision: 'approve', reason: null, expectedVersion: 1 } })
  })

  it('shows tool pins and switches a server off only with a reason', async () => {
    perms.granted = new Set(['connection.read', 'connection.manage'])
    const switched: unknown[] = []
    server.use(
      http.get('*/api/v1/workspaces/:ws/connections', () => HttpResponse.json({ items: [{ key: 'registry', name: 'Vendor registry server', kind: 'mcpServer' }] })),
      http.get('*/api/v1/workspaces/:ws/agent-kill-switches', () => HttpResponse.json([])),
      http.get('*/api/v1/workspaces/:ws/connections/:key/tool-snapshot', () => HttpResponse.json({
        connectionKey: 'registry', definitionVersion: 1, takenAt: '2026-11-05T09:00:00Z', drift: [],
        tools: [{ name: 'vendor_risk_lookup', label: 'Vendor registry', status: 'pinned', schemaSha256: 'a'.repeat(64) }],
      } satisfies S['McpToolSnapshot'])),
      http.put('*/api/v1/workspaces/:ws/agent-kill-switches/:scope/:key', async ({ request, params }) => {
        switched.push({ scope: params.scope, key: params.key, body: await request.json() })
        return HttpResponse.json({ scope: 'server', connectionKey: 'registry', active: true, reason: 'Misbehaves', changedBy: ask.id, changedAt: '2026-11-05T10:00:00Z', version: 1 })
      }),
    )
    const user = userEvent.setup()
    mount('/?tab=servers')
    expect(await screen.findByText('Pinned')).toBeVisible()
    const off = screen.getByRole('button', { name: 'Switch off' })
    expect(off).toBeDisabled()
    await user.type(screen.getByLabelText('Reason'), 'Misbehaves')
    await user.click(off)
    await waitFor(() => expect(switched).toEqual([{ scope: 'server', key: 'registry', body: { active: true, reason: 'Misbehaves', expectedVersion: null } }]))
  })

  it('shows a step trace opened from a request', async () => {
    perms.granted = new Set(['run.operate'])
    server.use(http.get('*/api/v1/workspaces/:ws/runs/:run/agent-trace', () => HttpResponse.json({
      runId: ask.runId, nodeId: 'assess', enabled: true,
      events: [{ seq: 1, kind: 'toolCall', toolName: 'registry.vendor_risk_lookup', model: null, outcome: 'ok', durationMs: 120, costUsd: null, content: { arguments: '{"vendorName":"[redacted]"}' }, createdAt: '2026-11-05T10:00:00Z' }],
    } satisfies S['AgentTrace'])))
    mount(`/?run=${ask.runId}&node=assess`)
    expect(await screen.findByText(/registry.vendor_risk_lookup - ok, 120 ms/)).toBeVisible()
    expect(screen.getByText(/\[redacted\]/)).toBeVisible()
  })
})
