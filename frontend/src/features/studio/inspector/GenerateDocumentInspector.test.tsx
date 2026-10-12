import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { db, PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'
import { documentPlaceholders } from './GenerateDocumentInspector'

describe('generate document authoring', () => {
  it('retains unique safe placeholders only', () => {
    expect(documentPlaceholders('{{customer}} {{customer}} {{total}} {{total + 1}}')).toEqual(['customer', 'total'])
  })
  it('edits the pinned template and maps the request field', async () => {
    signInAs('dana.designer')
    const draft = () => db.workflows.get(PURCHASE_WORKFLOW_ID)!.drafts.at(-1)!.definition
    const node = draft().nodes.find((n) => n.id === 'manager')!
    node.type = 'generateDocument'; node.config = { format: 'pdf', fileName: 'Receipt', template: 'Total: {{amount}}', inputMapping: { amount: '' } }
    const user = userEvent.setup(); renderApp(`/w/${WS}/workflows/${PURCHASE_WORKFLOW_ID}/edit`)
    await screen.findByText(/Draft revision 1/, undefined, { timeout: 15000 })
    await user.click(screen.getByRole('tab', { name: 'Steps' }))
    const row = await screen.findByTestId('step-row-manager', undefined, { timeout: 15000 }); await user.click(row.querySelector<HTMLElement>('button[aria-pressed]')!)
    await user.selectOptions(screen.getByLabelText(/^Value for amount/), '/input/amount')
    await user.selectOptions(screen.getByLabelText('Document format'), 'docx')
    await waitFor(() => expect(draft().nodes.find((n) => n.id === 'manager')!.config).toMatchObject({ format: 'docx', inputMapping: { amount: '/input/amount' } }), { timeout: 15000 })
  })
})
