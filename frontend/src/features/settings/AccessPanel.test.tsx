import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { db } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'

async function openAccess() {
  signInAs('wade.admin')
  const user = userEvent.setup()
  renderApp(`/w/${WS}/settings`)
  await screen.findByRole('heading', { name: 'Roles' })
  return user
}

describe('Settings → Members and groups', () => {
  it('maps a sign-in group to a role and removes it again after confirming', async () => {
    const user = await openAccess()
    await user.click(screen.getByRole('button', { name: 'Add role' }))
    const form = screen.getByRole('form', { name: 'Add role' })
    await user.selectOptions(within(form).getByLabelText('Role'), 'OPERATOR')
    await user.type(within(form).getByLabelText('Sign-in group name'), 'orqix-purchasing-ops')
    await user.click(within(form).getByRole('button', { name: 'Add role' }))
    const row = (await screen.findByRole('cell', { name: /orqix-purchasing-ops/ })).closest('tr')!
    expect(within(row).getByText('Operator')).toBeInTheDocument()
    expect(within(row).getByText('Added by an administrator')).toBeInTheDocument()
    expect(db.accessEdits?.roleBindings).toEqual([expect.objectContaining({ role: 'OPERATOR', principal: expect.objectContaining({ type: 'idpGroup', value: 'orqix-purchasing-ops' }) })])

    await user.click(within(row).getByRole('button', { name: 'Remove Operator from orqix-purchasing-ops' }))
    await user.click(await screen.findByRole('button', { name: 'Remove role' }))
    await waitFor(() => expect(screen.queryByRole('cell', { name: /orqix-purchasing-ops/ })).not.toBeInTheDocument())
  })

  it('creates an approval group (work queue) with a sign-in group member and edits it at its revision', async () => {
    const user = await openAccess()
    await user.click(screen.getByRole('button', { name: 'New approval group' }))
    const dialog = await screen.findByRole('dialog', { name: 'New approval group' })
    await user.type(within(dialog).getByLabelText('Name'), 'Legal reviewers')
    expect(within(dialog).getByLabelText('Key')).toHaveValue('legal-reviewers')
    await user.type(within(dialog).getByLabelText('Sign-in group name'), 'orqix-legal')
    await user.click(within(dialog).getByRole('button', { name: 'Add member' }))
    await user.click(within(dialog).getByRole('button', { name: 'Create group' }))
    const card = await screen.findByRole('listitem', { name: 'Approval group Legal reviewers' })
    expect(within(card).getByText('orqix-legal')).toBeInTheDocument()

    await user.click(within(card).getByRole('button', { name: 'Edit Legal reviewers' }))
    const edit = await screen.findByRole('dialog', { name: 'Edit Legal reviewers' })
    expect(within(edit).getByLabelText('Key')).toHaveAttribute('readonly')
    await user.click(within(edit).getByRole('button', { name: 'Remove member orqix-legal' }))
    await user.type(within(edit).getByLabelText('Sign-in group name'), 'orqix-legal-team')
    await user.click(within(edit).getByRole('button', { name: 'Add member' }))
    await user.click(within(edit).getByRole('button', { name: 'Save group' }))
    await waitFor(() => expect(within(screen.getByRole('listitem', { name: 'Approval group Legal reviewers' })).getByText('orqix-legal-team')).toBeInTheDocument())
    expect(db.accessEdits?.groups).toEqual([expect.objectContaining({ key: 'legal-reviewers', revision: 2, members: [expect.objectContaining({ value: 'orqix-legal-team' })] })])
  })

  it('grants starting requests to a sign-in group for every workflow', async () => {
    const user = await openAccess()
    await user.click(screen.getByRole('button', { name: 'Add permission' }))
    const form = screen.getByRole('form', { name: 'Add permission' })
    // An administrator without workflow read access cannot list workflows; the grant applies to all unless an ID is given.
    expect(await within(form).findByLabelText('One workflow only (optional)')).toHaveValue('')
    await user.type(within(form).getByLabelText('Sign-in group name'), 'orqix-all-staff')
    await user.click(within(form).getByRole('button', { name: 'Add permission' }))
    const row = (await screen.findByRole('cell', { name: /orqix-all-staff/ })).closest('tr')!
    expect(within(row).getByText('Can start requests')).toBeInTheDocument()
    expect(within(row).getByText('Every workflow in this workspace')).toBeInTheDocument()
  })
})
