import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { renderAppReady, signInAs, WS } from '@/test/utils'

describe('reusable view library with mock API', () => {
  it('shows the seeded published view, copies it as an independent draft, and opens its editor', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    const app = await renderAppReady(`/w/${WS}/views`)
    const row = await screen.findByRole('row', { name: /Purchase request/ })
    expect(within(row).getByText('Published v1')).toBeInTheDocument()
    await user.click(within(row).getByRole('button', { name: 'Copy Purchase request' }))
    const dialog = await screen.findByRole('dialog', { name: 'Copy view' })
    await user.click(within(dialog).getByRole('button', { name: 'Copy view' }))
    await waitFor(() => expect(app.router.state.location.pathname).toMatch(/\/views\/[0-9a-f-]+$/))
    expect(await screen.findByRole('heading', { level: 1, name: 'Purchase request copy' })).toBeInTheDocument()
    expect(screen.getByText('Not published yet.', { exact: false })).toBeInTheDocument()
  })

  it('creates a usable approval draft with a task binding', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS}/views`)
    await user.click(await screen.findByRole('button', { name: 'New view' }))
    const dialog = await screen.findByRole('dialog', { name: 'New view' })
    await user.type(within(dialog).getByLabelText('Name'), 'Manager decision')
    await user.selectOptions(within(dialog).getByLabelText('View type'), 'approval')
    await user.click(within(dialog).getByRole('button', { name: 'Create view' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Manager decision' })).toBeInTheDocument()
    expect(screen.getByLabelText('Data binding')).toHaveValue('task.field1')
  })

  it('hides authoring controls for a read-only viewer', async () => {
    signInAs('vic.viewer')
    await renderAppReady(`/w/${WS}/views`)
    expect(await screen.findByRole('row', { name: /Purchase request/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'New view' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Copy Purchase request' })).not.toBeInTheDocument()
  })
})
