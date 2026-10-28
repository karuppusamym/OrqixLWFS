import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useDictation } from './useDictation'

class FakeRecognition {
  static created: FakeRecognition[] = []
  lang = ''
  continuous = false
  interimResults = false
  onresult: ((event: unknown) => void) | null = null
  onerror: ((event: { error: string }) => void) | null = null
  onend: (() => void) | null = null
  start = vi.fn()
  stop = vi.fn(() => { this.onend?.() })
  constructor() { FakeRecognition.created.push(this) }
}

describe('useDictation', () => {
  beforeEach(() => {
    FakeRecognition.created = []
    ;(window as unknown as { SpeechRecognition: unknown }).SpeechRecognition = FakeRecognition
  })
  afterEach(() => {
    delete (window as unknown as { SpeechRecognition?: unknown }).SpeechRecognition
  })

  it('runs one recogniser at a time, so Stop always stops the one that is listening', () => {
    const { result } = renderHook(() => useDictation(() => {}))
    act(() => result.current.start())
    act(() => result.current.start())
    expect(FakeRecognition.created).toHaveLength(1)
    expect(result.current.listening).toBe(true)

    act(() => result.current.stop())
    expect(FakeRecognition.created[0]!.stop).toHaveBeenCalledTimes(1)
    expect(result.current.listening).toBe(false)

    // After it ended, a new start creates a fresh recogniser.
    act(() => result.current.start())
    expect(FakeRecognition.created).toHaveLength(2)
  })

  it('a start that throws leaves nothing behind and explains it', () => {
    const { result } = renderHook(() => useDictation(() => {}))
    const throwing = vi.fn(() => { throw new Error('busy') })
    class Throwing extends FakeRecognition { override start = throwing }
    ;(window as unknown as { SpeechRecognition: unknown }).SpeechRecognition = Throwing
    act(() => result.current.start())
    expect(result.current.problem).toBe('Dictation could not start.')
    expect(result.current.listening).toBe(false)

    // The failed recogniser is not kept: the next start tries again.
    ;(window as unknown as { SpeechRecognition: unknown }).SpeechRecognition = FakeRecognition
    act(() => result.current.start())
    expect(result.current.listening).toBe(true)
  })

  it('stops quietly when the page goes away', () => {
    const { result, unmount } = renderHook(() => useDictation(() => {}))
    act(() => result.current.start())
    const rec = FakeRecognition.created[0]!
    unmount()
    expect(rec.stop).toHaveBeenCalledTimes(1)
    expect(rec.onend).toBeNull()
  })

  it('passes final words to the text box', () => {
    const heard: string[] = []
    const { result } = renderHook(() => useDictation((text) => heard.push(text)))
    act(() => result.current.start())
    act(() => FakeRecognition.created[0]!.onresult?.({ resultIndex: 0, results: [{ isFinal: true, 0: { transcript: ' buy a laptop ' } }] }))
    expect(heard).toEqual(['buy a laptop'])
  })
})
