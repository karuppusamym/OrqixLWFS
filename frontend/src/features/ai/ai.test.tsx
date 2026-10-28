import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { db, PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import { server } from '@/test/server'
import { renderApp, signInAs, WS } from '@/test/utils'
import { aiMock, resetAiJobs } from './mocks'
import { aiPolling, formatUsd } from './api'
import { clearTrackedJobs } from './jobs'
import { readReviewNotes } from './reviewNotes'

const EDIT = `/w/${WS}/workflows/${PURCHASE_WORKFLOW_ID}/edit`

async function openNewWorkflow(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: /New workflow/ }))
  return screen.findByRole('dialog', { name: 'New workflow' })
}

describe('AI assistance (S10 section 6)', () => {
  beforeEach(() => {
    aiMock.enabled = true
    aiMock.trippedAuto = false
    aiMock.generationDelayMs = 0
    aiPolling.intervalMs = 20
    resetAiJobs()
    window.sessionStorage.clear()
    clearTrackedJobs()
  })
  afterEach(() => {
    aiMock.enabled = true
    aiMock.trippedAuto = false
    aiPolling.intervalMs = 2000
    aiMock.generationDelayMs = 2500
    clearTrackedJobs()
  })

  it('hides every AI entry point when AI is not configured', async () => {
    aiMock.enabled = false
    signInAs('dana.designer')
    const user = userEvent.setup()
    const { router } = renderApp(`/w/${WS}/workflows`)
    const dialog = await openNewWorkflow(user)
    await within(dialog).findByRole('button', { name: 'Start empty' })
    expect(within(dialog).getByTestId('new-workflow-ai-note')).toHaveTextContent(/AI drafting is turned off/)
    expect(within(dialog).queryByTestId('describe-it-choice')).not.toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))

    await router.navigate(EDIT)
    await screen.findByText(/Draft revision 1/)
    expect(screen.getByRole('button', { name: /Check workflow/ })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'More actions' }))
    expect(await screen.findByRole('menuitem', { name: /Preview the request form/ })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: /Explain this workflow/ })).not.toBeInTheDocument()
    expect(screen.queryByTestId('ai-review')).not.toBeInTheDocument()

    await router.navigate(`/w/${WS}/settings`)
    await screen.findByRole('tab', { name: /Connections/ })
    expect(screen.queryByRole('tab', { name: /^AI$/ })).not.toBeInTheDocument()
  })

  it('drafts a workflow from a description and opens it in the studio with the review panel', async () => {
    signInAs('dana.designer')
    let sent: { description?: string; profile?: string } = {}
    let idempotencyKey: string | null = null
    server.use(
      http.post('*/api/v1/workspaces/:ws/ai/drafts', async ({ request }) => {
        sent = (await request.clone().json()) as typeof sent
        idempotencyKey = request.headers.get('Idempotency-Key')
        return undefined
      }),
    )
    const user = userEvent.setup()
    const { router } = renderApp(`/w/${WS}/workflows`)
    const dialog = await openNewWorkflow(user)
    await user.click(await within(dialog).findByRole('button', { name: 'Describe it to AI' }))

    const describe = await screen.findByRole('dialog', { name: 'Describe your workflow' })
    // Examples fill the description; the profile choice explains the router's variable cost.
    await user.click(within(describe).getByRole('button', { name: /Use the example: Access request/ }))
    expect((within(describe).getByLabelText(/What should the workflow do/) as HTMLTextAreaElement).value).toContain('security team')
    expect(within(describe).getByText('Picks the model per request; cost varies.')).toBeInTheDocument()
    await user.click(within(describe).getByRole('radio', { name: /Auto \(Jev Router\)/ }))
    await user.click(within(describe).getByRole('button', { name: /Generate draft/ }))

    await waitFor(() => expect(router.state.location.pathname).toMatch(/\/workflows\/[^/]+\/edit$/))
    expect(sent.profile).toBe('auto')
    expect(sent.description).toContain('security team')
    expect(idempotencyKey).toMatch(/^[A-Za-z0-9._:-]{8,128}$/)

    const panel = await screen.findByTestId('ai-review')
    expect(within(panel).getByRole('heading', { name: /AI-drafted — review every step before publishing/ })).toBeInTheDocument()
    const missing = within(panel).getByRole('list', { name: 'Missing configuration' })
    expect(within(missing).getByText(/Create the group "security-team"/)).toBeInTheDocument()
    expect(within(panel).getByRole('list', { name: 'Assumptions' })).toHaveTextContent('one after the other')
    expect(within(panel).getByTestId('ai-generation')).toHaveTextContent('openai/gpt-6-luna')

    // Go to step selects the step that needs the group.
    await user.click(within(missing).getByRole('button', { name: /Go to step Second review/ }))
    await waitFor(() => expect(screen.getByTestId('inspector').querySelector('[data-field="label"]')).toHaveValue('Second review'))

    // Collapsible and dismissible; dismissing forgets the notes for this tab.
    await user.click(within(panel).getByRole('button', { name: 'Hide details' }))
    expect(within(panel).queryByRole('list', { name: 'Assumptions' })).not.toBeInTheDocument()
    const workflowId = router.state.location.pathname.split('/')[4]!
    expect(readReviewNotes(WS, workflowId)).not.toBeNull()
    await user.click(within(panel).getByRole('button', { name: 'Dismiss' }))
    expect(screen.queryByTestId('ai-review')).not.toBeInTheDocument()
    expect(readReviewNotes(WS, workflowId)).toBeNull()
  })

  it('validates the description and maps AI problems to business messages with retry where it helps', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    renderApp(`/w/${WS}/workflows`)
    const dialog = await openNewWorkflow(user)
    await user.click(await within(dialog).findByRole('button', { name: 'Describe it to AI' }))
    const describe = await screen.findByRole('dialog', { name: 'Describe your workflow' })
    const box = within(describe).getByLabelText(/What should the workflow do/)

    await user.click(within(describe).getByRole('button', { name: /Generate draft/ }))
    expect(await within(describe).findByText(/at least 10 characters/)).toBeInTheDocument()
    expect(box).toHaveAttribute('aria-invalid', 'true')

    await user.type(box, 'Write a poem about the sea and the stars.')
    await user.click(within(describe).getByRole('button', { name: /Generate draft/ }))
    expect(await within(describe).findByText(/couldn't produce a valid workflow/)).toBeInTheDocument()
    expect(within(describe).getByText(/does not describe a business workflow/)).toBeInTheDocument()

    await user.clear(box)
    await user.type(box, 'Approval for a laptop purchase (unavailable)')
    await user.click(within(describe).getByRole('button', { name: /Generate draft/ }))
    expect(await within(describe).findByText(/not responding right now/)).toBeInTheDocument()
    expect(within(describe).getByRole('button', { name: /Try again/ })).toBeInTheDocument()
    // Back to the other ways to start keeps the dialog flow.
    await user.click(within(describe).getByRole('button', { name: /Other ways to start/ }))
    expect(await screen.findByRole('dialog', { name: 'New workflow' })).toBeInTheDocument()
  })

  it('explains the current draft in a side panel with Go to step', async () => {
    signInAs('dana.designer')
    let revision: number | undefined
    server.use(
      http.post('*/api/v1/workspaces/:ws/workflows/:id/explain', async ({ request }) => {
        revision = ((await request.clone().json()) as { revision?: number }).revision
        return undefined
      }),
    )
    const user = userEvent.setup()
    renderApp(EDIT)
    await screen.findByText(/Draft revision 1/)
    await user.click(await screen.findByRole('button', { name: 'More actions' }))
    await user.click(await screen.findByRole('menuitem', { name: /Explain this workflow/ }))
    const panel = await screen.findByTestId('explain-panel')
    expect(await within(panel).findByTestId('explain-summary')).toHaveTextContent(/managers, then finance-approvers/)
    expect(revision).toBe(1)
    const steps = within(panel).getAllByRole('listitem')
    expect(steps.length).toBe(10)
    expect(within(panel).getByText(/cost USD 0.0007/)).toBeInTheDocument()
    await user.click(within(panel).getByRole('button', { name: 'Go to step Finance review' }))
    await waitFor(() => expect(screen.queryByTestId('explain-panel')).not.toBeInTheDocument())
    await waitFor(() => expect(screen.getByTestId('inspector').querySelector('[data-field="label"]')).toHaveValue('Finance review'))
  })
  it('shows compiler blockers independently of an AI claim that groups are ready', async () => {
    signInAs('dana.designer')
    server.use(http.post('*/api/v1/workspaces/:ws/workflows/:id/explain', () => HttpResponse.json({
      summary: 'All groups are ready.', steps: [], generation: { model: 'test', costUsd: 0, profile: 'economy', promptVersion: 'explain-v2', attempts: 1, escalated: false, durationMs: 1 },
      validation: { publishable: false, issues: [{ code: 'WF_GROUP_UNKNOWN', severity: 'blocker', message: 'Create the legal-reviewers group.', path: '/nodes/finance', nodeId: 'finance' }] },
    })))
    const user = userEvent.setup()
    renderApp(EDIT)
    await user.click(await screen.findByRole('button', { name: 'More actions' }))
    await user.click(await screen.findByRole('menuitem', { name: /Explain this workflow/ }))
    expect(await screen.findByText('This workflow is not ready to publish.')).toBeInTheDocument()
    expect(screen.getByText('Create the legal-reviewers group.')).toBeInTheDocument()
    expect(screen.getByText(/AI text below does not change it/)).toBeInTheDocument()
  })

  it('shows an explanation error with a retry', async () => {
    signInAs('dana.designer')
    let calls = 0
    server.use(
      http.post('*/api/v1/workspaces/:ws/workflows/:id/explain', () => {
        calls++
        if (calls > 1) return undefined
        return HttpResponse.json(
          { type: 'https://orqix.example/problems/ai-provider-unavailable', title: 'Unavailable', status: 503, code: 'AI_PROVIDER_UNAVAILABLE', correlationId: 'corr-12345678' },
          { status: 503, headers: { 'Content-Type': 'application/problem+json' } },
        )
      }),
    )
    const user = userEvent.setup()
    renderApp(EDIT)
    await user.click(await screen.findByRole('button', { name: 'More actions' }))
    await user.click(await screen.findByRole('menuitem', { name: /Explain this workflow/ }))
    const panel = await screen.findByTestId('explain-panel')
    await user.click(await within(panel).findByRole('button', { name: /Try again/ }))
    expect(await within(panel).findByTestId('explain-summary')).toBeInTheDocument()
    expect(calls).toBe(2)
  })

  it('shows Settings > AI read-only to workspace administrators', async () => {
    signInAs('wade.admin')
    db.latencyMs = 0
    const user = userEvent.setup()
    renderApp(`/w/${WS}/settings`)
    await user.click(await screen.findByRole('tab', { name: /^AI$/ }))
    const settings = await screen.findByTestId('ai-settings')
    expect(within(settings).getByText('On')).toBeInTheDocument()
    expect(within(settings).getByText('deepseek/deepseek-v3.2')).toBeInTheDocument()
    expect(within(settings).getByText(/Varies per request/)).toBeInTheDocument()
    expect(within(settings).getByText(/read-only here/)).toBeInTheDocument()
    expect(within(settings).getByRole('list', { name: 'Excluded models' })).toHaveTextContent('anthropic/claude-opus-*')
    expect(within(settings).getByText(/of USD 10.00/)).toBeInTheDocument()
  })

  it('lets the person close the dialog while drafting: a toast follows the job and opens the draft when ready', async () => {
    aiMock.generationDelayMs = 600
    signInAs('dana.designer')
    const user = userEvent.setup()
    const { router } = renderApp(`/w/${WS}/workflows`)
    const dialog = await openNewWorkflow(user)
    await user.click(await within(dialog).findByRole('button', { name: 'Describe it to AI' }))
    const describe = await screen.findByRole('dialog', { name: 'Describe your workflow' })
    await user.type(within(describe).getByLabelText(/What should the workflow do/), 'Access requests approved by the owner and by security.')
    await user.click(within(describe).getByRole('button', { name: /Generate draft/ }))
    expect(await within(describe).findByTestId('describe-progress')).toHaveTextContent(/we'll open the draft when it's ready/)

    await user.click(await within(describe).findByRole('button', { name: 'Close and keep drafting' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Describe your workflow' })).not.toBeInTheDocument())
    const toast = await screen.findByTestId('ai-job-toast')
    expect(toast).toHaveTextContent(/We'll let you know when the draft is ready/)

    // Still on the list; the toast turns into "Open draft" when the job succeeds.
    await within(toast).findByText('Your draft is ready', undefined, { timeout: 5000 })
    expect(router.state.location.pathname).toBe(`/w/${WS}/workflows`)
    await user.click(within(toast).getByRole('button', { name: 'Open draft' }))
    await waitFor(() => expect(router.state.location.pathname).toMatch(/\/workflows\/[^/]+\/edit$/))
    expect(await screen.findByTestId('ai-review')).toBeInTheDocument()
    expect(screen.queryByTestId('ai-job-toast')).not.toBeInTheDocument()
  })

  it('shows a failed generation in the dialog and starts the retry as a new command', async () => {
    signInAs('dana.designer')
    const keys: string[] = []
    server.use(
      http.post('*/api/v1/workspaces/:ws/ai/drafts', ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key') ?? '')
        return undefined
      }),
    )
    const user = userEvent.setup()
    renderApp(`/w/${WS}/workflows`)
    const dialog = await openNewWorkflow(user)
    await user.click(await within(dialog).findByRole('button', { name: 'Describe it to AI' }))
    const describe = await screen.findByRole('dialog', { name: 'Describe your workflow' })
    await user.type(within(describe).getByLabelText(/What should the workflow do/), 'Approval for a laptop purchase (unavailable)')
    await user.click(within(describe).getByRole('button', { name: /Generate draft/ }))
    expect(await within(describe).findByText(/not responding right now/)).toBeInTheDocument()
    await user.click(within(describe).getByRole('button', { name: /Try again/ }))
    await waitFor(() => expect(keys).toHaveLength(2))
    expect(keys[1]).not.toBe(keys[0])
  })

  it('shows a paused profile in Settings > AI and offers only the remaining profiles', async () => {
    aiMock.trippedAuto = true
    signInAs('wade.admin')
    const user = userEvent.setup()
    renderApp(`/w/${WS}/settings`)
    await user.click(await screen.findByRole('tab', { name: /^AI$/ }))
    const paused = await screen.findByTestId('ai-paused')
    expect(paused).toHaveTextContent(/anthropic\/claude-opus-9/)
    expect(paused).toHaveTextContent(/paused until 00:00 UTC/)
    const settings = screen.getByTestId('ai-settings')
    expect(within(settings).queryByText(/Varies per request/)).not.toBeInTheDocument()
  })

  it('formats small costs without rounding them to zero', () => {
    expect(formatUsd(null)).toBe('not reported')
    expect(formatUsd(0.0021)).toBe('USD 0.0021')
    expect(formatUsd(0.00001)).toBe('under USD 0.0001')
    expect(formatUsd(1.234)).toBe('USD 1.23')
  })
})
