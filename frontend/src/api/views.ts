import { api, call } from './client'
import type { components } from './schema'

type S = components['schemas']
export type ViewSummary = S['ViewSummary']
export type ViewDetail = S['ViewDetail']
export type PublishedView = S['ViewVersion']

export const listViews = (ws: string) => call(api.GET('/workspaces/{workspaceId}/views', { params: { path: { workspaceId: ws } } }))
export const getView = (ws: string, viewId: string) => call(api.GET('/workspaces/{workspaceId}/views/{viewId}', { params: { path: { workspaceId: ws, viewId } } }))
export const createView = (ws: string, body: S['CreateView']) => call(api.POST('/workspaces/{workspaceId}/views', { params: { path: { workspaceId: ws } }, body }))
export const copyView = (ws: string, viewId: string, name: string) => call(api.POST('/workspaces/{workspaceId}/views/{viewId}/copy', { params: { path: { workspaceId: ws, viewId } }, body: { name } }))
export const saveView = (ws: string, viewId: string, body: S['SaveViewDraft']) => call(api.PUT('/workspaces/{workspaceId}/views/{viewId}/draft', { params: { path: { workspaceId: ws, viewId } }, body }))
export const publishView = (ws: string, viewId: string, expectedRevision: number) => call(api.POST('/workspaces/{workspaceId}/views/{viewId}/publish', { params: { path: { workspaceId: ws, viewId } }, body: { expectedRevision } }))
export const getPublishedView = (ws: string, viewId: string, version: number) => call(api.GET('/workspaces/{workspaceId}/views/{viewId}/versions/{version}', { params: { path: { workspaceId: ws, viewId, version } } }))
