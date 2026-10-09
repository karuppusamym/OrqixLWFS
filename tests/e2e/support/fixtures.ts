import { expect, test as base } from '@playwright/test'
import { Sessions } from './sessions'

/**
 * `sessions` signs users in and watches every page. After each test it fails when a console error, an uncaught
 * exception or a Content-Security-Policy violation was seen (T-UI-06 applies to every spec, not only its own).
 */
export const test = base.extend<{ sessions: Sessions }>({
  sessions: async ({ browser }, use) => {
    const sessions = new Sessions(browser)
    await use(sessions)
    const problems = await sessions.collectProblems()
    await sessions.close()
    expect(problems, `Browser problems:\n${problems.map((p) => `[${p.kind}] ${p.user}: ${p.message}`).join('\n')}`).toEqual([])
  },
})

export { expect }
