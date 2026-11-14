import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { db, userByName, WORKSPACES } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'
import { parseGroups } from './NewWorkspaceDialog'

const newWorkspaceDialog = () => screen.findByRole('dialog', { name: 'New workspace' })

describe('platform administration: creating workspaces', () => {
  it('lets a platform administrator with no workspace create the first one and open its settings', async () => {
    signInAs('pat.platform')
    const user = userEvent.setup()
    const { router } = renderApp('/')

    // The dead end now offers the way out.
    expect(await screen.findByRole('heading', { level: 1, name: 'No workspace yet' })).toBeVisible()
    await user.click(screen.getByRole('link', { name: 'Create a workspace' }))

    const dialog = await newWorkspaceDialog()
    expect(router.state.location.pathname).toBe('/admin')
    expect(within(dialog).getByRole('combobox', { name: 'Belongs to' })).toHaveDisplayValue('Demo Organization')
    await user.type(within(dialog).getByRole('textbox', { name: /Workspace name/ }), 'Claims Operations')
    expect(within(dialog).getByRole('textbox', { name: /Workspace key/ })).toHaveValue('claims-operations')
    expect(within(dialog).getByRole('checkbox', { name: 'Make me a workspace administrator' })).toBeChecked()
    await user.type(within(dialog).getByRole('textbox', { name: /Sign-in groups/ }), 'claims-admins')
    await user.click(within(dialog).getByRole('button', { name: 'Create workspace' }))

    const done = await screen.findByTestId('workspace-created')
    expect(done).toHaveTextContent('Workspace Claims Operations is ready')
    const created = db.platform?.workspaces.find((w) => w.key === 'claims-operations')
    expect(created).toMatchObject({ name: 'Claims Operations', isTest: false, allowSelfApprovalWorkflows: false })
    expect(db.accessEdits?.roleBindings.filter((b) => b.wsId === created?.id).map((b) => [b.role, b.principal.type, b.principal.value])).toEqual([
      ['WORKSPACE_ADMIN', 'subject', userByName('pat.platform').id],
      ['WORKSPACE_ADMIN', 'idpGroup', 'claims-admins'],
    ])
    expect(within(screen.getByTestId('admin-workspace-claims-operations')).getByRole('link', { name: 'Open Claims Operations' })).toBeVisible()

    // The creator is the workspace's first administrator: Settings opens on Members and groups.
    await user.click(within(done).getByRole('link', { name: 'Add people in Settings' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Settings' })).toBeVisible()
    expect(router.state.location.pathname).toBe(`/w/${created?.id}/settings`)
    expect(await screen.findByText('claims-admins')).toBeVisible()
  })

  it('creates a new organization and a test workspace in one step', async () => {
    signInAs('pat.platform')
    const user = userEvent.setup()
    renderApp('/admin?new=workspace')
    const dialog = await newWorkspaceDialog()
    await user.selectOptions(within(dialog).getByRole('combobox', { name: 'Belongs to' }), 'New organization…')
    await user.type(within(dialog).getByRole('textbox', { name: /Organization name/ }), 'Northwind Bank')
    expect(within(dialog).getByRole('textbox', { name: /Organization key/ })).toHaveValue('northwind-bank')
    await user.type(within(dialog).getByRole('textbox', { name: /Workspace name/ }), 'Loans sandbox')

    const selfApproval = within(dialog).getByRole('checkbox', { name: /approve their own requests/ })
    expect(selfApproval).toBeDisabled()
    await user.click(within(dialog).getByRole('checkbox', { name: 'This is a test workspace' }))
    expect(selfApproval).toBeEnabled()
    await user.click(selfApproval)
    await user.click(within(dialog).getByRole('button', { name: 'Create workspace' }))

    await screen.findByTestId('workspace-created')
    const org = db.platform?.organizations.find((o) => o.key === 'northwind-bank')
    expect(org?.name).toBe('Northwind Bank')
    expect(db.platform?.workspaces.find((w) => w.key === 'loans-sandbox')).toMatchObject({ organizationId: org?.id, isTest: true, allowSelfApprovalWorkflows: true })
    const row = screen.getByTestId('admin-workspace-loans-sandbox')
    expect(row).toHaveTextContent('Northwind Bank')
    expect(row).toHaveTextContent('Test')
    expect(row).toHaveTextContent('Self-approval allowed')
    expect(within(screen.getByTestId('admin-organization-northwind-bank')).getByText('1')).toBeVisible()
  })

  it('needs an administrator and a free key', async () => {
    signInAs('pat.platform')
    const user = userEvent.setup()
    renderApp('/admin?new=workspace')
    const dialog = await newWorkspaceDialog()
    await user.type(within(dialog).getByRole('textbox', { name: /Workspace name/ }), 'Purchasing')
    await user.click(within(dialog).getByRole('checkbox', { name: 'Make me a workspace administrator' }))
    await user.click(within(dialog).getByRole('button', { name: 'Create workspace' }))
    expect(within(dialog).getByText('Choose at least one administrator: yourself or a sign-in group.')).toBeVisible()

    await user.click(within(dialog).getByRole('checkbox', { name: 'Make me a workspace administrator' }))
    await user.click(within(dialog).getByRole('button', { name: 'Create workspace' }))
    expect(await within(dialog).findByText('A workspace with this key already exists. Choose a different key.')).toBeVisible()
    expect(db.platform?.workspaces ?? []).toHaveLength(0)
  })

  it('creates an organization on its own', async () => {
    signInAs('pat.platform')
    const user = userEvent.setup()
    renderApp('/admin')
    await user.click(await screen.findByRole('button', { name: 'New organization' }))
    const dialog = await screen.findByRole('dialog', { name: 'New organization' })
    await user.type(within(dialog).getByRole('textbox', { name: /Name/ }), 'Contoso Group')
    await user.click(within(dialog).getByRole('button', { name: 'Create organization' }))
    expect(await screen.findByTestId('admin-organization-contoso-group')).toHaveTextContent('Contoso Group')
    expect(screen.getByTestId('admin-organization-demo')).toHaveTextContent('Demo Organization')
  })

  it('is only for platform administrators', async () => {
    signInAs('wade.admin')
    renderApp('/admin')
    expect(await screen.findByText('Only platform administrators can open this page.')).toBeVisible()
    expect(screen.queryByRole('button', { name: /New workspace/ })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Back to Purchasing/ })).toBeVisible()
  })

  it('tells people who are not platform administrators to ask for access', async () => {
    signInAs('otto.outsider')
    renderApp('/')
    expect(await screen.findByText('Ask a workspace administrator to add you.')).toBeVisible()
    expect(screen.queryByRole('link', { name: 'Create a workspace' })).not.toBeInTheDocument()
  })

  it('links to platform administration from the account menu of a platform administrator only', async () => {
    signInAs('pat.platform')
    const user = userEvent.setup()
    // Give Pat a workspace first, the same way the page does.
    renderApp('/admin?new=workspace')
    const dialog = await newWorkspaceDialog()
    await user.type(within(dialog).getByRole('textbox', { name: /Workspace name/ }), 'Facilities two')
    await user.click(within(dialog).getByRole('button', { name: 'Create workspace' }))
    await user.click(within(await screen.findByTestId('workspace-created')).getByRole('link', { name: 'Open workspace' }))

    await user.click(await screen.findByRole('button', { name: 'Account menu for Pat Platform' }))
    expect(await screen.findByRole('menuitem', { name: 'Platform administration' })).toBeVisible()
    await user.keyboard('{Escape}')
    await user.click(screen.getByRole('button', { name: /Switch workspace/ }))
    expect(await screen.findByRole('menuitem', { name: 'Create a workspace' })).toBeVisible()
  })

  it('does not show platform administration to workspace administrators', async () => {
    signInAs('wade.admin')
    const user = userEvent.setup()
    renderApp(`/w/${WS}`)
    await user.click(await screen.findByRole('button', { name: 'Account menu for Wade Admin' }))
    await screen.findByRole('menuitem', { name: /Sign out/ })
    expect(screen.queryByRole('menuitem', { name: 'Platform administration' })).not.toBeInTheDocument()
  })

  it('starts every test from the seeded workspaces', async () => {
    signInAs('pat.platform')
    expect(WORKSPACES.map((w) => w.key)).toEqual(['purchasing', 'purchasing-sandbox', 'facilities'])
    await waitFor(() => expect(db.platform).toBeUndefined())
  })
})

describe('sign-in groups field', () => {
  it('splits by line and comma, trims and drops repeats', () => {
    expect(parseGroups(' a-admins \nb-admins, a-admins\n\n')).toEqual(['a-admins', 'b-admins'])
  })
})
