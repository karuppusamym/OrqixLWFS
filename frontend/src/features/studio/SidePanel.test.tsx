import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import { renderAppReady, signInAs, WS } from '@/test/utils'

const EDIT = `/w/${WS}/workflows/${PURCHASE_WORKFLOW_ID}/edit`

describe('studio side panel and header', () => {
  it('shows Details, Ask AI and Discussion as tabs of one panel, with Details first', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(EDIT)
    await screen.findByText(/Draft revision 1/)
    const panel = screen.getByRole('complementary', { name: 'Side panel' })
    const tabs = within(panel).getAllByRole('tab').map((t) => t.textContent)
    expect(tabs).toEqual(['Details', 'Ask AI', 'Discussion'])
    expect(within(panel).getByRole('tab', { name: 'Details' })).toHaveAttribute('aria-selected', 'true')
    await user.click(within(panel).getByRole('tab', { name: 'Ask AI' }))
    expect(within(panel).getByRole('tab', { name: 'Ask AI' })).toHaveAttribute('aria-selected', 'true')
    expect(within(panel).getByLabelText('What should change?')).toBeVisible()
  })

  it('keeps the main actions in the header and the rest under More', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(EDIT)
    await screen.findByText(/Draft revision 1/)
    expect(screen.getByRole('button', { name: 'Check workflow' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Publish/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Preview form' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'More actions' }))
    expect(await screen.findByRole('menuitem', { name: /Preview the request form/ })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: /Download BPMN/ })).toBeInTheDocument()
  })

  it('moves the technical settings under an Advanced section with plain wording', async () => {
    signInAs('dana.designer')
    await renderAppReady(EDIT)
    await screen.findByText(/Draft revision 1/)
    const summary = screen.getByText('Governance and rules (optional)')
    expect(summary.closest('details')).not.toHaveAttribute('open')
    expect(screen.queryByText('Lightweight workflow profile')).not.toBeInTheDocument()
  })

  it('does not show the first-use hint on a draft that already has several steps', async () => {
    signInAs('dana.designer')
    window.localStorage.removeItem('orqix.studio.hintDismissed')
    await renderAppReady(EDIT)
    await screen.findByText(/Draft revision 1/)
    expect(screen.queryByTestId('studio-hint')).not.toBeInTheDocument()
  })
})
