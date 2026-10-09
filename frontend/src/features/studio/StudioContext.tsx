import { createContext, useContext, type ReactNode } from 'react'
import { useStore } from 'zustand'
import type { ConnectionLookup } from './model/fieldRefs'
import type { DraftSaver } from '@/stores/draftSaver'
import type { StudioState, StudioStore } from '@/stores/studioStore'

export interface StudioContextValue {
  store: StudioStore
  saver: DraftSaver
  wsId: string
  workflowId: string
  /** No editing: missing workflow.edit permission, or a screen narrower than 1024 px. */
  readOnly: boolean
  /** Response types of connection operations, for result field chips. */
  connections: ConnectionLookup
}

const StudioContext = createContext<StudioContextValue | null>(null)

export function StudioProvider({ value, children }: { value: StudioContextValue; children: ReactNode }) {
  return <StudioContext.Provider value={value}>{children}</StudioContext.Provider>
}

export function useStudioContext(): StudioContextValue {
  const ctx = useContext(StudioContext)
  if (!ctx) throw new Error('useStudioContext must be used inside StudioProvider')
  return ctx
}

/** Select from the studio store. */
export function useStudio<T>(selector: (state: StudioState) => T): T {
  return useStore(useStudioContext().store, selector)
}

export function useStudioStore(): StudioStore {
  return useStudioContext().store
}

export function useCanUndo(): { canUndo: boolean; canRedo: boolean } {
  const { store } = useStudioContext()
  const canUndo = useStore(store.temporal, (s) => s.pastStates.length > 0)
  const canRedo = useStore(store.temporal, (s) => s.futureStates.length > 0)
  return { canUndo, canRedo }
}
