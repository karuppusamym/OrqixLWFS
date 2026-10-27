import { render, screen, within } from '@testing-library/react'
import axe from 'axe-core'
import { describe, expect, it } from 'vitest'
import type { CaseModelItem } from './caseModel'
import { CMMN_KIND, CmmnPreview } from './CmmnPreview'

const items: CaseModelItem[] = [
  { id: 'task1', kind: 'humanTask', label: 'Review evidence', required: true, manual: true, group: 'reviewers' },
  {
    id: 'stage1',
    kind: 'stage',
    label: 'Investigation',
    after: 'task1',
    children: [
      { id: 'task2', kind: 'humanTask', label: 'Interview', repetitions: 3, group: 'reviewers' },
      { id: 'ms1', kind: 'milestone', label: 'Facts gathered', after: 'task2', required: true },
    ],
  },
  { id: 'ev1', kind: 'userEventListener', label: 'Customer withdrew', group: 'agents' },
]

describe('CMMN notation preview of the case designer (STU01)', () => {
  it('draws each plan item kind with its CMMN shape and says the same in words', () => {
    render(<CmmnPreview name="Fraud case" items={items} />)
    const preview = screen.getByTestId('cmmn-preview')
    expect(within(preview).getByRole('heading', { name: 'CMMN diagram (read only)' })).toBeInTheDocument()
    expect(within(preview).getByText('Case plan: Fraud case')).toBeInTheDocument()
    expect(screen.getByTestId('cmmn-task1')).toHaveAttribute('data-cmmn', 'humanTask')
    expect(screen.getByTestId('cmmn-stage1')).toHaveAttribute('data-cmmn', 'stage')
    expect(screen.getByTestId('cmmn-ms1')).toHaveAttribute('data-cmmn', 'milestone')
    expect(screen.getByTestId('cmmn-ms1').className).toMatch(/rounded-full/)
    expect(screen.getByTestId('cmmn-ev1')).toHaveAttribute('data-cmmn', 'userEventListener')
    // Markers (decorators) are drawn and written out.
    const task = screen.getByTestId('cmmn-task1')
    expect(task.querySelector('[data-marker="manual"]')).not.toBeNull()
    expect(task.querySelector('[data-marker="required"]')).not.toBeNull()
    expect(within(task).getByText('Required · Manual start')).toBeInTheDocument()
    expect(screen.getByTestId('cmmn-task2').querySelector('[data-marker="repeat"]')).not.toBeNull()
    expect(within(screen.getByTestId('cmmn-task2')).getByText('Repeats 3 times')).toBeInTheDocument()
    // The stage has a collapse marker; entry criteria are sentries (diamonds) with words.
    expect(screen.getByTestId('cmmn-stage1').querySelector('[data-marker="collapse"]')).not.toBeNull()
    expect(within(screen.getByTestId('cmmn-stage1')).getByText('Starts after Review evidence completes')).toBeInTheDocument()
    expect(screen.getByTestId('cmmn-ms1').querySelector('[data-testid="cmmn-sentry"]')).not.toBeNull()
    expect(screen.getByTestId('cmmn-task1').querySelector('[data-testid="cmmn-sentry"]')).toBeNull()
  })

  it('lists every CMMN symbol in a collapsed legend', () => {
    render(<CmmnPreview name="Fraud case" items={items} />)
    const legend = screen.getByTestId('cmmn-legend')
    expect(legend).not.toHaveAttribute('open')
    for (const k of Object.values(CMMN_KIND)) expect(within(legend).getByText(k.label)).toBeInTheDocument()
  })

  it('has no axe violations', async () => {
    const { container } = render(<CmmnPreview name="Fraud case" items={items} />)
    const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })
    expect(results.violations.map((v) => v.id)).toEqual([])
  })
})
