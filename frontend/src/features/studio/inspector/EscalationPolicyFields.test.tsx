import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import purchase from '../../../../../examples/purchase-approval.json'
import { StudioProvider, useStudio } from '../StudioContext'
import { normalizeDefinition } from '../model/types'
import { EscalationPolicyFields, nextLevel } from './EscalationPolicyFields'

const GROUPS = [{ key: 'managers', name: 'Managers' }, { key: 'finance-approvers', name: 'Finance approvers' }]

function setup(readOnly = false) {
  const store = createStudioStore()
  store.getState().load({ workflowId: 'workflow', revision: 1, definition: normalizeDefinition(purchase) })
  function Fields() {
    const node = useStudio((state) => state.definition.nodes.find((n) => n.id === 'manager')!)
    return <EscalationPolicyFields node={node} groups={GROUPS} />
  }
  render(<StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly, connections: { responseType: () => undefined } }}><Fields /></StudioProvider>)
  return store
}

const policy = (store: ReturnType<typeof createStudioStore>) =>
  store.getState().definition.nodes.find((n) => n.id === 'manager')!.config.escalationPolicy

describe('escalation policy authoring (CG-T04, S141)', () => {
  it('adds notify, reassign and alert levels to a supervisor group and removes the policy again', async () => {
    const store = setup()
    const user = userEvent.setup()
    await user.click(screen.getByRole('checkbox', { name: 'Escalate when nobody decides' }))
    expect(policy(store)).toEqual({ levels: [{ afterHours: 8, action: 'notify', groupRef: 'finance-approvers' }] })
    expect(screen.getByText(/elapsed hours/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Add level' }))
    await user.click(screen.getByRole('button', { name: 'Add level' }))
    expect(policy(store)).toEqual({ levels: [
      { afterHours: 8, action: 'notify', groupRef: 'finance-approvers' },
      { afterHours: 16, action: 'reassign', groupRef: 'finance-approvers' },
      { afterHours: 24, action: 'alertOperators' },
    ] })
    expect(screen.queryByLabelText('Level 3: group')).not.toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Level 1: then'), 'alertOperators')
    expect((policy(store) as { levels: object[] }).levels[0]).toEqual({ afterHours: 8, action: 'alertOperators' })
    await user.click(screen.getByRole('button', { name: 'Remove level 1' }))
    await user.click(screen.getByRole('button', { name: 'Remove level 1' }))
    await user.click(screen.getByRole('button', { name: 'Remove level 1' }))
    expect(policy(store)).toBeUndefined()
  })

  it('warns when a level is not later than the one before', async () => {
    const store = setup()
    const user = userEvent.setup()
    await user.click(screen.getByRole('checkbox', { name: 'Escalate when nobody decides' }))
    await user.click(screen.getByRole('button', { name: 'Add level' }))
    const second = screen.getByLabelText('Level 2: after (hours)')
    await user.clear(second)
    await user.type(second, '4')
    await user.tab()
    expect((policy(store) as { levels: { afterHours: number }[] }).levels[1]!.afterHours).toBe(4)
    expect(screen.getByRole('alert')).toHaveTextContent('later than the one before')
  })

  it('is read-only on a published version', () => {
    setup(true)
    expect(screen.getByRole('checkbox', { name: 'Escalate when nobody decides' })).toBeDisabled()
  })

  it('suggests the next level', () => {
    expect(nextLevel([], 'finance-approvers')).toEqual({ afterHours: 8, action: 'notify', groupRef: 'finance-approvers' })
    expect(nextLevel([{ afterHours: 2150, action: 'reassign', groupRef: 'x' }], 'y')).toEqual({ afterHours: 2158, action: 'alertOperators' })
  })
})
