import { QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import * as endpoints from '@/api/endpoints'
import { resetSharedAssets } from '@/mocks/sharedAssets'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import { signInAs, testQueryClient, WS } from '@/test/utils'
import purchase from '../../../../../examples/purchase-approval.json'
import { StudioProvider } from '../StudioContext'
import { formLink } from '../model/sharedForms'
import { normalizeDefinition, type Definition } from '../model/types'
import { SharedFormsSection } from './SharedFormsSection'

beforeEach(() => resetSharedAssets())

function setup(definition: Definition = normalizeDefinition(purchase), readOnly = false) {
  const store = createStudioStore()
  store.getState().load({ workflowId: 'workflow', revision: 1, definition })
  render(
    <QueryClientProvider client={testQueryClient()}>
      <StudioProvider value={{ store, saver: {} as DraftSaver, wsId: WS, workflowId: 'workflow', readOnly, connections: { responseType: () => undefined } }}>
        <SharedFormsSection />
      </StudioProvider>
    </QueryClientProvider>,
  )
  return store
}

/** A workflow with one question of its own whose key clashes with the purchase form's "title". */
function otherWorkflow(): Definition {
  const def = normalizeDefinition(purchase)
  def.workflowKey = 'equipment-request'
  def.name = 'Equipment request'
  def.inputSchema = { type: 'object', additionalProperties: false, required: ['title'], properties: { title: { type: 'string', title: 'Job title', maxLength: 80 } } }
  def.inputUi = { order: ['title'] }
  return def
}

async function sharePurchaseForm() {
  signInAs('dana.designer')
  const purchaseDef = normalizeDefinition(purchase)
  const { formContentOf } = await import('../model/sharedForms')
  await endpoints.createFormAsset(WS, { key: 'purchase-request', name: 'Purchase request', content: formContentOf(purchaseDef) })
}

describe('shared forms on the Form tab (docs/build/S128)', () => {
  it('saves the request form as a shared form and links its questions', async () => {
    signInAs('dana.designer')
    const store = setup()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: /Save as shared form/ }))
    const form = screen.getByRole('form', { name: 'Save as shared form' })
    await user.clear(within(form).getByLabelText(/Shared name/))
    await user.type(within(form).getByLabelText(/Shared name/), 'Purchase request')
    expect(within(form).getByLabelText(/Shared key/)).toHaveValue('purchase-request')
    await user.click(within(form).getByRole('button', { name: 'Save shared form' }))
    expect(await screen.findByText(/Saved as shared form Purchase request, version 1/)).toBeInTheDocument()
    const link = formLink(store.getState().definition, 'purchase-request')!
    expect(link.version).toBe(1)
    expect(Object.keys(link.fields).sort()).toEqual(['amount', 'costCenter', 'purpose', 'title'])
    const linked = await screen.findByTestId('shared-form-link')
    await waitFor(() => expect(linked).toHaveTextContent('Up to date'))
  })

  it('inserts a shared form only after each clashing key is renamed or skipped', async () => {
    await sharePurchaseForm()
    const store = setup(otherWorkflow())
    const user = userEvent.setup()
    const picker = await screen.findByLabelText('Insert a shared form')
    await waitFor(() => expect(within(picker).getByRole('option', { name: 'Purchase request (version 1)' })).toBeInTheDocument())
    await user.selectOptions(picker, 'purchase-request')
    const clashes = await screen.findByTestId('shared-form-clashes')
    expect(clashes).toHaveTextContent('This form already asks “Job title” with the key title.')
    const rename = within(clashes).getByLabelText('New key for What are you buying?')
    expect(rename).toHaveValue('title2')
    await user.clear(rename)
    await user.type(rename, 'purpose')
    expect(within(clashes).getByRole('alert')).toHaveTextContent('also has a question with the key "purpose"')
    const insert = screen.getByRole('button', { name: /Insert shared form v1/ })
    expect(insert).toBeDisabled()
    await user.clear(rename)
    await user.type(rename, 'item')
    expect(insert).toBeEnabled()
    await user.click(insert)
    expect(await screen.findByText(/Inserted 4 questions from Purchase request, version 1. Renamed: title as item./)).toBeInTheDocument()
    const def = store.getState().definition
    expect(def.inputSchema.properties.title!.title).toBe('Job title')
    expect(def.inputSchema.properties.item!.title).toBe('What are you buying?')
    expect(formLink(def, 'purchase-request')!.fields).toEqual({ title: 'item', purpose: 'purpose', costCenter: 'costCenter', amount: 'amount' })
    expect(def.inputUi?.currency).toBe('USD')
  })

  it('skips a clashing question and keeps this form\'s own', async () => {
    await sharePurchaseForm()
    const store = setup(otherWorkflow())
    const user = userEvent.setup()
    const picker = await screen.findByLabelText('Insert a shared form')
    await waitFor(() => expect(within(picker).getAllByRole('option')).toHaveLength(2))
    await user.selectOptions(picker, 'purchase-request')
    await user.click(await screen.findByLabelText(/Skip/))
    await user.click(screen.getByRole('button', { name: /Insert shared form v1/ }))
    expect(await screen.findByText(/Skipped: title./)).toBeInTheDocument()
    expect(formLink(store.getState().definition, 'purchase-request')!.fields).not.toHaveProperty('title')
  })

  it('offers an update to a newer version with a diff, and never changes the copy by itself', async () => {
    await sharePurchaseForm()
    const store = setup(otherWorkflow())
    const user = userEvent.setup()
    const picker = await screen.findByLabelText('Insert a shared form')
    await waitFor(() => expect(within(picker).getAllByRole('option')).toHaveLength(2))
    await user.selectOptions(picker, 'purchase-request')
    await user.click(await screen.findByRole('button', { name: /Insert shared form v1/ }))
    await screen.findByTestId('shared-form-link')

    // A designer elsewhere publishes version 2: one question changed, one added (clashing "title" again is not new).
    const v1 = (await endpoints.getSharedAsset(WS, 'form', 'purchase-request')).versions[0]!.content as { inputSchema: { properties: Record<string, Record<string, unknown>> } }
    const v2 = structuredClone(v1)
    v2.inputSchema.properties.purpose!.title = 'Why do you need it?'
    v2.inputSchema.properties.supplier = { type: 'string', title: 'Supplier', maxLength: 120 }
    await endpoints.addFormAssetVersion(WS, 'purchase-request', { expectedVersion: 1, content: v2 as never, changeNote: 'Supplier' })
    const before = structuredClone(store.getState().definition)

    // Reopen the section so it reads the new version.
    cleanup()
    setup(before)
    const linked = await screen.findByTestId('shared-form-link')
    await waitFor(() => expect(linked).toHaveTextContent('Version 2 available'))
    await user.click(within(linked).getByRole('button', { name: 'Update to v2' }))
    const diff = screen.getByTestId('shared-form-diff')
    expect(diff).toHaveTextContent('Added: Supplier')
    expect(diff).toHaveTextContent('Removed: none')
    expect(diff).toHaveTextContent('Changed: Why do you need it?')
  })

  it('shows nothing to read-only viewers of a form without links', () => {
    signInAs('vic.viewer')
    setup(normalizeDefinition(purchase), true)
    expect(screen.queryByText('Shared forms')).not.toBeInTheDocument()
  })
})
