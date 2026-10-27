import { useCallback, useSyncExternalStore } from 'react'
import { useOptionalWorkspace } from '@/app/workspace'
import type { Notation } from '../model/notation'

/**
 * Canvas notation preference (STU01): BPMN shapes (the default) or the step cards. Remembered per person in this
 * browser's localStorage, keyed by the signed-in subject, never synced. Storage can be missing or throw (private
 * windows, blocked site data), so every access is wrapped and the default is used when it fails.
 */
export const NOTATION_KEY = 'orqix.canvasNotation.v1'
const CHANGED = 'orqix.canvas-notation.changed'
export const DEFAULT_NOTATION: Notation = 'bpmn'

export function notationKey(subject: string | null | undefined): string {
  return subject ? `${NOTATION_KEY}:${encodeURIComponent(subject)}` : NOTATION_KEY
}

export function parseNotation(raw: string | null): Notation {
  return raw === 'cards' || raw === 'bpmn' ? raw : DEFAULT_NOTATION
}

export function readNotation(key: string): Notation {
  try {
    return parseNotation(window.localStorage.getItem(key))
  } catch {
    return DEFAULT_NOTATION
  }
}

export function writeNotation(key: string, value: Notation): void {
  try {
    window.localStorage.setItem(key, value)
  } catch {
    // Convenience only: the choice lasts for this page view.
  }
  window.dispatchEvent(new CustomEvent(CHANGED, { detail: { key, value } }))
}

/** Last choice per key, used only when storage cannot be read, so the toggle still works for this page view. */
const memory = new Map<string, Notation>()

export function useNotation(): [Notation, (value: Notation) => void] {
  const subject = useOptionalWorkspace()?.me.subject.id ?? null
  const key = notationKey(subject)
  const subscribe = useCallback(
    (listener: () => void) => {
      const storage = (event: StorageEvent) => {
        if (event.key === key || event.key === null) listener()
      }
      const local = (event: Event) => {
        if ((event as CustomEvent<{ key: string }>).detail?.key === key) listener()
      }
      window.addEventListener('storage', storage)
      window.addEventListener(CHANGED, local)
      return () => {
        window.removeEventListener('storage', storage)
        window.removeEventListener(CHANGED, local)
      }
    },
    [key],
  )
  const read = useCallback(() => {
    try {
      return parseNotation(window.localStorage.getItem(key))
    } catch {
      return memory.get(key) ?? DEFAULT_NOTATION
    }
  }, [key])
  const notation = useSyncExternalStore(subscribe, read, () => DEFAULT_NOTATION)
  const set = useCallback(
    (value: Notation) => {
      memory.set(key, value)
      writeNotation(key, value)
    },
    [key],
  )
  return [notation, set]
}
