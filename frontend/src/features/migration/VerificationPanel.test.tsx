import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { describe, expect, it } from 'vitest'
import type { components } from '@/api/schema'
import { server } from '@/test/server'
import { renderApp, signInAs, WS } from '@/test/utils'
import { PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import { reportCsv } from './reportExport'

type Job = components['schemas']['MigrationJob']
const id = '00000000-0000-7000-8000-000000000911'
const verifyIssue: components['schemas']['CompatibilityIssue'] = { sourceElementId: 'toLow', sourceName: 'toLow', sourceKind: 'sequenceFlow', severity: 'verify', reasonCode: 'CONDITION_REWRITTEN',
  explanation: "Converted but changed: source condition 'not(score > 100)' became score lte 100.", resolutionStatus: 'open' }
const job = (): Job => ({ id, fileName: 'feel.bpmn', status: 'converted', convertedWorkflowId: PURCHASE_WORKFLOW_ID, createdAt: '2026-10-09T00:00:00Z',
  report: { sourceSystem: 'camunda8', adapterVersion: 'camunda-bpmn-0.5', sourceHash: 'b'.repeat(64), elementCounts: { total: 2, supported: 1, verify: 1 }, assumptions: [], publishable: true,
    issues: [verifyIssue, { sourceElementId: 'start', sourceKind: 'startEvent', severity: 'supported', reasonCode: 'START_MAPPED', explanation: 'Start.', resolutionStatus: 'resolved' }] } })

describe('converted but changed — verify', () => {
  it('filters verify items, lets a publisher acknowledge them with a reason and offers the ledger download', async () => {
    signInAs('paul.publisher')
    let saved = job()
    let body: Record<string, unknown> | null = null
    let ledgerFormat: string | null = null
    server.use(
      http.get('*/api/v1/workspaces/:ws/migrations/:id', () => HttpResponse.json(saved)),
      http.post('*/api/v1/workspaces/:ws/migrations/:id/verifications', async ({ request }) => {
        body = await request.json() as Record<string, unknown>
        saved = { ...saved, report: { ...saved.report, issues: saved.report.issues.map(i => i.severity === 'verify' ? { ...i, resolutionStatus: 'acknowledged' } : i),
          verifications: [{ sourceElementId: 'toLow', reasonCode: 'CONDITION_REWRITTEN', acknowledgedBy: '00000000-0000-7000-8000-000000000005', acknowledgedByName: 'Paul Publisher', acknowledgedAt: '2026-10-30T10:00:00Z', reason: 'Checked 100 and 101' }] } }
        return HttpResponse.json(saved)
      }),
      http.get('*/api/v1/workspaces/:ws/migrations/:id/ledger', ({ request }) => {
        ledgerFormat = new URL(request.url).searchParams.get('format')
        return new HttpResponse(new Blob(['PK']), { headers: { 'Content-Type': 'application/zip', 'Content-Disposition': 'attachment; filename="migration-ledger-00000000.zip"' } })
      }),
    )
    const user = userEvent.setup()
    renderApp(`/w/${WS}/migrations/${id}`)
    expect(await screen.findByText('verify: 1')).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Severity'), 'verify')
    expect(screen.getByText('Showing 1 of 2 findings. Exports and conversion use the complete report.')).toBeInTheDocument()
    expect(screen.getByText('verify: converted but changed')).toBeInTheDocument()
    expect(screen.getByText(/1 of 1 changed item\(s\) still need verification/)).toBeInTheDocument()
    const acknowledge = screen.getByRole('button', { name: 'Acknowledge selected changes' })
    expect(acknowledge).toBeDisabled()
    await user.type(screen.getByLabelText('What did you check?'), 'Checked 100 and 101')
    await user.click(acknowledge)
    await waitFor(() => expect(body).toEqual({ items: [{ sourceElementId: 'toLow', reasonCode: 'CONDITION_REWRITTEN' }], reason: 'Checked 100 and 101' }))
    expect(await screen.findByText(/Every changed item is verified/)).toBeInTheDocument()
    expect(screen.getByText(/Verified by Paul Publisher/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Download migration ledger (ZIP)' }))
    await waitFor(() => expect(ledgerFormat).toBe('zip'))
  })

  it('explains why a person without publish permission cannot verify', async () => {
    signInAs('dana.designer')
    const saved = job()
    server.use(http.get('*/api/v1/workspaces/:ws/migrations/:id', () => HttpResponse.json(saved)))
    renderApp(`/w/${WS}/migrations/${id}`)
    expect(await screen.findByText('You need permission to publish workflows to verify changed behaviour.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Acknowledge selected changes' })).not.toBeInTheDocument()
  })

  it('exports the verification next to each finding in the CSV', () => {
    const report = job().report
    report.verifications = [{ sourceElementId: 'toLow', reasonCode: 'CONDITION_REWRITTEN', acknowledgedBy: 'x', acknowledgedByName: 'Paul Publisher', acknowledgedAt: '2026-10-30T10:00:00Z', reason: '=cmd' }]
    const csv = reportCsv(report)
    expect(csv.split('\r\n')[0]).toContain('"verifiedBy","verifiedAt","verificationReason"')
    expect(csv).toContain('"verify","CONDITION_REWRITTEN","open"')
    expect(csv).toContain('"Paul Publisher","2026-10-30T10:00:00Z","\'=cmd"')
  })
})
