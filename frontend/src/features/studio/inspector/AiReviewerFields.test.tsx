import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import { MOCK_AI_SKILL, MOCK_REVIEW_SKILL } from '@/mocks/aiSkills'
import { signInAs, WS } from '@/test/utils'
import purchase from '../../../../../examples/purchase-approval.json'
import { StudioProvider, useStudio } from '../StudioContext'
import { normalizeDefinition, type Definition, type WorkflowNode } from '../model/types'
import { eligibleReviewerSkills, reviewerBlocker, skillAnswers } from './AiReviewerFields'
import { ApprovalInspector } from './SimpleInspectors'

function renderManager(definition: Definition = normalizeDefinition(structuredClone(purchase))) {
  signInAs('dana.designer')
  const store = createStudioStore()
  store.getState().load({ workflowId: 'workflow', revision: 1, definition })
  function Inspector() {
    const node = useStudio((s) => s.definition.nodes.find((n) => n.id === 'manager')!)
    return <ApprovalInspector node={node} />
  }
  render(
    <QueryClientProvider client={new QueryClient()}>
      <StudioProvider value={{ store, saver: {} as DraftSaver, wsId: WS, workflowId: 'workflow', readOnly: false, connections: { responseType: () => undefined } }}>
        <Inspector />
      </StudioProvider>
    </QueryClientProvider>,
  )
  return () => store.getState().definition.nodes.find((n) => n.id === 'manager')!.config
}

describe('AI reviewer of an approval step (docs/build/S152)', () => {
  it('offers only approved skills that answer approve and reject', () => {
    expect(skillAnswers(MOCK_REVIEW_SKILL.versions[0]!.document as never)).toEqual(['approve', 'reject'])
    expect(eligibleReviewerSkills([MOCK_AI_SKILL, MOCK_REVIEW_SKILL]).map((s) => s.key)).toEqual(['purchase-review'])
    const recommend = { ...MOCK_REVIEW_SKILL, key: 'loan-advice', mode: 'recommend' as const, versions: [{ ...MOCK_REVIEW_SKILL.versions[0]!,
      document: { mode: 'recommend', outcomes: [{ key: 'approve' }, { key: 'reject' }, { key: 'escalate' }] } }] }
    expect(eligibleReviewerSkills([recommend]).map((s) => s.key)).toEqual(['loan-advice'])
    expect(eligibleReviewerSkills([{ ...MOCK_REVIEW_SKILL, approvedVersion: null }])).toEqual([])
  })

  it('refuses steps one AI answer cannot decide', () => {
    const node = (config: Record<string, unknown>) => ({ id: 'a', type: 'approval', label: 'A', config }) as unknown as WorkflowNode
    expect(reviewerBlocker(node({ multiInstance: { approvers: 2 } }))).toContain('several people')
    expect(reviewerBlocker(node({ perItem: { itemsRef: '/input/lines' } }))).toContain('list item')
    expect(reviewerBlocker(node({ fieldAccess: { costCenter: 'required' } }))).toContain('fill in fields')
    expect(reviewerBlocker(node({ capture: { required: ['po'] } }))).toContain('fill in fields')
    expect(reviewerBlocker(node({ capture: { properties: { po: {} } } }))).toBeNull()
  })

  it('picks the skill, autonomy, threshold and what is sent, and removes the reviewer again', async () => {
    const config = renderManager()
    const user = userEvent.setup()
    const picker = await screen.findByLabelText(/^Reviewing skill/)
    await screen.findByRole('option', { name: /Purchase review/ })
    expect([...picker.querySelectorAll('option')].map((o) => o.textContent)).toEqual(['No AI reviewer', 'Purchase review (classify) - v1'])
    await user.selectOptions(picker, 'purchase-review')
    expect(config().aiReviewer).toEqual({ skill: { key: 'purchase-review', version: 1 }, autonomy: 'suggest', inputs: {} })

    const section = screen.getByRole('group', { name: 'What the AI reviewer is sent' })
    await user.selectOptions(within(section).getByLabelText(/^purpose/), '/input/purpose')
    await user.selectOptions(within(section).getByLabelText(/^Redaction of purpose/), 'maskCardNumbersAndEmails')
    await user.selectOptions(screen.getByLabelText(/^How much the AI reviewer may decide/), 'autoWithReview')
    const threshold = screen.getByLabelText(/^Confidence threshold/)
    await user.clear(threshold)
    await user.type(threshold, '0.9')
    await user.tab()
    expect(config().aiReviewer).toEqual({ skill: { key: 'purchase-review', version: 1 }, autonomy: 'autoWithReview', minConfidence: 0.9,
      inputs: { purpose: '/input/purpose' }, redaction: { purpose: 'maskCardNumbersAndEmails' } })
    // Above the version's promotion the compiler's reason shows.
    await user.selectOptions(screen.getByLabelText(/^How much the AI reviewer may decide/), 'auto')
    expect(screen.getByText(/promoted up to "autoWithReview"/)).toBeInTheDocument()

    await user.selectOptions(picker, '')
    expect(config().aiReviewer).toBeUndefined()
  })

  it('cannot add a reviewer to a step with several approvers', async () => {
    const def = normalizeDefinition(structuredClone(purchase))
    def.nodes.find((n) => n.id === 'manager')!.config.multiInstance = { approvers: 2 }
    renderManager(def)
    await screen.findByRole('option', { name: /Purchase review/ })
    expect(screen.getByLabelText(/^Reviewing skill/)).toBeDisabled()
    expect(screen.getByText(/needs several people to approve/)).toBeInTheDocument()
  })
})
