import { describe, expect, it } from 'vitest'
import { isoOf, offsets, scheduleOf, seconds, summary, worstCase } from './pollSchedule'

describe('pollSchedule (twin of the compiler PollSchedule, docs/build/S129)', () => {
  it('matches the worked timing of the design: 52 checks over 2 days', () => {
    const s = scheduleOf({ every: 'PT5M', backoff: { multiplier: 2, maxInterval: 'PT1H' }, giveUpAfter: 'P2D' })!
    const at = offsets(s, 1000)
    expect(at.slice(0, 6)).toEqual([0, 300, 900, 2100, 4500, 8100])
    expect(at).toHaveLength(52)
    expect(at[50]).toBe(47 * 3600 + 15 * 60)
    expect(at[51]).toBe(172_800)
    expect(summary(s, 'ServiceNow', 500)).toBe(
      'Checks up to 52 times over 2 days; from 1 hour 15 minutes on, once an hour. With 500 requests waiting this is about 500 calls an hour to ServiceNow.',
    )
  })

  it('handles a constant interval, a first check after the interval and no final check', () => {
    const s = scheduleOf({ every: 'PT15M', giveUpAfter: 'PT1H', firstCheck: 'afterInterval', finalCheckAtDeadline: false })!
    expect(offsets(s, 100)).toEqual([900, 1800, 2700])
    expect(summary(s, 'X', 0)).toBe('Checks up to 3 times over 1 hour; every 15 minutes.')
  })

  it('reports the worst case and refuses timing out of bounds', () => {
    expect(worstCase({ every: 'PT1M', giveUpAfter: 'P2D' })).toBeGreaterThan(1000)
    expect(scheduleOf({ every: 'PT5S', giveUpAfter: 'PT1H' })).toBeNull()
    expect(scheduleOf({ every: 'PT1H', giveUpAfter: 'PT30M' })).toBeNull()
    expect(scheduleOf({ every: 'PT1M', giveUpAfter: 'P2D', maxChecks: 200 })!.maxChecks).toBe(200)
  })

  it('parses and writes durations with seconds', () => {
    expect(seconds('PT30S')).toBe(30)
    expect(seconds('P1DT2H')).toBe(93_600)
    expect(seconds('PT1W')).toBe(-1)
    expect(isoOf(90)).toBe('PT1M30S')
    expect(isoOf(172_800)).toBe('P2D')
  })
})
