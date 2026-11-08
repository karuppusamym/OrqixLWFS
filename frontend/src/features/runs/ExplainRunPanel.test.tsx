import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import type { components } from '@/api/schema'
import { server } from '@/test/server'
import { Providers } from '@/app/providers'
import { testQueryClient } from '@/test/utils'
import { mockRunExplanation } from '@/mocks/runExplain'
import { ExplainRunPanel } from './ExplainRunPanel'

type S = components['schemas']

const renderWithProviders = (element: React.ReactElement) => render(<Providers client={testQueryClient()}>{element}</Providers>)

const action = (id: string, kind: S['ExplainPermittedAction']['kind'], label: string): S['ExplainPermittedAction'] => ({
  id, operationId: kind === 'askAdministrator' ? null : id.split(':')[0] ?? null, kind, label, description: `${label} described`,
  permission: kind === 'askAdministrator' ? 'connection.manage' : 'run.operate', nodeId: 'ticket', targetId: null,
})

const explanation: S['RunExplanation'] = {
  runId: 'run1', runVersion: 4, lifecycle: 'needsAttention', source: 'ai',
  summary: 'ServiceNow returned 403 since 09:12.', cause: 'The binding token was rotated.',
  evidence: ['F2'], suggestedActions: [{ actionId: 'askAdministrator:connection:servicenow', why: 'fix first' }, { actionId: 'retryRun', why: 'then retry' }],
  facts: [
    { id: 'F1', kind: 'run', text: 'The request needs attention.', nodeId: null, at: null, eventSeq: null },
    { id: 'F2', kind: 'event', text: 'Step "Open ticket" has failed 3 time(s) with HTTP_403 since 09:12.', nodeId: 'ticket', at: '2026-11-08T09:12:00Z', eventSeq: 7 },
  ],
  permittedActions: [action('askAdministrator:connection:servicenow', 'askAdministrator', 'Ask a connection administrator to check servicenow'),
    action('retryRun', 'operate', 'Retry the failed step'), action('cancelRun', 'operate', 'Cancel the request')],
  droppedActions: ['deleteRequest'], fallbackReason: null,
  ai: { profile: 'economy', model: 'test/model', promptVersion: 'explain-run-v1', cached: false },
  readOnly: true, stateDigest: 'abc', generatedAt: '2026-11-08T10:00:00Z',
}

describe('Explain this request (CG-A07)', () => {
  it('shows the AI explanation as read-only and opens only permitted actions', async () => {
    let calls = 0
    server.use(http.post('/api/v1/workspaces/:ws/runs/:runId/explain', () => { calls++; return HttpResponse.json(explanation) }))
    const onAction = vi.fn()
    const user = userEvent.setup()
    renderWithProviders(<ExplainRunPanel ws="ws1" runId="run1" onAction={onAction} />)
    await user.click(screen.getByRole('button', { name: /Explain this request/ }))
    expect(await screen.findByTestId('explain-source')).toHaveTextContent('AI-generated, read-only')
    expect(screen.getByTestId('explain-summary')).toHaveTextContent('ServiceNow returned 403 since 09:12.')
    // Someone else's action is text, not a button; the dropped action is never shown.
    expect(screen.getByTestId('explain-ask-askAdministrator:connection:servicenow')).toHaveTextContent('Ask a connection administrator')
    expect(screen.queryByText(/deleteRequest/)).toBeNull()
    expect(screen.getByRole('link', { name: /HTTP_403/ })).toHaveAttribute('href', '#event-7')
    await user.click(screen.getByTestId('explain-action-retryRun'))
    expect(onAction).toHaveBeenCalledWith(expect.objectContaining({ id: 'retryRun', operationId: 'retryRun' }))
    expect(calls).toBe(1) // the click opened a dialog callback; the panel itself called nothing else
  })

  it('labels the rule-based fallback and says why', async () => {
    server.use(http.post('/api/v1/workspaces/:ws/runs/:runId/explain', () => HttpResponse.json({
      ...explanation, source: 'rules', ai: null, fallbackReason: 'budget', droppedActions: [],
    } satisfies S['RunExplanation'])))
    const user = userEvent.setup()
    renderWithProviders(<ExplainRunPanel ws="ws1" runId="run1" onAction={() => {}} />)
    await user.click(screen.getByRole('button', { name: /Explain this request/ }))
    expect(await screen.findByTestId('explain-source')).toHaveTextContent('Rule-based, read-only')
    expect(screen.getByText(/AI budget for this month is used up/)).toBeInTheDocument()
  })

  it('mock mode explains from the run detail without request values', () => {
    const run = {
      id: 'r1', version: 2, lifecycle: 'needsAttention', updatedAt: '2026-11-08T09:00:00Z',
      attention: { reason: 'RETRIES_EXHAUSTED', nodeId: 'procure', effectId: null, message: 'secret detail', since: '2026-11-08T09:00:00Z' },
      effects: [], tasks: [], currentSteps: [], suspension: null, input: { title: 'Private laptop' },
      allowedActions: { cancel: { allowed: true, reasonCode: null }, retry: { allowed: true, reasonCode: null }, reconcile: { allowed: false, reasonCode: 'X' }, suspend: { allowed: true, reasonCode: null }, resume: { allowed: false, reasonCode: 'X' } },
    } as unknown as S['RunDetail']
    const e = mockRunExplanation(run, '2026-11-08T10:00:00Z')
    expect(e.source).toBe('rules')
    expect(e.suggestedActions.map((s) => s.actionId)).toEqual(['retryRun', 'cancelRun'])
    expect(JSON.stringify(e)).not.toContain('Private laptop')
    expect(JSON.stringify(e)).not.toContain('secret detail')
  })
})
