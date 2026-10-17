import { useCallback, useState, useSyncExternalStore } from 'react'
import { useWorkspace } from '@/app/workspace'

export const PIN_LIMIT = 10
const CHANGED = 'orqix.workflow-pins.changed'
const UUID = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i

export const pinsKey = (subject: string, workspace: string) => `orqix.workflowPins.v1:${encodeURIComponent(subject)}:${encodeURIComponent(workspace)}`

/** Only IDs are retained, never labels, definitions, permissions or credentials. Malformed data is ignored. */
export function parsePins(raw: string | null): string[] {
  if (!raw || raw.length > 4096) return []
  try {
    const value = JSON.parse(raw) as { version?: unknown; ids?: unknown }
    if (value?.version !== 1 || !Array.isArray(value.ids) || value.ids.length > PIN_LIMIT
      || !value.ids.every(id => typeof id === 'string' && UUID.test(id))) return []
    return [...new Set(value.ids as string[])]
  } catch { return [] }
}

function snapshot(key: string): string | null {
  try { return window.localStorage.getItem(key) } catch { return null }
}

export function changePin(key: string, id: string): string | null {
  if (!UUID.test(id)) return 'This workflow cannot be pinned.'
  try {
    const ids = parsePins(window.localStorage.getItem(key))
    const pinned = ids.includes(id)
    if (!pinned && ids.length >= PIN_LIMIT) return `You can pin up to ${PIN_LIMIT} workflows per workspace. Unpin one first.`
    const next = pinned ? ids.filter(value => value !== id) : [...ids, id]
    window.localStorage.setItem(key, JSON.stringify({ version: 1, ids: next }))
    window.dispatchEvent(new CustomEvent(CHANGED, { detail: key }))
    return null
  } catch {
    return 'Could not save your pins. Allow browser site storage and try again.'
  }
}

export function useWorkflowPins() {
  const { me, workspace } = useWorkspace()
  const key = pinsKey(me.subject.id, workspace.id)
  const subscribe = useCallback((listener: () => void) => {
    const storage = (event: StorageEvent) => { if (event.key === key || event.key === null) listener() }
    const local = (event: Event) => { if ((event as CustomEvent<string>).detail === key) listener() }
    window.addEventListener('storage', storage)
    window.addEventListener(CHANGED, local)
    return () => { window.removeEventListener('storage', storage); window.removeEventListener(CHANGED, local) }
  }, [key])
  const read = useCallback(() => snapshot(key), [key])
  const raw = useSyncExternalStore(subscribe, read, () => null)
  const [error, setError] = useState<{ key: string; message: string | null } | null>(null)
  return {
    ids: parsePins(raw),
    error: error?.key === key ? error.message : null,
    toggle: (id: string) => setError({ key, message: changePin(key, id) }),
  }
}
