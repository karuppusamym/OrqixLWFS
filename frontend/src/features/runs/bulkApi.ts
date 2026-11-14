import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, call } from '@/api/client'
import type { components } from '@/api/schema'

/** Bulk operations by filter, searchable request fields and the job dashboard (CG-O01, CG-O04, CG-O05, docs/build/S142). */
export type BulkOperation = components['schemas']['BulkOperation']
export type BulkOperationItem = components['schemas']['BulkOperationItem']
export type BulkRunFilter = components['schemas']['BulkRunFilter']
export type BulkAction = components['schemas']['BulkOperationPreviewRequest']['action']
export type SearchableField = components['schemas']['SearchableField']
export type JobDashboard = components['schemas']['JobDashboard']

export const BULK_ACTION_LABEL: Record<BulkAction, string> = {
  retry: 'Retry',
  cancel: 'Cancel',
  suspend: 'Suspend',
  resume: 'Resume',
  migrate: 'Move to release',
}

/** What each action selects, in the words of the single-request rules. */
export const BULK_ACTION_RULE: Record<BulkAction, string> = {
  retry: 'requests that need attention',
  cancel: 'requests that are still open',
  suspend: 'open requests that are not suspended',
  resume: 'suspended requests',
  migrate: 'open requests of this workflow on another release',
}

export const ITEM_STATE_LABEL: Record<BulkOperationItem['state'], string> = {
  pending: 'Waiting',
  succeeded: 'Done',
  failed: 'Failed',
  skipped: 'Skipped',
  cancelled: 'Cancelled',
}

export const bulkKeys = {
  fields: (ws: string, workflowId: string) => ['ws', ws, 'searchable-fields', workflowId] as const,
  one: (ws: string, id: string) => ['ws', ws, 'bulk-operation', id] as const,
  items: (ws: string, id: string) => ['ws', ws, 'bulk-operation', id, 'items'] as const,
  dashboard: (ws: string) => ['ws', ws, 'job-dashboard'] as const,
}

export function useSearchableFields(ws: string, workflowId: string, enabled = true) {
  return useQuery({
    queryKey: bulkKeys.fields(ws, workflowId),
    queryFn: () =>
      call(api.GET('/workspaces/{workspaceId}/workflows/{workflowId}/searchable-fields', { params: { path: { workspaceId: ws, workflowId } } })),
    enabled: enabled && !!workflowId,
    staleTime: 60_000,
  })
}

export function usePreviewBulk(ws: string) {
  return useMutation({
    mutationFn: (body: components['schemas']['BulkOperationPreviewRequest']) =>
      call(api.POST('/workspaces/{workspaceId}/bulk-operations/preview', { params: { path: { workspaceId: ws } }, body })),
  })
}

export function useExecuteBulk(ws: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, confirmedCount, reason, key }: { id: string; confirmedCount: number; reason: string; key: string }) =>
      call(
        api.POST('/workspaces/{workspaceId}/bulk-operations/{bulkOperationId}/execute', {
          params: { path: { workspaceId: ws, bulkOperationId: id }, header: { 'Idempotency-Key': key } },
          body: { confirmedCount, reason },
        }),
      ),
    onSuccess: (op) => qc.setQueryData(bulkKeys.one(ws, op.id), op),
  })
}

export type BulkControl = 'pause' | 'resume' | 'cancel'

export function useControlBulk(ws: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, action, expectedVersion }: { id: string; action: BulkControl; expectedVersion: number }) => {
      const options = { params: { path: { workspaceId: ws, bulkOperationId: id } }, body: { expectedVersion } }
      if (action === 'pause') return call(api.POST('/workspaces/{workspaceId}/bulk-operations/{bulkOperationId}/pause', options))
      if (action === 'resume') return call(api.POST('/workspaces/{workspaceId}/bulk-operations/{bulkOperationId}/resume', options))
      return call(api.POST('/workspaces/{workspaceId}/bulk-operations/{bulkOperationId}/cancel', options))
    },
    onSuccess: (op) => qc.setQueryData(bulkKeys.one(ws, op.id), op),
  })
}

/** Polls while the operation runs. */
export function useBulkOperation(ws: string, id: string | null) {
  return useQuery({
    queryKey: bulkKeys.one(ws, id ?? ''),
    queryFn: () =>
      call(api.GET('/workspaces/{workspaceId}/bulk-operations/{bulkOperationId}', { params: { path: { workspaceId: ws, bulkOperationId: id ?? '' } } })),
    enabled: !!id,
    refetchInterval: (query) => (query.state.data?.state === 'running' ? 2_000 : false),
  })
}

export function useBulkItems(ws: string, id: string, state: BulkOperationItem['state'] | undefined, enabled: boolean) {
  return useQuery({
    queryKey: [...bulkKeys.items(ws, id), state ?? 'all'],
    queryFn: () =>
      call(
        api.GET('/workspaces/{workspaceId}/bulk-operations/{bulkOperationId}/items', {
          params: { path: { workspaceId: ws, bulkOperationId: id }, query: { ...(state ? { state } : {}), limit: 100 } },
        }),
      ),
    enabled,
  })
}

export function useJobDashboard(ws: string, enabled = true) {
  return useQuery({
    queryKey: bulkKeys.dashboard(ws),
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/job-dashboard', { params: { path: { workspaceId: ws } } })),
    enabled,
    staleTime: 15_000,
    refetchInterval: 30_000,
  })
}

/** "amount:gte:500" from its parts; text is trimmed. */
export function condition(key: string, op: string, value: string): string {
  return `${key}:${op}:${value.trim()}`
}

/** Operators that fit a field kind (the server refuses the others with 422). */
export function operatorsFor(kind: SearchableField['kind']): Array<{ value: string; label: string }> {
  if (kind === 'number' || kind === 'integer' || kind === 'date')
    return [
      { value: 'eq', label: 'is' },
      { value: 'gte', label: 'at least' },
      { value: 'lte', label: 'at most' },
    ]
  if (kind === 'text')
    return [
      { value: 'eq', label: 'is' },
      { value: 'prefix', label: 'starts with' },
    ]
  return [{ value: 'eq', label: 'is' }]
}

/** "45 s", "12 min", "3 h" or a dash. */
export function formatAge(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) return '–'
  if (seconds < 90) return `${seconds} s`
  if (seconds < 5400) return `${Math.round(seconds / 60)} min`
  return `${Math.round(seconds / 3600)} h`
}
