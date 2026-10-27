import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import { getMe } from '@/api/endpoints'
import { PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import { server } from '@/test/server'
import { renderAppReady, signInAs, WS } from '@/test/utils'
import { pinsKey } from './pins'

const panel = () => screen.getByRole('region', { name: 'Pinned workflows' })

describe('pinned workflow shortcuts', () => {
  it('pins from the catalog, persists through reload/search and unpins from the overview', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    const first = await renderAppReady(`/w/${WS}/workflows`)
    await user.click(await screen.findByRole('button', { name: 'Pin Purchase approval' }))
    expect(await within(panel()).findByRole('link', { name: 'Purchase approval' })).toBeInTheDocument()
    const stored = Object.keys(localStorage).find(key => key.startsWith('orqix.workflowPins.v1:'))!
    expect(JSON.parse(localStorage.getItem(stored)!).ids).toEqual([PURCHASE_WORKFLOW_ID])
    first.unmount()
    const second = await renderAppReady(`/w/${WS}/workflows?q=no-such-workflow`)
    await screen.findByText('No workflows match your search')
    await user.click(await within(panel()).findByRole('link', { name: 'Purchase approval' }))
    await user.click(await screen.findByRole('button', { name: 'Unpin Purchase approval' }))
    await waitFor(() => expect(JSON.parse(localStorage.getItem(stored)!).ids).toEqual([]))
    second.unmount()
    await renderAppReady(`/w/${WS}/workflows`)
    await screen.findByRole('region', { name: 'Pinned workflows' })
    expect(within(panel()).queryByRole('link')).not.toBeInTheDocument()
  })

  it('reacts to another tab clearing pins without retaining names', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS}/workflows`)
    await user.click(await screen.findByRole('button', { name: 'Pin Purchase approval' }))
    await within(panel()).findByRole('link', { name: 'Purchase approval' })
    act(() => { localStorage.clear(); window.dispatchEvent(new StorageEvent('storage', { key: null })) })
    expect(within(panel()).queryByRole('link')).not.toBeInTheDocument()
  })

  it('does not share another signed-in account\'s pins in the same browser', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    const first = await renderAppReady(`/w/${WS}/workflows`)
    await user.click(await screen.findByRole('button', { name: 'Pin Purchase approval' }))
    await within(panel()).findByRole('link', { name: 'Purchase approval' })
    first.unmount()
    signInAs('olga.operator')
    const second = await renderAppReady(`/w/${WS}/workflows`)
    await screen.findByRole('region', { name: 'Pinned workflows' })
    expect(within(panel()).queryByRole('link')).not.toBeInTheDocument()
    second.unmount()
    signInAs('dana.designer')
    await renderAppReady(`/w/${WS}/workflows`)
    await screen.findByRole('region', { name: 'Pinned workflows' })
    expect(await within(panel()).findByRole('link', { name: 'Purchase approval' })).toBeInTheDocument()
  })

  it('does not show a cached pinned label after an authorization refresh fails', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    const first = await renderAppReady(`/w/${WS}/workflows`)
    await user.click(await screen.findByRole('button', { name: 'Pin Purchase approval' }))
    await within(panel()).findByRole('link', { name: 'Purchase approval' })
    first.unmount()
    server.use(http.get('/api/v1/workspaces/:ws/workflows/:id', () => HttpResponse.json({ code: 'NOT_FOUND', status: 404, title: 'Not found' }, { status: 404 })))
    await renderAppReady(`/w/${WS}/workflows?q=absent`)
    await screen.findByRole('region', { name: 'Pinned workflows' })
    await within(panel()).findByText(/This pinned workflow is unavailable/)
    expect(within(panel()).queryByText('Purchase approval')).not.toBeInTheDocument()
    expect(within(panel()).queryByRole('link')).not.toBeInTheDocument()
    await user.click(within(panel()).getByRole('button', { name: 'Unpin unavailable workflow' }))
    expect(within(panel()).queryByText(/unavailable/)).not.toBeInTheDocument()
  })

  it('does not offer pins or fetch stored workflow IDs for a requester', async () => {
    signInAs('rhea.requester')
    const me = await getMe()
    localStorage.setItem(pinsKey(me.subject.id, WS), JSON.stringify({ version: 1, ids: [PURCHASE_WORKFLOW_ID] }))
    const request = vi.fn()
    server.use(http.get('/api/v1/workspaces/:ws/workflows/:id', () => { request(); return HttpResponse.json({}, { status: 403 }) }))
    await renderAppReady(`/w/${WS}/workflows`)
    await screen.findByRole('heading', { name: 'Workflows', level: 1 })
    await waitFor(() => expect(screen.getByRole('search')).toBeInTheDocument())
    expect(screen.queryByRole('region', { name: 'Pinned workflows' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Pin / })).not.toBeInTheDocument()
    expect(request).not.toHaveBeenCalled()
  })

  it('reports a storage failure and leaves the workflow unpinned', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS}/workflows`)
    const button = await screen.findByRole('button', { name: 'Pin Purchase approval' })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
    await user.click(button)
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not save your pins')
    expect(button).toHaveAttribute('aria-pressed', 'false')
    expect(within(panel()).queryByRole('link')).not.toBeInTheDocument()
  })
})
