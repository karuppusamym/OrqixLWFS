import { NodeToolbar, Position } from '@xyflow/react'
import { Flame } from 'lucide-react'
import { createContext, useContext, useMemo, useState, type ReactNode } from 'react'
import { ScrollTable } from '@/components/common/ScrollTable'
import { Select } from '@/components/ui/input'
import { TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { cn } from '@/lib/cn'
import { formatDateTime } from '@/lib/dates'
import { formatSeconds, rangeFor } from '@/features/insights/format'
import { useHeatmap, type Heatmap } from './api'
import { HEAT_CLASS, heatIndex, routeHeatText, stepHeatText, type HeatIndex, type RouteHeat } from './heatmap'

const HeatContext = createContext<HeatIndex | null>(null)

/** Makes the heat figures available to the route components of a canvas (Studio or run diagram). */
export function HeatmapProvider({ index, children }: { index: HeatIndex | null; children: ReactNode }) {
  return <HeatContext.Provider value={index}>{children}</HeatContext.Provider>
}

export function useRouteHeat(transitionId: string): RouteHeat | null {
  return useContext(HeatContext)?.routes.get(transitionId) ?? null
}

/** The pill a route shows while the heatmap is on: how many requests used it. */
export function RouteHeatLabel({ transitionId, x, y }: { transitionId: string; x: number; y: number }) {
  const heat = useRouteHeat(transitionId)
  if (!heat) return null
  return (
    <span
      aria-hidden="true"
      data-testid={`route-heat-${transitionId}`}
      className={cn('pointer-events-none absolute rounded-full border px-2 py-0.5 text-xs font-bold tabular-nums shadow-card', HEAT_CLASS[heat.level])}
      style={{ transform: `translate(-50%, -50%) translate(${x}px, ${y + 22}px)` }}
    >
      {heat.runs}
    </span>
  )
}

/** Badges above the steps (inside a React Flow canvas): visits, average and p95 time, colored by p95. */
export function HeatBadges({ index }: { index: HeatIndex }) {
  return (
    <>
      {[...index.steps.values()].map((h) => (
        <NodeToolbar key={h.nodeId} nodeId={h.nodeId} isVisible position={Position.Top} offset={26}>
          <span
            aria-hidden="true"
            data-testid={`step-heat-${h.nodeId}`}
            className={cn('pointer-events-none inline-flex items-center gap-1 rounded-full border-2 px-2.5 py-0.5 text-xs font-bold tabular-nums shadow-card', HEAT_CLASS[h.level])}
          >
            <Flame className="size-3.5" aria-hidden="true" />
            {stepHeatText(h)}
          </span>
        </NodeToolbar>
      ))}
    </>
  )
}

const RANGES = [7, 30, 90] as const

export interface HeatmapSettings {
  on: boolean
  days: (typeof RANGES)[number]
  releaseId: string | null
}

export function useHeatmapSettings(initialRelease: string | null = null) {
  return useState<HeatmapSettings>({ on: false, days: 30, releaseId: initialRelease })
}

/**
 * Heatmap for a workflow (CG-N01, docs/build/S145): fetched only while it is switched on. The release list is optional;
 * without one the figures cover every release of the workflow.
 */
export function useHeatIndex(ws: string, workflowId: string, settings: HeatmapSettings) {
  const query = useMemo(() => ({ workflowId, ...rangeFor(settings.days), ...(settings.releaseId ? { releaseId: settings.releaseId } : {}) }), [workflowId, settings.days, settings.releaseId])
  const result = useHeatmap(ws, query, settings.on)
  const index = useMemo(() => (settings.on && result.data ? heatIndex(result.data) : null), [settings.on, result.data])
  return { result, index }
}

/** Switch, period and release for the heatmap overlay, with how current the figures are. */
export function HeatmapControls({ settings, onChange, releases, data, loading, error }: {
  settings: HeatmapSettings
  onChange: (next: HeatmapSettings) => void
  releases?: { id: string; label: string }[]
  data?: Heatmap
  loading?: boolean
  error?: string | null
}) {
  return (
    <div className="flex flex-wrap items-end gap-3 text-sm" data-testid="heatmap-controls">
      <label className="inline-flex min-h-10 items-center gap-2 font-semibold">
        <input type="checkbox" checked={settings.on} onChange={(e) => onChange({ ...settings, on: e.target.checked })} />
        <Flame className="size-4" aria-hidden="true" /> Heatmap
      </label>
      {settings.on ? (
        <>
          <div>
            <label htmlFor="heatmap-days" className="mb-1 block text-xs font-semibold text-muted-foreground">Period</label>
            <Select id="heatmap-days" value={String(settings.days)} onChange={(e) => onChange({ ...settings, days: Number(e.target.value) as HeatmapSettings['days'] })}>
              {RANGES.map((d) => <option key={d} value={d}>Last {d} days</option>)}
            </Select>
          </div>
          {releases && releases.length > 0 ? (
            <div>
              <label htmlFor="heatmap-release" className="mb-1 block text-xs font-semibold text-muted-foreground">Release</label>
              <Select id="heatmap-release" value={settings.releaseId ?? ''} onChange={(e) => onChange({ ...settings, releaseId: e.target.value || null })}>
                <option value="">All releases</option>
                {releases.map((r) => <option key={r.id} value={r.id}>{r.label}</option>)}
              </Select>
            </div>
          ) : null}
          <p className="pb-2 text-muted-foreground" role="status" data-testid="heatmap-status">
            {error ? error
              : loading || !data ? 'Loading the figures…'
                : `${data.runs} ${data.runs === 1 ? 'request' : 'requests'} started ${data.range.from} to ${data.range.to}. ${data.freshness.lastProjectedAt ? `Figures as of ${formatDateTime(data.freshness.lastProjectedAt)}` : 'No figures yet'}${data.freshness.pendingUpdates > 0 ? `, ${data.freshness.pendingUpdates} updates still to come` : ''}.`}
          </p>
        </>
      ) : null}
    </div>
  )
}

/** The heatmap as a table, for keyboard and screen reader users and for exact numbers. */
export function HeatmapTable({ data, stepLabel, routeLabel }: {
  data: Heatmap
  stepLabel: (nodeId: string) => string
  routeLabel: (transitionId: string) => string
}) {
  const index = heatIndex(data)
  return (
    <details className="rounded-card border border-border bg-surface" data-testid="heatmap-table">
      <summary className="cursor-pointer px-4 py-3 text-sm font-semibold">Heatmap as a table</summary>
      <div className="space-y-4 px-4 pb-4">
        {data.steps.length === 0 ? <p className="text-sm text-muted-foreground">No finished steps in this period yet.</p> : (
          <ScrollTable label="Time and visits per step">
            <THead><TR><TH>Step</TH><TH>Visits</TH><TH>Requests</TH><TH>Average</TH><TH>95th percentile</TH><TH>Longest</TH></TR></THead>
            <TBody>
              {data.steps.map((s) => (
                <TR key={s.nodeId}>
                  <TD className="font-semibold">{stepLabel(s.nodeId)}</TD>
                  <TD className="tabular-nums">{s.visits}</TD>
                  <TD className="tabular-nums">{s.runs}</TD>
                  <TD className="tabular-nums">{formatSeconds(s.averageSeconds)}</TD>
                  <TD className="tabular-nums">{formatSeconds(s.p95Seconds)}</TD>
                  <TD className="tabular-nums">{formatSeconds(s.maxSeconds)}</TD>
                </TR>
              ))}
            </TBody>
          </ScrollTable>
        )}
        {data.routes.length > 0 ? (
          <ScrollTable label="Requests per connection">
            <THead><TR><TH>Connection</TH><TH>Requests</TH></TR></THead>
            <TBody>
              {data.routes.map((r) => (
                <TR key={r.transitionId}>
                  <TD className="font-semibold">{routeLabel(r.transitionId)}</TD>
                  <TD className="tabular-nums">{routeHeatText(index.routes.get(r.transitionId)!)}</TD>
                </TR>
              ))}
            </TBody>
          </ScrollTable>
        ) : null}
      </div>
    </details>
  )
}
