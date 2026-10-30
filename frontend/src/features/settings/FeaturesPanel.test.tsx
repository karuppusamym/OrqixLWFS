import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { db, userByName } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'

const navLinks = () => within(screen.getByRole('navigation', { name: 'Main' })).getAllByRole('link').map((a) => a.textContent?.trim())

function switchOff(feature: 'ai' | 'cases' | 'migration' | 'agents' | 'apps' | 'kafka', username = 'wade.admin') {
  ;(db.featureSwitches ??= []).push({ wsId: WS, feature, enabled: false, updatedAt: '2026-10-30T09:00:00Z', updatedById: userByName(username).id })
}

describe('workspace feature switches (S127)', () => {
  it('lets an administrator switch a module off after confirming, hides it from the menu, and switch it on again', async () => {
    signInAs('wade.admin')
    const user = userEvent.setup()
    renderApp(`/w/${WS}/settings`)
    await user.click(await screen.findByRole('tab', { name: 'Features' }))
    const apps = await screen.findByTestId('feature-apps')
    expect(within(apps).getByTestId('feature-apps-state')).toHaveTextContent('On')
    expect(within(apps).getByText('Work already running')).toBeVisible()
    expect(navLinks()).toContain('Apps')

    await user.click(within(apps).getByRole('button', { name: 'Switch off' }))
    // Nothing changes until it is confirmed; the confirmation repeats what happens to running work.
    expect(within(apps).getByTestId('feature-apps-state')).toHaveTextContent('On')
    expect(within(apps).getByText('Switch off Apps and extensions?')).toBeVisible()
    expect(within(apps).getAllByText(/Pages and extensions are kept/).length).toBeGreaterThan(1)
    await user.click(within(apps).getByRole('button', { name: 'Switch off Apps and extensions' }))
    await waitFor(() => expect(within(screen.getByTestId('feature-apps')).getByTestId('feature-apps-state')).toHaveTextContent('Off'))
    expect(within(screen.getByTestId('feature-apps')).getByText(/Switched off by Wade Admin/)).toBeVisible()
    await waitFor(() => expect(navLinks()).not.toContain('Apps'))
    expect(db.featureSwitches).toEqual([expect.objectContaining({ wsId: WS, feature: 'apps', enabled: false })])

    await user.click(within(screen.getByTestId('feature-apps')).getByRole('button', { name: 'Switch on' }))
    await waitFor(() => expect(within(screen.getByTestId('feature-apps')).getByTestId('feature-apps-state')).toHaveTextContent('On'))
    await waitFor(() => expect(navLinks()).toContain('Apps'))
  })

  it('hides switched-off modules from the menu, Home and the Workflows page for everyone in the workspace', async () => {
    signInAs('dana.designer')
    switchOff('cases')
    switchOff('apps')
    switchOff('migration')
    renderApp(`/w/${WS}`)
    await screen.findByRole('heading', { level: 1 })
    await waitFor(() => expect(navLinks()).toContain('Workflows'))
    expect(navLinks()).not.toContain('Cases')
    expect(navLinks()).not.toContain('Apps')
    const getStarted = screen.getByTestId('get-started')
    expect(within(getStarted).queryByText('Bring in a Camunda or Pega file')).toBeNull()
    expect(within(getStarted).getByText(/approvals, waits, rules/)).toBeVisible()
  })

  it('answers 409 FEATURE_DISABLED for a switched-off module and shows a notice on its page', async () => {
    signInAs('rhea.requester')
    switchOff('cases')
    renderApp(`/w/${WS}/cases`)
    expect(await screen.findByTestId('feature-off-notice')).toHaveTextContent(/Cases is switched off in this workspace/)
    expect(screen.getByTestId('feature-off-notice')).toHaveTextContent(/Cases that are already open keep running/)
    const response = await fetch(`http://localhost/api/v1/workspaces/${WS}/cases/definitions`, { method: 'POST', body: '{}', headers: { 'Content-Type': 'application/json' } })
    expect(response.status).toBe(409)
    expect(await response.json()).toMatchObject({ code: 'FEATURE_DISABLED', feature: 'cases' })
  })

  it('is not offered to people who cannot manage the workspace', async () => {
    signInAs('olga.operator')
    renderApp(`/w/${WS}/settings`)
    await screen.findByRole('tab', { name: /Connections/ })
    expect(screen.queryByRole('tab', { name: 'Features' })).toBeNull()
  })
})
