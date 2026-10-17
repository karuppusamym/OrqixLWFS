import { temporal, type TemporalState } from 'zundo'
import { createStore, type StoreApi } from 'zustand/vanilla'
import type { ProblemError } from '@/api/problem'
import type { ValidationIssue } from '@/api/types'
import { nodeById } from '@/features/studio/model/graph'
import {
  addNode as addNodeOp,
  addNodeAfter as addNodeAfterOp,
  connect as connectOp,
  moveNodes as moveNodesOp,
  removeNode as removeNodeOp,
  removeTransition as removeTransitionOp,
  renameNodeId as renameNodeIdOp,
  setRoute as setRouteOp,
  updateNodeConfig as updateNodeConfigOp,
  updateNodeLabel as updateNodeLabelOp,
  updateTransition as updateTransitionOp,
  type ConnectRequest,
  type OpResult,
} from '@/features/studio/model/ops'
import type { Definition, NodeKind, Position, RouteKind } from '@/features/studio/model/types'

/**
 * Studio draft store (docs/build/S7-frontend.md section 5): Zustand with zundo temporal history.
 *
 * - Undo/redo covers the definition only (never selection, viewport, validation results or save state).
 * - History keeps 100 entries. Dragging records one entry at drag end because positions are committed only then.
 * - Every edit goes through the pure operations in features/studio/model/ops.ts; "no change" returns the same
 *   reference, so it never creates a history entry.
 */

export type SaveStatus = 'idle' | 'saving' | 'offline' | 'conflict' | 'error'
export type Selection = { type: 'node'; id: string } | { type: 'edge'; id: string } | null

export interface FocusRequest {
  nodeId: string
  fieldKey: string | null
  /** Increments so the same request can be issued twice. */
  n: number
}

export interface StudioState {
  definition: Definition
  workflowId: string
  /** Last revision known to be stored on the server. */
  revision: number
  savedAt: string | null
  savedBy: string | null
  /** The definition object that was last saved (or loaded). Dirty means "definition !== lastSaved". */
  lastSaved: Definition | null
  viewport: { x: number; y: number; zoom: number } | null
  saveStatus: SaveStatus
  saveError: ProblemError | null
  conflictRevision: number | null
  selection: Selection
  issues: ValidationIssue[]
  issuesRevision: number | null
  focusRequest: FocusRequest | null

  load: (init: { workflowId: string; revision: number; definition: Definition; savedAt?: string | null; savedBy?: string | null }) => void
  apply: (fn: (def: Definition) => Definition) => void
  /** Without `position`, the step goes right of (or below) the selected step, or `anchorId`, without overlapping. */
  addNode: (kind: NodeKind, opts?: { label?: string; position?: Position; anchorId?: string | null }) => string
  addNodeAfter: (afterId: string, kind: NodeKind, slot: { on: RouteKind; transitionId?: string } | null) => OpResult & { id?: string }
  connect: (req: ConnectRequest) => OpResult & { transitionId?: string }
  setRoute: (fromId: string, on: RouteKind, toId: string | null, transitionId?: string) => OpResult
  removeTransition: (id: string) => void
  updateTransition: (id: string, patch: Parameters<typeof updateTransitionOp>[2]) => void
  removeNode: (id: string) => void
  removeSelection: () => void
  updateNodeLabel: (id: string, label: string) => void
  updateNodeConfig: (id: string, patch: Record<string, unknown>) => void
  renameNodeId: (oldId: string, newId: string) => OpResult
  moveNodes: (positions: Record<string, Position>) => void
  /** An undoable layout change. With `baseline`, the arranged draft counts as saved, so nothing autosaves until the next edit. */
  arrange: (positions: Record<string, Position>, opts?: { baseline?: boolean }) => void
  select: (selection: Selection) => void
  setViewport: (viewport: { x: number; y: number; zoom: number }) => void
  undo: () => void
  redo: () => void
  setIssues: (issues: ValidationIssue[], revision: number) => void
  requestFocus: (nodeId: string, fieldKey: string | null) => void
}

type PartialHistory = Pick<StudioState, 'definition'>
export type StudioStore = StoreApi<StudioState> & { temporal: StoreApi<TemporalState<PartialHistory>> }

export const HISTORY_LIMIT = 100

const emptyDefinition = (): Definition => ({
  schemaVersion: '0.1',
  workflowKey: '',
  name: '',
  metadata: { description: '' },
  inputSchema: { type: 'object', additionalProperties: false, required: [], properties: {} },
  outputSchema: { type: 'object', additionalProperties: false, properties: {} },
  triggers: ['manual'],
  nodes: [],
  transitions: [],
  decisions: [],
  execution: { maxConcurrentRuns: 500, maxAttempts: 3, retryDelaySeconds: 30 },
  security: { selfApproval: 'deny' },
  dependencies: { connections: [] },
  outputMapping: {},
  uiLayout: { positions: {} },
})

export function createStudioStore(): StudioStore {
  const initial = emptyDefinition()
  const store = createStore<StudioState>()(
    temporal(
      (set, get) => {
        const apply = (fn: (def: Definition) => Definition) => {
          const current = get().definition
          const next = fn(current)
          if (next !== current) set({ definition: next })
        }
        /** Drop a selection that points at something that no longer exists. */
        const repairSelection = () => {
          const { selection, definition } = get()
          if (!selection) return
          const exists = selection.type === 'node' ? !!nodeById(definition, selection.id) : definition.transitions.some((t) => t.id === selection.id)
          if (!exists) set({ selection: null })
        }
        return {
          definition: initial,
          workflowId: '',
          revision: 0,
          savedAt: null,
          savedBy: null,
          lastSaved: null,
          viewport: null,
          saveStatus: 'idle',
          saveError: null,
          conflictRevision: null,
          selection: null,
          issues: [],
          issuesRevision: null,
          focusRequest: null,

          load: ({ workflowId, revision, definition, savedAt = null, savedBy = null }) => {
            set({
              definition,
              workflowId,
              revision,
              savedAt,
              savedBy,
              lastSaved: definition,
              viewport: definition.uiLayout?.viewport ?? null,
              saveStatus: 'idle',
              saveError: null,
              conflictRevision: null,
              selection: null,
              issues: [],
              issuesRevision: null,
            })
            // A freshly loaded draft starts with an empty undo history.
            ;(store as unknown as StudioStore).temporal.getState().clear()
          },
          apply,
          addNode: (kind, opts = {}) => {
            const selected = get().selection
            const anchorId = opts.anchorId ?? (selected?.type === 'node' ? selected.id : null)
            const result = addNodeOp(get().definition, kind, { ...opts, anchorId })
            set({ definition: result.def, selection: { type: 'node', id: result.id } })
            return result.id
          },
          addNodeAfter: (afterId, kind, slot) => {
            const result = addNodeAfterOp(get().definition, afterId, kind, slot)
            if (result.ok) set({ definition: result.def, ...(result.id ? { selection: { type: 'node' as const, id: result.id } } : {}) })
            return result
          },
          connect: (req) => {
            const result = connectOp(get().definition, req)
            if (result.ok) set({ definition: result.def })
            return result
          },
          setRoute: (fromId, on, toId, transitionId) => {
            const result = setRouteOp(get().definition, fromId, on, toId, transitionId)
            if (result.ok && result.def !== get().definition) set({ definition: result.def })
            return result
          },
          removeTransition: (id) => {
            apply((d) => removeTransitionOp(d, id))
            repairSelection()
          },
          updateTransition: (id, patch) => apply((d) => updateTransitionOp(d, id, patch)),
          removeNode: (id) => {
            apply((d) => removeNodeOp(d, id))
            repairSelection()
          },
          removeSelection: () => {
            const { selection } = get()
            if (!selection) return
            if (selection.type === 'node') get().removeNode(selection.id)
            else get().removeTransition(selection.id)
          },
          updateNodeLabel: (id, label) => apply((d) => updateNodeLabelOp(d, id, label)),
          updateNodeConfig: (id, patch) => apply((d) => updateNodeConfigOp(d, id, patch)),
          renameNodeId: (oldId, newId) => {
            const result = renameNodeIdOp(get().definition, oldId, newId)
            if (result.ok && result.def !== get().definition) {
              set({ definition: result.def })
              const sel = get().selection
              if (sel?.type === 'node' && sel.id === oldId) set({ selection: { type: 'node', id: newId } })
            }
            return result
          },
          moveNodes: (positions) => apply((d) => moveNodesOp(d, positions)),
          arrange: (positions, opts) => {
            apply((d) => moveNodesOp(d, positions))
            if (opts?.baseline) set({ lastSaved: get().definition })
          },
          select: (selection) => set({ selection }),
          setViewport: (viewport) => set({ viewport }),
          undo: () => {
            ;(store as unknown as StudioStore).temporal.getState().undo()
            repairSelection()
          },
          redo: () => {
            ;(store as unknown as StudioStore).temporal.getState().redo()
            repairSelection()
          },
          setIssues: (issues, revision) => set({ issues, issuesRevision: revision }),
          requestFocus: (nodeId, fieldKey) => set((s) => ({ focusRequest: { nodeId, fieldKey, n: (s.focusRequest?.n ?? 0) + 1 } })),
        }
      },
      {
        limit: HISTORY_LIMIT,
        partialize: (state): PartialHistory => ({ definition: state.definition }),
        // Record a history entry only when the definition object changed.
        equality: (past, current) => past.definition === current.definition,
      },
    ),
  )
  return store as unknown as StudioStore
}

export function isDirty(state: Pick<StudioState, 'definition' | 'lastSaved'>): boolean {
  return state.lastSaved !== state.definition
}

/** The document to store: the definition plus the current canvas viewport (not undoable, so merged at save time). */
export function savePayload(state: Pick<StudioState, 'definition' | 'viewport'>): Definition {
  if (!state.viewport) return state.definition
  return { ...state.definition, uiLayout: { positions: state.definition.uiLayout?.positions ?? {}, viewport: state.viewport } }
}
