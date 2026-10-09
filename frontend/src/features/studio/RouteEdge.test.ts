import { describe, expect, it } from 'vitest'
import { roundedPath, routeGeometry } from './RouteEdge'

const rect = (x: number, y: number, width = 296, height = 128) => ({ x, y, width, height })

describe('route geometry', () => {
  it('a short forward route turns beside its source and runs into the target along the target row', () => {
    const g = routeGeometry({ x: 376, y: 100 }, { x: 472, y: 260 }, [], 0)
    expect(g.detour).toBe(false)
    expect(g.path.startsWith('M 376 100')).toBe(true)
    expect(g.path.endsWith('L 472 260')).toBe(true)
    // The label sits on the final straight run, on the target row.
    expect(g.label.y).toBe(260)
  })

  it('a route that would run through another step takes a lane below all the steps instead', () => {
    const blocking = rect(400, 40) // right in the way of a straight run at y = 100
    const g = routeGeometry({ x: 376, y: 100 }, { x: 1200, y: 100 }, [blocking, rect(800, 40)], 90)
    expect(g.detour).toBe(true)
    // Below the lowest step (bottom 168) plus the lane gap.
    expect(g.label.y).toBeGreaterThanOrEqual(168 + 40)
    expect(g.label.x).toBeGreaterThan(376)
    expect(g.label.x).toBeLessThan(1200)
  })

  it('a route that cannot go forward (target left of source) is also a detour', () => {
    expect(routeGeometry({ x: 800, y: 100 }, { x: 600, y: 100 }, [], 0).detour).toBe(true)
  })

  it('routes with different lane shifts do not share a lane', () => {
    const others = [rect(400, 40)]
    const a = routeGeometry({ x: 376, y: 100 }, { x: 1200, y: 100 }, others, 0, 0)
    const b = routeGeometry({ x: 376, y: 100 }, { x: 1200, y: 100 }, others, 0, 22)
    expect(b.label.y - a.label.y).toBe(22)
  })

  it('rounds corners without leaving the orthogonal grid', () => {
    const path = roundedPath([{ x: 0, y: 0 }, { x: 40, y: 0 }, { x: 40, y: 40 }], 10)
    expect(path).toBe('M 0 0 L 30 0 Q 40 0 40 10 L 40 40')
  })
})
