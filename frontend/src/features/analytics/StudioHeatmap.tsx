import { useEffect } from 'react'
import { useStudio } from '@/features/studio/StudioContext'
import { HeatmapControls, HeatmapTable } from './HeatmapOverlay'
import type { HeatIndex } from './heatmap'
import { useStudioHeat } from './useStudioHeat'

/**
 * The heatmap switch on the Studio canvas, with the figures as a table for keyboard and screen reader users. It hands
 * the figures to the canvas through {@code onIndex}, so the canvas itself needs no data hooks.
 */
export function StudioHeatmap({ onIndex }: { onIndex: (index: HeatIndex | null) => void }) {
  const heat = useStudioHeat()
  const def = useStudio((s) => s.definition)
  useEffect(() => {
    onIndex(heat.index)
  }, [heat.index, onIndex])
  useEffect(() => () => onIndex(null), [onIndex])
  const label = (id: string) => def.nodes.find((n) => n.id === id)?.label ?? `${id} (not in this draft)`
  return (
    <div className="max-h-80 max-w-md space-y-2 overflow-auto rounded-card border border-border bg-surface px-3 py-2 shadow-card" data-testid="studio-heatmap">
      <HeatmapControls
        settings={heat.settings}
        onChange={heat.setSettings}
        releases={heat.releases}
        data={heat.result.data}
        loading={heat.result.isPending}
        error={heat.result.isError ? 'The heatmap could not be loaded.' : null}
      />
      {heat.index && heat.result.data ? (
        <HeatmapTable
          data={heat.result.data}
          stepLabel={label}
          routeLabel={(id) => {
            const t = def.transitions.find((x) => x.id === id)
            return t ? `${label(t.from)} to ${label(t.to)}` : `${id} (not in this draft)`
          }}
        />
      ) : null}
    </div>
  )
}
