import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import { renderAppReady, signInAs, WS } from '@/test/utils'
import type { components } from '@/api/schema'
import { PURCHASE_WORKFLOW_ID } from '@/mocks/state'

type View = components['schemas']['BlueprintTranscriptionView']
type Job = components['schemas']['MigrationJob']
const id = '00000000-0000-7000-8000-000000000951'
const sha = (c: string) => c.repeat(64)

const issue = (sourceElementId: string, sourceName: string, severity: 'action' | 'blocker' | 'info', reasonCode: string, explanation: string): components['schemas']['CompatibilityIssue'] =>
  ({ sourceElementId, sourceName, sourceKind: 'blueprintSla', severity, reasonCode, explanation, resolutionStatus: 'open', sourcePath: 'page 3 /slas/' + sourceElementId })
const report = (issues: components['schemas']['CompatibilityIssue'][]): components['schemas']['CompatibilityReport'] => ({
  sourceSystem: 'pega', sourceVersion: 'Blueprint PDF (generic-outline 0.1, 3 pages)', adapterVersion: 'pega-blueprint-pdf-0.1', sourceHash: sha('b'),
  processId: 'equipment-purchase-request', processName: 'Equipment Purchase Request', elementCounts: { total: issues.length },
  issues, assumptions: [], requiredMappings: { groups: ['role-line-manager'], approvalTasks: [], serviceTasks: [] }, publishable: false })
const transcription = (): components['schemas']['BlueprintTranscription'] => ({
  format: 'orqix-blueprint-transcription-0.1',
  caseType: { name: 'Equipment Purchase Request', sourcePage: 1, sourceExcerpt: 'Case type: Equipment Purchase Request', disposition: 'proposed' },
  start: 'step-manager-approval',
  roles: [{ id: 'role-line-manager', name: 'Line Manager', sourcePage: 1, sourceExcerpt: '- Line Manager - approves every request', disposition: 'proposed' }],
  steps: [{ id: 'step-manager-approval', name: 'Manager approval', type: 'approval', role: 'role-line-manager', next: 'outcome-approved', onReject: 'outcome-rejected', sourcePage: 2, sourceExcerpt: '1. Manager approval (approval) - role: Line Manager', disposition: 'proposed' }],
  slas: [{ id: 'sla-manager-approval', name: 'Manager approval', step: 'step-manager-approval', amount: 8, sourcePage: 3, sourceExcerpt: '- Manager approval: 8', disposition: 'proposed' }],
  outcomes: [{ id: 'outcome-approved', name: 'Approved', type: 'success', sourcePage: 3, disposition: 'proposed' }, { id: 'outcome-rejected', name: 'Rejected', type: 'rejected', sourcePage: 3, disposition: 'proposed' }],
})
const view = (revision: number, t = transcription(), issues = [issue('sla-manager-approval', 'Manager approval', 'action', 'BLUEPRINT_SLA_UNIT_MISSING', 'The SLA has no unit (minutes, hours or days); its meaning is not guessed.')]): View => ({
  migrationId: id, status: 'analyzed', revision, transcriptionSha256: sha(String(revision)), origin: revision === 1 ? 'extracted' : 'edited', savedAt: '2026-10-25T00:00:00Z',
  layout: { id: 'generic-outline', revision: '0.1' }, pageCount: 3, javascript: true, embeddedFiles: false,
  pages: [{ number: 1, text: 'SYNTHETIC SAMPLE\nCase type: Equipment Purchase Request\n- Line Manager - approves every request', sha256: sha('1'), unclassifiedLines: 1 },
    { number: 2, text: 'Steps\n1. Manager approval (approval) - role: Line Manager', sha256: sha('2'), unclassifiedLines: 0 },
    { number: 3, text: 'SLAs\n- Manager approval: 8\nOutcomes', sha256: sha('3'), unclassifiedLines: 0 }],
  transcription: t, report: report(issues) })
const job = (): Job => ({ id, fileName: 'synthetic-blueprint.pdf', status: 'analyzed', report: view(1).report, createdAt: '2026-10-25T00:00:00Z' })

describe('Pega Blueprint (PDF) import', () => {
  it('uploads a PDF, shows each element beside its source page, saves edits and converts the reviewed revision', async () => {
    signInAs('dana.designer')
    let current = view(1)
    let saved: { expectedRevision: number; transcription: components['schemas']['BlueprintTranscription'] } | null = null
    let converted: Record<string, unknown> | null = null
    let uploadedName: string | null = null
    server.use(
      http.post('*/api/v1/workspaces/:ws/migrations/blueprints', async ({ request }) => {
        const form = await request.formData(); uploadedName = (form.get('file') as File).name
        return HttpResponse.json(job(), { status: 201 })
      }),
      http.get('*/api/v1/workspaces/:ws/migrations/:id', () => HttpResponse.json(job())),
      http.get('*/api/v1/workspaces/:ws/migrations/:id/blueprint', () => HttpResponse.json(current)),
      http.put('*/api/v1/workspaces/:ws/migrations/:id/blueprint', async ({ request }) => {
        saved = await request.json() as typeof saved
        current = view(2, saved!.transcription, [])
        return HttpResponse.json(current)
      }),
      http.post('*/api/v1/workspaces/:ws/migrations/:id/blueprint/ai-proposal', () => HttpResponse.json(
        { type: 'about:blank', title: 'AI assistance is not configured', status: 422, code: 'AI_NOT_CONFIGURED', detail: 'AI is not configured for this deployment.', correlationId: 'c' },
        { status: 422, headers: { 'Content-Type': 'application/problem+json' } })),
      http.post('*/api/v1/workspaces/:ws/migrations/:id/convert', async ({ request }) => {
        converted = await request.json() as Record<string, unknown>
        return HttpResponse.json({ workflow: { id: PURCHASE_WORKFLOW_ID }, draft: {}, report: current.report }, { status: 201 })
      }),
    )
    const user = userEvent.setup(); await renderAppReady(`/w/${WS}/migrations`)
    await user.selectOptions(await screen.findByLabelText('Source format'), 'pegaBlueprint')
    await user.upload(screen.getByLabelText(/Source file \(PDF/), new File(['%PDF-1.4'], 'synthetic-blueprint.pdf', { type: 'application/pdf' }))
    await user.click(screen.getByRole('button', { name: 'Analyze source' }))
    const review = await screen.findByRole('region', { name: 'Blueprint transcription review' })
    expect(uploadedName).not.toBeNull() // one multipart file part (jsdom does not keep the file name)
    expect(within(review).getByText('Active content ignored')).toBeInTheDocument()
    expect(within(review).getByText(/1 finding needs a decision before conversion/)).toBeInTheDocument()
    expect(within(review).getByText(/its meaning is not guessed/)).toBeInTheDocument()
    // The SLA card shows the source page text next to the editable element.
    expect(within(review).getAllByRole('article', { name: 'Manager approval (proposed)' })).toHaveLength(2)
    expect(within(review).getAllByLabelText('Text of source page 3')[0]).toHaveTextContent('- Manager approval: 8')
    expect(within(review).getByRole('button', { name: 'Convert to draft' })).toBeEnabled()
    // AI is optional: a clear message, nothing changed.
    await user.click(within(review).getByRole('button', { name: 'Ask AI for a proposal' }))
    expect(await within(review).findByText('AI is not configured for this deployment.')).toBeInTheDocument()
    // Edit the SLA unit, confirm the rest, save.
    await user.selectOptions(within(review).getByLabelText('Unit'), 'hours')
    await user.click(within(review).getByRole('button', { name: /Mark 6 unreviewed elements as reviewed/ }))
    expect(within(review).getByRole('button', { name: 'Convert to draft' })).toBeDisabled()
    await user.click(within(review).getByRole('button', { name: 'Save transcription' }))
    await waitFor(() => expect(saved).not.toBeNull())
    expect(saved!.expectedRevision).toBe(1)
    expect(saved!.transcription.slas![0]).toMatchObject({ unit: 'hours', disposition: 'mapped' })
    expect(saved!.transcription.steps![0]!.disposition).toBe('mapped')
    expect(await screen.findByText('Revision 2')).toBeInTheDocument()
    expect(screen.getByText('No open blocking findings in the saved revision.')).toBeInTheDocument()
    // Map the role, acknowledge, convert.
    await user.selectOptions(await screen.findByLabelText('Workspace group for role: Line Manager'), 'managers')
    await user.click(screen.getByRole('checkbox', { name: /I acknowledge the exclusions/ }))
    await user.click(screen.getByRole('button', { name: 'Convert to draft' }))
    await waitFor(() => expect(converted).not.toBeNull())
    expect(converted).toMatchObject({ transcriptionSha256: sha('2'), groupMappings: { 'role-line-manager': 'managers' }, acknowledgeInfo: true })
    expect(converted).not.toHaveProperty('approvalRoutes')
    expect(converted).not.toHaveProperty('inputSchema')
  })

  it('mock mode refuses a PDF clearly', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup(); await renderAppReady(`/w/${WS}/migrations`)
    await user.selectOptions(await screen.findByLabelText('Source format'), 'pegaBlueprint')
    await user.upload(screen.getByLabelText(/Source file \(PDF/), new File(['%PDF-1.4'], 'b.pdf', { type: 'application/pdf' }))
    await user.click(screen.getByRole('button', { name: 'Analyze source' }))
    expect(await screen.findByText(/Pega Blueprint import requires the real backend/)).toBeInTheDocument()
  })
})
