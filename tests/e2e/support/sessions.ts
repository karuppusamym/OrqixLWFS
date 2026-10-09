import type { Browser, BrowserContext, Page } from '@playwright/test'
import { BASE_URL, MOCK, passwordFor, type Username } from './env'

/** The hooks frontend/src/mocks/browser.ts exposes on window.__orqixMock (mock mode only). */
export interface MockControl {
  reset(options?: { published?: boolean; sampleRuns?: boolean; demo?: boolean }): void
  setProcurementFault(fault: 'none' | 'failOnce' | 'unknown' | 'down'): void
  decideTaskAs(username: string, taskId: string, action: 'approve' | 'reject', reason?: string): void
  claimTaskAs(username: string, taskId: string): void
  latency(ms: number): void
}

declare global {
  interface Window {
    __cspViolations?: Array<{ directive: string; blockedURI: string; sourceFile: string }>
    __orqixMock?: MockControl
  }
}

export interface Problem {
  kind: 'console-error' | 'page-error' | 'csp-violation'
  user: string
  message: string
}

/**
 * Signed-in browser sessions.
 *
 * Real stack: one browser context per user, signed in through the Keycloak login form (S1 section 6.1 users).
 * Mock mode: the API lives in the page and its data in localStorage, so ONE context is shared and `as()` only switches
 * the signed-in persona.
 *
 * Every page is watched: console errors, uncaught exceptions and Content-Security-Policy violations are collected
 * (T-UI-06) and reported by the `sessions` fixture after each test.
 */
export class Sessions {
  readonly problems: Problem[] = []
  private readonly browser: Browser
  private readonly contexts = new Map<string, { context: BrowserContext; page: Page }>()
  private ignoreHttpErrors = false
  /** Pages whose sign-in finished. Before that, the SPA's unauthenticated /api/v1/me probe answers 401 by design. */
  private readonly signedIn = new WeakSet<Page>()
  private mockContext: { context: BrowserContext; page: Page } | null = null
  private mockUser: string | null = null

  constructor(browser: Browser) {
    this.browser = browser
  }

  /** Expected 4xx responses (for example a denied cross-workspace request) log "Failed to load resource" in Chromium. */
  allowHttpErrors(): void {
    this.ignoreHttpErrors = true
  }

  private async open(user: string): Promise<{ context: BrowserContext; page: Page }> {
    const context = await this.browser.newContext({ baseURL: BASE_URL, viewport: { width: 1440, height: 900 } })
    await context.addInitScript(() => {
      window.__cspViolations = []
      document.addEventListener('securitypolicyviolation', (e) => {
        window.__cspViolations?.push({ directive: e.violatedDirective, blockedURI: e.blockedURI, sourceFile: e.sourceFile })
      })
    })
    const page = await context.newPage()
    this.watch(page, user)
    return { context, page }
  }

  private watch(page: Page, user: string): void {
    page.on('console', (msg) => {
      if (msg.type() !== 'error') return
      const text = msg.text()
      if (this.ignoreHttpErrors && /Failed to load resource: the server responded with a status of 4\d\d/.test(text)) return
      // The 401 that starts the OIDC login (S3 section 2) is expected; any 401 after sign-in is a real problem.
      if (!this.signedIn.has(page) && /Failed to load resource: the server responded with a status of 401/.test(text)) return
      this.problems.push({ kind: 'console-error', user, message: text })
    })
    page.on('pageerror', (error) => this.problems.push({ kind: 'page-error', user, message: error.message }))
  }

  /** The page of `user`, signed in. */
  async as(user: Username): Promise<Page> {
    if (MOCK) return this.asMock(user)
    const existing = this.contexts.get(user)
    if (existing) return existing.page
    const session = await this.open(user)
    this.contexts.set(user, session)
    await login(session.page, user)
    this.signedIn.add(session.page)
    return session.page
  }

  private async asMock(user: Username): Promise<Page> {
    this.mockContext ??= await this.open('mock')
    const { page } = this.mockContext
    if (this.mockUser !== user) {
      if (page.url() === 'about:blank') await page.goto('/')
      await page.evaluate((u) => window.localStorage.setItem('orqix.mock.user', u), user)
      this.mockUser = user
    }
    await page.goto('/')
    await page.locator('main h1').first().waitFor()
    return page
  }

  /** Mock mode only: reach into the in-page backend (another person acts, faults, reset). */
  async mockCall<K extends keyof MockControl>(name: K, ...args: Parameters<MockControl[K]>): Promise<void> {
    if (!this.mockContext) await this.asMock('rhea.requester')
    const page = (this.mockContext as { page: Page }).page
    await page.evaluate(([method, params]) => (window.__orqixMock as unknown as Record<string, (...a: unknown[]) => void>)[method as string]?.(...(params as unknown[])), [name, args] as const)
  }

  /** Report CSP violations recorded by the browser and return every problem seen so far. */
  async collectProblems(): Promise<Problem[]> {
    const pages = [...this.contexts.entries()].map(([user, s]) => [user, s.page] as const)
    if (this.mockContext) pages.push(['mock', this.mockContext.page])
    for (const [user, page] of pages) {
      if (page.isClosed()) continue
      const violations = await page.evaluate(() => window.__cspViolations ?? []).catch(() => [])
      for (const v of violations) this.problems.push({ kind: 'csp-violation', user, message: `${v.directive} blocked ${v.blockedURI} (${v.sourceFile})` })
    }
    return this.problems
  }

  async close(): Promise<void> {
    for (const { context } of this.contexts.values()) await context.close().catch(() => undefined)
    await this.mockContext?.context.close().catch(() => undefined)
  }
}

/** Sign in through Keycloak's login form (realm orqix-dev). Passwords come from environment variables only. */
async function login(page: Page, user: Username): Promise<void> {
  await page.goto('/')
  // Either the app shell is already shown, or the browser was redirected to Keycloak.
  await page.waitForURL(/\/realms\/orqix-dev\/|\/w\/|\/not-found/, { timeout: 60_000 })
  if (/\/realms\/orqix-dev\//.test(page.url())) {
    await page.locator('#username').fill(user)
    await page.locator('#password').fill(passwordFor(user))
    await Promise.all([page.waitForURL((url) => !/\/realms\/orqix-dev\//.test(url.toString()), { timeout: 60_000 }), page.locator('#kc-login').click()])
  }
  // Land on a page that has finished its first render (workspace home, or the "no workspace" page for otto).
  await page.locator('main h1').first().waitFor({ timeout: 60_000 })
}
