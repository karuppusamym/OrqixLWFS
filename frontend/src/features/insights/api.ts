import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { api, call } from '@/api/client'
import type { components } from '@/api/schema'

type S = components['schemas']

export type InsightsSummary = S['InsightsSummary']
export type InsightsThroughput = S['InsightsThroughput']
export type InsightsSteps = S['InsightsSteps']
export type InsightsStep = S['InsightsStep']

export interface InsightsQuery {
  from: string
  to: string
  workflowId?: string
}

const params = (ws: string, q: InsightsQuery) => ({ path: { workspaceId: ws }, query: { from: q.from, to: q.to, ...(q.workflowId ? { workflowId: q.workflowId } : {}) } })

export const getInsightsSummary = (ws: string, q: InsightsQuery) => call(api.GET('/workspaces/{workspaceId}/insights/summary', { params: params(ws, q) }))
export const getInsightsThroughput = (ws: string, q: InsightsQuery) => call(api.GET('/workspaces/{workspaceId}/insights/throughput', { params: params(ws, q) }))
export const getInsightsSteps = (ws: string, q: InsightsQuery) => call(api.GET('/workspaces/{workspaceId}/insights/steps', { params: params(ws, q) }))

const options = { staleTime: 30_000, refetchOnWindowFocus: false, retry: 1, placeholderData: keepPreviousData } as const

/** Figures are aggregates computed on the server on every call; nothing here is cached beyond this session's query cache. */
export function useInsightsSummary(ws: string, q: InsightsQuery, enabled = true) {
  return useQuery({ queryKey: ['ws', ws, 'insights', 'summary', q] as const, queryFn: () => getInsightsSummary(ws, q), enabled, ...options })
}
export function useInsightsThroughput(ws: string, q: InsightsQuery, enabled = true) {
  return useQuery({ queryKey: ['ws', ws, 'insights', 'throughput', q] as const, queryFn: () => getInsightsThroughput(ws, q), enabled, ...options })
}
export function useInsightsSteps(ws: string, q: InsightsQuery, enabled = true) {
  return useQuery({ queryKey: ['ws', ws, 'insights', 'steps', q] as const, queryFn: () => getInsightsSteps(ws, q), enabled, ...options })
}
