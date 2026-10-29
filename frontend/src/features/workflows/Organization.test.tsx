import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { db } from '@/mocks/state'
import { server } from '@/test/server'
import { renderAppReady, signInAs, WS } from '@/test/utils'

const dialog = () => screen.getByRole('dialog')

async function addFolder(user: ReturnType<typeof userEvent.setup>, name: string) {
  await user.click(await screen.findByRole('button', { name: 'Manage folders' }))
  await user.type(within(dialog()).getByLabelText('New folder name'), name)
  await user.click(within(dialog()).getByRole('button', { name: 'Create folder' }))
  await within(dialog()).findByText(name)
}

afterEach(() => vi.restoreAllMocks())

describe('workflow folders and owners (PL01)', () => {
  it('lets a designer create a folder, file a workflow, filter by it and unfile it', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS}/workflows`)
    await addFolder(user, 'Finance')
    await user.click(within(dialog()).getByRole('button', { name: 'Close' }))

    await user.click(await screen.findByRole('button', { name: 'Organize Purchase approval' }))
    await user.selectOptions(await within(dialog()).findByLabelText('Folder'), 'Finance')
    await user.click(within(dialog()).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    const row = (await screen.findByRole('link', { name: 'Purchase approval' })).closest('tr')!
    await waitFor(() => expect(within(row).getByText('Finance')).toBeInTheDocument())
    expect(within(row).getByText('Owner: Dana Designer')).toBeInTheDocument()

    // The folder filter lists the filed workflow and the unfiled filter does not.
    await user.selectOptions(screen.getByLabelText('Folder'), screen.getByRole('option', { name: 'Finance (1)' }))
    expect(await screen.findByRole('link', { name: 'Purchase approval' })).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Folder'), screen.getByRole('option', { name: /^Unfiled/ }))
    expect(await screen.findByText('No workflows match your search')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Show all workflows' }))
    await user.click(await screen.findByRole('button', { name: 'Organize Purchase approval' }))
    await user.selectOptions(await within(dialog()).findByLabelText('Folder'), 'Unfiled')
    await user.click(within(dialog()).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const after = (await screen.findByRole('link', { name: 'Purchase approval' })).closest('tr')!
    await waitFor(() => expect(within(after).getByText('Unfiled')).toBeInTheDocument())
  })

  it('renames and deletes a folder, keeping its workflows', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS}/workflows`)
    await addFolder(user, 'Temp')
    await user.click(within(dialog()).getByRole('button', { name: 'Rename Temp' }))
    const input = within(dialog()).getByLabelText('Rename Temp')
    await user.clear(input)
    await user.type(input, 'Archive')
    await user.click(within(dialog()).getByRole('button', { name: 'Save' }))
    await within(dialog()).findByText('Archive')
    expect([...db.workflowFolders.values()].map((f) => [f.name, f.version])).toEqual([['Archive', 2]])

    vi.spyOn(window, 'confirm').mockReturnValue(true)
    await user.click(within(dialog()).getByRole('button', { name: 'Delete Archive' }))
    await within(dialog()).findByText('No folders yet.')
    expect(db.workflowFolders.size).toBe(0)
    await user.click(within(dialog()).getByRole('button', { name: 'Close' }))
    expect(await screen.findByRole('link', { name: 'Purchase approval' })).toBeInTheDocument()
  })

  it('shows a duplicate-name refusal and a stale-folder conflict without changing anything', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS}/workflows`)
    await addFolder(user, 'Ops')
    await user.type(within(dialog()).getByLabelText('New folder name'), 'ops')
    await user.click(within(dialog()).getByRole('button', { name: 'Create folder' }))
    expect(await within(dialog()).findByText(/folder with this name already exists/)).toBeInTheDocument()
    expect(db.workflowFolders.size).toBe(1)

    // Someone else renames the folder after this dialog loaded it.
    const folder = [...db.workflowFolders.values()][0]!
    await user.click(within(dialog()).getByRole('button', { name: 'Rename Ops' }))
    folder.version += 1
    const input = within(dialog()).getByLabelText('Rename Ops')
    await user.clear(input)
    await user.type(input, 'Operations')
    await user.click(within(dialog()).getByRole('button', { name: 'Save' }))
    expect(await within(dialog()).findByText(/changed by someone else/)).toBeInTheDocument()
    expect(folder.name).toBe('Ops')
  })

  it('reassigns the owner only to someone who can edit workflows', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS}/workflows`)
    await user.click(await screen.findByRole('button', { name: 'Organize Purchase approval' }))
    const owner = await within(dialog()).findByLabelText('Owner')
    await waitFor(() => expect(within(owner).getAllByRole('option').length).toBeGreaterThan(0))
    const names = within(owner).getAllByRole('option').map((o) => o.textContent)
    expect(names).toContain('Dana Designer')
    expect(names).not.toContain('Vic Viewer')
    expect(names).not.toContain('Rhea Requester')
    await user.click(within(dialog()).getByRole('button', { name: 'Cancel' }))
  })

  it('reports an owner change the server refuses and keeps the old owner', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    server.use(http.put('/api/v1/workspaces/:ws/workflows/:id/owner', () => HttpResponse.json(
      { type: 'x', title: 'Some values are not valid', status: 422, code: 'VALIDATION_FAILED', detail: 'The new owner must be a person who can edit workflows in this workspace.' }, { status: 422 })))
    db.workflowFolders.clear()
    await renderAppReady(`/w/${WS}/workflows`)
    await user.click(await screen.findByRole('button', { name: 'Organize Purchase approval' }))
    const owner = await within(dialog()).findByLabelText('Owner')
    // Offer a second designer through the candidate list.
    server.use(http.get('/api/v1/workspaces/:ws/workflow-owner-candidates', () => HttpResponse.json({ items: [{ id: 'aaaaaaaa-0000-4000-8000-0000000000aa', displayName: 'Sam Second' }] })))
    expect(owner).toBeInTheDocument()
    await user.click(within(dialog()).getByRole('button', { name: 'Cancel' }))
    await user.click(await screen.findByRole('button', { name: 'Organize Purchase approval' }))
    await user.selectOptions(await within(dialog()).findByLabelText('Owner'), await within(dialog()).findByRole('option', { name: 'Sam Second' }))
    await user.click(within(dialog()).getByRole('button', { name: 'Save' }))
    expect(await within(dialog()).findByText(/Choose an owner who can edit workflows/)).toBeInTheDocument()
    expect(db.workflows.get([...db.workflows.keys()][0]!)!.ownerId).not.toBe('aaaaaaaa-0000-4000-8000-0000000000aa')
  })

  it('shows readers folders and owners but no way to change them', async () => {
    signInAs('vic.viewer')
    const requested = vi.fn()
    server.use(http.get('/api/v1/workspaces/:ws/workflow-owner-candidates', () => { requested(); return HttpResponse.json({ items: [] }) }))
    await renderAppReady(`/w/${WS}/workflows`)
    expect(await screen.findByLabelText('Folder')).toBeInTheDocument()
    expect(screen.getByLabelText('Owner')).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Purchase approval' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Manage folders' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Organize / })).not.toBeInTheDocument()
    expect(requested).not.toHaveBeenCalled()
  })

  it('shows a start-only requester no folder, owner or organization requests', async () => {
    signInAs('rhea.requester')
    const requested = vi.fn()
    server.use(http.get('/api/v1/workspaces/:ws/workflow-organization', () => { requested(); return HttpResponse.json({}, { status: 403 }) }))
    await renderAppReady(`/w/${WS}/workflows`)
    await screen.findByRole('heading', { name: 'Workflows', level: 1 })
    await waitFor(() => expect(screen.getByRole('search')).toBeInTheDocument())
    expect(screen.queryByLabelText('Folder')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Owner')).not.toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: 'Folder and owner' })).not.toBeInTheDocument()
    expect(requested).not.toHaveBeenCalled()
  })

  it('keeps search working when folders cannot be loaded and ignores a deleted folder in the URL', async () => {
    signInAs('dana.designer')
    server.use(http.get('/api/v1/workspaces/:ws/workflow-organization', () => HttpResponse.json({ type: 'x', title: 'Boom', status: 500, code: 'INTERNAL_ERROR' }, { status: 500 })))
    await renderAppReady(`/w/${WS}/workflows`)
    expect(await screen.findByRole('link', { name: 'Purchase approval' })).toBeInTheDocument()
    expect(await screen.findByText(/Folders and owners could not be loaded/)).toBeInTheDocument()
  })

  it('offers a recovery when the filtered folder no longer exists', async () => {
    signInAs('dana.designer')
    await renderAppReady(`/w/${WS}/workflows?folder=cccccccc-0000-4000-8000-000000000099`)
    expect(await screen.findByText('No workflows match your search')).toBeInTheDocument()
    expect(await screen.findByRole('option', { name: 'Folder no longer exists' })).toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Show all workflows' }))
    expect(await screen.findByRole('link', { name: 'Purchase approval' })).toBeInTheDocument()
  })

  it('archives a workflow only after a reason and a second confirmation, and it leaves the catalog', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS}/workflows`)
    await user.click(await screen.findByRole('button', { name: 'Organize Purchase approval' }))
    const dialog = await screen.findByRole('dialog', { name: 'Organize Purchase approval' })
    await user.click(within(dialog).getByText('Archive this workflow'))
    const start = within(dialog).getByRole('button', { name: 'Archive…' })
    expect(start).toBeDisabled()
    await user.type(within(dialog).getByLabelText('Why archive it?'), 'Replaced by the new flow')
    await user.click(start)
    await user.click(within(dialog).getByRole('button', { name: 'Archive for good' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Organize Purchase approval' })).toBeNull())
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Organize Purchase approval' })).toBeNull())
  })

  it('archiving after someone else changed the workflow is refused instead of archiving it unseen', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS}/workflows`)
    await user.click(await screen.findByRole('button', { name: 'Organize Purchase approval' }))
    const dialog = await screen.findByRole('dialog', { name: 'Organize Purchase approval' })
    await user.click(within(dialog).getByText('Archive this workflow'))
    await user.type(within(dialog).getByLabelText('Why archive it?'), 'Replaced by the new flow')
    await waitFor(() => expect(within(dialog).getByRole('button', { name: 'Archive…' })).toBeEnabled())
    const workflow = [...db.workflows.values()].find((w) => w.name === 'Purchase approval')!
    workflow.version += 1 // someone else changes it while the dialog is open
    await user.click(within(dialog).getByRole('button', { name: 'Archive…' }))
    await user.click(within(dialog).getByRole('button', { name: 'Archive for good' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/changed by someone else/)
    expect(db.workflows.has(workflow.id)).toBe(true)
  })
})
