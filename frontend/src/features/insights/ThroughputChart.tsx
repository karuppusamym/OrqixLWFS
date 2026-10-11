import type { components } from '@/api/schema'
import { ScrollTable } from '@/components/common/ScrollTable'
import { TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { formatCalendarDate as formatDay } from '@/lib/dates'

type Day = components['schemas']['InsightsDay']

const W = 720
const H = 240
const LEFT = 40
const RIGHT = 8
const TOP = 12
const BOTTOM = 44

/** A top value that divides into four whole-number gridlines. */
function niceMax(value: number): number {
  return Math.max(4, Math.ceil(value / 4) * 4)
}

/**
 * Daily throughput: bars for requests completed (Fulfilled with a solid fill, Rejected with a pattern) and a line with
 * round markers for requests started. Plain SVG with theme tokens only; the legend names every series, every bar and
 * point has a text equivalent in the table below the chart, and nothing depends on color alone (bar pattern, line shape).
 */
export function ThroughputChart({ days }: { days: Day[] }) {
  const max = niceMax(Math.max(1, ...days.map((d) => Math.max(d.started, d.completed))))
  const plotW = W - LEFT - RIGHT
  const plotH = H - TOP - BOTTOM
  const slot = plotW / Math.max(1, days.length)
  const barW = Math.max(2, Math.min(18, slot * 0.62))
  const x = (i: number) => LEFT + slot * i + slot / 2
  const y = (v: number) => TOP + plotH - (v / max) * plotH
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((t) => Math.round(max * t))
  const labelEvery = Math.max(1, Math.ceil(days.length / 8))
  const totalStarted = days.reduce((a, d) => a + d.started, 0)
  const totalCompleted = days.reduce((a, d) => a + d.completed, 0)
  const line = days.map((d, i) => `${x(i)},${y(d.started)}`).join(' ')

  return (
    <div data-testid="throughput-chart">
      <ul className="mb-3 flex flex-wrap gap-x-5 gap-y-1 text-sm" aria-label="Chart legend">
        <li className="inline-flex items-center gap-2">
          <svg width="18" height="14" aria-hidden="true">
            <rect width="18" height="14" className="fill-success" />
          </svg>
          Completed, fulfilled
        </li>
        <li className="inline-flex items-center gap-2">
          <svg width="18" height="14" aria-hidden="true">
            <rect width="18" height="14" fill="url(#rejected-pattern)" className="stroke-status-rejected-fg" strokeWidth="1.5" />
          </svg>
          Completed, rejected
        </li>
        <li className="inline-flex items-center gap-2">
          <svg width="26" height="14" aria-hidden="true">
            <line x1="0" y1="7" x2="26" y2="7" className="stroke-foreground" strokeWidth="2" />
            <circle cx="13" cy="7" r="4" className="fill-surface stroke-foreground" strokeWidth="2" />
          </svg>
          Started
        </li>
      </ul>
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`Requests per day, ${days.length} days: ${totalStarted} started and ${totalCompleted} completed. The table below has every day.`} className="h-auto w-full">
        <defs>
          <pattern id="rejected-pattern" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
            <rect width="6" height="6" className="fill-status-rejected-bg" />
            <line x1="0" y1="0" x2="0" y2="6" className="stroke-status-rejected-fg" strokeWidth="2.5" />
          </pattern>
        </defs>
        {ticks.map((t) => (
          <g key={t}>
            <line x1={LEFT} x2={W - RIGHT} y1={y(t)} y2={y(t)} className="stroke-border" strokeWidth="1" />
            <text x={LEFT - 6} y={y(t) + 4} textAnchor="end" className="fill-muted-foreground text-[12px]">
              {t}
            </text>
          </g>
        ))}
        {days.map((d, i) => (
          <g key={d.date}>
            <title>{`${formatDay(d.date)}: ${d.started} started, ${d.completed} completed (${d.fulfilled} fulfilled, ${d.rejected} rejected)`}</title>
            {d.fulfilled > 0 ? <rect x={x(i) - barW / 2} y={y(d.fulfilled)} width={barW} height={y(0) - y(d.fulfilled)} className="fill-success" /> : null}
            {d.rejected > 0 ? <rect x={x(i) - barW / 2} y={y(d.fulfilled + d.rejected)} width={barW} height={y(d.fulfilled) - y(d.fulfilled + d.rejected)} fill="url(#rejected-pattern)" className="stroke-status-rejected-fg" strokeWidth="1" /> : null}
            {i % labelEvery === 0 ? (
              <text x={x(i)} y={H - BOTTOM + 18} textAnchor="middle" className="fill-muted-foreground text-[12px]">
                {formatDay(d.date)}
              </text>
            ) : null}
          </g>
        ))}
        <polyline points={line} fill="none" className="stroke-foreground" strokeWidth="2" />
        {days.length <= 45 ? days.map((d, i) => <circle key={d.date} cx={x(i)} cy={y(d.started)} r="3.5" className="fill-surface stroke-foreground" strokeWidth="2" />) : null}
      </svg>

      <details className="mt-3 rounded-card border border-border">
        <summary className="cursor-pointer px-4 py-2.5 text-sm font-semibold">Show the numbers as a table</summary>
        <div className="px-2 pb-2">
          <ScrollTable label="Requests started and completed per day" className="max-h-80">
            <THead>
              <TR>
                <TH>Day</TH>
                <TH>Started</TH>
                <TH>Completed</TH>
                <TH>Fulfilled</TH>
                <TH>Rejected</TH>
              </TR>
            </THead>
            <TBody>
              {days.map((d) => (
                <TR key={d.date}>
                  <TD>{formatDay(d.date)}</TD>
                  <TD className="tabular-nums">{d.started}</TD>
                  <TD className="tabular-nums">{d.completed}</TD>
                  <TD className="tabular-nums">{d.fulfilled}</TD>
                  <TD className="tabular-nums">{d.rejected}</TD>
                </TR>
              ))}
            </TBody>
          </ScrollTable>
        </div>
      </details>
    </div>
  )
}
