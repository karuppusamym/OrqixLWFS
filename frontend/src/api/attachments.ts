import { api, call, callVoid } from './client'
import { networkProblem, problemFromResponse } from './problem'
import { readCookie } from './auth'
import type { Attachment, StagedUpload } from './types'
import { uuid } from '@/lib/text'

export { FILE_TYPES, ALL_FILE_TYPES, MAX_FILE_BYTES, formatBytes, describeTypes, acceptAttribute, checkFile } from '@/lib/files'

interface UploadOptions {
  key?: string
  fields?: Record<string, string>
  onProgress?: (fraction: number) => void
}

async function parseResponse<T>(response: Response): Promise<T> {
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

/**
 * Multipart upload of one file. Uses XMLHttpRequest for upload progress in the real app, and fetch in mock mode and in
 * tests (the in-page API double intercepts fetch only).
 */
export async function uploadFile<T>(path: string, file: File, options: UploadOptions = {}): Promise<T> {
  const form = new FormData()
  form.append('file', file, file.name)
  for (const [k, v] of Object.entries(options.fields ?? {})) form.append(k, v)
  const key = options.key ?? uuid()
  const headers: Record<string, string> = { 'Idempotency-Key': key, 'X-Correlation-Id': uuid(), Accept: 'application/json, application/problem+json' }
  const token = readCookie('XSRF-TOKEN')
  if (token) headers['X-XSRF-TOKEN'] = token
  const url = `/api/v1${path}`
  const useXhr = !__ORQIX_MOCK__ && import.meta.env.MODE !== 'test' && typeof XMLHttpRequest !== 'undefined' && !!options.onProgress
  if (!useXhr) {
    let response: Response
    try {
      response = await fetch(url, { method: 'POST', body: form, headers, credentials: 'same-origin' })
    } catch (cause) {
      throw networkProblem(cause)
    }
    options.onProgress?.(1)
    return parseResponse<T>(response)
  }
  return new Promise<T>((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', url)
    for (const [k, v] of Object.entries(headers)) xhr.setRequestHeader(k, v)
    xhr.withCredentials = true
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) options.onProgress?.(event.loaded / event.total)
    }
    xhr.onerror = () => reject(networkProblem(new Error('upload failed')))
    xhr.onload = () => {
      const response = new Response(xhr.responseText || null, {
        status: xhr.status,
        headers: { 'Content-Type': xhr.getResponseHeader('Content-Type') ?? 'application/json', 'Retry-After': xhr.getResponseHeader('Retry-After') ?? '' },
      })
      parseResponse<T>(response).then(resolve, reject)
    }
    xhr.send(form)
  })
}

export const uploadFormFile = (ws: string, file: File, options?: UploadOptions) =>
  uploadFile<StagedUpload>(`/workspaces/${ws}/attachment-uploads`, file, options)

export const discardFormFile = (ws: string, uploadId: string) =>
  callVoid(api.DELETE('/workspaces/{workspaceId}/attachment-uploads/{uploadId}', { params: { path: { workspaceId: ws, uploadId } } }))

export const listAttachments = (ws: string, runId: string) =>
  call(api.GET('/workspaces/{workspaceId}/runs/{runId}/attachments', { params: { path: { workspaceId: ws, runId } } }))

export const addAttachment = (ws: string, runId: string, file: File, description: string, options?: UploadOptions) =>
  uploadFile<Attachment>(`/workspaces/${ws}/runs/${runId}/attachments`, file, { ...options, fields: description.trim() ? { description: description.trim() } : {} })

export const removeAttachment = (ws: string, runId: string, attachmentId: string) =>
  callVoid(api.DELETE('/workspaces/{workspaceId}/runs/{runId}/attachments/{attachmentId}', { params: { path: { workspaceId: ws, runId, attachmentId } } }))

export const attachmentUrl = (ws: string, runId: string, attachmentId: string) => `/api/v1/workspaces/${ws}/runs/${runId}/attachments/${attachmentId}/content`

/**
 * Downloads through fetch so mock mode works and a blocked file shows a message instead of a broken page; the browser
 * is then handed the bytes as a download (never rendered).
 */
export async function downloadAttachment(ws: string, runId: string, attachment: Pick<Attachment, 'id' | 'name'>): Promise<void> {
  let response: Response
  try {
    response = await fetch(attachmentUrl(ws, runId, attachment.id), { credentials: 'same-origin' })
  } catch (cause) {
    throw networkProblem(cause)
  }
  if (!response.ok) await parseResponse(response)
  const blob = await response.blob()
  const link = document.createElement('a')
  link.href = URL.createObjectURL(new Blob([blob], { type: 'application/octet-stream' }))
  link.download = attachment.name
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(link.href), 10_000)
}
