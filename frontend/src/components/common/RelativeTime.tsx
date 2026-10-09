import { useSyncExternalStore } from 'react'
import { formatDateTime, formatRelative } from '@/lib/dates'

// One shared ticker for every RelativeTime on the page.
const listeners = new Set<() => void>()
let tick = Date.now()
let timer: number | undefined

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  if (timer === undefined) {
    timer = window.setInterval(() => {
      tick = Date.now()
      listeners.forEach((l) => l())
    }, 30_000)
  }
  return () => {
    listeners.delete(listener)
    if (listeners.size === 0 && timer !== undefined) {
      window.clearInterval(timer)
      timer = undefined
    }
  }
}

/** Relative time with the exact timestamp available on the element. */
export function RelativeTime({ value }: { value: string | null | undefined }) {
  const now = useSyncExternalStore(subscribe, () => tick)
  if (!value) return null
  return (
    <time dateTime={value} title={formatDateTime(value)}>
      {formatRelative(value, new Date(now))}
    </time>
  )
}
