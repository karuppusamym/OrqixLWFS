/**
 * localStorage helpers. Storage can be unavailable or throw (private windows, blocked site data,
 * previews), so every access is wrapped and the UI must work without it. Use only for per-viewer conveniences.
 */
export function readStorage(key: string): string | null {
  try {
    return window.localStorage.getItem(key)
  } catch {
    return null
  }
}

export function writeStorage(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value)
  } catch {
    // ignore: convenience only
  }
}

export function removeStorage(key: string): void {
  try {
    window.localStorage.removeItem(key)
  } catch {
    // ignore
  }
}

const LAST_WORKSPACE_KEY = 'orqix.lastWorkspace'

export function readLastWorkspace(): string | null {
  return readStorage(LAST_WORKSPACE_KEY)
}

export function writeLastWorkspace(id: string): void {
  writeStorage(LAST_WORKSPACE_KEY, id)
}
