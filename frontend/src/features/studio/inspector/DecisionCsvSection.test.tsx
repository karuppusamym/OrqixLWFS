import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { db, PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import { signInAs, testQueryClient, WS } from '@/test/utils'
import { StudioProvider } from '../StudioContext'
import { DecisionCsvSection } from './DecisionCsvSection'

const downloads: Array<{ text: string; filename: string }> = []
vi.mock('@/lib/download', () => ({
  downloadBlob: (blob: Blob, filename: string) => {
    void blob.text().then((text) => downloads.push({ text, filename }))
  },
  downloadJson: () => undefined,
}))

const HEAD = 'hitPolicy:FIRST,effectiveFrom,effectiveTo,in:number:/input/amount,in:text:/input/costCenter,out:text:route\n'
const draft = () => db.workflows.get(PURCHASE_WORKFLOW_ID)!.drafts.at(-1)!

function setup(user: string, readOnly = false) {
  signInAs(user)
  const store = createStudioStore()
  store.getState().load({ workflowId: PURCHASE_WORKFLOW_ID, revision: draft().revision, definition: draft().definition })
  render(
    <QueryClientProvider client={testQueryClient()}>
      <StudioProvider value={{ store, saver: { flush: async () => true } as unknown as DraftSaver, wsId: WS, workflowId: PURCHASE_WORKFLOW_ID, readOnly, connections: { responseType: () => undefined } }}>
        <DecisionCsvSection tableKey="amount-policy" />
      </StudioProvider>
    </QueryClientProvider>,
  )
  return store
}

const file = (text: string, name = 'rules.csv') => new File([text], name, { type: 'text/csv' })

beforeEach(() => {
  downloads.length = 0
})

describe('decision table CSV (G3)', () => {
  it('exports the table and downloads a template', async () => {
    setup('dana.designer')
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Export CSV' }))
    await waitFor(() => expect(downloads).toHaveLength(1))
    expect(downloads[0]!.text.split('\n')[0]).toBe('hitPolicy:UNIQUE,effectiveFrom,effectiveTo,in:number:/input/amount,out:text:route')
    expect(downloads[0]!.filename).toMatch(/amount-policy\.csv$/)
    await user.click(screen.getByRole('button', { name: 'Download template' }))
    await waitFor(() => expect(downloads).toHaveLength(2))
    expect(downloads[1]!.filename).toMatch(/template\.csv$/)
    expect(downloads[1]!.text).toContain('rule,,,gte 0,')
  })

  it('imports a file as the next draft revision and updates the table', async () => {
    const store = setup('dana.designer')
    const user = userEvent.setup()
    const before = draft().revision
    await user.upload(screen.getByTestId('decision-csv-file'), file(HEAD + 'rule,,,gte 50000,,exec\nrule,,,gte 5000,eq IT,itFinance\ndefault,,,,,standard\n'))
    expect(await screen.findByTestId('decision-csv-done')).toHaveTextContent(`draft revision ${before + 1}`)
    expect(draft().revision).toBe(before + 1)
    expect(store.getState().revision).toBe(before + 1)
    const table = store.getState().definition.decisions[0]!
    expect(table.hitPolicy).toBe('FIRST')
    expect(table.rows).toHaveLength(2)
    expect(table.rows[1]!.when).toMatchObject({ all: [{ fieldRef: '/input/amount' }, { fieldRef: '/input/costCenter', operator: 'eq', value: 'IT' }] })
  })

  it('lists every problem with its line and column and changes nothing', async () => {
    const store = setup('dana.designer')
    const user = userEvent.setup()
    const before = JSON.stringify(draft())
    await user.upload(screen.getByTestId('decision-csv-file'), file(HEAD + 'rule,,,"gte 1,000",,a\nrule,,,between 1 2,,b\ndefault,,,,,c\n'))
    const list = await screen.findByTestId('decision-csv-errors')
    expect(list).toHaveAttribute('role', 'alert')
    expect(list).toHaveTextContent('Line 2, column 4:')
    expect(list).toHaveTextContent('Line 3, column 4:')
    expect(JSON.stringify(draft())).toBe(before)
    expect(store.getState().definition.decisions[0]!.hitPolicy).toBe('UNIQUE')
  })

  it('reports the overlap of a UNIQUE table with its position', async () => {
    setup('dana.designer')
    const user = userEvent.setup()
    await user.upload(screen.getByTestId('decision-csv-file'), file(HEAD.replace('FIRST', 'UNIQUE') + 'rule,,,gte 100,,a\nrule,,,gte 200,,b\ndefault,,,,,c\n'))
    expect(await screen.findByTestId('decision-csv-errors')).toHaveTextContent(/Line 3, column 4/)
  })

  it('offers no import to a reader but still lets them export', () => {
    setup('vic.viewer', true)
    expect(screen.queryByRole('button', { name: 'Import CSV' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Export CSV' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Download template' })).toBeEnabled()
  })
})
