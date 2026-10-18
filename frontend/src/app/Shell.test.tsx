import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { claim } from '@/mocks/engine'
import { db, userByName, WS_PURCHASING, WS_SANDBOX } from '@/mocks/state'
import { writeLastWorkspace } from '@/lib/storage'
import { renderApp, signInAs, WS } from '@/test/utils'

const navLinks = () => within(screen.getByRole('navigation', { name: 'Main' })).getAllByRole('link').map((a) => a.textContent?.replace(/\d+ assigned to you$/, '').replace(/\d+$/, '').trim())

describe('shell: permission-based navigation, workspaces, notifications', () => {
  it('shows only what the caller may use (hiding is convenience; the server enforces)', async () => {
    signInAs('rhea.requester')
    renderApp(`/w/${WS}`)
    await screen.findByRole('heading', { level: 1 })
    expect(navLinks()).toEqual(['Home', 'My requests', 'Assistant', 'Workflows', 'Cases'])
  })

  it('approvers get My tasks with their assigned count', async () => {
    signInAs('mani.manager')
    // The seed leaves no task claimed by mani: claim one open manager task that is not his own request.
    const mani = userByName('mani.manager')
    const isOpen = (t: { status: string; assigneeId: string | null; candidateGroup: string }) =>
      t.status === 'open' && t.assigneeId === null && t.candidateGroup === 'managers'
    const run = [...db.runs.values()].find((r) => r.wsId === WS && r.initiatorId !== mani.id && r.tasks.some(isOpen))!
    claim(run, run.tasks.find(isOpen)!, mani)
    renderApp(`/w/${WS}`)
    await waitFor(() => expect(navLinks()).toContain('My tasks'))
    expect(navLinks()).toEqual(['Home', 'My requests', 'Assistant', 'My tasks', 'Workflows', 'Cases'])
    const link = within(screen.getByRole('navigation', { name: 'Main' })).getByRole('link', { name: /My tasks/ })
    await waitFor(() => expect(link).toHaveTextContent('1 assigned to you'))
  })

  it('operators get Runs and Settings (connections), admins get Settings, outsiders get a clear page', async () => {
    signInAs('olga.operator')
    const first = renderApp(`/w/${WS}`)
    await screen.findByRole('heading', { level: 1 })
    expect(navLinks()).toEqual(['Home', 'My requests', 'Assistant', 'Workflows', 'Runs', 'Cases', 'Views', 'Insights', 'Settings'])
    first.unmount()

    signInAs('wade.admin')
    const second = renderApp(`/w/${WS}`)
    await screen.findByRole('heading', { level: 1 })
    expect(navLinks()).toEqual(['Home', 'My requests', 'Assistant', 'Promotions', 'Settings'])
    second.unmount()

    signInAs('otto.outsider')
    renderApp('/')
    expect(await screen.findByRole('heading', { level: 1, name: 'No workspace yet' })).toBeInTheDocument()
  })

  it('answers "not found" for a workspace the caller is not a member of', async () => {
    signInAs('fred.facilities')
    renderApp(`/w/${WS_PURCHASING}`)
    expect(await screen.findByRole('heading', { level: 1, name: 'Page not found' })).toBeInTheDocument()
  })

  it('"/" opens the last used workspace, or the first one', async () => {
    signInAs('dana.designer')
    writeLastWorkspace(WS_SANDBOX)
    const { router, unmount } = renderApp('/')
    await waitFor(() => expect(router.state.location.pathname).toBe(`/w/${WS_SANDBOX}`))
    unmount()
    window.localStorage.clear()
    const second = renderApp('/')
    await waitFor(() => expect(second.router.state.location.pathname).toBe(`/w/${WS_PURCHASING}`))
  })

  it('marks test workspaces with a persistent banner', async () => {
    signInAs('dana.designer')
    const { unmount } = renderApp(`/w/${WS_SANDBOX}`)
    expect(await screen.findByTestId('test-banner')).toHaveTextContent('Test workspace — requests here use test connections.')
    unmount()
    renderApp(`/w/${WS_PURCHASING}`)
    await screen.findByRole('heading', { level: 1 })
    expect(screen.queryByTestId('test-banner')).not.toBeInTheDocument()
  })

  it('switches workspace from the top bar', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    const { router } = renderApp(`/w/${WS_PURCHASING}`)
    await user.click(await screen.findByRole('button', { name: /Switch workspace/ }))
    await user.click(await screen.findByRole('menuitem', { name: /Purchasing sandbox/ }))
    await waitFor(() => expect(router.state.location.pathname).toBe(`/w/${WS_SANDBOX}`))
  })

  it('shows an unread badge on the bell, lists notifications, and marks one read when it is opened', async () => {
    signInAs('rhea.requester')
    const user = userEvent.setup()
    const { router } = renderApp(`/w/${WS}`)
    const bell = await screen.findByRole('button', { name: /Notifications, \d+ unread/ })
    const unread = Number(/(\d+) unread/.exec(bell.getAttribute('aria-label') ?? '')?.[1])
    expect(unread).toBeGreaterThan(0)
    await user.click(bell)
    const list = within(await screen.findByRole('dialog', { name: 'Notifications' })).getByRole('list')
    const first = within(list).getAllByRole('link')[0]!
    expect(first).toHaveTextContent(/Unread:/)
    const message = first.textContent!.replace(/^Unread:\s*/, '').split(/(Needs attention|just now|\d+ (minute|hour|day))/)[0]!.trim()
    const stored = db.notifications.find((n) => message.startsWith(n.message))
    expect(stored?.readAt).toBeNull()
    await user.click(first)
    await waitFor(() => expect(router.state.location.pathname).toMatch(/\/runs\//))
    await waitFor(() => expect(stored?.readAt).toBeTruthy())
  })

  it('has a skip link and a main landmark', async () => {
    signInAs('rhea.requester')
    renderApp(`/w/${WS}`)
    await screen.findByRole('heading', { level: 1 })
    expect(screen.getByRole('link', { name: 'Skip to main content' })).toHaveAttribute('href', '#main')
    expect(screen.getAllByRole('main')).toHaveLength(1)
  })
})
