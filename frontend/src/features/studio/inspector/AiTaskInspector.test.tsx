import { describe, expect, it } from 'vitest'
import { aiRouteSlots } from './AiTaskInspector'

describe('aiRouteSlots (docs/build/S134)', () => {
  it('offers one path per label when the task decides, plus low confidence and AI unavailable', () => {
    const slots = aiRouteSlots('classify', 'auto', ['pricing', 'duplicate'])
    expect(slots.map((s) => `${s.on}${s.aiLabel ? `:${s.aiLabel}` : ''}`)).toEqual(['aiLabel:pricing', 'aiLabel:duplicate', 'lowConfidence', 'needsReview', 'aiUnavailable'])
    expect(slots.find((s) => s.on === 'needsReview')?.required).toBe(false)
  })

  it('has no label paths with suggest: a person decides', () => {
    expect(aiRouteSlots('classify', 'suggest', ['pricing']).some((s) => s.on === 'aiLabel')).toBe(false)
  })

  it('keeps shadow mode to its next step only', () => {
    expect(aiRouteSlots('classify', 'shadow', ['pricing'])).toEqual([{ on: 'next', title: 'Next step', required: true }])
  })

  it('gives extraction a review path and other modes next and AI unavailable', () => {
    expect(aiRouteSlots('extract', 'auto', []).map((s) => s.on)).toEqual(['next', 'needsReview', 'aiUnavailable'])
    expect(aiRouteSlots('score', 'suggest', []).map((s) => s.on)).toEqual(['next', 'aiUnavailable'])
  })
})
