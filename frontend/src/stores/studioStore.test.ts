import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import purchase from '../../../examples/purchase-approval.json'
import { ProblemError } from '@/api/problem'
import { normalizeDefinition } from '@/features/studio/model/types'
import { renameHttpResult } from '@/features/studio/model/ops'
import { DraftSaver, draftSaverTestTuning, type SaveFn } from './draftSaver'
import { createStudioStore, HISTORY_LIMIT, isDirty, type StudioStore } from './studioStore'

function loaded(): StudioStore {
  const store = createStudioStore()
  store.getState().load({ workflowId: 'wf-1', revision: 3, definition: normalizeDefinition(purchase), savedAt: '2026-10-07T10:00:00Z', savedBy: 'Dana Designer' })
  return store
}

const past = (store: StudioStore) => store.temporal.getState().pastStates.length
const future = (store: StudioStore) => store.temporal.getState().futureStates.length

describe('studio store: add, connect, delete, undo', () => {
  it('undoes connector result names and their references together in one action', () => {
    const store = loaded()
    const original = store.getState().definition
    store.getState().apply((def) => {
      const result = renameHttpResult(def, 'procure', 'procurementId', 'orderId')
      return result.ok ? result.def : def
    })
    expect(past(store)).toBe(1)
    expect(store.getState().definition.nodes.find((n) => n.id === 'procure')!.config.resultMapping).toHaveProperty('orderId')
    store.getState().undo()
    expect(store.getState().definition).toEqual(original)
    expect(isDirty(store.getState())).toBe(false)
  })
  it('starts clean with an empty undo history after load', () => {
    const store = loaded()
    expect(past(store)).toBe(0)
    expect(isDirty(store.getState())).toBe(false)
  })

  it('adds a step, connects it, deletes it, and undoes each edit in turn', () => {
    const store = loaded()
    const original = store.getState().definition
    const id = store.getState().addNode('notification')
    expect(store.getState().selection).toEqual({ type: 'node', id })
    expect(past(store)).toBe(1)

    const connected = store.getState().connect({ from: id, to: 'fulfilled', on: 'next' })
    expect(connected.ok).toBe(true)
    expect(past(store)).toBe(2)

    store.getState().removeNode(id)
    expect(store.getState().definition.nodes.some((n) => n.id === id)).toBe(false)
    expect(store.getState().selection).toBeNull()

    store.getState().undo() // delete
    expect(store.getState().definition.nodes.some((n) => n.id === id)).toBe(true)
    expect(store.getState().definition.transitions.some((t) => t.from === id)).toBe(true)
    store.getState().undo() // connect
    expect(store.getState().definition.transitions.some((t) => t.from === id)).toBe(false)
    store.getState().undo() // add
    expect(store.getState().definition).toBe(original)
    expect(isDirty(store.getState())).toBe(false)

    store.getState().redo()
    expect(store.getState().definition.nodes.some((n) => n.id === id)).toBe(true)
    expect(future(store)).toBe(2)
  })

  it('refuses invalid connections without touching the document or the history', () => {
    const store = loaded()
    const before = store.getState().definition
    const result = store.getState().connect({ from: 'fulfilled', to: 'start', on: 'next' })
    expect(result.ok).toBe(false)
    expect(store.getState().definition).toBe(before)
    expect(past(store)).toBe(0)
  })

  it('records one history entry for a drag (positions are committed at drag end) and none for no-ops', () => {
    const store = loaded()
    store.getState().moveNodes({ start: { x: 80, y: 200 } }) // unchanged
    expect(past(store)).toBe(0)
    store.getState().moveNodes({ start: { x: 120, y: 240 }, manager: { x: 340, y: 240 } })
    expect(past(store)).toBe(1)
    store.getState().undo()
    expect(store.getState().definition.uiLayout?.positions.start).toEqual({ x: 80, y: 200 })
  })

  it('arranging on open is undoable but counts as saved, so nothing autosaves until the next edit', () => {
    const store = loaded()
    store.getState().arrange({ start: { x: 500, y: 300 } }, { baseline: true })
    expect(store.getState().definition.uiLayout?.positions.start).toEqual({ x: 500, y: 300 })
    expect(past(store)).toBe(1)
    expect(isDirty(store.getState())).toBe(false)
    store.getState().updateNodeLabel('manager', 'Edited')
    expect(isDirty(store.getState())).toBe(true) // the next edit saves the arranged layout with it
    store.getState().undo()
    store.getState().undo()
    expect(store.getState().definition.uiLayout?.positions.start).toEqual({ x: 80, y: 200 }) // undo restores the stored layout
  })

  it('a button-triggered layout is a normal edit that autosaves', () => {
    const store = loaded()
    store.getState().moveNodes({ start: { x: 500, y: 300 } })
    expect(isDirty(store.getState())).toBe(true)
  })

  it('keeps selection, viewport and validation results out of the undo history', () => {
    const store = loaded()
    store.getState().select({ type: 'node', id: 'manager' })
    store.getState().setViewport({ x: 10, y: 20, zoom: 1.2 })
    store.getState().setIssues([], 3)
    expect(past(store)).toBe(0)
  })

  it('limits history to 100 entries', () => {
    const store = loaded()
    for (let i = 0; i < HISTORY_LIMIT + 25; i++) store.getState().updateNodeLabel('manager', `Review ${i}`)
    expect(past(store)).toBe(HISTORY_LIMIT)
  })

  it('treats the saved document as clean again after undoing back to it', () => {
    const store = loaded()
    store.getState().updateNodeLabel('manager', 'Manager sign-off')
    expect(isDirty(store.getState())).toBe(true)
    store.getState().undo()
    expect(isDirty(store.getState())).toBe(false)
  })

  it('clears the selection when undo removes the selected step', () => {
    const store = loaded()
    const id = store.getState().addNode('end')
    store.getState().undo()
    expect(store.getState().definition.nodes.some((n) => n.id === id)).toBe(false)
    expect(store.getState().selection).toBeNull()
  })
})

describe('draft autosave', () => {
  beforeEach(() => {
    draftSaverTestTuning.debounceMs = undefined // this suite asserts the spec's 1.5 s quiet period
    vi.useFakeTimers()
  })
  afterEach(() => vi.useRealTimers())

  const ok = (revision: number): SaveFn => vi.fn(async () => ({ revision, savedAt: '2026-10-07T10:05:00Z', savedBy: 'Dana Designer' }))

  it('saves 1.5 seconds after the last change, sending the last known revision', async () => {
    const store = loaded()
    const save = vi.fn<SaveFn>(async (_def, revision) => ({ revision: revision + 1, savedAt: 'now', savedBy: 'Dana' }))
    const saver = new DraftSaver(store, save)
    saver.start()

    store.getState().updateNodeLabel('manager', 'A')
    await vi.advanceTimersByTimeAsync(1000)
    store.getState().updateNodeLabel('manager', 'AB') // restarts the quiet period
    await vi.advanceTimersByTimeAsync(1400)
    expect(save).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(200)
    expect(save).toHaveBeenCalledTimes(1)
    expect(save.mock.calls[0]?.[1]).toBe(3)
    expect(store.getState().revision).toBe(4)
    expect(isDirty(store.getState())).toBe(false)

    store.getState().updateNodeLabel('manager', 'ABC')
    await vi.advanceTimersByTimeAsync(1500)
    expect(save.mock.calls[1]?.[1]).toBe(4)
    saver.stop()
  })

  it('saves again when more edits arrive while a save is in flight', async () => {
    const store = loaded()
    let release: (() => void) | undefined
    const save = vi.fn<SaveFn>(
      (_def, revision) =>
        new Promise((resolve) => {
          release = () => resolve({ revision: revision + 1, savedAt: 'now', savedBy: 'Dana' })
        }),
    )
    const saver = new DraftSaver(store, save)
    saver.start()
    store.getState().updateNodeLabel('manager', 'First')
    await vi.advanceTimersByTimeAsync(1500)
    expect(store.getState().saveStatus).toBe('saving')
    store.getState().updateNodeLabel('manager', 'Second')
    release?.()
    await vi.advanceTimersByTimeAsync(0)
    expect(isDirty(store.getState())).toBe(true)
    await vi.advanceTimersByTimeAsync(1500)
    expect(save).toHaveBeenCalledTimes(2)
    release?.()
    await vi.advanceTimersByTimeAsync(0)
    expect(isDirty(store.getState())).toBe(false)
    saver.stop()
  })

  it('stops autosave on 412 and reports the conflict', async () => {
    const store = loaded()
    const save = vi.fn<SaveFn>(async () => {
      throw new ProblemError({ code: 'STALE_REVISION', status: 412, title: 'stale', currentRevision: 5 })
    })
    const saver = new DraftSaver(store, save)
    saver.start()
    store.getState().updateNodeLabel('manager', 'Mine')
    await vi.advanceTimersByTimeAsync(1500)
    expect(store.getState().saveStatus).toBe('conflict')
    expect(store.getState().conflictRevision).toBe(5)
    store.getState().updateNodeLabel('manager', 'Mine too')
    await vi.advanceTimersByTimeAsync(5000)
    expect(save).toHaveBeenCalledTimes(1)
    expect(await saver.flush()).toBe(false)
    saver.stop()
  })

  it('keeps the local draft and retries with backoff when the network fails', async () => {
    const store = loaded()
    let calls = 0
    const save = vi.fn<SaveFn>(async (_def, revision) => {
      calls++
      if (calls < 3) throw new ProblemError({ code: 'NETWORK_ERROR', status: 0, title: 'offline' })
      return { revision: revision + 1, savedAt: 'now', savedBy: 'Dana' }
    })
    const saver = new DraftSaver(store, save, { backoffMs: [2000, 4000] })
    saver.start()
    store.getState().updateNodeLabel('manager', 'Offline edit')
    await vi.advanceTimersByTimeAsync(1500)
    expect(store.getState().saveStatus).toBe('offline')
    expect(isDirty(store.getState())).toBe(true)
    await vi.advanceTimersByTimeAsync(2000)
    expect(store.getState().saveStatus).toBe('offline')
    await vi.advanceTimersByTimeAsync(4000)
    expect(store.getState().saveStatus).toBe('idle')
    expect(store.getState().definition.nodes.find((n) => n.id === 'manager')?.label).toBe('Offline edit')
    expect(isDirty(store.getState())).toBe(false)
    saver.stop()
  })

  it('flush saves immediately (Ctrl+S) and resolves true when clean', async () => {
    const store = loaded()
    const save = ok(4)
    const saver = new DraftSaver(store, save)
    expect(await saver.flush()).toBe(true)
    expect(save).not.toHaveBeenCalled()
    store.getState().updateNodeLabel('manager', 'Now')
    expect(await saver.flush()).toBe(true)
    expect(save).toHaveBeenCalledTimes(1)
  })
})
