import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { server } from '@/test/server'
import { renderApp, signInAs, WS } from '@/test/utils'
import { db } from '@/mocks/state'
import type * as WorkspaceModule from '@/app/workspace'

const flags = vi.hoisted(() => ({ grantWorkflowRead: false, downloads: [] as Array<{ name: string; size: number }> }))

// Administrators do not hold workflow.read by default; one test grants it to reach the workflow picker.
vi.mock('@/app/workspace', async (importOriginal) => {
  const actual = await importOriginal<typeof WorkspaceModule>()
  return {
    ...actual,
    useWorkspace: () => {
      const value = actual.useWorkspace()
      return { ...value, can: (...wanted: Parameters<typeof value.can>) => (flags.grantWorkflowRead && wanted.includes('workflow.read')) || value.can(...wanted) }
    },
  }
})

vi.mock('@/lib/download', () => ({
  downloadBlob: (blob: Blob, name: string) => { flags.downloads.push({ name, size: blob.size }) },
  downloadJson: () => undefined,
}))

const B = '/api/v1'

async function openTab(user: ReturnType<typeof userEvent.setup>) {
  renderApp(`/w/${WS}/settings`)
  await user.click(await screen.findByRole('tab', { name: /Exit export/ }))
  return screen.findByRole('form', { name: 'Exit export' })
}

describe('exit export', () => {
  let bodies: unknown[]
  beforeEach(() => {
    flags.grantWorkflowRead = false
    flags.downloads = []
    bodies = []
    server.events.on('request:start', async ({ request }) => {
      if (request.method === 'POST' && request.url.endsWith('/exit-export')) bodies.push(JSON.parse(await request.clone().text()))
    })
  })
  afterEach(() => server.events.removeAllListeners())

  it('downloads the whole workspace without run data by default', async () => {
    signInAs('wade.admin')
    const user = userEvent.setup()
    const form = await openTab(user)
    expect(within(form).getByRole('radio', { name: 'The whole workspace' })).toBeChecked()
    // An administrator without workflow.read cannot pick single workflows.
    expect(within(form).getByRole('radio', { name: 'Selected workflows' })).toBeDisabled()
    await user.click(within(form).getByRole('button', { name: 'Download exit archive' }))
    await waitFor(() => expect(flags.downloads).toHaveLength(1))
    expect(flags.downloads[0]?.name).toBe('orqix-exit-purchasing-mock.zip')
    expect(bodies).toEqual([{ includeRunData: false }])
    expect(await screen.findByText(/Archive downloaded/)).toBeInTheDocument()
    expect(screen.getByTestId('announcer')).toHaveTextContent('Exit archive orqix-exit-purchasing-mock.zip downloaded.')
  })

  it('exports only the chosen workflows, with run data when asked', async () => {
    signInAs('wade.admin', { published: true })
    flags.grantWorkflowRead = true
    server.use(http.get(`${B}/workspaces/:ws/workflows`, () => HttpResponse.json({
      items: [...db.workflows.values()].filter((w) => w.wsId === WS).map((w) => ({ id: w.id, key: w.key, name: w.name })),
      nextCursor: null,
    })))
    const user = userEvent.setup()
    const form = await openTab(user)
    await user.click(within(form).getByRole('radio', { name: 'Selected workflows' }))
    const download = within(form).getByRole('button', { name: 'Download exit archive' })
    expect(download).toBeDisabled() // nothing chosen yet
    const list = await within(form).findByRole('list', { name: 'Workflows to export' })
    const first = [...db.workflows.values()].find((w) => w.wsId === WS)!
    await user.click(await within(list).findByRole('checkbox', { name: new RegExp(first.key) }))
    await user.click(within(form).getByRole('checkbox', { name: /Include run input and output data/ }))
    await user.click(download)
    await waitFor(() => expect(flags.downloads).toHaveLength(1))
    expect(bodies).toEqual([{ workflowIds: [first.id], includeRunData: true }])
  })

  it('shows the problem when the server refuses the export', async () => {
    signInAs('wade.admin')
    server.use(http.post(`${B}/workspaces/:ws/exit-export`, () => HttpResponse.json(
      { type: 'about:blank', title: 'Some values are not valid', status: 422, code: 'VALIDATION_FAILED', detail: 'One or more selected workflows do not exist in this workspace.', correlationId: 'c-1' },
      { status: 422, headers: { 'Content-Type': 'application/problem+json' } })))
    const user = userEvent.setup()
    const form = await openTab(user)
    await user.click(within(form).getByRole('button', { name: 'Download exit archive' }))
    expect(await within(form).findByText('One or more selected workflows do not exist in this workspace.')).toBeInTheDocument()
    expect(flags.downloads).toHaveLength(0)
  })

  it('is not offered to people who are not workspace administrators', async () => {
    signInAs('dana.designer')
    renderApp(`/w/${WS}/settings`)
    await screen.findByRole('tablist', { name: 'Settings sections' })
    expect(screen.queryByRole('tab', { name: /Exit export/ })).not.toBeInTheDocument()
  })
})
