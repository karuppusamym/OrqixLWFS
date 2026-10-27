import { LayoutGrid, Shapes } from 'lucide-react'
import { useId } from 'react'
import { cn } from '@/lib/cn'
import { KINDS } from '../model/kinds'
import { BPMN_NOTATION, type BpmnShape, type Notation } from '../model/notation'
import { SHAPE_SIZES } from '../model/shapeSizes'
import type { NodeKind } from '../model/types'
import { KIND_VAR } from '../nodeMeta'
import { BpmnGlyph } from './BpmnGlyph'

/**
 * "Notation" switch (STU01): BPMN shapes or step cards. Two toggle buttons in a labelled group; the pressed one is
 * said in words (aria-pressed) and drawn filled, never by colour alone.
 */
export function NotationToggle({ value, onChange, className }: { value: Notation; onChange: (value: Notation) => void; className?: string }) {
  const labelId = useId()
  const options: { value: Notation; label: string; Icon: typeof Shapes }[] = [
    { value: 'bpmn', label: 'BPMN shapes', Icon: Shapes },
    { value: 'cards', label: 'Cards', Icon: LayoutGrid },
  ]
  return (
    <div role="group" aria-labelledby={labelId} className={cn('inline-flex items-center gap-2 rounded-control border border-border bg-surface p-1 shadow-card', className)} data-testid="notation-toggle">
      <span id={labelId} className="pl-1.5 text-sm font-semibold text-foreground">
        Notation
      </span>
      {options.map(({ value: option, label, Icon }) => {
        const pressed = value === option
        return (
          <button
            key={option}
            type="button"
            aria-pressed={pressed}
            onClick={() => onChange(option)}
            className={cn(
              'inline-flex min-h-8 items-center gap-1.5 rounded-control border px-2.5 text-sm font-semibold focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring',
              pressed ? 'border-primary bg-primary text-primary-foreground' : 'border-border bg-surface text-foreground hover:bg-neutral-soft',
            )}
          >
            <Icon className="size-4" aria-hidden="true" />
            {label}
          </button>
        )
      })}
    </div>
  )
}

interface LegendRow {
  shape: BpmnShape
  symbol: string
  element: string
  kinds: NodeKind[]
}

/** One row per BPMN symbol, with every step kind drawn with it. Every kind appears exactly once. */
export function legendRows(): LegendRow[] {
  const rows = new Map<string, LegendRow>()
  for (const kind of Object.keys(KINDS) as NodeKind[]) {
    const n = BPMN_NOTATION[kind]
    const key = `${n.shape}|${n.element}`
    const row = rows.get(key)
    if (row) row.kinds.push(kind)
    else rows.set(key, { shape: n.shape, symbol: n.symbol, element: n.element, kinds: [kind] })
  }
  return [...rows.values()]
}

/** Draw a legend symbol at a small, fixed height, keeping the shape's proportions. */
function LegendGlyph({ shape, kind }: { shape: BpmnShape; kind: NodeKind }) {
  const box = SHAPE_SIZES[BPMN_NOTATION[kind].sizeClass]
  const scale = box.width > 64 ? 0.32 : 0.6
  const colors = kind === 'end' ? { stroke: 'var(--status-success-fg)', fill: 'var(--status-success-bg)', marker: 'var(--status-success-fg)' } : { stroke: KIND_VAR[kind], fill: 'var(--surface)', marker: KIND_VAR[kind] }
  return (
    <span className="inline-flex w-16 shrink-0 justify-center">
      <BpmnGlyph shape={shape} colors={colors} width={Math.round(box.width * scale)} height={Math.round(box.height * scale)} />
    </span>
  )
}

/**
 * Collapsible legend of the BPMN symbols (STU01), shared by the studio canvas and the run diagram. Closed by default.
 * Each row says the symbol in words, the step kinds drawn with it and the BPMN element they compile to.
 */
export function NotationLegend({ className, defaultOpen = false }: { className?: string; defaultOpen?: boolean }) {
  return (
    <details className={cn('rounded-card border border-border bg-surface text-sm shadow-card', className)} open={defaultOpen} data-testid="notation-legend">
      <summary className="cursor-pointer px-3 py-2 font-semibold focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring">BPMN symbols</summary>
      {/* Scrollable, so it is focusable for keyboard scrolling (axe scrollable-region-focusable, WCAG 2.1.1). */}
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex -- a focusable scroll region is required for keyboard users */}
      <div className="max-h-[22rem] overflow-y-auto px-3 pb-3 focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-ring" tabIndex={0} role="region" aria-label="BPMN symbols list">
        <p className="mb-2 text-muted-foreground">Each step is drawn with the BPMN 2.0 symbol it runs as. Its name and kind are always printed too.</p>
        <ul className="space-y-2" aria-label="BPMN symbols and the steps that use them">
          {legendRows().map((row) => (
            <li key={`${row.shape}|${row.element}`} className="flex items-center gap-2" data-testid={`legend-${row.shape}`}>
              <LegendGlyph shape={row.shape} kind={row.kinds[0] as NodeKind} />
              <span>
                <span className="font-semibold">{row.kinds.map((k) => KINDS[k].paletteLabel).join(', ')}</span>
                <span className="block text-muted-foreground">
                  {row.symbol} · BPMN {row.element}
                </span>
              </span>
            </li>
          ))}
          <li className="flex items-center gap-2">
            <span className="inline-flex w-16 shrink-0 justify-center" aria-hidden="true">
              <span className="block h-6 w-12 rounded-md border-2 border-dashed border-kind-branch" />
            </span>
            <span>
              <span className="font-semibold">Sections and handlers</span>
              <span className="block text-muted-foreground">Frame around their steps (handlers dashed) · BPMN sub-process and event sub-process</span>
            </span>
          </li>
        </ul>
      </div>
    </details>
  )
}
