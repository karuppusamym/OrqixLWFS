import { api, call } from './client'
import type { components } from './schema'

type S = components['schemas']
export type AppPageRoute = S['AppPageRoute']
export type AppPageRuntime = S['AppPageRuntime']
export type ChainRunRequest = S['ChainRunRequest']
export type ChainRunResult = S['ChainRunResult']

/** S111 application page routes, runtime, drafts, extension data and chain runs. */
export const listAppPages = (ws: string) => call(api.GET('/workspaces/{workspaceId}/app-pages', { params: { path: { workspaceId: ws } } }))
export const getAppPage = (ws: string, slug: string) => call(api.GET('/workspaces/{workspaceId}/app-pages/{slug}', { params: { path: { workspaceId: ws, slug } } }))
export const assignAppPage = (ws: string, slug: string, expectedRevision: number, viewRef: { id: string; version: number } | null) =>
  call(api.PUT('/workspaces/{workspaceId}/app-pages/{slug}', { params: { path: { workspaceId: ws, slug } }, body: { expectedRevision, viewRef } }))
export const listAppPageHistory = (ws: string, slug: string) => call(api.GET('/workspaces/{workspaceId}/app-pages/{slug}/history', { params: { path: { workspaceId: ws, slug } } }))
export const listAppPageDrafts = (ws: string, slug: string) => call(api.GET('/workspaces/{workspaceId}/app-pages/{slug}/drafts', { params: { path: { workspaceId: ws, slug } } }))
export const getComponentData = (ws: string, slug: string, componentId: string, context?: string) =>
  call(api.GET('/workspaces/{workspaceId}/app-pages/{slug}/components/{componentId}/data', { params: { path: { workspaceId: ws, slug, componentId }, query: context ? { context } : {} } }))
export const runChain = (ws: string, slug: string, chainId: string, body: ChainRunRequest, idempotencyKey: string) =>
  call(api.POST('/workspaces/{workspaceId}/app-pages/{slug}/chains/{chainId}/run', { params: { path: { workspaceId: ws, slug, chainId }, header: { 'Idempotency-Key': idempotencyKey } }, body }))
