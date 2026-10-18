import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { db, PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import { resetDocumentLibrary } from '@/mocks/documentLibrary'
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
  it('copies the latest saved template into the step and keeps its own text afterwards', async () => {
    resetDocumentLibrary()
    signInAs('dana.designer')
    const draft = () => db.workflows.get(PURCHASE_WORKFLOW_ID)!.drafts.at(-1)!.definition
    const node = draft().nodes.find((n) => n.id === 'manager')!
    node.type = 'generateDocument'; node.config = { format: 'docx', fileName: 'Receipt', template: 'Old {{amount}}', inputMapping: { amount: '/input/amount' } }
    const user = userEvent.setup(); renderApp(`/w/${WS}/workflows/${PURCHASE_WORKFLOW_ID}/edit`)
    await screen.findByText(/Draft revision 1/, undefined, { timeout: 15000 })
    await user.click(screen.getByRole('tab', { name: 'Steps' }))
    const row = await screen.findByTestId('step-row-manager', undefined, { timeout: 15000 }); await user.click(row.querySelector<HTMLElement>('button[aria-pressed]')!)
    const picker = await screen.findByLabelText('Start from a saved template')
    await waitFor(() => expect(within(picker).getByRole('option', { name: 'Order confirmation letter (version 1)' })).toBeInTheDocument())
    await user.selectOptions(picker, within(picker).getByRole('option', { name: 'Order confirmation letter (version 1)' }))
    expect(await screen.findByText(/Copied version 1 of Order confirmation letter/)).toBeInTheDocument()
    await waitFor(() => expect(draft().nodes.find((n) => n.id === 'manager')!.config).toMatchObject({ format: 'pdf', template: expect.stringContaining('Dear {{customer}}'), inputMapping: { customer: '', amount: '/input/amount' } }), { timeout: 15000 })
    // PL01: the step is linked to the copied version, so validation can report newer or archived versions.
    await waitFor(() => expect(draft().dependencies.assets).toEqual([{ kind: 'documentTemplate', key: 'order-confirmation-letter', version: 1, target: 'manager' }]), { timeout: 15000 })
    expect(await screen.findByTestId('template-link')).toHaveTextContent('Linked to saved template Order confirmation letter (order-confirmation-letter), version 1.')
  })
})
