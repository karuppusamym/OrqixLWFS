import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import purchase from '../../../../../examples/purchase-approval.json'
import { StudioProvider, useStudio } from '../StudioContext'
import { addNode, canConnect, removeNode, renameNodeId } from '../model/ops'
import { normalizeDefinition, type Definition, type WorkflowNode } from '../model/types'
import { CompensateInspector, RaiseErrorInspector, setUndo, SignalInspector, SubprocessesInspector, UndoFields } from './ScopeInspectors'
import { ScopeList } from '../../runs/diagram/RunDiagram'

/** Renders an inspector with the live node from the store, as the real inspector does. */
function Live({ id, view }: { id: string; view: (node: WorkflowNode) => React.ReactNode }) {
  const node = useStudio((s) => s.definition.nodes.find((n) => n.id === id))
  return node ? <>{view(node)}</> : null
}

function renderWith(ui: (store: ReturnType<typeof createStudioStore>) => React.ReactNode, definition: Definition = normalizeDefinition(purchase)) {
  const store = createStudioStore()
  store.getState().load({ workflowId: 'workflow', revision: 1, definition })
  render(<StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly: false, connections: { responseType: () => undefined } }}>{ui(store)}</StudioProvider>)
  return store
}

describe('sections and handlers (S55)', () => {
  it('adds a section with members, a time limit and an error route, and a handler on a signal', async () => {
    const store = renderWith(() => <SubprocessesInspector />)
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Add a section' }))
    const section = screen.getByRole('group', { name: 'Section: Section 1' })
    const manager = store.getState().definition.nodes.find((n) => n.id === 'manager')!
    await user.click(within(section).getByRole('checkbox', { name: manager.label }))
    await user.type(within(section).getByLabelText(/^Time limit/), 'P5D')
    await user.tab()
    await user.selectOptions(within(section).getByLabelText(/^When the time limit passes/), 'notifyRejected')
    await user.selectOptions(within(section).getByLabelText(/^When a step inside raises an error/), 'notifyRejected')
    expect(store.getState().definition.subprocesses?.[0]).toEqual({ id: 'section1', label: 'Section 1', type: 'embedded', nodes: ['manager'], timeoutAfter: 'P5D', onTimeout: 'notifyRejected', onError: { to: 'notifyRejected' } })

    await user.click(screen.getByRole('button', { name: 'Add a handler' }))
    const handler = screen.getByRole('group', { name: 'Handler: Handler 2' })
    // A step already in the section cannot join the handler.
    expect(within(handler).queryByRole('checkbox', { name: manager.label })).toBeNull()
    await user.selectOptions(within(handler).getByLabelText(/^Starts on/), 'error')
    expect(store.getState().definition.subprocesses?.[1]).toMatchObject({ type: 'event', trigger: { type: 'error' }, interrupting: true })
    expect(within(handler).getByRole('checkbox', { name: /Stop the rest of the request/ })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Remove Section 1' }))
    await user.click(screen.getByRole('button', { name: 'Remove Handler 2' }))
    expect(store.getState().definition.subprocesses).toBeUndefined()
  })

  it('keeps section references when a step is renamed or deleted', () => {
    const definition = normalizeDefinition(purchase)
    definition.subprocesses = [{ id: 'review', label: 'Review', type: 'embedded', nodes: ['manager'], timeoutAfter: 'P5D', onTimeout: 'notifyRejected', onError: { to: 'notifyRejected' } }]
    const renamed = renameNodeId(definition, 'notifyRejected', 'sorry')
    expect(renamed.ok).toBe(true)
    if (!renamed.ok) return
    expect(renamed.def.subprocesses?.[0]).toMatchObject({ onTimeout: 'sorry', onError: { to: 'sorry' } })
    const removed = removeNode(renamed.def, 'sorry')
    expect(removed.subprocesses?.[0]).toEqual({ id: 'review', label: 'Review', type: 'embedded', nodes: ['manager'] })
  })
})

describe('errors, signals and automatic undo (S55)', () => {
  it('edits the signal name and the error code with their formats', async () => {
    const added = addNode(normalizeDefinition(purchase), 'waitForSignal', {})
    const node = added.def.nodes.find((n) => n.id === added.id)!
    const store = renderWith(() => <Live id={node.id} view={(n) => <SignalInspector node={n} />} />, added.def)
    const user = userEvent.setup()
    const input = screen.getByLabelText(/^Signal/)
    await user.clear(input)
    await user.type(input, 'Goods Received')
    await user.tab()
    expect(screen.getByText(/Use lowercase letters and digits/)).toBeInTheDocument()
    expect(store.getState().definition.nodes.find((n) => n.id === node.id)?.config.signal).toBe('Goods Received')
  })

  it('upper-cases error codes and refuses paths from a raised error', async () => {
    const added = addNode(normalizeDefinition(purchase), 'raiseError', {})
    const store = renderWith(() => <Live id={added.id} view={(n) => <RaiseErrorInspector node={n} />} />, added.def)
    const user = userEvent.setup()
    const input = screen.getByLabelText(/^Error code/)
    await user.clear(input)
    await user.type(input, 'supplier_blocked')
    await user.tab()
    expect(store.getState().definition.nodes.find((n) => n.id === added.id)?.config.errorCode).toBe('SUPPLIER_BLOCKED')
    expect(canConnect(store.getState().definition, { from: added.id!, to: 'notify', on: 'next' }).ok).toBe(false)
  })

  it('marks a call as the automatic undo of another, removing its paths, and lists it on the Undo step', async () => {
    const base = normalizeDefinition(purchase)
    const withUndo = addNode(base, 'http', { label: 'Cancel the order' })
    const undo = addNode(withUndo.def, 'compensate', {})
    const definition = undo.def
    definition.transitions.push({ id: 'x1', from: withUndo.id!, to: 'notify', on: 'next' })
    const store = renderWith(() => <><Live id={withUndo.id} view={(n) => <UndoFields node={n} />} /><Live id={undo.id} view={(n) => <CompensateInspector node={n} />} /></>, definition)
    const user = userEvent.setup()
    expect(screen.getByText(/No call is marked as an automatic undo/)).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText(/^This call undoes/), 'procure')
    await user.click(screen.getByRole('checkbox', { name: /Run automatically/ }))
    const call = store.getState().definition.nodes.find((n) => n.id === withUndo.id)!
    expect(call.config).toMatchObject({ compensates: 'procure', compensation: 'automatic' })
    expect(store.getState().definition.transitions.some((t) => t.from === withUndo.id || t.to === withUndo.id)).toBe(false)
    expect(canConnect(store.getState().definition, { from: 'notify', to: withUndo.id!, on: 'next' }).ok).toBe(false)
    expect(setUndo(store.getState().definition, withUndo.id!, undefined, false).nodes.find((n) => n.id === withUndo.id)?.config.compensation).toBeUndefined()
  })
})

describe('run diagram scopes (S55)', () => {
  it('says how a section was left and where the request continued', () => {
    render(<ScopeList path={{
      runId: 'r', lifecycle: 'completed', current: [], visited: [], historyTruncated: false,
      taken: ['review__onTimeout'],
      diagram: {
        nodes: [{ id: 'manager', kind: 'approval', label: 'Manager review' }, { id: 'sorry', kind: 'notification', label: 'Tell the requester' }],
        transitions: [],
        scopes: [
          { id: 'review', label: 'Review', type: 'embedded', nodes: ['manager'], trigger: null, interrupting: null, routes: [{ id: 'review__onTimeout', from: 'review', to: 'sorry', on: 'timedOut', label: null }] },
          { id: 'withdraw', label: 'Withdraw', type: 'event', nodes: [], trigger: 'signal', interrupting: true, routes: [] },
        ],
      },
      scopeVisits: [{ scopeId: 'review', startedAt: '2026-10-17T10:00:00Z', endedAt: '2026-10-17T11:00:00Z', how: 'timedOut' }],
    }} />)
    const list = screen.getByTestId('run-scopes')
    expect(list).toHaveTextContent('Section Review: left because its time limit passed, continued at Tell the requester. Steps: Manager review.')
    expect(list).toHaveTextContent('Handler Withdraw (starts on signal, stops the rest of the request): not started.')
  })
})
