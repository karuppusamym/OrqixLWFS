import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import purchase from '../../../../../examples/purchase-approval.json'
import { createStudioStore } from '@/stores/studioStore'
import type { DraftSaver } from '@/stores/draftSaver'
import { StudioProvider, useStudio } from '../StudioContext'
import { normalizeDefinition } from '../model/types'
import { StageEmailFields } from './StageEmailFields'

function setup(readOnly = false) {
  const store = createStudioStore(); store.getState().load({ workflowId: 'workflow', revision: 1, definition: normalizeDefinition(purchase) })
  function Fields() { const node = useStudio(s => s.definition.nodes.find(n => n.id === 'manager')!); return <StageEmailFields node={node} /> }
  render(<StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly, connections: { responseType: () => undefined } }}><Fields /></StudioProvider>)
  return store
}
describe('stage email authoring', () => {
  it('creates all four stage events with safe recipient defaults', async () => {
    const store = setup(); await userEvent.click(screen.getByRole('button', { name: 'Configure stage email' }))
    const templates = store.getState().definition.nodes.find(n => n.id === 'manager')!.config.notifications as Record<string, { recipients: string[] }>
    expect(Object.keys(templates)).toEqual(['assignment', 'completion', 'rejection', 'overdue'])
    expect(templates.overdue?.recipients).toEqual(['operators']); expect(templates.assignment?.recipients).toEqual(['candidateGroup'])
  })
  it('does not permit configuration from a read-only release', () => {
    setup(true); expect(screen.getByRole('button', { name: 'Configure stage email' })).toBeDisabled()
  })
})
