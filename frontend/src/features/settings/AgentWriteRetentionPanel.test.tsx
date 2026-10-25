import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { components } from '@/api/schema'
import { server } from '@/test/server'
import { renderApp, signInAs, WS } from '@/test/utils'

type S = components['schemas']

describe('agent write record retention setting (S85)', () => {
  it('saves a number of days or none with the version seen, rejects invalid input locally and reloads after a conflict', async () => {
    const sent: unknown[] = []
    let setting: S['AgentWriteRetention'] = { retentionDays: null, version: 0, eligibleNow: 0, updatedAt: null }
    let conflictOnce = false
    server.use(
      http.get('*/api/v1/workspaces/:ws/agent-write-retention', () => HttpResponse.json(setting)),
      http.put('*/api/v1/workspaces/:ws/agent-write-retention', async ({ request }) => {
        sent.push(await request.json())
        if (conflictOnce) {
          conflictOnce = false
          setting = { retentionDays: 60, version: 2, eligibleNow: 0, updatedAt: '2026-10-25T10:00:00Z' }
          return HttpResponse.json({ type: 'https://orqix.example/problems/workflow-version-conflict', title: 'Changed', status: 409,
            code: 'WORKFLOW_VERSION_CONFLICT', correlationId: 'c1', currentVersion: 2 }, { status: 409, headers: { 'Content-Type': 'application/problem+json' } })
        }
        setting = { retentionDays: 30, version: 1, eligibleNow: 3, updatedAt: '2026-10-25T09:00:00Z' }
        return HttpResponse.json(setting)
      }),
    )
    signInAs('wade.admin')
    const user = userEvent.setup()
    renderApp(`/w/${WS}/settings`)
    await user.click(await screen.findByRole('tab', { name: 'Agent write retention' }))
    expect(await screen.findByRole('heading', { name: 'Agent write record retention' })).toBeVisible()
    const field = await screen.findByLabelText('Days to keep write records after a request ends')
    expect(field).toHaveValue('')
    await user.type(field, '4000')
    await user.click(screen.getByRole('button', { name: 'Save write record retention' }))
    expect(field).toHaveAttribute('aria-invalid', 'true')
    expect(field).toHaveAccessibleDescription(/Enter a whole number of days from 1 to 3650/)
    expect(sent).toEqual([])
    await user.clear(field)
    await user.type(field, '30')
    await user.click(screen.getByRole('button', { name: 'Save write record retention' }))
    await waitFor(() => expect(sent).toEqual([{ retentionDays: 30, expectedVersion: 0 }]))
    expect(await screen.findByTestId('write-retention-eligible')).toHaveTextContent('3 write records are past this period')

    // Someone else changed it meanwhile: the save is refused and the current value is reloaded.
    conflictOnce = true
    const current = screen.getByLabelText('Days to keep write records after a request ends')
    expect(current).toHaveValue('30')
    await user.clear(current)
    await user.click(screen.getByRole('button', { name: 'Save write record retention' }))
    await waitFor(() => expect(sent).toHaveLength(2))
    expect(sent[1]).toEqual({ retentionDays: null, expectedVersion: 1 })
    await waitFor(() => expect(screen.getByLabelText('Days to keep write records after a request ends')).toHaveValue('60'))
  })

  it('is not offered to people without workspace.manage-access', async () => {
    signInAs('dana.designer')
    renderApp(`/w/${WS}/settings`)
    await screen.findByRole('tab', { name: 'Document templates' })
    expect(screen.queryByRole('tab', { name: 'Agent write retention' })).not.toBeInTheDocument()
  })
})
