import { useQuery } from '@tanstack/react-query'
import { api, call } from '@/api/client'
import type { components } from '@/api/schema'

/**
 * AI skills Studio over the S134 AiSkills API (contracts/openapi.yaml tag AiSkills, docs/build/S134 section 2 and 6).
 * One thin function per operationId; the query keys keep `['ws', ws, 'ai-skills']` that the Studio's AiTaskInspector
 * already uses, so a change here refreshes the inspector's skill picker as well.
 */
type S = components['schemas']
export type AiSkill = S['AiSkill']
export type AiSkillVersion = S['AiSkillVersion']
export type AiEvaluation = S['AiEvaluation']
export type AiEvalSuite = S['AiEvalSuite']
export type AiShadowDashboard = S['AiShadowDashboard']
export type AiSkillPromotion = S['AiSkillPromotion']
export type AiDriftCheck = S['AiDriftCheck']
export type AiReviewItem = S['AiReviewQueue']['items'][number]
export type AiEvalCandidate = S['AiEvalCandidateList']['items'][number]
export type AiEvalCandidateReview = S['AiEvalCandidateReview']
export type AiPolicy = S['AiPolicy']
export type AiPolicyUpdate = S['AiPolicyUpdate']
export type SkillMode = AiSkill['mode']
export type Autonomy = AiSkillVersion['autonomyCeiling']

export const aiSkillKeys = {
  list: (ws: string) => ['ws', ws, 'ai-skills'] as const,
  one: (ws: string, key: string) => ['ws', ws, 'ai-skills', key] as const,
  evaluations: (ws: string, key: string, version: number) => ['ws', ws, 'ai-skills', key, 'v', version, 'evaluations'] as const,
  evaluation: (ws: string, id: string) => ['ws', ws, 'ai-evaluations', id] as const,
  shadow: (ws: string, key: string, version: number) => ['ws', ws, 'ai-skills', key, 'v', version, 'shadow'] as const,
  promotions: (ws: string, key: string) => ['ws', ws, 'ai-skills', key, 'promotions'] as const,
  suites: (ws: string) => ['ws', ws, 'ai-eval-suites'] as const,
  suite: (ws: string, key: string) => ['ws', ws, 'ai-eval-suites', key] as const,
  reviewQueue: (ws: string) => ['ws', ws, 'ai-review-queue'] as const,
  candidates: (ws: string) => ['ws', ws, 'ai-eval-candidates'] as const,
  policy: (ws: string) => ['ws', ws, 'ai-policy'] as const,
}

const W = (ws: string) => ({ workspaceId: ws })
const V = (ws: string, skillKey: string, version: number) => ({ workspaceId: ws, skillKey, version })

// Skills and versions
export const listAiSkills = (ws: string) => call(api.GET('/workspaces/{workspaceId}/ai-skills', { params: { path: W(ws) } }))
export const getAiSkill = (ws: string, skillKey: string) =>
  call(api.GET('/workspaces/{workspaceId}/ai-skills/{skillKey}', { params: { path: { workspaceId: ws, skillKey } } }))
export const createAiSkill = (ws: string, document: Record<string, unknown>) =>
  call(api.POST('/workspaces/{workspaceId}/ai-skills', { params: { path: W(ws) }, body: { document } }))
export const createAiSkillVersion = (ws: string, skillKey: string, document: Record<string, unknown>) =>
  call(api.POST('/workspaces/{workspaceId}/ai-skills/{skillKey}/versions', { params: { path: { workspaceId: ws, skillKey } }, body: { document } }))
export const updateAiSkillVersion = (ws: string, skillKey: string, version: number, document: Record<string, unknown>) =>
  call(api.PUT('/workspaces/{workspaceId}/ai-skills/{skillKey}/versions/{version}', { params: { path: V(ws, skillKey, version) }, body: { document } }))
export const submitAiSkillVersion = (ws: string, skillKey: string, version: number) =>
  call(api.POST('/workspaces/{workspaceId}/ai-skills/{skillKey}/versions/{version}/submit', { params: { path: V(ws, skillKey, version) } }))
export const approveAiSkillVersion = (ws: string, skillKey: string, version: number, reason: string) =>
  call(api.POST('/workspaces/{workspaceId}/ai-skills/{skillKey}/versions/{version}/approve', { params: { path: V(ws, skillKey, version) }, body: { reason } }))
export const retireAiSkillVersion = (ws: string, skillKey: string, version: number) =>
  call(api.POST('/workspaces/{workspaceId}/ai-skills/{skillKey}/versions/{version}/retire', { params: { path: V(ws, skillKey, version) } }))

// Evaluations
export const startAiEvaluation = (ws: string, skillKey: string, version: number, suiteKey?: string) =>
  call(api.POST('/workspaces/{workspaceId}/ai-skills/{skillKey}/versions/{version}/evaluations', { params: { path: V(ws, skillKey, version) }, body: suiteKey ? { suiteKey } : {} }))
export const listAiEvaluations = (ws: string, skillKey: string, version: number) =>
  call(api.GET('/workspaces/{workspaceId}/ai-skills/{skillKey}/versions/{version}/evaluations', { params: { path: V(ws, skillKey, version) } }))
export const getAiEvaluation = (ws: string, evaluationId: string) =>
  call(api.GET('/workspaces/{workspaceId}/ai-evaluations/{evaluationId}', { params: { path: { workspaceId: ws, evaluationId } } }))
export const listAiEvalSuites = (ws: string) => call(api.GET('/workspaces/{workspaceId}/ai-eval-suites', { params: { path: W(ws) } }))
export const getAiEvalSuite = (ws: string, suiteKey: string) =>
  call(api.GET('/workspaces/{workspaceId}/ai-eval-suites/{suiteKey}', { params: { path: { workspaceId: ws, suiteKey } } }))
export const saveAiEvalSuite = (ws: string, suiteKey: string, document: Record<string, unknown>) =>
  call(api.PUT('/workspaces/{workspaceId}/ai-eval-suites/{suiteKey}', { params: { path: { workspaceId: ws, suiteKey } }, body: { document } }))

// Shadow mode, promotions, drift
export const getAiShadowDashboard = (ws: string, skillKey: string, version: number) =>
  call(api.GET('/workspaces/{workspaceId}/ai-skills/{skillKey}/versions/{version}/shadow', { params: { path: V(ws, skillKey, version) } }))
export const requestAiSkillPromotion = (ws: string, skillKey: string, version: number, body: S['AiSkillPromotionRequest']) =>
  call(api.POST('/workspaces/{workspaceId}/ai-skills/{skillKey}/versions/{version}/promotions', { params: { path: V(ws, skillKey, version) }, body }))
export const listAiSkillPromotions = (ws: string, skillKey: string) =>
  call(api.GET('/workspaces/{workspaceId}/ai-skills/{skillKey}/promotions', { params: { path: { workspaceId: ws, skillKey } } }))
export const decideAiSkillPromotion = (ws: string, promotionId: string, body: S['AiSkillPromotionDecision']) =>
  call(api.POST('/workspaces/{workspaceId}/ai-skill-promotions/{promotionId}/decision', { params: { path: { workspaceId: ws, promotionId } }, body }))
export const checkAiSkillDrift = (ws: string, skillKey: string, version: number) =>
  call(api.POST('/workspaces/{workspaceId}/ai-skills/{skillKey}/versions/{version}/drift-check', { params: { path: V(ws, skillKey, version) } }))
export const clearAiSkillDrift = (ws: string, skillKey: string, version: number, reason: string) =>
  call(api.POST('/workspaces/{workspaceId}/ai-skills/{skillKey}/versions/{version}/clear-drift', { params: { path: V(ws, skillKey, version) }, body: { reason } }))

// Review queue and evaluation candidates
export const listAiReviewQueue = (ws: string) => call(api.GET('/workspaces/{workspaceId}/ai-review-queue', { params: { path: W(ws) } }))
export const listAiEvalCandidates = (ws: string) => call(api.GET('/workspaces/{workspaceId}/ai-eval-candidates', { params: { path: W(ws) } }))
export const reviewAiEvalCandidate = (ws: string, candidateId: string, body: AiEvalCandidateReview) =>
  call(api.POST('/workspaces/{workspaceId}/ai-eval-candidates/{candidateId}/review', { params: { path: { workspaceId: ws, candidateId } }, body }))

// Workspace AI policy
export const getAiPolicy = (ws: string) => call(api.GET('/workspaces/{workspaceId}/ai-policy', { params: { path: W(ws) } }))
export const updateAiPolicy = (ws: string, body: AiPolicyUpdate) =>
  call(api.PUT('/workspaces/{workspaceId}/ai-policy', { params: { path: W(ws) }, body }))

export function useAiSkills(ws: string, enabled = true) {
  return useQuery({ queryKey: aiSkillKeys.list(ws), queryFn: () => listAiSkills(ws), enabled })
}

export function useAiSkill(ws: string, key: string) {
  return useQuery({ queryKey: aiSkillKeys.one(ws, key), queryFn: () => getAiSkill(ws, key) })
}

export function useAiEvalSuites(ws: string, enabled = true) {
  return useQuery({ queryKey: aiSkillKeys.suites(ws), queryFn: () => listAiEvalSuites(ws), enabled })
}

export function useAiPolicy(ws: string, enabled = true) {
  return useQuery({ queryKey: aiSkillKeys.policy(ws), queryFn: () => getAiPolicy(ws), enabled })
}

/** Polling cadence of running evaluations; tests shorten it. */
export const evaluationPolling = { intervalMs: 2000 }

export const MODE_LABEL: Record<SkillMode, string> = {
  classify: 'Classify', extract: 'Extract', score: 'Score', recommend: 'Recommend', summarize: 'Summarize',
}

export const STATUS_TONE: Record<AiSkillVersion['status'], 'neutral' | 'info' | 'success' | 'warning'> = {
  draft: 'neutral', submitted: 'info', approved: 'success', retired: 'warning',
}

export const STATUS_LABEL: Record<AiSkillVersion['status'], string> = {
  draft: 'Draft', submitted: 'Waiting for approval', approved: 'Approved', retired: 'Retired',
}

export const AUTONOMY_LABEL: Record<Autonomy | 'shadow', string> = {
  shadow: 'Shadow', suggest: 'Suggest', autoWithReview: 'Automatic with review sample', auto: 'Automatic',
}

/** The next step of the autonomy ceiling (suggest -> autoWithReview -> auto), or null at the top. */
export function nextAutonomy(current: Autonomy): 'autoWithReview' | 'auto' | null {
  return current === 'suggest' ? 'autoWithReview' : current === 'autoWithReview' ? 'auto' : null
}

export const percent = (value: number | null | undefined) =>
  value === null || value === undefined || Number.isNaN(value) ? 'n/a' : `${Math.round(value * 1000) / 10} %`

export const shortId = (id: string | null | undefined) => (id ? id.slice(-6) : '')
