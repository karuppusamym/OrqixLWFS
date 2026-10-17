import path from 'node:path'
import { fileURLToPath } from 'node:url'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { loadEnv, type Plugin, type ProxyOptions } from 'vite'
import { defineConfig } from 'vitest/config'

const here = path.dirname(fileURLToPath(import.meta.url))
const backend = process.env.ORQIX_BACKEND_URL ?? 'http://localhost:8080'

/** Same-origin proxy used by `vite` and `vite preview`: the browser only ever talks to one origin. */
function proxyTable(): Record<string, ProxyOptions> {
  const entry = (): ProxyOptions => ({
    target: backend,
    changeOrigin: false, // keep the Host header so Spring builds redirect URIs for the SPA origin
    xfwd: true, // X-Forwarded-Host / -Proto / -For
  })
  return { '/api': entry(), '/oauth2': entry(), '/login': entry(), '/logout': entry() }
}

/**
 * Writes the brand (VITE_ORQIX_BRAND, default `wf`) into index.html: <html data-brand>, the favicon and the browser
 * theme color. Done at build time because the production CSP forbids an inline script, and a data attribute set by
 * the bundle would flash the wrong palette on first paint.
 */
function brandPlugin(brand: string): Plugin {
  const known = brand === 'orqix' ? 'orqix' : 'wf'
  return {
    name: 'orqix-brand',
    transformIndexHtml: (html) =>
      html
        .replace('data-brand="wf"', `data-brand="${known}"`)
        .replace('href="/favicon.svg"', known === 'orqix' ? 'href="/favicon-orqix.svg"' : 'href="/favicon.svg"')
        .replace('content="#d71e28"', known === 'orqix' ? 'content="#153b49"' : 'content="#d71e28"'),
  }
}

/** Content-Security-Policy from docs/build/S6-security.md section 6. Applied to `vite preview` so E2E can detect violations. */
const CSP =
  "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'"

const securityHeaders = {
  'Content-Security-Policy': CSP,
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'no-referrer',
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, here, 'VITE_')
  // Mock mode: `--mode mock`, or VITE_MOCK_API=true in the environment or a .env file.
  const mock = mode === 'mock' || env.VITE_MOCK_API === 'true' || process.env.VITE_MOCK_API === 'true'
  return {
  plugins: [react(), tailwindcss(), brandPlugin(env.VITE_ORQIX_BRAND ?? process.env.VITE_ORQIX_BRAND ?? 'wf')],
  resolve: { alias: { '@': path.resolve(here, 'src') } },
  define: {
    // Static flag: lets the bundler drop the whole mock tree from normal builds.
    __ORQIX_MOCK__: JSON.stringify(mock),
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: proxyTable(),
    // Mock mode and tests import the shared fixtures from ../examples (never copies).
    fs: { allow: [path.resolve(here, '..')] },
  },
  preview: { port: 4173, headers: securityHeaders, proxy: proxyTable() },
  // The ELK auto-layout engine is its own on-demand chunk (about 1.4 MB, loaded only when a layout is needed).
  build: { sourcemap: false, chunkSizeWarningLimit: 1500 },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    // CSS is not processed in tests, except when a test reads a stylesheet as text (the design-token checks in src/styles).
    css: { include: [/\.css\?raw/] },
    restoreMocks: true,
    testTimeout: 15000,
  },
  }
})
