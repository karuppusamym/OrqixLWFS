import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it } from 'vitest'
import { mockAiStore } from '@/mocks/aiSkills'
import { userByName } from '@/mocks/state'
import { server } from '@/test/server'
import { renderApp, signInAs, WS } from '@/test/utils'
import { evaluationPolling } from './api'

const problem = (status: number, code: string, detail: string) =>
  HttpResponse.json({ status, code, title: detail, detail }, { status, headers: { 'Content-Type': 'application/problem+json' } })

describe('AI skill detail: versions, four-eyes approval, evaluations, promotions (S146 part D)', () => {
  afterEach(() => {
    evaluationPolling.intervalMs = 2000
  })

  it('lets a different approver approve a submitted version only with a reason', async () => {
    signInAs('paul.publisher')
    const user = userEvent.setup()
    renderApp(`/w/${WS}/ai-skills/refund-reason`)
    expect(await screen.findByRole('heading', { level: 1, name: 'Refund reason' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit draft' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Approve version' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }))
    expect(within(dialog).getByText('Say why this skill version may be used.')).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText(/Reason/), 'Checked the outcomes with the refunds team')
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const version = screen.getByRole('region', { name: 'Version 1' })
    expect(await within(version).findByText('Approved', { selector: 'span' })).toBeInTheDocument()
    expect(mockAiStore().skills.find((s) => s.key === 'refund-reason')?.versions[0]?.approverId).toBe(userByName('paul.publisher').id)
  })

  it('tells the author with the approve grant why they cannot approve, and shows the server refusal', async () => {
    signInAs('paul.publisher')
    mockAiStore().skills.find((s) => s.key === 'refund-reason')!.versions[0]!.authorId = userByName('paul.publisher').id
    renderApp(`/w/${WS}/ai-skills/refund-reason`)
    expect(await screen.findByTestId('self-approval-hint')).toHaveTextContent('You wrote this version, so you cannot approve it')
    expect(screen.queryByRole('button', { name: 'Approve version' })).not.toBeInTheDocument()
  })

  it('shows the four-eyes refusal and state conflicts of the server in the approval dialog', async () => {
    signInAs('paul.publisher')
    const user = userEvent.setup()
    server.use(http.post('*/api/v1/workspaces/:ws/ai-skills/:key/versions/:v/approve', () => problem(403, 'SELF_APPROVAL_DENIED', 'A skill version must be approved by someone other than its author.')))
    renderApp(`/w/${WS}/ai-skills/refund-reason`)
    await user.click(await screen.findByRole('button', { name: 'Approve version' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText(/Reason/), 'Looks right')
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }))
    expect(await within(dialog).findByTestId('ai-problem')).toHaveTextContent('You are the author of this change, so someone else who may approve AI skills has to approve it (four eyes).')

    server.use(http.post('*/api/v1/workspaces/:ws/ai-skills/:key/versions/:v/approve', () => problem(409, 'AI_SKILL_STATE_CONFLICT', 'Only a submitted version can be approved.')))
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }))
    await waitFor(() => expect(within(dialog).getByTestId('ai-problem')).toHaveAttribute('data-code', 'AI_SKILL_STATE_CONFLICT'))
    expect(within(dialog).getByTestId('ai-problem')).toHaveTextContent('Reason: Only a submitted version can be approved.')
  })

  it('designers submit drafts; people without the grant see who must approve', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    const refund = mockAiStore().skills.find((s) => s.key === 'refund-reason')!
    refund.versions[0]!.status = 'draft'
    renderApp(`/w/${WS}/ai-skills/refund-reason`)
    expect(await screen.findByRole('button', { name: 'Edit draft' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'New version' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Submit for approval' }))
    expect(await screen.findByTestId('approve-hint')).toHaveTextContent('ai.approve-skill')
    expect(refund.versions[0]!.status).toBe('submitted')
  })

  it('renders the evaluation metrics against the targets and follows a new run until it ends', async () => {
    signInAs('dana.designer')
    evaluationPolling.intervalMs = 50
    const user = userEvent.setup()
    renderApp(`/w/${WS}/ai-skills/dispute-triage`)
    await user.click(await screen.findByRole('tab', { name: 'Evaluations' }))
    const metrics = await screen.findByTestId('evaluation-metrics')
    expect(within(metrics).getByText('Did not pass')).toBeInTheDocument()
    expect(within(metrics).getByText('Recall of fraudSuspected is 0.9412, below 0.95.')).toBeInTheDocument()
    expect(within(metrics).getByText('Accuracy', { selector: 'dt' }).parentElement).toHaveTextContent('93.3 %')
    expect(within(metrics).getByText('Cost per case', { selector: 'dt' }).parentElement).toHaveTextContent('USD 0.0003')
    expect(within(metrics).getByText('1840 ms')).toBeInTheDocument()
    const targets = within(metrics).getByRole('table', { name: 'Targets' })
    expect(within(within(targets).getByText('Recall of fraudSuspected').closest('tr')!).getByText('Not met')).toBeInTheDocument()
    expect(within(within(targets).getByText('Accuracy').closest('tr')!).getByText('Met')).toBeInTheDocument()
    const perLabel = within(metrics).getByRole('table', { name: 'Per label' })
    expect(within(within(perLabel).getByText('pricing').closest('tr')!).getByText('95.7 %')).toBeInTheDocument()
    const confusion = within(metrics).getByRole('table', { name: 'Confusion matrix' })
    expect(within(within(confusion).getByRole('rowheader', { name: 'pricing' }).closest('tr')!).getByText('22')).toBeInTheDocument()
    expect(within(metrics).getByRole('table', { name: /Calibration/ })).toHaveTextContent('fewer than 5 cases')
    expect(within(metrics).getByRole('table', { name: /Threshold trade-off/ })).toHaveTextContent('95 %')

    await user.click(screen.getByRole('button', { name: /Start evaluation/ }))
    expect(await screen.findByTestId('run-running')).toBeInTheDocument()
    mockAiStore().runs[0]!.finishAt = 0
    expect(await screen.findByText('Passed the targets')).toBeInTheDocument()
  })

  it('hides starting an evaluation from people without ai.evaluate', async () => {
    signInAs('vic.viewer')
    const user = userEvent.setup()
    renderApp(`/w/${WS}/ai-skills/dispute-triage`)
    await user.click(await screen.findByRole('tab', { name: 'Evaluations' }))
    expect(await screen.findByTestId('evaluate-hint')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Start evaluation/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Retire version' })).not.toBeInTheDocument()
  })

  it('lets an approver decide a promotion asked for by someone else, raising the ceiling', async () => {
    signInAs('paul.publisher')
    const user = userEvent.setup()
    renderApp(`/w/${WS}/ai-skills/dispute-triage`)
    await user.click(await screen.findByRole('tab', { name: 'Promotions' }))
    const history = await screen.findByRole('list', { name: 'Promotion history' })
    expect(within(history).getByText('Waiting for a decision')).toBeInTheDocument()
    await user.click(within(history).getByRole('button', { name: 'Approve promotion' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('228 of 200 compared cases needed for promotion')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }))
    expect(within(dialog).getByText('Give a reason for the decision.')).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText(/Reason/), 'Agreement held for a month')
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(await within(history).findByText('Approved')).toBeInTheDocument()
    expect(mockAiStore().skills[0]!.versions[0]!.autonomyCeiling).toBe('autoWithReview')
  })

  it('designers request a promotion with a reason; the requester sees the four-eyes rule', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    mockAiStore().promotions = []
    renderApp(`/w/${WS}/ai-skills/dispute-triage`)
    await user.click(await screen.findByRole('tab', { name: 'Promotions' }))
    await user.click(await screen.findByRole('button', { name: /Request promotion to automatic with review sample/ }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText(/Reason/), 'Shadow figures are above target')
    await user.click(within(dialog).getByRole('button', { name: 'Request promotion' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(await screen.findByText('A promotion of this version is waiting for a decision.')).toBeInTheDocument()
    expect(mockAiStore().promotions[0]).toMatchObject({ toAutonomy: 'autoWithReview', status: 'pending', requestedBy: userByName('dana.designer').id })
  })

  it('shows the four-eyes rule to an approver who asked for the promotion', async () => {
    signInAs('paul.publisher')
    mockAiStore().promotions[0]!.requestedBy = userByName('paul.publisher').id
    const user = userEvent.setup()
    renderApp(`/w/${WS}/ai-skills/dispute-triage`)
    await user.click(await screen.findByRole('tab', { name: 'Promotions' }))
    expect(await screen.findByTestId('promotion-four-eyes')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve promotion' })).not.toBeInTheDocument()
  })

  it('shows the shadow dashboard and runs a drift check for evaluators', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    renderApp(`/w/${WS}/ai-skills/dispute-triage`)
    await user.click(await screen.findByRole('tab', { name: 'Shadow and drift' }))
    const dashboard = await screen.findByTestId('shadow-dashboard')
    expect(within(dashboard).getByText('Agreement', { selector: 'dt' }).parentElement).toHaveTextContent('91.7 %')
    expect(within(dashboard).getByRole('table', { name: 'Shadow confusion matrix' })).toBeInTheDocument()
    expect(within(dashboard).queryByRole('button', { name: 'Clear drift' })).not.toBeInTheDocument()
    await user.click(within(dashboard).getByRole('button', { name: 'Run drift check now' }))
    expect(await within(dashboard).findByTestId('drift-result')).toHaveTextContent('No drift.')
  })
})
