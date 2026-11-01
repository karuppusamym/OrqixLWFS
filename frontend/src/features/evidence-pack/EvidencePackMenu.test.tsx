import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { downloadBlob } from '@/lib/download'
import { server } from '@/test/server'
import { renderInWorkspace, signInAs } from '@/test/utils'
import { EvidencePackMenu } from './EvidencePackMenu'

vi.mock('@/lib/download', () => ({ downloadBlob: vi.fn(), downloadJson: vi.fn() }))

describe('evidence pack download', () => {
  beforeEach(() => vi.mocked(downloadBlob).mockClear())

  it('downloads a release pack in the chosen format with the server file name', async () => {
    signInAs('vic.viewer')
    const formats: string[] = []
    server.use(http.get('/api/v1/workspaces/:ws/workflows/:wf/releases/:rel/evidence-pack', ({ request, params }) => {
      const format = new URL(request.url).searchParams.get('format') ?? 'pdf'
      formats.push(`${String(params.rel)}:${format}`)
      return new HttpResponse(new Blob(['%PDF-1.7']), { headers: { 'Content-Type': 'application/pdf', 'Content-Disposition': `attachment; filename="purchase-r2-evidence-pack.${format}"` } })
    }))
    renderInWorkspace(<EvidencePackMenu target={{ kind: 'release', workflowId: 'wf', releaseId: 'rel-2' }} label="Evidence pack for version 2" />)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Evidence pack for version 2' }))
    expect(screen.getByText('Download for auditors')).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: 'JSON (machine-readable)' })).toBeInTheDocument()
    await user.click(screen.getByRole('menuitem', { name: 'Word document (.docx)' }))
    await vi.waitFor(() => expect(downloadBlob).toHaveBeenCalledWith(expect.anything(), 'purchase-r2-evidence-pack.docx'))
    expect(formats).toEqual(['rel-2:docx'])
  })

  it('downloads a run pack and shows a refusal from the server', async () => {
    signInAs('vic.viewer')
    let status = 200
    server.use(http.get('/api/v1/workspaces/:ws/runs/:run/evidence-pack', () => status === 200
      ? new HttpResponse(new Blob(['{}']), { headers: { 'Content-Type': 'application/json', 'Content-Disposition': 'attachment; filename="run-PR-7-evidence-pack.json"' } })
      : HttpResponse.json({ type: 'about:blank', title: 'Forbidden', status: 403, code: 'FORBIDDEN', detail: 'You do not have permission.', correlationId: 'c' }, { status: 403, headers: { 'Content-Type': 'application/problem+json' } })))
    renderInWorkspace(<EvidencePackMenu target={{ kind: 'run', runId: 'run-7' }} label="Evidence pack for request PR-7" />)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Evidence pack for request PR-7' }))
    await user.click(screen.getByRole('menuitem', { name: 'JSON (machine-readable)' }))
    await vi.waitFor(() => expect(downloadBlob).toHaveBeenCalledWith(expect.anything(), 'run-PR-7-evidence-pack.json'))
    status = 403
    await user.click(screen.getByRole('button', { name: 'Evidence pack for request PR-7' }))
    await user.click(screen.getByRole('menuitem', { name: 'PDF document' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(downloadBlob).toHaveBeenCalledTimes(1)
  })

  it('is hidden from people who cannot read runs or audit evidence', async () => {
    signInAs('rhea.requester')
    renderInWorkspace(<><p>Request details</p><EvidencePackMenu target={{ kind: 'run', runId: 'run-7' }} label="Evidence pack for request PR-7" /></>)
    await screen.findByText('Request details')
    expect(screen.queryByRole('button', { name: /Evidence pack/ })).not.toBeInTheDocument()
  })
})
