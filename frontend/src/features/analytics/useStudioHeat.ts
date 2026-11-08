import { useReleases } from '@/api/queries'
import { useOptionalWorkspace } from '@/app/workspace'
import { useStudioContext } from '@/features/studio/StudioContext'
import { useHeatIndex, useHeatmapSettings } from './HeatmapOverlay'

/**
 * Heatmap state of the Studio canvas (CG-N01, docs/build/S145): off until switched on, for people with run.read only
 * (aggregates of all requests), for a chosen release and period. The draft is overlaid by step and connection id, so a
 * step added since the release simply shows no figures.
 */
export function useStudioHeat() {
  const { wsId, workflowId } = useStudioContext()
  const allowed = useOptionalWorkspace()?.can('run.read') ?? false
  const [settings, setSettings] = useHeatmapSettings(null)
  const on = settings.on && allowed
  const { result, index } = useHeatIndex(wsId, workflowId, { ...settings, on })
  const releases = useReleases(wsId, workflowId, on)
  const releaseOptions = (releases.data?.pages.flatMap((p) => p.items) ?? []).map((r) => ({
    id: r.id,
    label: `Release ${r.releaseNumber}${r.isActive ? ' (active)' : ''}`,
  }))
  return { allowed, settings, setSettings, index: on ? index : null, result, releases: releaseOptions }
}

export type StudioHeat = ReturnType<typeof useStudioHeat>
