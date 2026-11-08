import { useReleases } from '@/api/queries'
import { useStudioContext } from '@/features/studio/StudioContext'
import { useHeatIndex, useHeatmapSettings } from './HeatmapOverlay'

/**
 * Heatmap state of the Studio canvas (CG-N01, docs/build/S145): off until switched on, for a chosen release and period.
 * The draft is overlaid by step and connection id, so a step added since the release simply shows no figures. Only
 * mounted for people with run.read (aggregates of all requests).
 */
export function useStudioHeat() {
  const { wsId, workflowId } = useStudioContext()
  const [settings, setSettings] = useHeatmapSettings(null)
  const { result, index } = useHeatIndex(wsId, workflowId, settings)
  const releases = useReleases(wsId, workflowId, settings.on)
  const releaseOptions = (releases.data?.pages.flatMap((p) => p.items) ?? []).map((r) => ({
    id: r.id,
    label: `Release ${r.releaseNumber}${r.isActive ? ' (active)' : ''}`,
  }))
  return { settings, setSettings, index: settings.on ? index : null, result, releases: releaseOptions }
}

export type StudioHeat = ReturnType<typeof useStudioHeat>
