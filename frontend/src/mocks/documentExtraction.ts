import { http, HttpResponse, type HttpHandler } from 'msw'
import type { components } from '@/api/schema'
import { db, WS_PURCHASING } from './state'

type S = components['schemas']

/**
 * UI double of document extraction (CG-A06, docs/build/S146-parts/B-document-extraction.md), mock mode and unit tests
 * only. The newest active purchase request gets one open verification task over a text invoice whose total was read
 * with low confidence; submitting it closes the task. OCR settings offer local and one EU cloud provider.
 */
const B = '/api/v1/workspaces/:ws'
const NOW = '2026-11-08T09:00:00Z'
export const MOCK_VERIFICATION_ID = '00000000-0000-7000-8000-0000000000b1'
export const MOCK_DOCUMENT_ID = '00000000-0000-7000-8000-0000000000b2'
export const MOCK_DOCUMENT_TEXT = ['ACME Supplies GmbH', 'Invoice number: INV-2026-8', 'Invoice date: 2026-10-01',
  'Subtotal: 100.00', 'VAT: 19.50', 'Total: 119.00', 'Currency: EUR'].join('\n')

function span(line: number, value: string): { start: number; end: number } {
  const lines = MOCK_DOCUMENT_TEXT.split('\n')
  const offset = lines.slice(0, line - 1).reduce((n, l) => n + l.length + 1, 0)
  const at = (lines[line - 1] ?? '').indexOf(value)
  return { start: offset + at, end: offset + at + value.length }
}

function field(name: string, type: S['AiVerificationField']['type'], required: boolean, value: unknown, confidence: number,
  line: number, text: string, problems: S['AiVerificationField']['problems'] = []): S['AiVerificationField'] {
  return {
    name, type, required, description: null, hint: null, value, confidence, problems,
    needsAttention: confidence < 0.8 || problems.length > 0,
    source: { kind: 'ocrLine', page: 1, line, box: null, span: span(line, text) },
    sourceText: MOCK_DOCUMENT_TEXT.split('\n')[line - 1] ?? null,
  }
}

function initial(runId: string): S['AiVerification'] {
  return {
    id: MOCK_VERIFICATION_ID, runId, nodeId: 'read', occurrence: 1, decisionId: '00000000-0000-7000-8000-0000000000b3',
    skillKey: 'invoice-reader', skillVersion: 1, documentType: 'invoice', provider: 'local', status: 'open', version: 1,
    fieldCount: 7, needsAttention: 1, createdAt: NOW, threshold: 0.8, canSubmit: true, ownRequest: false,
    attachment: { id: MOCK_DOCUMENT_ID, name: 'invoice.txt', contentType: 'text/plain', available: true, unavailableReason: null },
    pages: [{ number: 1, width: 0, height: 0, unit: 'none' }],
    fields: [
      field('supplierName', 'string', true, 'ACME Supplies GmbH', 0.95, 1, 'ACME Supplies GmbH'),
      field('invoiceNumber', 'string', true, 'INV-2026-8', 0.97, 2, 'INV-2026-8'),
      field('invoiceDate', 'date', true, '2026-10-01', 0.95, 3, '2026-10-01'),
      field('subtotalAmount', 'number', false, 100, 0.95, 4, '100.00'),
      field('taxAmount', 'number', false, 19.5, 0.95, 5, '19.50'),
      field('totalAmount', 'number', true, 119, 0.3, 6, '119.00', [{ code: 'SUM_MISMATCH', message: 'The value does not equal subtotalAmount + taxAmount (difference -0.50).' }]),
      field('currency', 'string', false, 'EUR', 0.95, 7, 'EUR'),
    ],
    decision: { decidedBy: null, decidedAt: null, corrected: [] },
  }
}

let current: S['AiVerification'] | null = null

function verificationFor(runId: string): S['AiVerification'] | null {
  // Unit tests opt in with resetMockVerification(runId); mock mode binds the task to the newest active request.
  if (!current && import.meta.env.MODE !== 'test') {
    const run = [...db.runs.values()].filter((r) => r.wsId === WS_PURCHASING && (r.lifecycle === 'waiting' || r.lifecycle === 'running'))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0]
    if (!run) return null
    current = initial(run.id)
  }
  return current?.runId === runId ? current : null
}

/** Resets the mock (unit tests). */
export function resetMockVerification(runId?: string): void {
  current = runId ? initial(runId) : null
}

function summary(v: S['AiVerification']): S['AiVerificationSummary'] {
  const { id, runId, nodeId, occurrence, decisionId, skillKey, skillVersion, documentType, provider, status, version, fieldCount, needsAttention, createdAt } = v
  return { id, runId, nodeId, occurrence, decisionId, skillKey, skillVersion, documentType, provider, status, version, fieldCount, needsAttention, createdAt }
}

const problem = (status: number, code: string, title: string, fieldErrors?: Array<{ pointer: string; code: string; message: string }>) =>
  HttpResponse.json({ type: 'about:blank', title, status, code, ...(fieldErrors ? { fieldErrors } : {}) }, { status })

export function createDocumentExtractionHandlers(): HttpHandler[] {
  return [
    http.get(`${B}/ai-ocr-settings`, () => HttpResponse.json({
      provider: 'local', chosen: false, version: 0, updatedAt: null, refusal: null,
      providers: [
        { id: 'local', kind: 'local', label: 'On this deployment (text and PDF text layer)', residency: ['private'], allowed: true, refusal: null },
        { id: 'azure-eu', kind: 'azureDocumentIntelligence', label: 'Azure Document Intelligence (EU)', residency: ['eu'], allowed: false, refusal: 'Cloud OCR is used only after a workspace administrator chooses it.' },
      ],
    } satisfies S['AiOcrSettings'])),
    http.get(`${B}/ai-verifications`, () => HttpResponse.json({ items: current && current.status === 'open' ? [summary(current)] : [] } satisfies S['AiVerificationQueue'])),
    http.get(`${B}/runs/:runId/ai-verifications`, ({ params }) => {
      const v = verificationFor(String(params.runId))
      return HttpResponse.json({ items: v ? [summary(v)] : [], canVerify: true } satisfies S['AiVerificationList'])
    }),
    http.get(`${B}/runs/:runId/ai-verifications/:id`, ({ params }) => {
      const v = verificationFor(String(params.runId))
      return v && v.id === params.id ? HttpResponse.json(v) : problem(404, 'NOT_FOUND', 'Not found')
    }),
    http.post(`${B}/runs/:runId/ai-verifications/:id/submit`, async ({ params, request }) => {
      const v = verificationFor(String(params.runId))
      if (!v || v.id !== params.id) return problem(404, 'NOT_FOUND', 'Not found')
      const body = await request.json() as S['AiVerificationSubmit']
      if (v.status !== 'open' || body.expectedVersion !== v.version) return problem(409, 'TASK_VERSION_CONFLICT', 'The task changed since you loaded it')
      const answers = body.fields ?? {}
      const outcome = body.outcome ?? 'verified'
      if (outcome === 'escalated' && !body.reason?.trim()) return problem(422, 'REASON_REQUIRED', 'A reason is required')
      const missing = outcome === 'verified' ? v.fields.filter((f) => f.needsAttention && !answers[f.name]) : []
      if (missing.length) return problem(422, 'VALIDATION_FAILED', 'Some fields cannot be accepted', missing.map((f) => ({ pointer: `/fields/${f.name}`, code: 'required', message: 'Confirm or correct this field: its value needs attention.' })))
      const values = Object.fromEntries(v.fields.map((f) => [f.name, answers[f.name]?.action === 'correct' ? answers[f.name]?.value : f.value]))
      if (outcome === 'verified' && !body.acknowledgeProblems && typeof values.totalAmount === 'number' && Math.abs(values.totalAmount - 119.5) > 0.01) {
        return problem(422, 'VALIDATION_FAILED', 'Some values fail the document\'s checks. Correct them or acknowledge the problems.', [{ pointer: '/fields/totalAmount', code: 'SUM_MISMATCH', message: 'The value does not equal subtotalAmount + taxAmount.' }])
      }
      const corrected = v.fields.filter((f) => answers[f.name]?.action === 'correct' && answers[f.name]?.value !== f.value).map((f) => f.name)
      current = {
        ...v, status: outcome, version: v.version + 1, canSubmit: false,
        fields: v.fields.map((f) => ({ ...f, value: values[f.name] ?? null })),
        decision: { decidedBy: '00000000-0000-7000-8000-00000000a004', decidedAt: new Date().toISOString(), corrected },
      }
      return HttpResponse.json(current)
    }),
    // The mock document; other files fall through to the request-file handlers.
    http.get(`${B}/runs/:runId/attachments/:id/content`, ({ params }) => {
      if (params.id !== MOCK_DOCUMENT_ID) return undefined
      return new HttpResponse(MOCK_DOCUMENT_TEXT, { status: 200, headers: { 'Content-Type': 'application/octet-stream' } })
    }),
  ]
}
