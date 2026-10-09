import { useQuery } from '@tanstack/react-query'
import { api, call, callWithResponse } from '@/api/client'
import type { components } from '@/api/schema'
import { useWorkspace } from '@/app/workspace'

/** AI assistance API (docs/build/S10-ai-assistance.md; contracts/openapi.yaml tag AI). */
export type AiStatus = components['schemas']['AiStatus']
export type AiProfile = components['schemas']['AiProfile']
export type AiDraftRequest = components['schemas']['AiDraftRequest']
export type AiDraftResult = components['schemas']['AiDraftResult']
export type AiGeneration = components['schemas']['AiGeneration']
export type AiMissingItem = AiDraftResult['missingConfiguration'][number]
export type WorkflowExplanation = components['schemas']['WorkflowExplanation']

export { DESCRIPTION_MAX, DESCRIPTION_MIN } from './limits'

export const aiKeys = {
  status: (ws: string) => ['ws', ws, 'ai', 'status'] as const,
}

export const getAiStatus = (ws: string) =>
  call(api.GET('/workspaces/{workspaceId}/ai/status', { params: { path: { workspaceId: ws } } }))

export const generateAiDraft = (ws: string, body: AiDraftRequest, idempotencyKey: string) =>
  callWithResponse(
    api.POST('/workspaces/{workspaceId}/ai/drafts', {
      params: { path: { workspaceId: ws }, header: { 'Idempotency-Key': idempotencyKey } },
      body,
    }),
  )

export const explainWorkflow = (ws: string, workflowId: string, body: { revision?: number; profile?: string }) =>
  call(
    api.POST('/workspaces/{workspaceId}/workflows/{workflowId}/explain', {
      params: { path: { workspaceId: ws, workflowId } },
      body,
    }),
  )

/** Whether the signed-in person may read the AI status here (designers, readers, workspace administrators). */
export function useCanSeeAi(): boolean {
  const { can } = useWorkspace()
  return can('workflow.read', 'workflow.edit', 'workspace.manage-access')
}

/** AI status of the current workspace; not fetched for people who cannot use AI. Cached for a minute. */
export function useAiStatus(enabled = true) {
  const { workspace } = useWorkspace()
  const allowed = useCanSeeAi()
  return useQuery({
    queryKey: aiKeys.status(workspace.id),
    queryFn: () => getAiStatus(workspace.id),
    enabled: enabled && allowed,
    staleTime: 60_000,
    retry: false,
  })
}

/** True only when AI assistance is available; entry points stay hidden while loading, on errors and when off. */
export function useAiEnabled(): boolean {
  const status = useAiStatus()
  return status.data?.enabled === true
}

/** Plain USD amount for small AI costs (never rounds a reported cost to zero). */
export function formatUsd(value: number | null | undefined): string {
  if (value === null || value === undefined) return 'not reported'
  if (value === 0) return 'USD 0.00'
  if (value < 0.0001) return 'under USD 0.0001'
  if (value < 0.01) return `USD ${value.toFixed(4).replace(/0+$/, '')}`
  return `USD ${value.toFixed(2)}`
}

/** "12 s" / "1 min 5 s". */
export function formatDuration(ms: number): string {
  const seconds = Math.max(0, Math.round(ms / 1000))
  if (seconds < 60) return `${seconds} s`
  return `${Math.floor(seconds / 60)} min ${seconds % 60} s`
}
