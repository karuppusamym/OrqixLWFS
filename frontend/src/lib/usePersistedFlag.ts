import { useCallback, useSyncExternalStore } from 'react'
import { readStorage, writeStorage } from './storage'

const EVENT = 'orqix:flag'

/**
 * A boolean kept in localStorage (a per-viewer convenience such as "pane shown") and shared by every component that
 * uses the same key in this tab, so a menu and the pane it controls always agree. Falls back to the default when
 * storage is unavailable.
 */
export function usePersistedFlag(key: string, defaultValue: boolean): [boolean, (next: boolean) => void] {
  const subscribe = useCallback((notify: () => void) => {
    window.addEventListener(EVENT, notify)
    window.addEventListener('storage', notify)
    return () => {
      window.removeEventListener(EVENT, notify)
      window.removeEventListener('storage', notify)
    }
  }, [])
  const value = useSyncExternalStore(
    subscribe,
    () => {
      const stored = readStorage(key)
      return stored === null ? defaultValue : stored === 'true'
    },
    () => defaultValue,
  )
  const set = useCallback((next: boolean) => {
    writeStorage(key, String(next))
    window.dispatchEvent(new Event(EVENT))
  }, [key])
  return [value, set]
}
