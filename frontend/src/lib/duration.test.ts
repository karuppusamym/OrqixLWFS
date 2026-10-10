import { describe, expect, it } from 'vitest'
import { describeMinutes, durationMinutes, durationPreview } from './duration'

describe('durations', () => {
  it('parses days, hours and minutes only', () => {
    expect(durationMinutes('PT1M')).toBe(1)
    expect(durationMinutes('P2D')).toBe(2880)
    expect(durationMinutes('P1DT12H')).toBe(2160)
    for (const bad of ['P', 'PT', '2 days', 'PT30S', 'P1W', 'P1Y']) expect(durationMinutes(bad)).toBeNull()
  })
  it('describes them in plain language', () => {
    expect(describeMinutes(1)).toBe('1 minute')
    expect(describeMinutes(2880)).toBe('2 days')
    expect(describeMinutes(2160)).toBe('1 day 12 hours')
  })
  it('previews and explains limits', () => {
    expect(durationPreview('P2D', 'waits')).toEqual({ ok: true, text: 'Waits 2 days.' })
    expect(durationPreview('PT4H', 'allows').text).toBe('Allows 4 hours.')
    expect(durationPreview('PT0M', 'waits').ok).toBe(false)
    expect(durationPreview('P91D', 'waits').ok).toBe(false)
    expect(durationPreview('soon', 'waits').ok).toBe(false)
  })
})
