import { api, call } from '@/api/client'

export const getOAuth = (workspaceId: string, connectionKey: string) => call(api.GET('/workspaces/{workspaceId}/connections/{connectionKey}/oauth', { params: { path: { workspaceId, connectionKey } } }))
export const beginOAuth = (workspaceId: string, connectionKey: string) => call(api.POST('/workspaces/{workspaceId}/connections/{connectionKey}/oauth/authorize', { params: { path: { workspaceId, connectionKey } } }))
export const finishOAuth = (workspaceId: string, connectionKey: string, state: string, code: string) => call(api.POST('/workspaces/{workspaceId}/connections/{connectionKey}/oauth/complete', { params: { path: { workspaceId, connectionKey } }, body: { state, code } }))
export const disconnectOAuth = (workspaceId: string, connectionKey: string) => call(api.POST('/workspaces/{workspaceId}/connections/{connectionKey}/oauth/disconnect', { params: { path: { workspaceId, connectionKey } } }))
