import { useStudio } from '@/features/studio/StudioContext'
import { HeatmapControls, HeatmapTable } from './HeatmapOverlay'
import type { StudioHeat } from './useStudioHeat'

/** The heatmap switch on the Studio canvas, with the figures as a table for keyboard and screen reader users. */
export function StudioHeatmap({ heat }: { heat: StudioHeat }) {
  const def = useStudio((s) => s.definition)
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
