import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import { renderAppReady, signInAs, WS } from '@/test/utils'

const EDIT = `/w/${WS}/workflows/${PURCHASE_WORKFLOW_ID}/edit`

describe('studio side panel and header', () => {
  it('shows Details and Discussion as collapsible cards, with Ask AI docked under the canvas', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(EDIT)
    await screen.findByText(/Draft revision 1/)
    const side = screen.getByRole('complementary', { name: 'Details and discussion' })
    const details = within(side).getByRole('button', { name: 'Details' })
    const discussion = within(side).getByRole('button', { name: 'Discussion' })
    expect(details).toHaveAttribute('aria-expanded', 'true')
    expect(discussion).toHaveAttribute('aria-expanded', 'false')
    await user.click(discussion)
    expect(discussion).toHaveAttribute('aria-expanded', 'true')
    const dock = screen.getByTestId('ask-dock')
    expect(within(dock).getByLabelText('What should change?')).toBeVisible()
  })

  it('hides and restores the panes from the View menu, and remembers the choice', async () => {
    signInAs('dana.designer')
    window.localStorage.removeItem('orqix.studio.view.ai')
    window.localStorage.removeItem('orqix.studio.view.side')
    const user = userEvent.setup()
    await renderAppReady(EDIT)
    await screen.findByText(/Draft revision 1/)
    await user.click(screen.getByRole('button', { name: /View: show or hide panels/ }))
    await user.click(await screen.findByRole('menuitemcheckbox', { name: 'Ask AI' }))
    expect(screen.queryByTestId('ask-dock')).not.toBeInTheDocument()
    expect(window.localStorage.getItem('orqix.studio.view.ai')).toBe('false')
    await user.click(screen.getByRole('button', { name: /View: show or hide panels/ }))
    await user.click(await screen.findByRole('menuitemcheckbox', { name: 'Details and discussion' }))
    expect(screen.queryByRole('complementary', { name: 'Details and discussion' })).not.toBeInTheDocument()
    window.localStorage.removeItem('orqix.studio.view.ai')
    window.localStorage.removeItem('orqix.studio.view.side')
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
