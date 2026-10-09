import { useSearchParams } from 'react-router'
import type { Lifecycle } from '@/api/types'
import { LIFECYCLE_LABELS } from './status'

/** Keep list filters in the URL so links, reloads and browser Back retain context. */
export function useListFilters() {
  const [params, setParams] = useSearchParams()
  const update = (values: Record<string, string>) => {
    setParams((previous) => {
      const next = new URLSearchParams(previous)
      for (const [key, value] of Object.entries(values)) {
        if (value) next.set(key, value)
        else next.delete(key)
      }
      return next
    })
  }
  const raw = params.get('lifecycle') ?? ''
  const lifecycle: Lifecycle | '' = Object.hasOwn(LIFECYCLE_LABELS, raw) ? raw as Lifecycle : ''
  return { params, update, lifecycle }
}
