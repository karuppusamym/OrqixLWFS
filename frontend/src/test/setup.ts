import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterAll, afterEach, beforeAll, expect, vi } from 'vitest'
import { resetLoginRedirectGuard } from '@/api/auth'
import { draftSaverTestTuning } from '@/stores/draftSaver'
import { db } from '@/mocks/state'
import { server } from './server'

// Lazy routes (studio, React Flow) are slow to load the first time in a cold test worker.
configure({ asyncUtilTimeout: 6000 })

// The 1.5 s autosave quiet period is real time in UI tests (idle waiting after every edit). Shorten it; the timing
// itself is still covered with fake timers in stores/studioStore.test.ts, which restores the spec value.
const TEST_AUTOSAVE_DEBOUNCE_MS = 150
draftSaverTestTuning.debounceMs = TEST_AUTOSAVE_DEBOUNCE_MS

// jsdom lacks several browser APIs the UI relies on.
beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' })

  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      // Tests run at desktop width unless a test overrides matchMedia.
      matches: /min-width:\s*1024px/.test(query),
      media: query,
      onchange: null,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      addListener: () => undefined,
      removeListener: () => undefined,
      dispatchEvent: () => false,
    }),
  })

  class ResizeObserverStub {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  vi.stubGlobal('ResizeObserver', ResizeObserverStub)

  if (!HTMLDialogElement.prototype.showModal) {
    HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
      this.setAttribute('open', '')
    }
    HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
      this.removeAttribute('open')
      this.dispatchEvent(new Event('close'))
    }
  }
  Element.prototype.scrollIntoView = () => undefined
  HTMLElement.prototype.scrollTo = () => undefined
})

// The first render of the studio route pays for loading React Flow, the inspectors and the editor (seconds on a busy
// machine). Do that in a hook with its own generous budget instead of inside the first test's 15 s, so the test only
// measures its own interaction. Only the files that open the studio need it.
beforeAll(async () => {
  const file = expect.getState().testPath ?? ''
  if (/\/(studio|ai|workflows)\//.test(file.split('\\').join('/')) && file.endsWith('.tsx')) await import('@/features/studio/StudioPage')
}, 60000)

afterEach(async () => {
  cleanup()
  draftSaverTestTuning.debounceMs = TEST_AUTOSAVE_DEBOUNCE_MS // a test may lengthen it, see StudioPage.test.tsx
  // Leaving the studio flushes an unsaved draft; let that request finish before the next test reseeds the mock database.
  await new Promise((resolve) => setTimeout(resolve, 30))
  server.resetHandlers()
  resetLoginRedirectGuard()
  db.latencyMs = 0
  window.localStorage.clear()
})

afterAll(() => server.close())
