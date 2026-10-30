import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { exportDmn } from '@/mocks/decisionDmn'
import { db, PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import { signInAs, testQueryClient, WS } from '@/test/utils'
import { StudioProvider } from '../StudioContext'
import type { DecisionTable } from '../model/types'
import { DecisionDmnSection } from './DecisionDmnSection'

const downloads: Array<{ text: string; filename: string }> = []
vi.mock('@/lib/download', () => ({
  downloadBlob: (blob: Blob, filename: string) => {
    void blob.text().then((text) => downloads.push({ text, filename }))
  },
  downloadJson: () => undefined,
}))

const draft = () => db.workflows.get(PURCHASE_WORKFLOW_ID)!.drafts.at(-1)!

function setup(user: string, readOnly = false) {
  signInAs(user)
  const store = createStudioStore()
  store.getState().load({ workflowId: PURCHASE_WORKFLOW_ID, revision: draft().revision, definition: draft().definition })
  render(
    <QueryClientProvider client={testQueryClient()}>
      <StudioProvider value={{ store, saver: { flush: async () => true } as unknown as DraftSaver, wsId: WS, workflowId: PURCHASE_WORKFLOW_ID, readOnly, connections: { responseType: () => undefined } }}>
        <DecisionDmnSection tableKey="amount-policy" />
      </StudioProvider>
    </QueryClientProvider>,
  )
  return store
}

const priority: Pick<DecisionTable, 'hitPolicy' | 'rows' | 'default' | 'priority'> = {
  hitPolicy: 'PRIORITY',
  priority: { output: 'route', order: ['financeReview', 'standard'] },
  rows: [
    { when: { fieldRef: '/input/amount', operator: 'between', value: { from: 1000, to: 5000 } }, then: { route: 'standard' } },
    { when: { fieldRef: '/input/amount', operator: 'gte', value: 5000 }, then: { route: 'financeReview' } },
  ],
  default: { route: 'standard' },
}

beforeEach(() => {
  downloads.length = 0
})

describe('decision table DMN (docs/build/S121)', () => {
  it('downloads the table as a DMN file', async () => {
    setup('dana.designer')
    await userEvent.setup().click(screen.getByRole('button', { name: 'Download DMN' }))
    await waitFor(() => expect(downloads).toHaveLength(1))
    expect(downloads[0]!.filename).toMatch(/amount-policy-draft-\d+\.dmn$/)
    expect(downloads[0]!.text).toContain('hitPolicy="UNIQUE"')
  })

  it('asks which decision to import from a file with several, then saves the next draft revision', async () => {
    const store = setup('dana.designer')
    const user = userEvent.setup()
    const before = draft().revision
    const xml = exportDmn(priority, 'routing', 'Routing').replace('</definitions>', '<decision id="other" name="Other"></decision></definitions>')
    await user.upload(screen.getByTestId('decision-dmn-file'), new File([xml], 'rules.dmn', { type: 'application/xml' }))
    expect(await screen.findByTestId('decision-dmn-choice')).toHaveTextContent('rules.dmn has several decisions')
    await user.selectOptions(screen.getByLabelText(/has several decisions/), 'routing')
    await user.click(screen.getByRole('button', { name: 'Import this decision' }))
    expect(await screen.findByTestId('decision-dmn-done')).toHaveTextContent(`draft revision ${before + 1}`)
    const table = store.getState().definition.decisions[0]!
    expect(table.hitPolicy).toBe('PRIORITY')
    expect(table.priority).toEqual(priority.priority)
    expect(table.rows[0]!.when).toMatchObject({ operator: 'between' })
  })

  it('lists the problems of a file it cannot read and changes nothing', async () => {
    setup('dana.designer')
    const before = JSON.stringify(draft())
    await userEvent.setup().upload(screen.getByTestId('decision-dmn-file'), new File(['<root/>'], 'bad.dmn', { type: 'application/xml' }))
    expect(await screen.findByTestId('decision-dmn-errors')).toHaveTextContent('not a DMN file')
    expect(JSON.stringify(draft())).toBe(before)
  })

  it('offers only the download to readers', () => {
    setup('dana.designer', true)
    expect(screen.getByRole('button', { name: 'Download DMN' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Import DMN' })).not.toBeInTheDocument()
  })
})
