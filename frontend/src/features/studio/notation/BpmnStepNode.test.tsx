import type { NodeProps } from '@xyflow/react'
import { render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { KINDS } from '../model/kinds'
import { BPMN_NOTATION } from '../model/notation'
import { SHAPE_SIZES } from '../model/shapeSizes'
import type { NodeKind } from '../model/types'
import { StepNode, type StepFlowNode, type StepNodeData } from '../StepNode'

// Handles need a React Flow store; here they render as markers so their ids and sides can be checked.
vi.mock('@xyflow/react', async (importOriginal) => ({
  ...(await importOriginal<object>()),
  Handle: ({ id, type, position }: { id: string; type: string; position: string }) => <span data-handleid={id} data-handletype={type} data-handlepos={position} />,
}))

function renderStep(data: Partial<StepNodeData> & { kind: NodeKind }, selected = false) {
  const full: StepNodeData = { label: `${KINDS[data.kind].caption} step`, subtitle: 'Summary', blockers: 0, warnings: 0, notation: 'bpmn', ...data }
  return render(<StepNode {...({ id: data.kind, selected, data: full } as unknown as NodeProps<StepFlowNode>)} />)
}

const handles = (el: HTMLElement) => [...el.querySelectorAll('[data-handleid]')].map((h) => `${h.getAttribute('data-handletype')}:${h.getAttribute('data-handleid')}:${h.getAttribute('data-handlepos')}`)

describe('steps drawn in BPMN notation (STU01)', () => {
  it.each(Object.keys(KINDS) as NodeKind[])('draws %s with its mapped shape, at its bounding box, with its name and kind as text', (kind) => {
    renderStep({ kind })
    const shape = screen.getByTestId(`node-shape-${kind}`)
    const n = BPMN_NOTATION[kind]
    expect(shape).toHaveAttribute('data-shape', n.shape)
    expect(shape).toHaveAttribute('data-shape-class', n.sizeClass)
    expect(shape.style.width).toBe(`${SHAPE_SIZES[n.sizeClass].width}px`)
    expect(shape.style.height).toBe(`${SHAPE_SIZES[n.sizeClass].height}px`)
    // The symbol itself is decoration; meaning is in words.
    expect(shape.querySelector(`svg[data-glyph="${n.shape}"]`)).toHaveAttribute('aria-hidden', 'true')
    expect(within(shape).getByText(`${KINDS[kind].caption} step`)).toBeInTheDocument()
    if (kind !== 'end') expect(within(shape).getByText(KINDS[kind].caption)).toBeInTheDocument()
    // The card is not drawn.
    expect(screen.queryByTestId(`node-card-${kind}`)).toBeNull()
  })

  it('keeps the same handle ids as the cards, on the left and right edges of every shape', () => {
    const expected: Partial<Record<NodeKind, string[]>> = {
      start: ['source:next:right'],
      branch: ['target:in:left', 'source:route:right'],
      approval: ['target:in:left', 'source:approved:right', 'source:rejected:right'],
      http: ['target:in:left', 'source:next:right', 'source:failed:right'],
      end: ['target:in:left'],
      raiseError: ['target:in:left'],
      wait: ['target:in:left', 'source:next:right'],
      parallelSplit: ['target:in:left', 'source:next:right'],
    }
    for (const [kind, ids] of Object.entries(expected)) {
      const { container, unmount } = renderStep({ kind: kind as NodeKind })
      expect(handles(container), kind).toEqual(ids)
      unmount()
    }
    const { container } = renderStep({ kind: 'waitForMessage', timeout: true })
    expect(handles(container)).toEqual(['target:in:left', 'source:next:right', 'source:timedOut:bottom'])
    // The name moves above the event so the Timed out route leaving from the bottom does not cross it.
    expect(screen.getByTestId('shape-label').className).toMatch(/bottom-full/)
  })

  it('prints a finish outcome in words with its icon, and the issue count with words for screen readers', () => {
    renderStep({ kind: 'end', label: 'Finish request', outcomeKind: 'failed', blockers: 1, warnings: 1 })
    expect(screen.getByText('Failed')).toBeInTheDocument()
    expect(screen.getByTestId('node-issues')).toHaveTextContent('2 issues')
    expect(screen.getByTestId('node-shape-end').querySelector('.bpmn-outline')).toHaveAttribute('stroke', 'var(--status-rejected-fg)')
  })

  it('marks the selected shape with the primary outline, not colour alone (thicker stroke)', () => {
    renderStep({ kind: 'branch' }, true)
    const outline = screen.getByTestId('node-shape-branch').querySelector('.bpmn-outline')
    expect(outline).toHaveAttribute('stroke', 'var(--primary)')
    expect(Number(outline?.getAttribute('stroke-width'))).toBeGreaterThan(2)
  })

  it('draws the cards when the notation is cards or not set (existing behaviour)', () => {
    renderStep({ kind: 'approval', notation: 'cards' })
    expect(screen.getByTestId('node-card-approval')).toBeInTheDocument()
    expect(screen.getByText('Approved')).toBeInTheDocument()
  })
})
