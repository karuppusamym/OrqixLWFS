import { useQuery } from '@tanstack/react-query'
import { api, call, callWithResponse } from '@/api/client'
import { ProblemError } from '@/api/problem'
import type { components } from '@/api/schema'
import { useWorkspace } from '@/app/workspace'

/** AI assistance API (docs/build/S10-ai-assistance.md; contracts/openapi.yaml tag AI). */
export type AiStatus = components['schemas']['AiStatus']
export type AiProfile = components['schemas']['AiProfile']
export type AiDraftRequest = components['schemas']['AiDraftRequest']
export type AiDraftResult = components['schemas']['AiDraftResult']
export type AiGenerationJob = components['schemas']['AiGenerationJob']
export type AiGeneration = components['schemas']['AiGeneration']
export type AiMissingItem = AiDraftResult['missingConfiguration'][number]
export type WorkflowExplanation = components['schemas']['WorkflowExplanation']

export { DESCRIPTION_MAX, DESCRIPTION_MIN } from './limits'

export const aiKeys = {
  status: (ws: string) => ['ws', ws, 'ai', 'status'] as const,
  generation: (ws: string, id: string) => ['ws', ws, 'ai', 'generation', id] as const,
}

export const getAiStatus = (ws: string) =>
  call(api.GET('/workspaces/{workspaceId}/ai/status', { params: { path: { workspaceId: ws } } }))

/** Starts a generation (202, or 200 for an identical replay): the job to poll with {@link getAiGeneration}. */
export const generateAiDraft = (ws: string, body: AiDraftRequest, idempotencyKey: string) =>
  callWithResponse(
    api.POST('/workspaces/{workspaceId}/ai/drafts', {
      params: { path: { workspaceId: ws }, header: { 'Idempotency-Key': idempotencyKey } },
      body,
    }),
  )

export const getAiGeneration = (ws: string, generationId: string) =>
  call(api.GET('/workspaces/{workspaceId}/ai/generations/{generationId}', { params: { path: { workspaceId: ws, generationId } } }))

export const explainWorkflow = (ws: string, workflowId: string, body: { revision?: number; profile?: string }) =>
  call(
    api.POST('/workspaces/{workspaceId}/workflows/{workflowId}/explain', {
      params: { path: { workspaceId: ws, workflowId } },
      body,
    }),
  )

export type AiFormSuggestion = components['schemas']['AiFormSuggestion']
export type AiFormChange = components['schemas']['AiFormChange']

/** Validated request-form suggestions (docs/build/S90). Nothing is applied: the designer reviews and applies them. */
export const suggestFormChanges = (ws: string, workflowId: string, body: { description: string; inputSchema: Record<string, unknown>; profile?: string }) =>
  call(
    api.POST('/workspaces/{workspaceId}/workflows/{workflowId}/ai/form-suggestions', {
      params: { path: { workspaceId: ws, workflowId } },
      body: body as never,
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

/** Polling cadence of generations (S10 section 3); tests shorten it. */
export const aiPolling = { intervalMs: 2000 }

export const isFinished = (job: AiGenerationJob): boolean => job.status === 'succeeded' || job.status === 'failed'

/** The Problem of a failed job as a ProblemError, so the usual problem messages apply. */
export function jobProblem(job: AiGenerationJob): ProblemError {
  const p = job.problem
  return new ProblemError({
    code: (p?.code ?? 'AI_PROVIDER_UNAVAILABLE') as ProblemError['code'],
    status: p?.status ?? 503,
    title: p?.title ?? 'The AI could not finish',
    detail: p?.detail,
    correlationId: p?.correlationId,
    issues: p?.issues ?? [],
  })
}

/**
 * Polls a generation until it ends (succeeded or failed) and reports every state through onUpdate. Brief network
 * failures are retried; a 404 (the job is gone) or any other problem ends the wait with that error.
 */
export async function waitForGeneration(
  ws: string,
  id: string,
  options: { signal?: AbortSignal; onUpdate?: (job: AiGenerationJob) => void } = {},
): Promise<AiGenerationJob> {
  let failures = 0
  for (;;) {
    if (options.signal?.aborted) throw new DOMException('Stopped waiting', 'AbortError')
    try {
      const job = await getAiGeneration(ws, id)
      failures = 0
      options.onUpdate?.(job)
      if (isFinished(job)) return job
    } catch (cause) {
      if (cause instanceof ProblemError && cause.is('NETWORK_ERROR') && ++failures <= 5) {
        // retry after the usual pause
      } else {
        throw cause
      }
    }
    await new Promise<void>((resolve) => window.setTimeout(resolve, aiPolling.intervalMs))
  }
}
