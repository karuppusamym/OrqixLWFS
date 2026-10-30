import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { runTitle } from '@/lib/runText'
import { db, USERS } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'

const laptop = () => [...db.runs.values()].find((r) => r.input.title === 'Replacement laptop')!

describe('request titles tell requests apart', () => {
  it('My requests shows the title with the reference, the workflow and the version', async () => {
    signInAs('rhea.requester')
    renderApp(`/w/${WS}/requests`)
    const table = await screen.findByRole('table', { name: 'Your requests' })
    const row = (await within(table).findByText('Replacement laptop')).closest('tr')!
    expect(within(row).getByText(laptop().reference ?? laptop().businessKey!)).toBeInTheDocument()
    expect(within(row).getByText(/Purchase approval/)).toBeInTheDocument()
  })

  it('home lists recent requests by title and reference', async () => {
    signInAs('rhea.requester')
    renderApp(`/w/${WS}`)
    const heading = await screen.findByRole('heading', { name: 'Your recent requests' })
    const list = heading.closest('section')!
    expect(await within(list).findByText('Replacement laptop')).toBeInTheDocument()
  })

  it('the run list of an operator shows titles', async () => {
    signInAs('olga.operator')
    renderApp(`/w/${WS}/runs?scope=all`)
    expect(await screen.findAllByText('Replacement laptop')).not.toHaveLength(0)
  })

  it('the run detail header leads with the title and keeps the workflow name and reference', async () => {
    signInAs('rhea.requester')
    renderApp(`/w/${WS}/runs/${laptop().id}`)
    expect(await screen.findByRole('heading', { level: 1, name: 'Replacement laptop' })).toBeInTheDocument()
    expect(screen.getByTestId('run-reference')).toBeInTheDocument()
    expect(screen.getByText(/Purchase approval ·/)).toBeInTheDocument()
  })

  it('a caller who may not read the request data sees the workflow name instead (the server sends no title)', async () => {
    signInAs('vic.viewer')
    renderApp(`/w/${WS}/runs/${laptop().id}`)
    expect(await screen.findByRole('heading', { level: 1, name: 'Purchase approval' })).toBeInTheDocument()
    expect(screen.queryByText('Replacement laptop')).not.toBeInTheDocument()
  })

  it('notifications carry the title next to the reference', async () => {
    signInAs('rhea.requester')
    const user = userEvent.setup()
    const rhea = USERS.find((u) => u.username === 'rhea.requester')!
    const note = db.notifications.find((n) => n.userId === rhea.id && n.runId && typeof db.runs.get(n.runId)?.input.title === 'string')
    expect(note).toBeDefined()
    const run = db.runs.get(note!.runId!)!
    renderApp(`/w/${WS}`)
    await user.click(await screen.findByRole('button', { name: /Notifications/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Notifications' })
    await waitFor(() => expect(within(dialog).getAllByText(new RegExp(String(run.input.title))).length).toBeGreaterThan(0))
  })

  it('runTitle ignores blank or missing titles', () => {
    expect(runTitle({ requestTitle: '  Annual licences ' })).toBe('Annual licences')
    expect(runTitle({ requestTitle: '   ' })).toBeNull()
    expect(runTitle({ requestTitle: null })).toBeNull()
    expect(runTitle({})).toBeNull()
  })
})

describe('workflow list: unpublished changes', () => {
  it('shows "Changes not published" next to the active version when the draft moved on', async () => {
    signInAs('dana.designer')
    const wf = [...db.workflows.values()].find((w) => w.name === 'Purchase approval')!
    wf.drafts.push({ ...wf.drafts.at(-1)!, revision: (wf.drafts.at(-1)?.revision ?? 1) + 1 })
    renderApp(`/w/${WS}/workflows`)
    const row = (await screen.findByText('Purchase approval', { selector: 'a' })).closest('tr')!
    expect(within(row).getByText('Active v1')).toBeInTheDocument()
    expect(within(row).getByText('Changes not published')).toBeInTheDocument()
  })

  it('does not show it when the active version was published from the latest draft', async () => {
    signInAs('dana.designer')
    renderApp(`/w/${WS}/workflows`)
    const row = (await screen.findByText('Purchase approval', { selector: 'a' })).closest('tr')!
    expect(within(row).getByText('Active v1')).toBeInTheDocument()
    expect(within(row).queryByText('Changes not published')).not.toBeInTheDocument()
  })
})
