import { api, call } from '@/api/client'
import type { components } from '@/api/schema'

type S = components['schemas']
export const listDocumentSources = (workspaceId: string) => call(api.GET('/workspaces/{workspaceId}/document-sources', { params: { path: { workspaceId } } }))
export const listDocumentSourceGrants = (workspaceId: string) => call(api.GET('/workspaces/{workspaceId}/document-sources/grants', { params: { path: { workspaceId } } }))
export const putDocumentSourceGrant = (workspaceId: string, sourceId: string, body: S['PutDocumentSourceGrant']) => call(api.PUT('/workspaces/{workspaceId}/document-sources/grants/{sourceId}', { params: { path: { workspaceId, sourceId } }, body }))
export const listDocumentSourceFiles = (workspaceId: string, sourceId: string) => call(api.GET('/workspaces/{workspaceId}/document-sources/{sourceId}/files', { params: { path: { workspaceId, sourceId } } }))
export const listDocumentSourceFileVersions = (workspaceId: string, sourceId: string, itemId: string) => call(api.GET('/workspaces/{workspaceId}/document-sources/{sourceId}/files/{itemId}/versions', { params: { path: { workspaceId, sourceId, itemId } } }))
