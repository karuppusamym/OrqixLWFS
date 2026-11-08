import type { Heatmap } from './api'
import { formatSeconds } from '@/features/insights/format'

/** Heat from 0 (cool) to 4 (hottest), so the overlay uses a few theme tokens rather than a free color scale. */
export type HeatLevel = 0 | 1 | 2 | 3 | 4

export interface StepHeat {
  nodeId: string
  visits: number
  runs: number
  averageSeconds: number | null
  p95Seconds: number | null
  level: HeatLevel
}

export interface RouteHeat {
  transitionId: string
  runs: number
  share: number
  level: HeatLevel
}

export interface HeatIndex {
  runs: number
  steps: Map<string, StepHeat>
  routes: Map<string, RouteHeat>
}

/** Ranks a value against the largest one: the slowest step (by p95) and the busiest connection are level 4. */
export function levelOf(value: number | null | undefined, max: number): HeatLevel {
  if (value === null || value === undefined || max <= 0 || value <= 0) return 0
  const ratio = value / max
  return ratio > 0.75 ? 4 : ratio > 0.5 ? 3 : ratio > 0.25 ? 2 : 1
}

/** CG-N01: step duration heat (p95, falling back to the average) and connection frequency heat. */
export function heatIndex(heatmap: Heatmap): HeatIndex {
  const duration = (s: Heatmap['steps'][number]) => s.p95Seconds ?? s.averageSeconds ?? 0
  const maxDuration = Math.max(0, ...heatmap.steps.map(duration))
  const maxRuns = Math.max(0, ...heatmap.routes.map((r) => r.runs))
  const steps = new Map<string, StepHeat>()
  for (const s of heatmap.steps) {
    steps.set(s.nodeId, { nodeId: s.nodeId, visits: s.visits, runs: s.runs, averageSeconds: s.averageSeconds, p95Seconds: s.p95Seconds, level: levelOf(duration(s), maxDuration) })
  }
  const routes = new Map<string, RouteHeat>()
  for (const r of heatmap.routes) routes.set(r.transitionId, { transitionId: r.transitionId, runs: r.runs, share: r.share, level: levelOf(r.runs, maxRuns) })
  return { runs: heatmap.runs, steps, routes }
}

/** "12 visits · avg 3 min · p95 20 min": what the badge on a step says, in words a screen reader can read too. */
export function stepHeatText(h: StepHeat): string {
  const visits = `${h.visits} ${h.visits === 1 ? 'visit' : 'visits'}`
  return h.averageSeconds === null ? visits : `${visits} · avg ${formatSeconds(h.averageSeconds)} · p95 ${formatSeconds(h.p95Seconds)}`
}

export function routeHeatText(h: RouteHeat): string {
  return `${h.runs} ${h.runs === 1 ? 'request' : 'requests'} (${Math.round(h.share * 100)}%)`
}

/** Badge classes per level: theme tokens only, and the text always says the numbers. */
export const HEAT_CLASS: Record<HeatLevel, string> = {
  0: 'border-border bg-surface text-muted-foreground',
  1: 'border-status-success-fg bg-status-success-bg text-status-success-fg',
  2: 'border-status-running-fg bg-status-running-bg text-status-running-fg',
  3: 'border-status-attention-fg bg-status-attention-bg text-status-attention-fg',
  4: 'border-danger bg-danger-soft text-danger-foreground',
}

/** Route stroke width by frequency, from thin (rarely used) to thick (most used). */
export const ROUTE_WIDTH: Record<HeatLevel, number> = { 0: 2, 1: 3, 2: 5, 3: 7, 4: 9 }
