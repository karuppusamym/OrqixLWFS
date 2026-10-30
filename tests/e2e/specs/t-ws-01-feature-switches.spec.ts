import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { workspaceId } from '../support/app'
import { apiSend } from '../support/api'
import { expectNoSeriousViolations } from '../support/axe'

/**
 * Workspace feature switches (docs/build/S127): a workspace administrator switches Apps and extensions off in
 * Settings → Features after a confirmation that says what happens to running work; the module leaves everyone's menu,
 * its page explains why, and its API refuses new work with 409 FEATURE_DISABLED. Switching it on brings it back.
 */
test('an administrator switches a module off and on again for the whole workspace', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { demo: true })
  const admin = await sessions.as('wade.admin')
  const ws = await workspaceId(admin)
  const nav = (page: typeof admin) => page.getByRole('navigation', { name: 'Main' })
  try {
    await admin.goto(`/w/${ws}/settings`)
    await admin.getByRole('tab', { name: 'Features' }).click()
    await expect(admin.getByRole('heading', { name: 'Features', level: 2 })).toBeVisible()
    const apps = admin.getByTestId('feature-apps')
    await expect(apps.getByTestId('feature-apps-state')).toHaveText('On')
    await expect(apps).toContainText('Work already running')
    await expectNoSeriousViolations(admin, 'Settings → Features')

    await apps.getByRole('button', { name: 'Switch off' }).click()
    await expect(apps.getByText('Switch off Apps and extensions?')).toBeVisible()
    await expectNoSeriousViolations(admin, 'Switch-off confirmation')
    await apps.getByRole('button', { name: 'Switch off Apps and extensions' }).click()
    await expect(apps.getByTestId('feature-apps-state')).toHaveText('Off')
    await expect(apps).toContainText('Switched off by Wade Admin')
    await expect(nav(admin).getByRole('link', { name: 'Apps' })).toHaveCount(0)

    // Everyone in the workspace: no Apps in the menu, a notice on the page, and the API refuses new work.
    const requester = await sessions.as('rhea.requester')
    await expect(nav(requester).getByRole('link', { name: 'My requests' })).toBeVisible()
    await expect(nav(requester).getByRole('link', { name: 'Apps' })).toHaveCount(0)
    await requester.goto(`/w/${ws}/apps`)
    await expect(requester.getByTestId('feature-off-notice')).toContainText('Apps and extensions is switched off in this workspace')
    await expect(requester.getByTestId('feature-off-notice')).toContainText('A workspace administrator can switch it on again.')
    sessions.allowHttpErrors() // the refused call below answers 409 on purpose
    const refused = await apiSend<{ code: string; feature: string }>(requester, 'POST', `/workspaces/${ws}/ui-extensions`, { name: 'Blocked' })
    expect(refused.status).toBe(409)
    expect(refused.body).toMatchObject({ code: 'FEATURE_DISABLED', feature: 'apps' })

    // Switched on again: it is back for everyone.
    const again = await sessions.as('wade.admin')
    await again.goto(`/w/${ws}/settings`)
    await again.getByRole('tab', { name: 'Features' }).click()
    await again.getByTestId('feature-apps').getByRole('button', { name: 'Switch on' }).click()
    await expect(again.getByTestId('feature-apps-state')).toHaveText('On')
    await expect(nav(again).getByRole('link', { name: 'Apps' })).toBeVisible()
    const back = await sessions.as('rhea.requester')
    await expect(nav(back).getByRole('link', { name: 'Apps' })).toBeVisible()
  } finally {
    // Never leave the shared workspace with a module off (real stack runs specs in sequence on one database).
    const page = await sessions.as('wade.admin')
    await apiSend(page, 'PUT', `/workspaces/${ws}/features/apps`, { enabled: true })
  }
})
