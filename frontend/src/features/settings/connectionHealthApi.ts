import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, call } from '@/api/client'
import type { components } from '@/api/schema'

/** Connection health, circuit state and binding request limits (CG-C09, CG-W04, docs/build/S131). */
export type ConnectionHealth = components['schemas']['ConnectionHealth']
export type ConnectionHealthList = components['schemas']['ConnectionHealthList']
export type ConnectionHealthWindow = components['schemas']['ConnectionHealthWindow']
export type ConnectionHealthNotice = ConnectionHealth['notices'][number]

export const listConnectionHealth = (workspaceId: string) =>
  call(api.GET('/workspaces/{workspaceId}/connection-health', { params: { path: { workspaceId } } }))

export const getConnectionHealth = (workspaceId: string, connectionKey: string) =>
  call(api.GET('/workspaces/{workspaceId}/connections/{connectionKey}/health', { params: { path: { workspaceId, connectionKey } } }))

export const putConnectionRateLimit = (workspaceId: string, connectionKey: string, maxRequestsPerMinute: number | null) =>
  call(
    api.PUT('/workspaces/{workspaceId}/connections/{connectionKey}/rate-limit', {
      params: { path: { workspaceId, connectionKey } },
      body: { maxRequestsPerMinute },
    }),
  )

export const healthKeys = {
  all: (ws: string) => ['ws', ws, 'connection-health'] as const,
  one: (ws: string, key: string) => ['ws', ws, 'connection-health', key] as const,
}

export function useConnectionHealthList(ws: string, enabled = true) {
  return useQuery({ queryKey: healthKeys.all(ws), queryFn: () => listConnectionHealth(ws), enabled, staleTime: 15_000, refetchInterval: 60_000 })
}

export function useConnectionHealth(ws: string, key: string, enabled = true) {
  return useQuery({ queryKey: healthKeys.one(ws, key), queryFn: () => getConnectionHealth(ws, key), enabled, staleTime: 15_000, refetchInterval: 60_000 })
}

export function useSetRateLimit(ws: string, key: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (limit: number | null) => putConnectionRateLimit(ws, key, limit),
    onSuccess: (health) => {
      qc.setQueryData(healthKeys.one(ws, key), health)
      void qc.invalidateQueries({ queryKey: healthKeys.all(ws), exact: true })
    },
  })
}

/** "97 %" or "No calls". */
export function formatRate(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) return 'No calls'
  const percent = rate * 100
  return `${percent >= 99.95 || percent === 0 ? percent.toFixed(0) : percent.toFixed(1)} %`
}

/** "120 ms", "1.4 s" or a dash. */
export function formatLatency(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return '–'
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`
}

export const CIRCUIT_LABEL: Record<ConnectionHealth['circuit']['state'], string> = {
  closed: 'Calls flowing',
  open: 'Calls paused',
  halfOpen: 'Trial call',
}
