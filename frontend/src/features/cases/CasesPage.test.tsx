import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import { renderAppReady, signInAs } from '@/test/utils'
import { WS_SANDBOX } from '@/mocks/state'

const ws = WS_SANDBOX
const definitionId = '00000000-0000-7000-8000-000000000a11'
const instanceId = '00000000-0000-7000-8000-000000000a12'
const definition = { id: definitionId, key: 'review', name: 'Evidence review', version: 1, createdAt: '2026-10-17T12:00:00Z' }
const active = { id: instanceId, definitionId, definitionVersion: 1, status: 'active', version: 1,
  tasks: [{ id: 'task-1', name: 'Collect evidence', status: 'available', candidateGroups: ['case-workers'], eligible: true }], createdAt: '2026-10-17T12:00:00Z' }

describe('case workbench', () => {
  it('uploads a reviewed model and starts a case from the returned definition', async () => {
    signInAs('dana.designer')
    let submitted: unknown
    let definitions: typeof definition[] = []
    server.use(
      http.get('*/api/v1/workspaces/:ws/cases/definitions', () => HttpResponse.json(definitions)),
      http.get('*/api/v1/workspaces/:ws/cases/instances', () => HttpResponse.json([])),
      http.post('*/api/v1/workspaces/:ws/cases/definitions', async ({ request }) => {
        submitted = await request.json(); definitions = [definition]
        return HttpResponse.json(definition, { status: 201 })
      }),
      http.post('*/api/v1/workspaces/:ws/cases/definitions/:id/instances', ({ request }) => {
        expect(request.headers.get('Idempotency-Key')).toBeTruthy()
        return HttpResponse.json(active, { status: 201 })
      }),
      http.get('*/api/v1/workspaces/:ws/cases/instances/:id', () => HttpResponse.json(active)),
    )
    const user = userEvent.setup(); const { router } = await renderAppReady(`/w/${ws}/cases`)
    await screen.findByText('No case definitions are available in this workspace.')
    await user.type(screen.getByLabelText('Key'), 'review')
    await user.type(screen.getByLabelText('Name'), 'Evidence review')
    const file = new File(['<definitions/>'], 'review.cmmn', { type: 'application/xml' })
    Object.defineProperty(file, 'text', { value: async () => '<definitions/>' })
    await user.upload(screen.getByLabelText(/CMMN file/), file)
    await user.click(screen.getByRole('button', { name: 'Create definition' }))
    expect(submitted).toMatchObject({ key: 'review', name: 'Evidence review', sourceXml: '<definitions/>' })
    await user.click(await screen.findByRole('button', { name: 'Start case' }))
    await waitFor(() => expect(router.state.location.pathname).toBe(`/w/${ws}/cases/${instanceId}`))
    expect(await screen.findByText('Collect evidence')).toBeInTheDocument()
  })

  it('completes only eligible tasks with the current case version', async () => {
    signInAs('dana.designer')
    let body: unknown
    server.use(
      http.get('*/api/v1/workspaces/:ws/cases/instances/:id', () => HttpResponse.json(active)),
      http.post('*/api/v1/workspaces/:ws/cases/instances/:id/tasks/:taskId/complete', async ({ request }) => {
        expect(request.headers.get('Idempotency-Key')).toBeTruthy()
        body = await request.json()
        return HttpResponse.json({ ...active, status: 'completed', version: 2, tasks: [] })
      }),
    )
    const user = userEvent.setup(); await renderAppReady(`/w/${ws}/cases/${instanceId}`)
    await user.click(await screen.findByRole('button', { name: 'Complete task' }))
    await waitFor(() => expect(body).toEqual({ expectedVersion: 1 }))
    expect(await screen.findByText('No tasks are currently available.')).toBeInTheDocument()
  })
})
