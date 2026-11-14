import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { components } from '@/api/schema'
import { claim, currentRelease } from '@/mocks/engine'
import { db, userByName, type MockRun, type MockTask } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'
import { AiReviewPanel, recommendationText, suggestionText } from './AiReviewPanel'

type AiReview = components['schemas']['TaskAiReview']

const suggestion: AiReview = {
  status: 'suggested', skillKey: 'purchase-review', skillVersion: 1, autonomy: 'autoWithReview', effectiveAutonomy: 'autoWithReview',
  recommendation: 'reject', confidence: 0.62, threshold: 0.85, rationale: null, reasons: ['The vendor is on the decline list.'],
  decidedByAi: false, heldFor: 'lowConfidence', unavailableReason: null, decisionId: null, humanOutcome: null,
}

function openTask(title: string): { run: MockRun; task: MockTask } {
  for (const run of db.runs.values()) {
    if (run.input.title === title) {
      const task = run.tasks.find((t) => t.status === 'open')
      if (task) return { run, task }
    }
  }
  throw new Error(`No open task for ${title}`)
}

describe('AI reviewer suggestion on a task (docs/build/S152)', () => {
  it('says what AI suggests, why it did not decide, and offers the reasons as the comment', async () => {
    expect(recommendationText(suggestion)).toBe('Reject')
    expect(suggestionText(suggestion)).toBe('The vendor is on the decline list.')
    expect(suggestionText({ ...suggestion, rationale: 'Over budget.' })).toBe('Over budget.')
    const onUse = vi.fn()
    render(<AiReviewPanel review={suggestion} canUse onUse={onUse} />)
    expect(screen.getByRole('heading', { name: 'AI reviewer suggests: Reject (0.62)' })).toBeInTheDocument()
    expect(screen.getByText('The AI reviewer is not confident enough, so a person decides.')).toBeInTheDocument()
    expect(screen.getByRole('list', { name: 'Reasons given by the AI reviewer' })).toHaveTextContent('decline list')
    await userEvent.setup().click(screen.getByRole('button', { name: 'Use the suggestion as my comment' }))
    expect(onUse).toHaveBeenCalledWith('The vendor is on the decline list.', 'reject')
  })

  it('tells people when AI is still working, unavailable, or a person decided first', () => {
    const { rerender } = render(<AiReviewPanel review={{ ...suggestion, status: 'pending', recommendation: null, confidence: null, reasons: [] }} canUse onUse={() => {}} />)
    expect(screen.getByRole('heading', { name: /is reviewing this request/ })).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    rerender(<AiReviewPanel review={{ ...suggestion, status: 'unavailable', recommendation: null, reasons: [], unavailableReason: 'The AI provider is unavailable.' }} canUse onUse={() => {}} />)
    expect(screen.getByText('The AI provider is unavailable. A person decides.')).toBeInTheDocument()
    rerender(<AiReviewPanel review={{ ...suggestion, status: 'cancelled', recommendation: null, reasons: [] }} canUse onUse={() => {}} />)
    expect(screen.getByRole('heading', { name: /a person decided first/ })).toBeInTheDocument()
  })

  it('shows the suggestion on the task page and prefills the comment, and the approver still decides', async () => {
    signInAs('max.manager')
    const { run, task } = openTask('Replacement laptop')
    const manager = currentRelease(run).definition.nodes.find((n) => n.id === task.nodeId)!
    manager.config.aiReviewer = { skill: { key: 'purchase-review', version: 1 }, autonomy: 'suggest', inputs: { purpose: '/input/purpose' } }
    claim(run, task, userByName('max.manager'))
    const user = userEvent.setup()
    renderApp(`/w/${WS}/tasks/${task.id}?view=assigned`)
    expect(await screen.findByRole('heading', { name: 'AI reviewer suggests: Approve (0.91)' })).toBeInTheDocument()
    expect(screen.getByText(/Within the purchasing policy for this cost center/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Use the suggestion as my comment' }))
    expect(screen.getByLabelText(/^Comment/)).toHaveValue('Within the purchasing policy for this cost center, and the amount is below the team budget.')
    await user.click(screen.getByRole('button', { name: 'Approve' }))
    await waitFor(() => expect(task.decision?.comment).toContain('Within the purchasing policy'))
  })

  it('marks decisions of the AI reviewer in the decision history', async () => {
    signInAs('max.manager')
    const { run, task } = openTask('Replacement laptop')
    task.status = 'completed'
    task.decision = {
      id: '00000000-0000-7000-8000-00000000e001', taskId: task.id, runId: run.id, nodeId: task.nodeId, label: task.label,
      actor: { id: '00000000-0000-4000-8000-0000000a1001', displayName: 'AI reviewer' }, action: 'approve',
      reason: 'Within the cost center budget.', comment: null, decidedAt: '2026-11-05T09:00:00Z', policyFlags: [],
      decidedBy: 'aiReviewer', aiReview: { skillKey: 'purchase-review', skillVersion: 1, decisionId: '00000000-0000-7000-8000-00000000e002' },
    }
    renderApp(`/w/${WS}/tasks/${task.id}`)
    expect(await screen.findByText('Decided by AI reviewer')).toBeInTheDocument()
    expect(screen.getByText(/AI reviewer approved/)).toBeInTheDocument()
    expect(screen.getByText('Reason: Within the cost center budget.')).toBeInTheDocument()
  })
})
