import { isProblemError, ProblemError } from '@/api/problem'
import type { Definition } from '@/features/studio/model/types'
import { isDirty, savePayload, type StudioStore } from './studioStore'

export interface SaveResult {
  revision: number
  savedAt: string
  savedBy: string
}

export type SaveFn = (definition: Definition, revision: number) => Promise<SaveResult>

export interface DraftSaverOptions {
  /** Quiet period after the last change before a save starts. Spec: 1.5 s. */
  debounceMs?: number
  /** Retry delays while the network or server is unavailable. */
  backoffMs?: number[]
}

/**
 * Autosave (docs/build/S7-frontend.md section 5): 1.5 s after the last change, `If-Match` with the last known
 * revision. On 412 autosave stops and the conflict dialog opens. A failed save keeps the local draft and retries
 * with backoff; the header shows the state honestly (Saving / Saved / Unsaved changes / Offline).
 */
export class DraftSaver {
  private readonly debounceMs: number
  private readonly backoffMs: number[]
  private debounceTimer: ReturnType<typeof setTimeout> | undefined
  private retryTimer: ReturnType<typeof setTimeout> | undefined
  private inFlight: Promise<boolean> | null = null
  private attempts = 0
  private unsubscribe: (() => void) | undefined

  private readonly store: StudioStore
  private readonly saveFn: SaveFn

  constructor(store: StudioStore, saveFn: SaveFn, options: DraftSaverOptions = {}) {
    this.store = store
    this.saveFn = saveFn
    this.debounceMs = options.debounceMs ?? 1500
    this.backoffMs = options.backoffMs ?? [2000, 4000, 8000, 16000, 30000]
  }

  start(): void {
    this.unsubscribe = this.store.subscribe((state, prev) => {
      if (state.definition !== prev.definition && state.saveStatus !== 'conflict') this.schedule()
    })
  }

  stop(): void {
    this.unsubscribe?.()
    this.unsubscribe = undefined
    clearTimeout(this.debounceTimer)
    clearTimeout(this.retryTimer)
  }

  /** After the conflict dialog reloads the draft, autosave may run again. */
  resume(): void {
    this.attempts = 0
    clearTimeout(this.retryTimer)
  }

  private schedule(): void {
    clearTimeout(this.debounceTimer)
    this.debounceTimer = setTimeout(() => void this.flush(), this.debounceMs)
  }

  /** Save now if there is anything unsaved. Resolves true when the stored draft matches the editor. */
  async flush(): Promise<boolean> {
    clearTimeout(this.debounceTimer)
    clearTimeout(this.retryTimer)
    if (this.inFlight) {
      await this.inFlight
      return this.flush()
    }
    const before = this.store.getState()
    if (before.saveStatus === 'conflict') return false
    if (!isDirty(before)) return true
    this.inFlight = this.run()
    try {
      return await this.inFlight
    } finally {
      this.inFlight = null
    }
  }

  private async run(): Promise<boolean> {
    const { store } = this
    const sent = store.getState().definition
    store.setState({ saveStatus: 'saving', saveError: null })
    try {
      const result = await this.saveFn(savePayload(store.getState()), store.getState().revision)
      this.attempts = 0
      store.setState({ revision: result.revision, savedAt: result.savedAt, savedBy: result.savedBy, lastSaved: sent, saveStatus: 'idle', saveError: null })
      // Edits made while the request was in flight still need saving.
      if (isDirty(store.getState())) this.schedule()
      return !isDirty(store.getState())
    } catch (cause) {
      const error = isProblemError(cause) ? cause : new ProblemError({ code: 'UNKNOWN_ERROR', status: 0, title: 'Unexpected error' })
      if (error.code === 'STALE_REVISION') {
        store.setState({ saveStatus: 'conflict', saveError: error, conflictRevision: error.currentRevision ?? null })
        clearTimeout(this.debounceTimer)
        return false
      }
      const transient = error.code === 'NETWORK_ERROR' || error.status >= 500 || error.status === 429
      if (transient) {
        store.setState({ saveStatus: 'offline', saveError: error })
        const delay = this.backoffMs[Math.min(this.attempts, this.backoffMs.length - 1)] ?? 30_000
        this.attempts++
        this.retryTimer = setTimeout(() => void this.flush(), delay)
        return false
      }
      // 4xx other than 412: the local draft is kept; the next edit tries again.
      store.setState({ saveStatus: 'error', saveError: error })
      return false
    }
  }
}
