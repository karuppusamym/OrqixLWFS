import { api, call } from './client'
import type { components } from './schema'

type S = components['schemas']
export type UiExtension = S['UiExtension']
export type UiExtensionVersion = S['UiExtensionVersion']
export type UiExtensionVersionSummary = S['UiExtensionVersionSummary']
export type UiExtensionStatus = S['UiExtensionStatus']

/** S111 UI extension registry. Approval is an administrator decision; the server serves only approved bundles to members. */
export const listExtensions = (ws: string) => call(api.GET('/workspaces/{workspaceId}/ui-extensions', { params: { path: { workspaceId: ws } } }))
export const createExtension = (ws: string, body: S['CreateUiExtension']) => call(api.POST('/workspaces/{workspaceId}/ui-extensions', { params: { path: { workspaceId: ws } }, body }))
export const submitExtensionVersion = (ws: string, extensionId: string, body: S['SubmitUiExtensionVersion']) =>
  call(api.POST('/workspaces/{workspaceId}/ui-extensions/{extensionId}/versions', { params: { path: { workspaceId: ws, extensionId } }, body }))
export const getExtensionVersion = (ws: string, extensionId: string, version: number) =>
  call(api.GET('/workspaces/{workspaceId}/ui-extensions/{extensionId}/versions/{version}', { params: { path: { workspaceId: ws, extensionId, version } } }))
export const decideExtensionVersion = (ws: string, extensionId: string, version: number, body: S['UiExtensionDecision']) =>
  call(api.POST('/workspaces/{workspaceId}/ui-extensions/{extensionId}/versions/{version}/decision', { params: { path: { workspaceId: ws, extensionId, version } }, body }))
