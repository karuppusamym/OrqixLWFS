import { api, call, callVoid } from './client'
import type { components } from './schema'
import { networkProblem, problemFromResponse } from './problem'
import { uuid } from '@/lib/text'

type S = components['schemas']

export type LookupOptions = S['LookupOptions']
export type GuestForm = S['GuestForm']
export type GuestFile = S['GuestFile']
export type GuestLink = S['GuestLink']
export type GuestSubmitted = S['GuestSubmitted']

/** CG-F02 (docs/build/S140): options of a lookup question, searched as the person types. */
export const searchLookupOptions = (ws: string, workflowId: string, fieldKey: string, search: string, releaseId?: string, signal?: AbortSignal) =>
  call(api.POST('/workspaces/{workspaceId}/workflows/{workflowId}/lookups/{fieldKey}', {
    params: { path: { workspaceId: ws, workflowId, fieldKey } }, body: { search, ...(releaseId ? { releaseId } : {}) }, signal,
  }))

// Operators (run.operate) and administrators (connection.manage), CG-F05.
export const listGuestLinks = (ws: string, runId: string) =>
  call(api.GET('/workspaces/{workspaceId}/guest-links', { params: { path: { workspaceId: ws }, query: { runId } } }))
export const issueGuestLink = (ws: string, linkId: string) =>
  call(api.POST('/workspaces/{workspaceId}/guest-links/{linkId}/token', { params: { path: { workspaceId: ws, linkId } } }))
export const revokeGuestLink = (ws: string, linkId: string) =>
  callVoid(api.DELETE('/workspaces/{workspaceId}/guest-links/{linkId}/token', { params: { path: { workspaceId: ws, linkId } } }))
export const sendGuestLink = (ws: string, linkId: string) =>
  call(api.POST('/workspaces/{workspaceId}/guest-links/{linkId}/send', { params: { path: { workspaceId: ws, linkId } } }))
export const listGuestDomains = (ws: string) =>
  call(api.GET('/workspaces/{workspaceId}/guest-domains', { params: { path: { workspaceId: ws } } }))
export const replaceGuestDomains = (ws: string, domains: string[]) =>
  call(api.PUT('/workspaces/{workspaceId}/guest-domains', { params: { path: { workspaceId: ws } }, body: { domains } }))

/**
 * The guest side: no session and no cookies; the link token travels only in the X-Orqix-Guest-Token header (it comes
 * from the URL fragment, which the browser never sends to a server).
 */
async function guestFetch<T>(ws: string, path: string, token: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('X-Orqix-Guest-Token', token)
  headers.set('X-Correlation-Id', uuid())
  headers.set('Accept', 'application/json, application/problem+json')
  let response: Response
  try {
    response = await fetch(`/api/v1/workspaces/${encodeURIComponent(ws)}${path}`, { ...init, headers, credentials: 'omit' })
  } catch (cause) {
    throw networkProblem(cause)
  }
  const text = await response.text()
  let body: unknown = null
  try {
    body = text ? JSON.parse(text) : null
  } catch {
    body = null
  }
  if (!response.ok) throw problemFromResponse(response, body)
  return body as T
}

export const getGuestForm = (ws: string, token: string) => guestFetch<GuestForm>(ws, '/guest-response', token)

export function uploadGuestFile(ws: string, token: string, file: File): Promise<GuestFile> {
  const form = new FormData()
  form.append('file', file, file.name)
  return guestFetch<GuestFile>(ws, '/guest-response/files', token, { method: 'POST', body: form })
}

export const submitGuestResponse = (ws: string, token: string, key: string, result: Record<string, unknown>, files: string[]) =>
  guestFetch<GuestSubmitted>(ws, '/guest-response/submit', token, {
    method: 'POST', body: JSON.stringify({ result, files }),
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
  })
