import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterAll, afterEach, beforeAll, vi } from 'vitest'
import { resetLoginRedirectGuard } from '@/api/auth'
import { db } from '@/mocks/state'
import { server } from './server'

// Lazy routes (studio, React Flow) are slow to load the first time in a cold test worker.
configure({ asyncUtilTimeout: 6000 })

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

afterEach(async () => {
  cleanup()
  // Leaving the studio flushes an unsaved draft; let that request finish before the next test reseeds the mock database.
  await new Promise((resolve) => setTimeout(resolve, 30))
  server.resetHandlers()
  resetLoginRedirectGuard()
  db.latencyMs = 0
  window.localStorage.clear()
})

afterAll(() => server.close())
