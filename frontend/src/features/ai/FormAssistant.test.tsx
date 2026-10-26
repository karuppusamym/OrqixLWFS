import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import purchase from '../../../../examples/purchase-approval.json'
import { StudioProvider } from '@/features/studio/StudioContext'
import { normalizeDefinition } from '@/features/studio/model/types'
import { PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import { server } from '@/test/server'
import { signInAs, WS } from '@/test/utils'
import { aiMock } from './mocks'
import { applyFormChanges, FormAssistant } from './FormAssistant'
import type * as Api from './api'
import type * as Workspace from '@/app/workspace'
import type { AiFormChange } from './api'

const ai = vi.hoisted(() => ({ enabled: true }))
vi.mock('@/app/workspace', async (original) => ({ ...(await original<typeof Workspace>()), useInWorkspace: () => true }))
vi.mock('./api', async (original) => ({ ...(await original<typeof Api>()), useAiEnabled: () => ai.enabled }))

function setup(readOnly = false) {
  const store = createStudioStore()
  store.getState().load({ workflowId: PURCHASE_WORKFLOW_ID, revision: 1, definition: normalizeDefinition(purchase) })
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><StudioProvider value={{ store, saver: {} as DraftSaver, wsId: WS, workflowId: PURCHASE_WORKFLOW_ID, readOnly, connections: { responseType: () => undefined } }}><FormAssistant /></StudioProvider></QueryClientProvider>)
  return store
}

describe('AI form assistant (docs/build/S90, G4)', () => {
  beforeEach(() => { signInAs('dana.designer'); ai.enabled = true; aiMock.enabled = true })
  afterEach(() => { ai.enabled = true; aiMock.enabled = true })

  it('is hidden when AI is not configured and for read-only drafts', () => {
    ai.enabled = false
    setup()
    expect(screen.queryByRole('button', { name: /Suggest questions with AI/ })).not.toBeInTheDocument()
    ai.enabled = true
    setup(true)
    expect(screen.queryByRole('button', { name: /Suggest questions with AI/ })).not.toBeInTheDocument()
  })

  it('shows validated suggestions as a reviewable difference and applies only what the designer chooses', async () => {
    const store = setup(), user = userEvent.setup()
    const before = structuredClone(store.getState().definition)
    await user.click(screen.getByRole('button', { name: /Suggest questions with AI/ }))
    const panel = screen.getByTestId('ai-form-assistant')
    const suggest = within(panel).getByRole('button', { name: 'Suggest changes' })
    expect(suggest).toBeDisabled()
    await user.type(within(panel).getByLabelText('Describe what the form should ask'), 'Add a cost code and a phone, and a backreference.')
    await user.click(suggest)
    expect(await within(panel).findByRole('status')).toHaveTextContent('2 suggested changes to review. Nothing has been applied.')
    // Nothing is applied by suggesting.
    expect(store.getState().definition).toEqual(before)
    const cost = within(panel).getByTestId('ai-form-change-costCode')
    expect(within(cost).getByRole('list', { name: /Differences for Cost code/ })).toHaveTextContent('Pattern: ^[A-Z]{2}-\\d{4}$')
    expect(within(panel).getByRole('list', { name: 'Rejected suggestions' })).toHaveTextContent('Backreferences are not supported')
    const apply = within(panel).getByRole('button', { name: /Apply 0 selected changes/ })
    expect(apply).toBeDisabled()
    await user.click(within(cost).getByRole('checkbox'))
    await user.click(within(panel).getByRole('button', { name: 'Apply 1 selected change' }))
    const def = store.getState().definition
    expect(def.inputSchema.properties.costCode).toMatchObject({ pattern: '^[A-Z]{2}-\\d{4}$', patternMessage: 'Enter a code like AB-1234.' })
    expect(def.inputSchema.required).toContain('costCode')
    expect(def.inputSchema.properties.phone).toBeUndefined()
    expect(def.inputUi?.order?.at(-1)).toBe('costCode')
    expect(within(panel).getByRole('status')).toHaveTextContent('Applied 1 change. Use Undo to revert.')
    store.getState().undo()
    expect(store.getState().definition.inputSchema).toEqual(before.inputSchema)
  })

  it('reports problems in an alert and keeps the form unchanged', async () => {
    server.use(http.post('*/api/v1/workspaces/:ws/workflows/:id/ai/form-suggestions', () => HttpResponse.json({ type: 'x', title: 'AI assistance is not configured', status: 422, code: 'AI_NOT_CONFIGURED', correlationId: 'c1' }, { status: 422, headers: { 'Content-Type': 'application/problem+json' } })))
    const store = setup(), user = userEvent.setup()
    const before = structuredClone(store.getState().definition)
    await user.click(screen.getByRole('button', { name: /Suggest questions with AI/ }))
    await user.type(screen.getByLabelText('Describe what the form should ask'), 'Add a cost code question please.')
    await user.click(screen.getByRole('button', { name: 'Suggest changes' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(store.getState().definition).toEqual(before)
  })

  it('skips changes whose question changed since the suggestion', () => {
    const def = normalizeDefinition(purchase)
    const outcome = applyFormChanges(def, ([
      { op: 'update', key: 'title', field: { type: 'string', title: 'New' }, required: true, reason: 'r', before: { type: 'string', title: 'Old' }, wasRequired: true },
      { op: 'remove', key: 'purpose', required: false, reason: 'r', before: def.inputSchema.properties.purpose, wasRequired: true },
      { op: 'add', key: 'amount', field: { type: 'boolean', title: 'x' }, required: false, reason: 'r' },
    ] as unknown) as AiFormChange[])
    expect(outcome.applied).toEqual(['purpose'])
    expect(outcome.skipped).toEqual(['title', 'amount'])
    expect(outcome.def.inputSchema.required).not.toContain('purpose')
    expect(outcome.def.inputUi?.order).not.toContain('purpose')
  })
})
