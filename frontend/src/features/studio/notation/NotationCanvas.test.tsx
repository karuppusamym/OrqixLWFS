import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import purchase from '../../../../../examples/purchase-approval.json'
import { CanvasView } from '../CanvasView'
import { KINDS } from '../model/kinds'
import { computeLayout } from '../model/layout'
import { normalizeDefinition, type Definition } from '../model/types'
import { StudioProvider } from '../StudioContext'
import { legendRows, NotationLegend } from './NotationControls'
import { NOTATION_KEY, notationKey, parseNotation, readNotation } from './useNotation'

function renderCanvas(definition: Definition = normalizeDefinition(purchase), readOnly = false) {
  const store = createStudioStore()
  store.getState().load({ workflowId: 'workflow', revision: 1, definition })
  const utils = render(
    <StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly, connections: { responseType: () => undefined } }}>
      <div style={{ width: 1200, height: 800 }}>
        <CanvasView groupNames={{}} onBranchConnect={() => undefined} />
      </div>
    </StudioProvider>,
  )
  return { store, ...utils }
}

afterEach(() => vi.restoreAllMocks())

describe('notation toggle (STU01)', () => {
  it('draws BPMN shapes by default, keeps each step focusable with its accessible name', async () => {
    renderCanvas()
    await waitFor(() => expect(document.querySelectorAll('.react-flow__node').length).toBeGreaterThan(0))
    expect(screen.getByTestId('node-shape-start')).toHaveAttribute('data-shape', 'startEvent')
    for (const approval of screen.getAllByTestId('node-shape-approval')) expect(approval).toHaveAttribute('data-shape', 'userTask')
    const route = document.querySelector('.react-flow__node[data-id="route"]') as HTMLElement
    expect(route.querySelector('[data-shape="exclusiveGateway"]')).not.toBeNull()
    expect(route).toHaveAttribute('tabindex', '0')
    expect(route.getAttribute('aria-label')).toMatch(/^Choose a path: /)
    expect(screen.getByRole('button', { name: 'BPMN shapes' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Cards' })).toHaveAttribute('aria-pressed', 'false')
    // (Edges are not drawn in jsdom, where nothing is measured: the route labels are checked in the browser, see S94.)
  })

  it('switches to the cards, remembers the choice per person and switches back', async () => {
    const user = userEvent.setup()
    const first = renderCanvas()
    await waitFor(() => expect(screen.getByTestId('node-shape-start')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Cards' }))
    expect(await screen.findByTestId('node-card-start')).toBeInTheDocument()
    expect(screen.queryByTestId('node-shape-start')).toBeNull()
    expect(window.localStorage.getItem(NOTATION_KEY)).toBe('cards')
    // The legend explains BPMN symbols, so it is offered with the shapes only.
    expect(screen.queryByTestId('notation-legend')).toBeNull()
    first.unmount()

    renderCanvas()
    expect(await screen.findByTestId('node-card-start')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'BPMN shapes' }))
    expect(await screen.findByTestId('node-shape-start')).toBeInTheDocument()
    expect(window.localStorage.getItem(NOTATION_KEY)).toBe('bpmn')
  })

  it('works when browser storage is blocked (default BPMN, the choice lasts for the page view)', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    expect(readNotation('anything')).toBe('bpmn')
    const user = userEvent.setup()
    renderCanvas()
    expect(await screen.findByTestId('node-shape-start')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Cards' }))
    expect(await screen.findByTestId('node-card-start')).toBeInTheDocument()
  })

  it('keys the preference by the signed-in person and ignores unknown values', () => {
    expect(notationKey('user-1')).toBe(`${NOTATION_KEY}:user-1`)
    expect(notationKey(null)).toBe(NOTATION_KEY)
    expect(parseNotation('cards')).toBe('cards')
    expect(parseNotation('<script>')).toBe('bpmn')
    expect(parseNotation(null)).toBe('bpmn')
  })

  it('offers the toggle on a read-only canvas too, and does not move saved positions when switching', async () => {
    const user = userEvent.setup()
    const def = normalizeDefinition(purchase)
    // A saved layout that needs no arranging in either notation, so nothing moves when the canvas opens.
    def.uiLayout = { positions: await computeLayout(def) }
    const { store } = renderCanvas(def, true)
    await waitFor(() => expect(screen.getByTestId('node-shape-start')).toBeInTheDocument())
    const before = JSON.stringify(store.getState().definition.uiLayout)
    await user.click(screen.getByRole('button', { name: 'Cards' }))
    await user.click(screen.getByRole('button', { name: 'BPMN shapes' }))
    expect(JSON.stringify(store.getState().definition.uiLayout)).toBe(before)
  })

  it('has no axe violations on the BPMN canvas (structure and names; contrast is checked in the browser)', async () => {
    const { container } = renderCanvas()
    await waitFor(() => expect(screen.getByTestId('node-shape-start')).toBeInTheDocument())
    const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })
    expect(results.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`)).toEqual([])
  })
})

describe('BPMN legend (STU01)', () => {
  it('is collapsed by default and explains every step kind exactly once', async () => {
    const user = userEvent.setup()
    render(<NotationLegend />)
    const legend = screen.getByTestId('notation-legend')
    expect(legend).not.toHaveAttribute('open')
    await user.click(within(legend).getByText('BPMN symbols'))
    expect(legend).toHaveAttribute('open')
    const listed = legendRows().flatMap((r) => r.kinds)
    expect([...listed].sort()).toEqual(Object.keys(KINDS).sort())
    const names = [...legend.querySelectorAll('li .font-semibold')].map((el) => el.textContent)
    for (const kind of Object.values(KINDS)) expect(names.some((n) => n?.split(', ').includes(kind.paletteLabel)), kind.paletteLabel).toBe(true)
    expect(within(legend).getByText(/Diamond with an X · BPMN exclusive gateway/)).toBeInTheDocument()
    expect(within(legend).getByText(/Sections and handlers/)).toBeInTheDocument()
  })
})
