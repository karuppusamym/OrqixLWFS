import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { resetDocumentLibrary } from '@/mocks/documentLibrary'
import { server } from '@/test/server'
import { renderApp, signInAs, WS } from '@/test/utils'

beforeEach(() => resetDocumentLibrary())

async function openTemplates(user: ReturnType<typeof userEvent.setup>) {
  renderApp(`/w/${WS}/settings`)
  await user.click(await screen.findByRole('tab', { name: 'Document templates' }))
}

describe('document template registry (DOC08)', () => {
  it('creates a template and adds an immutable version while older versions stay visible', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await openTemplates(user)
    await user.click(await screen.findByRole('button', { name: /New template/ }))
    const form = screen.getByRole('form', { name: 'New document template' })
    await user.type(within(form).getByLabelText(/Template name/), 'Welcome letter')
    await user.click(within(form).getByLabelText(/Template text/))
    await user.paste('Hello {{name}}')
    await user.click(within(form).getByRole('button', { name: 'Create template' }))
    const detail = await screen.findByTestId('template-detail')
    expect(within(detail).getAllByTestId('template-version')).toHaveLength(1)

    const edit = within(detail).getByRole('form', { name: 'New version of Welcome letter' })
    const save = within(edit).getByRole('button', { name: 'Save as version 2' })
    expect(save).toBeDisabled()
    const text = within(edit).getByLabelText('Template text')
    await user.clear(text)
    await user.click(text)
    await user.paste('Dear {{name}}')
    await user.click(save)
    await waitFor(() => expect(within(screen.getByTestId('template-detail')).getAllByTestId('template-version')).toHaveLength(2))
    const versions = within(screen.getByTestId('template-detail')).getAllByTestId('template-version')
    expect(versions[0]).toHaveTextContent('Version 2')
    expect(versions[1]).toHaveTextContent('Version 1')
    expect(versions[1]).toHaveTextContent('Hello {{name}}')
  })

  it('shows a refused template with the server message and keeps what was typed', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await openTemplates(user)
    await user.click(await screen.findByRole('button', { name: /New template/ }))
    const form = screen.getByRole('form', { name: 'New document template' })
    await user.type(within(form).getByLabelText(/Template name/), 'Bad letter')
    await user.click(within(form).getByLabelText(/Template text/))
    await user.paste('Hello {{name')
    await user.click(within(form).getByRole('button', { name: 'Create template' }))
    expect(await within(form).findByText(/invalid placeholder/)).toBeInTheDocument()
    expect(within(form).getByLabelText(/Template name/)).toHaveValue('Bad letter')
  })

  it('lets readers see versions but not change them', async () => {
    signInAs('vic.viewer')
    const user = userEvent.setup()
    await openTemplates(user)
    await user.click(await screen.findByRole('button', { name: /Order confirmation letter/ }))
    expect(await screen.findByTestId('template-version')).toHaveTextContent('Dear {{customer}}')
    expect(screen.queryByRole('button', { name: /New template/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Archive template/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('form', { name: /New version/ })).not.toBeInTheDocument()
  })

  it('explains a version conflict instead of overwriting', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    server.use(
      http.post('*/api/v1/workspaces/:ws/document-templates/:id/versions', () =>
        HttpResponse.json({ type: 'about:blank', title: 'Conflict', status: 409, code: 'WORKFLOW_VERSION_CONFLICT', currentVersion: 3 }, { status: 409, headers: { 'Content-Type': 'application/problem+json' } })),
    )
    await openTemplates(user)
    await user.click(await screen.findByRole('button', { name: /Order confirmation letter/ }))
    const text = await screen.findByLabelText('Template text')
    await user.clear(text)
    await user.click(text)
    await user.paste('Changed {{customer}}')
    await user.click(screen.getByRole('button', { name: 'Save as version 2' }))
    expect(await screen.findByText(/changed while you were editing/)).toBeInTheDocument()
  })
})

describe('file retention setting (DOC08)', () => {
  it('saves a number of days, rejects invalid input locally and sends the version it saw', async () => {
    signInAs('wade.admin')
    const user = userEvent.setup()
    let sent: unknown
    server.use(
      http.put('*/api/v1/workspaces/:ws/attachment-retention', async ({ request }) => {
        sent = await request.json()
        return HttpResponse.json({ retentionDays: 30, version: 1, eligibleNow: 2, updatedAt: new Date().toISOString() })
      }),
    )
    renderApp(`/w/${WS}/settings`)
    await user.click(await screen.findByRole('tab', { name: 'File retention' }))
    const days = await screen.findByLabelText(/Days to keep files/)
    await user.type(days, '0')
    await user.click(screen.getByRole('button', { name: 'Save retention' }))
    expect(await screen.findByText(/whole number of days from 1 to 3650/)).toBeInTheDocument()
    expect(sent).toBeUndefined()
    await user.clear(days)
    await user.type(days, '30')
    await user.click(screen.getByRole('button', { name: 'Save retention' }))
    await waitFor(() => expect(sent).toEqual({ retentionDays: 30, expectedVersion: 0 }))
    expect(await screen.findByTestId('retention-eligible')).toHaveTextContent('2 files are past this period')
  })

  it('is not offered to designers', async () => {
    signInAs('dana.designer')
    renderApp(`/w/${WS}/settings`)
    await screen.findByRole('tab', { name: 'Document templates' })
    expect(screen.queryByRole('tab', { name: 'File retention' })).not.toBeInTheDocument()
  })
})
