import { ReactFlow, ReactFlowProvider } from '@xyflow/react'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import purchase from '../../../../examples/purchase-approval.json'
import { runFrames } from '../runs/diagram/RunDiagram'
import type { RunPath } from '../runs/diagram/api'
import { CanvasView } from './CanvasView'
import { CallFailureErrorFields, setRaiseOnFailure } from './inspector/ScopeInspectors'
import { canConnect } from './model/ops'
import { normalizeDefinition, type Definition, type WorkflowNode } from './model/types'
import { FRAME_LABEL, FRAME_PAD, ScopeFrames, frameBox, framesOf } from './ScopeFrames'
import { StudioProvider, useStudio } from './StudioContext'

function withScopes(): Definition {
  const d = normalizeDefinition(purchase)
  d.subprocesses = [
    { id: 'order', label: 'Order', type: 'embedded', nodes: ['procure', 'notify'], onError: { to: 'notifyRejected', errorCode: 'ORDER_REJECTED' } },
    { id: 'review', label: 'Review', type: 'embedded', nodes: ['manager'], timeoutAfter: 'P5D', onTimeout: 'notifyRejected' },
    { id: 'withdraw', label: 'Withdraw', type: 'event', nodes: ['rejected'], trigger: { type: 'signal', signal: 'withdraw' }, interrupting: true },
  ]
  return d
}

function renderStudio(ui: React.ReactNode, definition: Definition) {
  const store = createStudioStore()
  store.getState().load({ workflowId: 'workflow', revision: 1, definition })
  render(<StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly: false, connections: { responseType: () => undefined } }}>{ui}</StudioProvider>)
  return store
}

function Live({ id, view }: { id: string; view: (node: WorkflowNode) => React.ReactNode }) {
  const node = useStudio((s) => s.definition.nodes.find((n) => n.id === id))
  return node ? <>{view(node)}</> : null
}

describe('section and handler frames (S66)', () => {
  it('frames the measured steps with room for the label, and nothing when no member is drawn', () => {
    const nodes = [
      { id: 'a', position: { x: 100, y: 50 }, measured: { width: 200, height: 100 } },
      { id: 'b', position: { x: 400, y: 300 } },
      { id: 'c', position: { x: 9000, y: 9000 }, measured: { width: 10, height: 10 } },
    ]
    // b has no measured size yet: the default card size is used.
    expect(frameBox(nodes, ['a', 'b'])).toEqual({ x: 100 - FRAME_PAD, y: 50 - FRAME_PAD - FRAME_LABEL, width: 400 + 296 - 100 + 2 * FRAME_PAD, height: 300 + 128 - 50 + 2 * FRAME_PAD + FRAME_LABEL })
    expect(frameBox(nodes, ['missing'])).toBeNull()
  })

  it('says what a section or handler does', () => {
    const frames = framesOf(withScopes())
    expect(frames.map((f) => [f.kind, f.title, f.caption])).toEqual([
      ['section', 'Section: Order', 'error ORDER_REJECTED → Tell requester it was rejected'],
      ['section', 'Section: Review', 'time limit P5D → Tell requester it was rejected'],
      ['handler', 'Handler: Withdraw', 'on signal withdraw · stops the request'],
    ])
  })

  it('draws a frame per section and handler on the studio canvas, behind the steps', async () => {
    renderStudio(<div style={{ width: 1200, height: 800 }}><CanvasView groupNames={{}} onBranchConnect={() => undefined} /></div>, withScopes())
    const order = await screen.findByTestId('scope-frame-order')
    expect(order).toHaveAttribute('data-kind', 'section')
    expect(order).toHaveAttribute('aria-hidden', 'true')
    expect(order).toHaveTextContent('Section: Order · error ORDER_REJECTED → Tell requester it was rejected')
    expect(screen.getByTestId('scope-frame-withdraw')).toHaveAttribute('data-kind', 'handler')
    // It sits in the flow's viewport (moves and zooms with the steps) and is not a step: no extra node, no focus stop.
    expect(order.closest('.react-flow__viewport-portal')).not.toBeNull()
    expect(order.closest('.react-flow__node')).toBeNull()
    expect(document.querySelectorAll('.react-flow__node')).toHaveLength(normalizeDefinition(purchase).nodes.length)
    // The steps say which section they are in.
    const card = document.querySelector('.react-flow__node[data-id="procure"]')
    expect(card?.getAttribute('aria-label')).toMatch(/, in the section Order$/)
  })

  it('draws no frames for a workflow without sections or handlers', async () => {
    renderStudio(<div style={{ width: 1200, height: 800 }}><CanvasView groupNames={{}} onBranchConnect={() => undefined} /></div>, normalizeDefinition(purchase))
    await waitFor(() => expect(document.querySelectorAll('.react-flow__node').length).toBeGreaterThan(0))
    expect(document.querySelector('.orqix-scope-frame')).toBeNull()
  })

  it('shows on the run diagram whether the request is inside, has left or never entered each frame', () => {
    const path = {
      runId: 'r', lifecycle: 'completed', current: [], visited: [], historyTruncated: false,
      taken: ['order__onError'],
      diagram: {
        nodes: [
          { id: 'manager', kind: 'approval', label: 'Manager review', position: { x: 0, y: 0 } },
          { id: 'procure', kind: 'http', label: 'Order', position: { x: 400, y: 0 } },
          { id: 'sorry', kind: 'notification', label: 'Tell the requester', position: { x: 800, y: 0 } },
        ],
        transitions: [],
        scopes: [
          { id: 'order', label: 'Order', type: 'embedded', nodes: ['procure'], trigger: null, interrupting: null, routes: [{ id: 'order__onError', from: 'order', to: 'sorry', on: 'error', label: null }] },
          { id: 'review', label: 'Review', type: 'embedded', nodes: ['manager'], trigger: null, interrupting: null, routes: [] },
          { id: 'withdraw', label: 'Withdraw', type: 'event', nodes: ['sorry'], trigger: 'signal', interrupting: true, routes: [] },
        ],
      },
      scopeVisits: [
        { scopeId: 'order', startedAt: '2026-10-18T10:00:00Z', endedAt: '2026-10-18T10:01:00Z', how: 'error' },
        { scopeId: 'review', startedAt: '2026-10-18T09:00:00Z', endedAt: null, how: null },
      ],
    } as unknown as RunPath
    const frames = runFrames(path)
    expect(frames.map((f) => [f.id, f.state, f.caption])).toEqual([
      ['order', 'passed', 'left because a step raised an error, continued at Tell the requester'],
      ['review', 'active', 'in progress'],
      ['withdraw', 'idle', 'not started'],
    ])
    render(
      <ReactFlowProvider>
        <div style={{ width: 1200, height: 600 }}>
          <ReactFlow nodes={path.diagram.nodes.map((n) => ({ id: n.id, position: n.position!, data: {} }))} edges={[]}>
            <ScopeFrames frames={frames} />
          </ReactFlow>
        </div>
      </ReactFlowProvider>,
    )
    expect(screen.getByTestId('scope-frame-order')).toHaveAttribute('data-state', 'passed')
    expect(screen.getByTestId('scope-frame-review')).toHaveAttribute('data-state', 'active')
    expect(screen.getByTestId('scope-frame-withdraw')).toHaveAttribute('data-state', 'idle')
  })
})

describe('a call that raises an error for its section when it fails (S66)', () => {
  it('is offered only inside a section with an error route, uses its code, removes the Failed route and refuses a new one', async () => {
    const definition = withScopes()
    definition.nodes.push({ id: 'failedNote', type: 'notification', label: 'Order failed', config: { channel: 'inApp', recipient: 'initiator', message: 'Failed' } })
    definition.transitions.push({ id: 'f1', from: 'procure', to: 'failedNote', on: 'failed' })
    const store = renderStudio(<><Live id="procure" view={(n) => <CallFailureErrorFields node={n} />} /><Live id="manager" view={(n) => <CallFailureErrorFields node={n} />} /></>, definition)
    const user = userEvent.setup()
    // Only the call in the Order section is offered the setting.
    const box = screen.getByRole('checkbox', { name: /Raise an error for the section's error route/ })
    expect(screen.getAllByRole('checkbox')).toHaveLength(1)
    expect(screen.getByText(/The section Order then stops and the request continues at Tell requester it was rejected/)).toBeInTheDocument()
    await user.click(box)
    const procure = () => store.getState().definition.nodes.find((n) => n.id === 'procure')!
    expect(procure().config.raiseErrorOnFailure).toBe('ORDER_REJECTED')
    expect(store.getState().definition.transitions.some((t) => t.id === 'f1')).toBe(false)
    expect(canConnect(store.getState().definition, { from: 'procure', to: 'failedNote', on: 'failed' }).ok).toBe(false)
    // A code the section does not catch is flagged.
    const code = screen.getByLabelText(/^Error code/)
    await user.clear(code)
    await user.type(code, 'other_code')
    await user.tab()
    expect(procure().config.raiseErrorOnFailure).toBe('OTHER_CODE')
    expect(within(code.closest('div')!.parentElement!).getByText(/does not catch this code/)).toBeInTheDocument()
    await user.click(screen.getByRole('checkbox', { name: /Raise an error for the section's error route/ }))
    expect(procure().config.raiseErrorOnFailure).toBeUndefined()
  })

  it('leaves definitions without the opt-in untouched', () => {
    const d = normalizeDefinition(purchase)
    expect(setRaiseOnFailure(d, 'procure', undefined).nodes.find((n) => n.id === 'procure')?.config).toEqual(d.nodes.find((n) => n.id === 'procure')?.config)
    expect(canConnect(d, { from: 'procure', to: 'notifyRejected', on: 'failed' }).ok).toBe(true)
  })
})
