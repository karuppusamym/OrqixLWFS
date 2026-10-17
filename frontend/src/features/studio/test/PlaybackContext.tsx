import { createContext, useContext, useMemo, useState, type ReactNode } from 'react'
import type { SimulationResult } from '@/api/types'

/**
 * What the canvas shows of a test run while the test panel is open: the step being explained, the path so far and the
 * steps no run has reached. Kept outside the studio store, so it never enters the undo history or the saved draft.
 */
export interface Playback {
  active: boolean
  /** The step being explained. */
  current: string | null
  /** Steps up to and including the current one. */
  visited: ReadonlySet<string>
  /** Every step the run reaches (later ones show lightly). */
  reached: ReadonlySet<string>
  /** Routes followed up to and including the one out of the current step. */
  edges: ReadonlySet<string>
}

const INACTIVE: Playback = { active: false, current: null, visited: new Set(), reached: new Set(), edges: new Set() }

export function playbackAt(result: SimulationResult | null, index: number): Playback {
  if (!result || result.trace.length === 0) return INACTIVE
  const last = Math.min(Math.max(index, 0), result.trace.length - 1)
  const visited = new Set<string>()
  const edges = new Set<string>()
  result.trace.forEach((entry, i) => {
    if (i <= last) {
      visited.add(entry.stepId)
      if (entry.transitionId) edges.add(entry.transitionId)
    }
  })
  return { active: true, current: result.trace[last]?.stepId ?? null, visited, reached: new Set(result.trace.map((e) => e.stepId)), edges }
}

const PlaybackContext = createContext<Playback>(INACTIVE)
const SetPlaybackContext = createContext<(p: Playback) => void>(() => undefined)

export function PlaybackProvider({ children }: { children: ReactNode }) {
  const [playback, setPlayback] = useState<Playback>(INACTIVE)
  const set = useMemo(() => setPlayback, [])
  return (
    <SetPlaybackContext.Provider value={set}>
      <PlaybackContext.Provider value={playback}>{children}</PlaybackContext.Provider>
    </SetPlaybackContext.Provider>
  )
}

export const usePlayback = (): Playback => useContext(PlaybackContext)
export const useSetPlayback = (): ((p: Playback) => void) => useContext(SetPlaybackContext)
export const NO_PLAYBACK = INACTIVE
