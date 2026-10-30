import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { normalizeDefinition, type Definition } from '@/features/studio/model/types'
import { db, PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import { server } from '@/test/server'
import { renderAppReady, signInAs, WS } from '@/test/utils'
import purchase from '../../../../examples/purchase-approval.json'
import type { AiDraftEditChange } from './api'
import { applyDraftChanges } from './draftEdits'
import { aiMock } from './mocks'

const EDIT = `/w/${WS}/workflows/${PURCHASE_WORKFLOW_ID}/edit`
const latest = () => db.workflows.get(PURCHASE_WORKFLOW_ID)!.drafts.at(-1)!.definition

async function openPanel() {
  await screen.findByText(/Draft revision 1/)
  return screen.findByTestId('authoring-panel')
}

const change = (id: string, operations: unknown[]): AiDraftEditChange => ({ id, summary: id, operations: operations as AiDraftEditChange['operations'], dependsOn: [] })

describe('applying the S124 operations', () => {
  it('sets a field rule, form groups and a step form only while their targets are unchanged', () => {
    const def: Definition = normalizeDefinition(purchase)
    const manager = structuredClone(def.nodes.find((n) => n.id === 'manager')!)
    const outcome = applyDraftChanges(def, [
      change('c1', [{ op: 'setFieldRule', key: 'purpose', rule: 'visibleWhen', condition: { fieldRef: '/input/amount', operator: 'gte', value: 5000 }, before: null }]),
      change('c2', [{ op: 'setFormGroups', groups: [{ id: 'why', title: 'Why', fields: ['purpose'] }], before: null }]),
      change('c3', [{ op: 'setStepFieldAccess', id: 'manager', config: { fieldAccess: { costCenter: 'hidden' } }, before: manager }]),
      change('c4', [{ op: 'setFieldRule', key: 'title', rule: 'requiredWhen', condition: null, before: { fieldRef: '/input/amount', operator: 'gte', value: 1 } }]),
    ], new Set(['c1', 'c2', 'c3', 'c4']))
    expect(outcome.applied).toEqual(['c1', 'c2', 'c3'])
    expect(outcome.skipped[0]?.reason).toContain('changed since the proposal')
    expect(outcome.def.inputUi?.visibleWhen?.purpose).toEqual({ fieldRef: '/input/amount', operator: 'gte', value: 5000 })
    expect(outcome.def.inputUi?.layout?.sections?.[0]?.title).toBe('Why')
    expect(outcome.def.nodes.find((n) => n.id === 'manager')?.config.fieldAccess).toEqual({ costCenter: 'hidden' })
  })

  it('adds a decision with its table and a call with its connection, and places both', () => {
    const def: Definition = normalizeDefinition(purchase)
    def.dependencies.connections = []
    const outcome = applyDraftChanges(def, [
      change('c1', [{ op: 'addDecision', id: 'riskPolicy', label: 'Rate the risk', table: { key: 'risk-policy', version: 1, hitPolicy: 'UNIQUE', rows: [], default: { risk: 'low' } } }]),
      change('c2', [{ op: 'addCall', id: 'confirm', label: 'Confirm', config: { connectionRef: 'procurement', operation: 'createOrder', inputMapping: {}, resultMapping: {}, timeoutSeconds: 20 }, definitionVersion: 3, addsDependency: true }]),
      change('c3', [{ op: 'addDecision', id: 'again', label: 'Again', table: { key: 'amount-policy', rows: [], default: {} } }]),
    ], new Set(['c1', 'c2', 'c3']))
    expect(outcome.applied).toEqual(['c1', 'c2'])
    expect(outcome.def.decisions.map((d) => d.key)).toContain('risk-policy')
    expect(outcome.def.nodes.find((n) => n.id === 'riskPolicy')?.config).toEqual({ decisionRef: 'risk-policy' })
    expect(outcome.def.dependencies.connections).toEqual([{ key: 'procurement', definitionVersion: 3 }])
    expect(outcome.def.uiLayout?.positions.confirm).toBeDefined()
  })
})

describe('one AI conversation (S124)', () => {
  beforeEach(() => { localStorage.setItem('orqix.studio.view.aiExpanded', 'true') })
  afterEach(() => { aiMock.enabled = true })

  it('asks before it drafts, drafts on "Just draft it", and saves the conversation with the draft', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    const bodies: Array<Record<string, unknown>> = []
    const saves: unknown[] = []
    server.events.on('request:start', ({ request }) => {
      if (request.url.includes('/ai/draft-edits')) void request.clone().json().then((b) => bodies.push(b as Record<string, unknown>))
      if (request.url.includes('/ai/conversation') && request.method === 'PUT') void request.clone().json().then((b) => saves.push(b))
    })
    await renderAppReady(EDIT)
    const panel = await openPanel()
    await user.type(within(panel).getByLabelText('What should change?'), 'Help me add a director approval with a PO number.')
    await user.click(within(panel).getByRole('button', { name: /Send/ }))
    const questions = await within(panel).findByTestId('assistant-questions')
    expect(within(questions).getByRole('list', { name: 'Questions from the AI' }).children).toHaveLength(2)
    expect(within(panel).getByText(/The AI asks 2 questions first/)).toBeInTheDocument()

    await user.click(within(questions).getByRole('button', { name: 'Just draft it' }))
    expect(await within(panel).findByText(/proposed change.* to review/)).toBeInTheDocument()
    await waitFor(() => expect(bodies).toHaveLength(2))
    expect(bodies[0]).toMatchObject({ noQuestions: false })
    expect(bodies[1]).toMatchObject({ noQuestions: true, instruction: 'Help me add a director approval with a PO number.' })
    expect(within(panel).getAllByText(/Step form of “Review request”: asks for PO number/).length).toBeGreaterThan(0)
    await waitFor(() => expect(saves.length).toBeGreaterThanOrEqual(2))
    const last = saves.at(-1) as { turns: Array<{ role: string; text: string; questions?: string[] }> }
    expect(last.turns).toHaveLength(4)
    expect(last.turns[1]?.questions).toHaveLength(2)
    expect(last.turns[1]?.text).toContain('Questions:')
    server.events.removeAllListeners()
    expect(latest().nodes.find((n) => n.id === 'manager')?.config.capture).toBeUndefined()
  })

  it('restores a saved conversation read-only and clears it for everyone', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    let deleted = false
    server.use(
      http.get('*/api/v1/workspaces/:ws/workflows/:id/ai/conversation', () => HttpResponse.json({
        turns: [
          { role: 'author', text: 'Describe: a purchase approval for laptops', mode: 'regenerate' },
          { role: 'assistant', text: 'I drafted this workflow. Assumptions: managers approve.', proposal: { changes: [{ id: 'c1', summary: 'Old change', operations: [{ op: 'renameStep', id: 'manager', label: 'X' }], dependsOn: [] }] } },
        ], updatedAt: '2026-10-30T10:00:00Z', version: 3,
      })),
      http.delete('*/api/v1/workspaces/:ws/workflows/:id/ai/conversation', () => { deleted = true; return new HttpResponse(null, { status: 204 }) }),
    )
    await renderAppReady(EDIT)
    const panel = await openPanel()
    expect(await within(panel).findByText('Describe: a purchase approval for laptops')).toBeInTheDocument()
    expect(within(panel).getByText(/From an earlier session\. Ask again/)).toBeInTheDocument()
    expect(within(panel).queryByRole('button', { name: /Apply/ })).toBeNull()
    await user.click(within(panel).getByRole('button', { name: 'Clear conversation' }))
    await waitFor(() => expect(deleted).toBe(true))
    expect(await within(panel).findByText(/Conversation cleared, also for the other people/)).toBeInTheDocument()
  })
})
