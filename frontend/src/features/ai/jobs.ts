import { useSyncExternalStore } from 'react'

/**
 * AI generations the person closed the dialog on (S10 section 6): kept for this tab so the toast can follow them
 * across pages and reloads. The label is only the first words of the description, shown back to the same person.
 */
export interface TrackedJob {
  id: string
  ws: string
  label: string
}

const KEY = 'orqix.ai.jobs'
let memory: TrackedJob[] = []
let cached: TrackedJob[] = []
const listeners = new Set<() => void>()

function read(): TrackedJob[] {
  try {
    const raw = window.sessionStorage.getItem(KEY)
    if (raw) {
      const parsed = JSON.parse(raw) as TrackedJob[]
      if (Array.isArray(parsed)) return parsed.filter((j) => typeof j?.id === 'string' && typeof j?.ws === 'string')
    }
  } catch {
    // fall back to memory
  }
  return memory
}

function write(jobs: TrackedJob[]): void {
  memory = jobs
  try {
    window.sessionStorage.setItem(KEY, JSON.stringify(jobs))
  } catch {
    // convenience only
  }
  cached = jobs
  listeners.forEach((l) => l())
}

cached = read()

export function trackJob(job: TrackedJob): void {
  write([...read().filter((j) => j.id !== job.id), job])
}

export function untrackJob(id: string): void {
  write(read().filter((j) => j.id !== id))
}

export function labelOf(description: string): string {
  const words = description.replace(/\s+/g, ' ').trim()
  return words.length <= 48 ? words : `${words.slice(0, 47).trimEnd()}…`
}

/** Tracked jobs of one workspace. */
export function useTrackedJobs(ws: string): TrackedJob[] {
  const all = useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    () => cached,
  )
  return all.filter((j) => j.ws === ws)
}

/** Test helper: forget everything. */
export function clearTrackedJobs(): void {
  write([])
}
