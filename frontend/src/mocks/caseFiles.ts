import { delay, http, HttpResponse, type HttpHandler } from 'msw'
import type { Attachment } from '@/api/types'
import { ALL_FILE_TYPES, FILE_TYPES } from '@/lib/files'
import { addEvent, canSeeRunData, isRunParticipant } from './engine'
import { getCurrentUser } from './handlers'
import { WORKSPACES, clock, db, isMember, newId, permissionsOf, userById, type MockRun, type MockUser } from './state'

/**
 * Attachments in mock mode (docs/build/S17): the same rules and answers as the server, in memory. The metadata lives
 * on the run (so it survives a reload with the rest of the mock data); the bytes live only until the page is reloaded,
 * after which a download answers with a short placeholder.
 */

export interface MockAttachment {
  id: string
  fieldKey: string | null
  name: string
  description: string | null
  contentType: string
  sizeBytes: number
  sha256: string
  scanStatus: Attachment['scanStatus']
  uploadedById: string
  uploadedAt: string
  removedAt: string | null
  removedById: string | null
}

interface Staged extends MockAttachment {
  wsId: string
  expiresAt: string
}

const MAX_FILE = 10 * 1024 * 1024
const MAX_FILES = 25
const MAX_TOTAL = 50 * 1024 * 1024
const staged = new Map<string, Staged>()
const bytes = new Map<string, Uint8Array>()
const B = '/api/v1'

function problem(status: number, code: string, title: string, extra: Record<string, unknown> = {}): Response {
  return HttpResponse.json({ type: `https://orqix.example/problems/${code.toLowerCase().replace(/_/g, '-')}`, title, status, code, correlationId: newId(), ...extra }, { status, headers: { 'Content-Type': 'application/problem+json' } })
}
const notFound = () => problem(404, 'NOT_FOUND', 'Not found')
const forbidden = () => problem(403, 'FORBIDDEN', 'Forbidden')

function typeOf(name: string, content: Uint8Array): { key: string; contentType: string } | string {
  const ext = name.toLowerCase().split('.').pop() ?? ''
  const key = ext === 'jpg' ? 'jpeg' : ext
  if (!ALL_FILE_TYPES.includes(key)) return 'This kind of file is not allowed. Allowed: PDF, PNG, JPEG, text, CSV, Word (docx), Excel (xlsx) and PowerPoint (pptx) files.'
  const head = (n: number[]) => n.every((b, i) => content[i] === b)
  const ok = key === 'pdf' ? head([0x25, 0x50, 0x44, 0x46]) : key === 'png' ? head([0x89, 0x50, 0x4e, 0x47]) : key === 'jpeg' ? head([0xff, 0xd8, 0xff])
    : ['docx', 'xlsx', 'pptx'].includes(key) ? head([0x50, 0x4b, 0x03, 0x04]) : !content.includes(0)
  if (!ok) return `The content of this file is not a valid ${FILE_TYPES[key]?.label ?? key}.`
  const contentType = { pdf: 'application/pdf', png: 'image/png', jpeg: 'image/jpeg', txt: 'text/plain', csv: 'text/csv', docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', pptx: 'application/vnd.openxmlformats-officedocument.presentationml.presentation' }[key] as string
  return { key, contentType }
}

function sanitize(name: string): string {
  const base = name.split(/[\\/]/).pop() ?? ''
  const clean = base.replace(/[^\p{L}\p{N} ._()+,&'[\]-]/gu, '_').replace(/^\.+/, '').trim()
  return (clean || 'file').slice(0, 120)
}

async function sha256(data: Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', data as BufferSource)
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

async function read(request: Request): Promise<{ name: string; data: Uint8Array; description: string | null } | Response> {
  let form: FormData
  try {
    form = await request.formData()
  } catch {
    return problem(400, 'MALFORMED_REQUEST', 'The request is malformed')
  }
  const file = form.get('file')
  if (!(file instanceof File)) return problem(422, 'VALIDATION_FAILED', 'Some values are not valid', { fieldErrors: [{ pointer: '/file', code: 'required', message: 'Choose a file.' }] })
  if (file.size > MAX_FILE) return problem(413, 'PAYLOAD_TOO_LARGE', 'The file is too large')
  const data = new Uint8Array(await file.arrayBuffer())
  const note = form.get('description')
  return { name: sanitize(file.name), data, description: typeof note === 'string' && note.trim() ? note.trim().slice(0, 200) : null }
}

async function build(user: MockUser, name: string, data: Uint8Array, description: string | null): Promise<MockAttachment | Response> {
  if (data.length === 0) return problem(422, 'VALIDATION_FAILED', 'Some values are not valid', { fieldErrors: [{ pointer: '/file', code: 'required', message: 'The file is empty.' }] })
  const type = typeOf(name, data)
  if (typeof type === 'string') return problem(422, 'VALIDATION_FAILED', 'Some values are not valid', { fieldErrors: [{ pointer: '/file', code: 'type', message: type }] })
  const id = newId()
  bytes.set(id, data)
  return {
    id, fieldKey: null, name, description, contentType: type.contentType, sizeBytes: data.length, sha256: await sha256(data),
    // The mock has no scanner; a file named "infected" demonstrates a blocked file.
    scanStatus: name.toLowerCase().includes('infected') ? 'infected' : 'notScanned',
    uploadedById: user.id, uploadedAt: clock.iso(), removedAt: null, removedById: null,
  }
}

const ref = (id: string | null) => (id ? { id, displayName: userById(id)?.displayName ?? 'Unknown' } : null)
const blocked = (a: MockAttachment) => a.scanStatus === 'infected' || a.scanStatus === 'error'

function view(run: MockRun, a: MockAttachment, user: MockUser): Attachment {
  const operator = permissionsOf(user, run.wsId).includes('run.operate')
  const active = run.lifecycle === 'running' || run.lifecycle === 'waiting' || run.lifecycle === 'needsAttention'
  return {
    id: a.id, runId: run.id, fieldKey: a.fieldKey, name: a.name, description: a.description, contentType: a.contentType, sizeBytes: a.sizeBytes,
    sha256: a.sha256, scanStatus: a.scanStatus, uploadedBy: ref(a.uploadedById)!, uploadedAt: a.uploadedAt, removedAt: a.removedAt, removedBy: ref(a.removedById),
    canRemove: !a.removedAt && ((run.initiatorId === user.id && active) || operator), downloadable: !a.removedAt && !blocked(a),
  }
}

function access(wsId: string, runId: string): { user: MockUser; run: MockRun } | Response {
  const user = getCurrentUser()
  const run = db.runs.get(runId)
  if (!WORKSPACES.some((w) => w.id === wsId) || !isMember(user, wsId) || !run || run.wsId !== wsId) return notFound()
  const perms = permissionsOf(user, wsId)
  if (!perms.includes('run.read') && run.initiatorId !== user.id && !isRunParticipant(run, user)) return notFound()
  return { user, run }
}

function key(request: Request): Response | null {
  const value = request.headers.get('Idempotency-Key')
  return value && /^[A-Za-z0-9._:-]{8,128}$/.test(value) ? null : problem(400, 'IDEMPOTENCY_KEY_REQUIRED', 'An Idempotency-Key header is required')
}

/** Validates the files a request form refers to and returns them; or a 422 response. */
export function planStartFiles(user: MockUser, wsId: string, schema: { properties: Record<string, { format?: string; acceptTypes?: string[]; maxFileBytes?: number }> }, input: Record<string, unknown>): { files: Array<{ id: string; field: string }> } | Response {
  const files: Array<{ id: string; field: string }> = []
  const errors: Array<{ pointer: string; code: string; message: string }> = []
  for (const [field, def] of Object.entries(schema.properties)) {
    const value = input[field]
    if (def.format !== 'attachment' || typeof value !== 'string') continue
    const row = staged.get(value)
    const pointer = `/input/${field}`
    if (!row || row.wsId !== wsId || row.uploadedById !== user.id || row.expiresAt < clock.iso() || files.some((f) => f.id === value)) {
      errors.push({ pointer, code: 'attachment', message: 'This file is no longer available. Choose it again.' })
      continue
    }
    const kind = Object.entries(FILE_TYPES).find(([k]) => (({ pdf: 'application/pdf', png: 'image/png', jpeg: 'image/jpeg', txt: 'text/plain', csv: 'text/csv' }) as Record<string, string>)[k] === row.contentType)?.[0] ?? row.name.split('.').pop()
    if (def.acceptTypes && def.acceptTypes.length > 0 && kind && !def.acceptTypes.includes(kind)) errors.push({ pointer, code: 'attachment', message: `Choose one of: ${def.acceptTypes.map((t) => FILE_TYPES[t]?.label ?? t).join(', ')}.` })
    else if (row.sizeBytes > Math.min(def.maxFileBytes ?? MAX_FILE, MAX_FILE)) errors.push({ pointer, code: 'attachment', message: 'This file is larger than this question allows.' })
    else if (blocked(row)) errors.push({ pointer, code: 'attachment', message: 'This file was blocked by the virus scan. Choose another file.' })
    else files.push({ id: value, field })
  }
  return errors.length > 0 ? problem(422, 'VALIDATION_FAILED', 'The request has invalid fields', { fieldErrors: errors }) : { files }
}

export function attachStartFiles(run: MockRun, plan: { files: Array<{ id: string; field: string }> }): void {
  run.attachments ??= []
  for (const f of plan.files) {
    const row = staged.get(f.id)
    if (!row) continue
    staged.delete(f.id)
    const { wsId: _w, expiresAt: _e, ...attachment } = row
    run.attachments.push({ ...attachment, fieldKey: f.field })
    addEvent(run, 'ATTACHMENT_ADDED', { actorId: row.uploadedById, data: { attachmentId: row.id, name: row.name, sizeBytes: row.sizeBytes, contentType: row.contentType, fieldKey: f.field } })
  }
}

export function createCaseFileHandlers(): HttpHandler[] {
  const slow = () => delay(db.latencyMs)
  return [
    http.post(`${B}/workspaces/:ws/attachment-uploads`, async ({ params, request }) => {
      await slow()
      const wsId = String(params.ws)
      const user = getCurrentUser()
      if (!WORKSPACES.some((w) => w.id === wsId) || !isMember(user, wsId)) return notFound()
      if (!permissionsOf(user, wsId).includes('workflow.start')) return forbidden()
      const missing = key(request)
      if (missing) return missing
      const waiting = [...staged.values()].filter((s) => s.uploadedById === user.id && s.wsId === wsId && s.expiresAt > clock.iso()).length
      if (waiting >= 10) return problem(409, 'ATTACHMENT_LIMIT_REACHED', 'The request has reached its file limit', { detail: 'You have 10 files waiting on request forms.' })
      const file = await read(request)
      if (file instanceof Response) return file
      const built = await build(user, file.name, file.data, null)
      if (built instanceof Response) return built
      const row: Staged = { ...built, wsId, expiresAt: new Date(clock.now().getTime() + 24 * 3_600_000).toISOString() }
      staged.set(row.id, row)
      return HttpResponse.json({ id: row.id, name: row.name, contentType: row.contentType, sizeBytes: row.sizeBytes, sha256: row.sha256, scanStatus: row.scanStatus, expiresAt: row.expiresAt }, { status: 201 })
    }),
    http.delete(`${B}/workspaces/:ws/attachment-uploads/:id`, async ({ params }) => {
      const row = staged.get(String(params.id))
      const user = getCurrentUser()
      if (!row || row.uploadedById !== user.id || row.wsId !== String(params.ws)) return notFound()
      staged.delete(row.id)
      bytes.delete(row.id)
      return new HttpResponse(null, { status: 204 })
    }),
    http.get(`${B}/workspaces/:ws/runs/:run/attachments`, async ({ params }) => {
      await slow()
      const found = access(String(params.ws), String(params.run))
      if (found instanceof Response) return found
      const { user, run } = found
      if (!canSeeRunData(run, user)) return forbidden()
      const all = run.attachments ?? []
      const live = all.filter((a) => !a.removedAt)
      const active = run.lifecycle === 'running' || run.lifecycle === 'waiting' || run.lifecycle === 'needsAttention'
      const canAdd = active && (run.initiatorId === user.id || isRunParticipant(run, user) || permissionsOf(user, run.wsId).includes('run.operate'))
      return HttpResponse.json({
        items: all.map((a) => view(run, a, user)),
        limits: { maxFileBytes: MAX_FILE, maxFiles: MAX_FILES, maxTotalBytes: MAX_TOTAL, usedFiles: live.length, usedBytes: live.reduce((n, a) => n + a.sizeBytes, 0), allowedTypes: ALL_FILE_TYPES },
        canAdd,
      })
    }),
    http.post(`${B}/workspaces/:ws/runs/:run/attachments`, async ({ params, request }) => {
      await slow()
      const found = access(String(params.ws), String(params.run))
      if (found instanceof Response) return found
      const { user, run } = found
      if (!canSeeRunData(run, user) || !(run.initiatorId === user.id || isRunParticipant(run, user) || permissionsOf(user, run.wsId).includes('run.operate'))) return forbidden()
      if (!(run.lifecycle === 'running' || run.lifecycle === 'waiting' || run.lifecycle === 'needsAttention')) return problem(409, 'RUN_NOT_ACTIVE', 'The run is not active')
      const missing = key(request)
      if (missing) return missing
      const file = await read(request)
      if (file instanceof Response) return file
      const live = (run.attachments ?? []).filter((a) => !a.removedAt)
      if (live.length >= MAX_FILES) return problem(409, 'ATTACHMENT_LIMIT_REACHED', 'The request has reached its file limit', { detail: `This request already has ${MAX_FILES} files. Remove one first.` })
      if (live.reduce((n, a) => n + a.sizeBytes, 0) + file.data.length > MAX_TOTAL) return problem(409, 'ATTACHMENT_LIMIT_REACHED', 'The request has reached its file limit', { detail: 'The files of this request would exceed 50 MiB in total.' })
      const built = await build(user, file.name, file.data, file.description)
      if (built instanceof Response) return built
      ;(run.attachments ??= []).push(built)
      addEvent(run, 'ATTACHMENT_ADDED', { actorId: user.id, data: { attachmentId: built.id, name: built.name, description: built.description, sizeBytes: built.sizeBytes, contentType: built.contentType } })
      return HttpResponse.json(view(run, built, user), { status: 201 })
    }),
    http.get(`${B}/workspaces/:ws/runs/:run/attachments/:id/content`, async ({ params }) => {
      await slow()
      const found = access(String(params.ws), String(params.run))
      if (found instanceof Response) return found
      const { user, run } = found
      if (!canSeeRunData(run, user)) return forbidden()
      const file = (run.attachments ?? []).find((a) => a.id === String(params.id))
      if (!file || file.removedAt) return notFound()
      if (blocked(file)) return problem(409, 'ATTACHMENT_BLOCKED', 'The file is blocked and cannot be downloaded')
      const content = bytes.get(file.id) ?? new TextEncoder().encode(`${file.name}: the demo file content is not kept after a reload.`)
      return new HttpResponse(content as BodyInit, {
        status: 200,
        headers: { 'Content-Type': 'application/octet-stream', 'Content-Disposition': `attachment; filename="${file.name}"`, 'X-Content-Type-Options': 'nosniff', 'Content-Security-Policy': 'sandbox' },
      })
    }),
    http.delete(`${B}/workspaces/:ws/runs/:run/attachments/:id`, async ({ params }) => {
      await slow()
      const found = access(String(params.ws), String(params.run))
      if (found instanceof Response) return found
      const { user, run } = found
      if (!canSeeRunData(run, user)) return forbidden()
      const file = (run.attachments ?? []).find((a) => a.id === String(params.id))
      if (!file) return notFound()
      const active = run.lifecycle === 'running' || run.lifecycle === 'waiting' || run.lifecycle === 'needsAttention'
      if (!((run.initiatorId === user.id && active) || permissionsOf(user, run.wsId).includes('run.operate'))) return forbidden()
      if (!file.removedAt) {
        file.removedAt = clock.iso()
        file.removedById = user.id
        bytes.delete(file.id)
        addEvent(run, 'ATTACHMENT_REMOVED', { actorId: user.id, data: { attachmentId: file.id, name: file.name } })
      }
      return new HttpResponse(null, { status: 204 })
    }),
  ]
}

/** Test helper: forget every staged file and stored byte. */
export function resetCaseFiles(): void {
  staged.clear()
  bytes.clear()
}
