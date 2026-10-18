import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { resetDocumentLibrary } from '@/mocks/documentLibrary'
import { resetSharedAssets } from '@/mocks/sharedAssets'
import { db, PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import { server } from '@/test/server'
import { renderApp, signInAs, WS } from '@/test/utils'

beforeEach(() => {
  resetDocumentLibrary()
  resetSharedAssets()
})

async function openAssets(user: ReturnType<typeof userEvent.setup>) {
  renderApp(`/w/${WS}/settings`)
  await user.click(await screen.findByRole('tab', { name: 'Shared assets' }))
}

const names = () => screen.getAllByTestId('asset-row').map((row) => row.querySelector('span')?.textContent)

describe('shared assets (PL01)', () => {
  it('searches only the kinds a viewer may read and shows where an asset is used', async () => {
    signInAs('vic.viewer')
    // The purchase draft uses version 1 of a decision that has a newer version.
    const draft = db.workflows.get(PURCHASE_WORKFLOW_ID)!.drafts.at(-1)!.definition
    draft.dependencies.assets = [{ kind: 'decision', key: 'spend-limit', version: 1, target: 'amount-policy' }]
    const user = userEvent.setup()
    await openAssets(user)
    await waitFor(() => expect(names()).toEqual(['Order confirmation letter', 'Spend limit']))
    expect(screen.getByTestId('asset-count')).toHaveTextContent('Searched: Decision table, Document template.')
    expect(within(screen.getByLabelText('Kind')).queryByRole('option', { name: 'Connection' })).not.toBeInTheDocument()

    await user.type(screen.getByLabelText('Name, key or owner'), 'spend-')
    await user.click(screen.getByRole('button', { name: /Search/ }))
    await waitFor(() => expect(names()).toEqual(['Spend limit']))
    await user.click(screen.getAllByTestId('asset-row')[0]!.querySelector('button')!)
    const detail = await screen.findByTestId('asset-detail')
    const usage = await within(detail).findByTestId('asset-usage')
    expect(usage).toHaveTextContent('Version 1')
    expect(usage).toHaveTextContent('Latest')
    expect(within(detail).queryByRole('button', { name: 'Archive' })).not.toBeInTheDocument()
    expect(within(detail).getAllByTestId('asset-version')[0]).toHaveTextContent('1 row; exactly one row applies')
  })

  it('filters by owner', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await openAssets(user)
    await waitFor(() => expect(names()).toContain('Procurement service'))
    await waitFor(() => expect(within(screen.getByLabelText('Owner')).getByRole('option', { name: 'Wade Admin' })).toBeInTheDocument())
    await user.selectOptions(screen.getByLabelText('Owner'), screen.getByRole('option', { name: 'Wade Admin' }))
    await waitFor(() => expect(names().every((n) => n !== 'Spend limit')).toBe(true))
    expect(names().length).toBeGreaterThan(0)
  })

  it('lets a designer archive a decision and explains a concurrent change', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await openAssets(user)
    await user.click(screen.getByLabelText('Show archived'))
    await waitFor(() => expect(names()).toContain('Spend limit'))
    const row = screen.getAllByTestId('asset-row').find((r) => r.textContent?.includes('Spend limit'))!
    await user.click(row.querySelector('button')!)
    const detail = await screen.findByTestId('asset-detail')
    await user.click(await within(detail).findByRole('button', { name: 'Archive' }))
    expect(await within(screen.getByTestId('asset-detail')).findByRole('button', { name: 'Restore' })).toBeInTheDocument()

    server.use(http.put('*/api/v1/workspaces/:ws/shared-assets/:kind/:key/archived', () => HttpResponse.json(
      { type: 'about:blank', title: 'Conflict', status: 409, code: 'WORKFLOW_VERSION_CONFLICT', correlationId: 'x' },
      { status: 409, headers: { 'Content-Type': 'application/problem+json' } })))
    await user.click(within(screen.getByTestId('asset-detail')).getByRole('button', { name: 'Restore' }))
    expect(await screen.findByText(/Someone changed this asset/)).toBeInTheDocument()
  })

  it('shows connection managers only connections and lets them archive one', async () => {
    signInAs('wade.admin')
    const user = userEvent.setup()
    await openAssets(user)
    await waitFor(() => expect(names()).toHaveLength(2))
    expect(names()).toContain('Procurement service')
    expect(screen.getByTestId('asset-count')).toHaveTextContent('Searched: Connection.')
    await user.click(screen.getAllByTestId('asset-row').find((r) => r.textContent?.includes('Procurement service'))!.querySelector('button')!)
    const detail = await screen.findByTestId('asset-detail')
    await user.click(await within(detail).findByRole('button', { name: 'Archive' }))
    expect(await within(screen.getByTestId('asset-detail')).findByRole('button', { name: 'Restore' })).toBeInTheDocument()
  })
})
