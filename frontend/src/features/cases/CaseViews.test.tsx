import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import { renderAppReady, signInAs } from '@/test/utils'
import { WS_SANDBOX as ws } from '@/mocks/state'
import type { ViewDefinition } from '@/features/views/model'

const id = '00000000-0000-7000-8000-000000000b11'
const viewId = '00000000-0000-7000-8000-000000000b12'
const instance = { id, definitionId: id, definitionVersion: 1, status: 'active', version: 1, tasks: [], createdAt: '2026-10-21T12:00:00Z' }
const view: ViewDefinition = { schemaVersion: 1, kind: 'case', sections: [{ id: 'details', label: 'Shared case details', columns: 1, fields: [{ id: 'summary', binding: 'case.summary', type: 'text', label: 'Review summary', required: true }] }], actions: [{ id: 'save', type: 'saveData', label: 'Save reviewed details' }] }
const fields = [{ key: 'summary', label: 'Summary', type: 'text', required: true, maxLength: 80 }]

describe('reusable case presentation', () => {
  it('renders the immutable layout and saves through the existing reason/version boundary', async () => {
    signInAs('olga.operator')
    let body: unknown, updated = false
    server.use(
      http.get('*/api/v1/workspaces/:ws/cases/instances/:id', () => HttpResponse.json({ ...instance, version: updated ? 2 : 1 })),
      http.get('*/api/v1/workspaces/:ws/cases/instances/:id/data', () => HttpResponse.json({ fields, values: { summary: updated ? 'Reviewed' : 'Original' }, version: updated ? 2 : 1, canEdit: true, changes: [], viewBinding: { id: viewId, version: 1, definition: view } })),
      http.post('*/api/v1/workspaces/:ws/cases/instances/:id/data', async ({ request }) => { expect(request.headers.get('Idempotency-Key')).toBeTruthy(); body = await request.json(); updated = true; return HttpResponse.json({ ...instance, version: 2 }) }),
    )
    const user = userEvent.setup(); await renderAppReady(`/w/${ws}/cases/${id}`)
    await user.click(await screen.findByRole('button', { name: 'Show case data' }))
    const region = await screen.findByRole('region', { name: 'Shared case details' })
    const input = within(region).getByLabelText('Review summary (required)')
    expect(input).toHaveAttribute('maxLength', '80')
    await user.clear(input); await user.type(input, 'Reviewed')
    expect(screen.getByRole('button', { name: 'Save reviewed details' })).toBeDisabled()
    await user.type(screen.getByLabelText('Reason for data change'), 'Reviewed evidence')
    await user.click(screen.getByRole('button', { name: 'Save reviewed details' }))
    await waitFor(() => expect(body).toEqual({ expectedVersion: 1, reason: 'Reviewed evidence', values: { summary: 'Reviewed' } }))
    expect(await screen.findByLabelText('Review summary (required)')).toHaveValue('Reviewed')
  })
  it('does not expose the custom Save action when current case data access is read-only', async () => {
    signInAs('rhea.requester')
    server.use(
      http.get('*/api/v1/workspaces/:ws/cases/instances/:id', () => HttpResponse.json(instance)),
      http.get('*/api/v1/workspaces/:ws/cases/instances/:id/data', () => HttpResponse.json({ fields, values: { summary: 'Protected' }, version: 1, canEdit: false, changes: [], viewBinding: { id: viewId, version: 1, definition: view } })),
    )
    const user = userEvent.setup(); await renderAppReady(`/w/${ws}/cases/${id}`)
    await user.click(await screen.findByRole('button', { name: 'Show case data' }))
    expect(await screen.findByLabelText('Review summary (required)')).toHaveAttribute('readonly')
    expect(screen.queryByRole('button', { name: 'Save reviewed details' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Reason for data change')).not.toBeInTheDocument()
  })
  it('chooses an exact case view version when creating a definition', async () => {
    signInAs('dana.designer')
    let body: unknown
    const candidate = structuredClone(view); candidate.sections[0]!.fields = [{ id: 'field1', binding: 'case.field1', type: 'text', label: 'Shared field' }]
    server.use(
      http.get('*/api/v1/workspaces/:ws/cases/definitions', () => HttpResponse.json([])),
      http.get('*/api/v1/workspaces/:ws/cases/instances', () => HttpResponse.json([])),
      http.get('*/api/v1/workspaces/:ws/views', () => HttpResponse.json({ items: [{ id: viewId, kind: 'case', name: 'Shared case view', publishedVersion: 2 }] })),
      http.get('*/api/v1/workspaces/:ws/views/:id/versions/:version', () => HttpResponse.json({ viewId, version: 2, definition: candidate })),
      http.post('*/api/v1/workspaces/:ws/cases/definitions', async ({ request }) => { body = await request.json(); return HttpResponse.json({ ...instance, key: 'review', name: 'Review', version: 1 }, { status: 201 }) }),
    )
    const user = userEvent.setup(); await renderAppReady(`/w/${ws}/cases`)
    await user.type(await screen.findByLabelText('Key'), 'review'); await user.type(screen.getByLabelText('Name'), 'Review')
    const file = new File(['<definitions/>'], 'review.cmmn', { type: 'application/xml' }); Object.defineProperty(file, 'text', { value: async () => '<definitions/>' })
    await user.upload(screen.getByLabelText(/CMMN file/), file)
    await user.click(screen.getByRole('button', { name: 'Add case data field' }))
    await user.click(screen.getByRole('button', { name: 'Choose case view' }))
    await screen.findByRole('option', { name: /Shared case view/ })
    await user.selectOptions(screen.getByLabelText('Published case view'), viewId)
    await user.clear(screen.getByLabelText('Case view version')); await user.type(screen.getByLabelText('Case view version'), '2')
    await user.click(screen.getByRole('button', { name: 'Create definition' }))
    await waitFor(() => expect(body).toMatchObject({ viewRef: { id: viewId, version: 2 }, dataFields: [{ key: 'field1', type: 'text' }] }))
  })
})
