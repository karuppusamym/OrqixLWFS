import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, call } from '@/api/client'
import type { components } from '@/api/schema'

type S = components['schemas']

export type Heatmap = S['AnalyticsHeatmap']
export type HumanWork = S['AnalyticsHumanWork']
export type KpiOverview = S['AnalyticsKpiOverview']
export type KpiWorkflow = S['AnalyticsKpiWorkflow']
export type KpiTargets = S['AnalyticsKpiTargets']
export type KpiTargetsUpdate = S['AnalyticsKpiTargetsUpdate']

export interface AnalyticsRange {
  from: string
  to: string
}

const options = { staleTime: 30_000, refetchOnWindowFocus: false, retry: 1, placeholderData: keepPreviousData } as const

/** CG-N01 heatmap of one workflow (and release) from the analytics read model (docs/build/S145). */
export function useHeatmap(ws: string, q: AnalyticsRange & { workflowId: string; releaseId?: string }, enabled = true) {
  return useQuery({
    queryKey: ['ws', ws, 'analytics', 'heatmap', q] as const,
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/analytics/heatmap', {
      params: { path: { workspaceId: ws }, query: { workflowId: q.workflowId, from: q.from, to: q.to, ...(q.releaseId ? { releaseId: q.releaseId } : {}) } },
    })),
    enabled,
    ...options,
  })
}

export function useHumanWork(ws: string, q: AnalyticsRange & { workflowId?: string }, enabled = true) {
  return useQuery({
    queryKey: ['ws', ws, 'analytics', 'human-work', q] as const,
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/analytics/human-work', {
      params: { path: { workspaceId: ws }, query: { from: q.from, to: q.to, ...(q.workflowId ? { workflowId: q.workflowId } : {}) } },
    })),
    enabled,
    ...options,
  })
}

export function useKpiOverview(ws: string, q: AnalyticsRange & { workflowId?: string }, enabled = true) {
  return useQuery({
    queryKey: ['ws', ws, 'analytics', 'kpis', q] as const,
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/analytics/kpis', {
      params: { path: { workspaceId: ws }, query: { from: q.from, to: q.to, ...(q.workflowId ? { workflowId: q.workflowId } : {}) } },
    })),
    enabled,
    ...options,
  })
}

export function useKpiTargets(ws: string, workflowId: string, enabled = true) {
  return useQuery({
    queryKey: ['ws', ws, 'analytics', 'kpi-targets', workflowId] as const,
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/workflows/{workflowId}/kpi-targets', {
      params: { path: { workspaceId: ws, workflowId } },
    })),
    enabled,
    staleTime: 30_000,
  })
}

export function useSetKpiTargets(ws: string, workflowId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: KpiTargetsUpdate) => call(api.PUT('/workspaces/{workspaceId}/workflows/{workflowId}/kpi-targets', {
      params: { path: { workspaceId: ws, workflowId } },
      body,
    })),
    onSuccess: (targets) => {
      client.setQueryData(['ws', ws, 'analytics', 'kpi-targets', workflowId], targets)
      void client.invalidateQueries({ queryKey: ['ws', ws, 'analytics', 'kpis'] })
    },
  })
}
