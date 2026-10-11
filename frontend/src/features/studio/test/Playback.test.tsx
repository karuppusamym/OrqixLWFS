import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import type { SimulationResult } from '@/api/types'
import { Playback } from './Playback'
import { playbackAt } from './PlaybackContext'

const entry = (seq: number, stepId: string, summary: string, extra: Record<string, unknown> = {}) => ({ seq, stepId, stepLabel: stepId, kind: 'notification', kindLabel: 'Notify someone', summary, details: {}, transitionId: `t${seq}`, nextStepId: null, ...extra })

const result = {
  source: { revision: 1, releaseNumber: null },
  outcome: { status: 'finished', businessOutcome: 'Fulfilled', outcomeKind: 'success', endStepId: 'c', summary: 'The request would finish with the outcome "Fulfilled" (successful).', attention: null },
  outputs: {},
  trace: [
    entry(1, 'a', 'The request is submitted.'),
    entry(2, 'b', 'Routed to Finance because Amount (USD 5,000) is at least USD 5,000.', { kind: 'branch', kindLabel: 'Choose a path', details: { routes: [{ transitionId: 't2', label: 'USD 5,000 or more', matched: true }] } }),
    entry(3, 'c', 'The request finishes: "Fulfilled" (successful).', { transitionId: null }),
  ],
  warnings: [],
  coverage: { steps: { visited: 3, total: 3, notVisited: [] }, routes: { taken: 1, total: 2, notTaken: [] }, rules: { matched: 0, total: 0, notMatched: [] }, summary: 'x' },
  limits: 'This is a dry run.',
} as unknown as SimulationResult

function Harness() {
  const [index, setIndex] = useState(0)
  return <Playback result={result} index={index} onIndex={setIndex} />
}

describe('playback of a test run', () => {
  it('explains one step at a time and lists all of them as text', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    expect(screen.getByTestId('test-outcome')).toHaveTextContent('Fulfilled')
    expect(screen.getByText('Step 1 of 3')).toBeInTheDocument()
    expect(screen.getByTestId('current-step')).toHaveTextContent('The request is submitted.')
    await user.click(screen.getByRole('button', { name: /Next step/ }))
    expect(screen.getByTestId('current-step')).toHaveTextContent('Routed to Finance because Amount (USD 5,000)')
    expect(screen.getByTestId('playback-live')).toHaveTextContent('Step 2 of 3: Routed to Finance')
    const list = screen.getByRole('list', { name: 'Steps of the test run' })
    expect(list.querySelectorAll('li')).toHaveLength(3)
    expect(screen.getByRole('button', { name: /Step 2:/ })).toHaveAttribute('aria-current', 'step')
    await user.click(screen.getByRole('button', { name: /Step 3:/ }))
    expect(screen.getByText('Step 3 of 3')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Next step/ })).toHaveAttribute('aria-disabled', 'true')
  })

  it('moves with the keyboard and stays inside the run', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    screen.getByRole('button', { name: /Next step/ }).focus()
    await user.keyboard('{ArrowRight}{ArrowRight}{ArrowRight}')
    expect(screen.getByText('Step 3 of 3')).toBeInTheDocument()
    await user.keyboard('{ArrowLeft}')
    expect(screen.getByText('Step 2 of 3')).toBeInTheDocument()
    await user.keyboard('{Home}')
    expect(screen.getByText('Step 1 of 3')).toBeInTheDocument()
    await user.keyboard('{ArrowUp}')
    expect(screen.getByText('Step 1 of 3')).toBeInTheDocument()
    await user.keyboard('{End}')
    expect(screen.getByText('Step 3 of 3')).toBeInTheDocument()
  })

  it('tells the canvas which steps and routes the run has reached so far', () => {
    const p = playbackAt(result, 1)
    expect(p.current).toBe('b')
    expect([...p.visited]).toEqual(['a', 'b'])
    expect([...p.reached]).toEqual(['a', 'b', 'c'])
    expect([...p.edges]).toEqual(['t1', 't2'])
    expect(playbackAt(null, 0).active).toBe(false)
  })
})
