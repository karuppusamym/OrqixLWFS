import { defineConfig, devices } from '@playwright/test'

/**
 * Orqix E2E configuration.
 *
 * Real stack (default): the SPA and API are served by the application at E2E_BASE_URL (default http://localhost:8080)
 * and users sign in through the Keycloak realm `orqix-dev`. See README.md for the password variables.
 *
 * Mock mode (E2E_MOCK=true): the frontend is built with `--mode mock` and served by `vite preview`
 * (with the production Content-Security-Policy header) on http://localhost:4173. No backend or Keycloak is needed;
 * the API is simulated in the page. It exercises the same specs, but a passing mock run never proves backend behavior.
 */
const mock = process.env.E2E_MOCK === 'true'
const baseURL = process.env.E2E_BASE_URL ?? (mock ? 'http://localhost:4173' : 'http://localhost:8080')
const channel = process.env.E2E_BROWSER_CHANNEL || undefined // e.g. "msedge" or "chrome" to use an installed browser

export default defineConfig({
  testDir: './specs',
  // One shared database: specs build on each other's data, so run them one after another.
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  timeout: 90_000,
  expect: { timeout: 10_000 },
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }], ['junit', { outputFile: 'test-results/junit.xml' }]] : [['list']],
  use: {
    baseURL,
    viewport: { width: 1440, height: 900 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    // The dev realm is plain HTTP on localhost; nothing else is accepted.
    ignoreHTTPSErrors: false,
  },
  projects: [{ name: mock ? 'mock-chromium' : 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 }, ...(channel ? { channel } : {}) } }],
  webServer: mock
    ? {
        // Build once (fast) and serve with the production CSP header.
        command: 'npm --prefix ../../frontend run build:mock && npm --prefix ../../frontend run preview:mock',
        url: baseURL,
        reuseExistingServer: true,
        timeout: 240_000,
        stdout: 'ignore',
        stderr: 'pipe',
      }
    : undefined,
})
