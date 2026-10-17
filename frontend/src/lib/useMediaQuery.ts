import { useSyncExternalStore } from 'react'

/** Subscribe to a CSS media query. */
export function useMediaQuery(query: string): boolean {
  return useSyncExternalStore(
    (notify) => {
      const mql = window.matchMedia(query)
      mql.addEventListener('change', notify)
      return () => mql.removeEventListener('change', notify)
    },
    () => window.matchMedia(query).matches,
    () => false,
  )
}

/** Desktop layout starts at 1024 px (docs/build/S7-frontend.md sections 1 and 4). */
export function useIsDesktop(): boolean {
  return useMediaQuery('(min-width: 1024px)')
}
