import { api, call } from './client'
import type { components } from './schema'
export type WorklistViewState = components['schemas']['WorklistViewState']
export const getWorklistView = (ws: string) => call(api.GET('/workspaces/{workspaceId}/worklist-view', { params: { path: { workspaceId: ws } } }))
export const assignWorklistView = (ws: string, expectedRevision: number, viewRef: { id: string; version: number } | null) => call(api.PUT('/workspaces/{workspaceId}/worklist-view', { params: { path: { workspaceId: ws } }, body: { expectedRevision, viewRef } }))
