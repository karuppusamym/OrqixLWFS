import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import purchase from '../../../../../examples/purchase-approval.json'
import { StudioProvider, useStudio } from '../StudioContext'
import { sendBackTargets, wouldCreateCycle } from '../model/graph'
import { canConnect, setLimitTarget, setMaxTraversals, setSendBack } from '../model/ops'
import { routeSlotLabel, routeText } from '../model/summary'
import { normalizeDefinition, type Definition } from '../model/types'
import { ForEachFields } from './ForEachFields'
import { SendBackFields } from './SendBackFields'

const template = (): Definition => normalizeDefinition(structuredClone(purchase))
const t8 = (def: Definition) => def.transitions.find((t) => t.id === 't8')!

describe('send back to an earlier step (CG-E01, S138)', () => {
  it('turns a path into a bounded loop path with a limitReached route to where it led before', () => {
    const result = setSendBack(template(), 't8', 'manager', 3)
    expect(result.ok).toBe(true)
    const def = result.ok ? result.def : template()
    expect(t8(def)).toMatchObject({ from: 'finance', to: 'manager', on: 'rejected', maxTraversals: 3 })
    expect(def.transitions.find((t) => t.on === 'limitReached')).toMatchObject({ from: 'finance', to: 'notifyRejected' })
    expect(routeText(def, t8(def))).toBe('Rejected (send back, at most 3 times)')
    expect(routeSlotLabel(def, undefined, 'limitReached')).toBe('When the send-back limit is reached')

    const changed = setLimitTarget(setMaxTraversals(def, 't8', 12), 'finance', 'procure')
    expect(t8(changed).maxTraversals).toBe(10)
    expect(changed.transitions.find((t) => t.on === 'limitReached')?.to).toBe('procure')

    const undone = setSendBack(changed, 't8', null)
    expect(undone.ok && t8(undone.def)).toMatchObject({ to: 'procure' })
    expect(undone.ok && undone.def.transitions.some((t) => t.on === 'limitReached')).toBe(false)
    expect(undone.ok && t8(undone.def).maxTraversals).toBeUndefined()
  })

  it('offers only the step itself and the steps before it, never the request form', () => {
    const ids = sendBackTargets(template(), 'finance').map((n) => n.id)
    expect(ids).toEqual(expect.arrayContaining(['manager', 'amountPolicy', 'route', 'finance']))
    expect(ids).not.toContain('start')
    expect(ids).not.toContain('procure')
  })

  it('still refuses drawing a loop by hand and points to the send-back setting', () => {
    const def = template()
    expect(wouldCreateCycle(def, 'finance', 'manager')).toBe(true)
    const check = canConnect(def, { from: 'notify', to: 'manager', on: 'next' })
    expect(check.ok).toBe(false)
  })

  it('edits the limit from the connection panel', async () => {
    const definition = template()
    const store = createStudioStore()
    store.getState().load({ workflowId: 'workflow', revision: 1, definition })
    function Panel() {
      const t = useStudio((s) => s.definition.transitions.find((x) => x.id === 't8')!)
      return <SendBackFields transition={t} />
    }
    render(<StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly: false, connections: { responseType: () => undefined } }}><Panel /></StudioProvider>)
    const user = userEvent.setup()
    await user.selectOptions(screen.getByLabelText(/^Send back to/), 'manager')
    expect(t8(store.getState().definition)).toMatchObject({ to: 'manager', maxTraversals: 2 })
    const max = screen.getByLabelText(/^At most \(times\)/)
    await user.clear(max)
    await user.type(max, '4')
    await user.tab()
    expect(t8(store.getState().definition).maxTraversals).toBe(4)
    await user.click(screen.getByRole('button', { name: 'Stop sending back' }))
    expect(t8(store.getState().definition)).toMatchObject({ to: 'notifyRejected' })
  })
})

describe('for each on a call (CG-E06, S138)', () => {
  it('runs a call once per row of a list, in parallel batches, and collects a result', async () => {
    const definition = template()
    definition.inputSchema.properties.lines = {
      type: 'array',
      title: 'Order lines',
      maxItems: 40,
      items: { type: 'object', additionalProperties: false, required: ['title'], properties: { title: { type: 'string', title: 'Item' } } },
    } as never
    const store = createStudioStore()
    store.getState().load({ workflowId: 'workflow', revision: 1, definition })
    function Panel() {
      const node = useStudio((s) => s.definition.nodes.find((n) => n.id === 'procure')!)
      return <ForEachFields node={node} results={['procurementId']} parallel />
    }
    render(<StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly: false, connections: { responseType: () => undefined } }}><Panel /></StudioProvider>)
    const user = userEvent.setup()
    await user.click(screen.getByLabelText('Run once for each item of a list'))
    const config = () => store.getState().definition.nodes.find((n) => n.id === 'procure')!.config.forEach as Record<string, unknown>
    expect(config()).toMatchObject({ itemsRef: '/input/lines', maxItems: 40, itemMapping: {} })
    await user.type(screen.getByLabelText(/^Item goes to input/), 'title')
    await user.tab()
    expect(config().itemMapping).toEqual({ title: 'title' })
    await user.selectOptions(screen.getByLabelText(/^Order/), 'parallel')
    expect(config().mode).toBe('parallel')
    await user.click(screen.getByLabelText(/procurementId/))
    expect(config().collect).toEqual({ procurementIdList: 'procurementId' })
    await user.selectOptions(screen.getByLabelText(/^The step is done/), 'firstSuccess')
    expect(config().completion).toEqual({ when: 'firstSuccess' })
  })
})
