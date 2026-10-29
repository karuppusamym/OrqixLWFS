import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { describe, expect, it } from 'vitest'
import { createExtension, submitExtensionVersion } from '@/api/extensions'
import { setCurrentUser } from '@/mocks/handlers'
import { renderAppReady, signInAs, WS } from '@/test/utils'

const axeOptions = { iframes: false, rules: { 'color-contrast': { enabled: false } } }

describe('extension registry with mock API (S111)', () => {
  it('lets a designer register an extension and submit a version that waits for approval', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    const { container } = await renderAppReady(`/w/${WS}/extensions`)
    const seeded = await screen.findByRole('heading', { level: 2, name: 'Request badge' })
    expect(seeded.closest('li')).toHaveTextContent('approved')
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Register extension' }))
    const create = await screen.findByRole('dialog', { name: 'Register extension' })
    await user.type(within(create).getByLabelText('Name'), 'Score card')
    await user.click(within(create).getByRole('button', { name: 'Register' }))
    const card = (await screen.findByRole('heading', { level: 2, name: 'Score card' })).closest('li')!
    await user.click(within(card).getByRole('button', { name: 'Submit version' }))
    const submit = await screen.findByRole('dialog', { name: 'Submit a version of Score card' })
    await user.click(within(submit).getByLabelText('Approve'))
    await user.click(within(submit).getByRole('button', { name: 'Submit for approval' }))
    await waitFor(() => expect(card).toHaveTextContent('pending'))
    expect(card).toHaveTextContent('Data: task.requestTitle · Actions: Approve')

    await user.click(within(seeded.closest('li')!).getByRole('button', { name: 'Version 1' }))
    const review = await screen.findByRole('region', { name: 'Version review' })
    expect(review).toHaveTextContent('SHA-256')
    expect(await within(review).findByTitle('Request badge preview')).toHaveAttribute('sandbox', 'allow-scripts')
    expect((await axe.run(container, axeOptions)).violations).toEqual([])
  }, 30_000)

  it('lets an administrator approve and later revoke a version with a note', async () => {
    signInAs('dana.designer')
    const extension = await createExtension(WS, { name: 'Risk meter' })
    await submitExtensionVersion(WS, extension.id, { source: "orqix.onData(function (d) { document.getElementById('root').textContent = String(d['request.amount']); });", capabilities: { bindings: ['request.amount'], actions: [] }, height: 120 })
    setCurrentUser('wade.admin')
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS}/extensions`)
    const card = (await screen.findByRole('heading', { level: 2, name: 'Risk meter' })).closest('li')!
    await user.click(within(card).getByRole('button', { name: 'Approve' }))
    const approve = await screen.findByRole('dialog', { name: 'Approve Risk meter version 1' })
    expect(within(approve).getByRole('button', { name: 'Approve' })).toBeDisabled()
    await user.type(within(approve).getByLabelText('Decision note'), 'Reads one amount, renders text only')
    await user.click(within(approve).getByRole('button', { name: 'Approve' }))
    await waitFor(() => expect(card).toHaveTextContent('approved by Wade Admin: Reads one amount, renders text only'))

    await user.click(within(card).getByRole('button', { name: 'Revoke' }))
    const revoke = await screen.findByRole('dialog', { name: 'Revoke Risk meter version 1' })
    expect(revoke).toHaveTextContent('Pages using this version show a fallback at once')
    await user.type(within(revoke).getByLabelText('Decision note'), 'Superseded')
    await user.click(within(revoke).getByRole('button', { name: 'Revoke' }))
    await waitFor(() => expect(card).toHaveTextContent('revoked by Wade Admin: Superseded'))
  }, 30_000)

  it('hides the registry from members who can neither read workflows nor manage access', async () => {
    signInAs('rhea.requester')
    await renderAppReady(`/w/${WS}/extensions`)
    expect(await screen.findByRole('alert')).toHaveTextContent('You do not have permission to read extensions.')
  })

  it('submits a multi-file bundle with an entry module and shows each file with its hash for review (S116)', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS}/extensions`)
    await user.click(await screen.findByRole('button', { name: 'Register extension' }))
    const create = await screen.findByRole('dialog', { name: 'Register extension' })
    await user.type(within(create).getByLabelText('Name'), 'Module card')
    await user.click(within(create).getByRole('button', { name: 'Register' }))
    const card = (await screen.findByRole('heading', { level: 2, name: 'Module card' })).closest('li')!
    await user.click(within(card).getByRole('button', { name: 'Submit version' }))
    const submit = await screen.findByRole('dialog', { name: 'Submit a version of Module card' })
    await user.clear(within(submit).getByLabelText('Bundle source (JavaScript using the orqix bridge)'))
    await user.upload(within(submit).getByLabelText(/Bundle files/), [
      new File(["import { x } from './util.js'; orqix.onData(function () {});"], 'main.js', { type: 'text/javascript' }),
      new File(['export const x = 1;'], 'util.js', { type: 'text/javascript' }),
    ])
    expect(await within(submit).findByLabelText('Entry module')).toHaveValue('main.js')
    await user.click(within(submit).getByRole('button', { name: 'Submit for approval' }))
    await waitFor(() => expect(card).toHaveTextContent('pending'))
    await user.click(within(card).getByRole('button', { name: 'Version 1' }))
    const files = await screen.findByRole('list', { name: 'Bundle files' })
    expect(within(files).getAllByRole('listitem').map(item => item.textContent?.split(' · ')[0])).toEqual(['main.js', 'util.js'])
    expect(files).toHaveTextContent('SHA-256')
  }, 30_000)
})
