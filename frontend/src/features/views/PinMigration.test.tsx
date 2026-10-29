import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { describe, expect, it } from 'vitest'
import { isProblemError } from '@/api/problem'
import { listViewPins, migrateViewPins } from '@/api/viewPins'
import { getView, publishView, saveView } from '@/api/views'
import { setCurrentUser } from '@/mocks/handlers'
import { renderInWorkspace, signInAs, WS } from '@/test/utils'
import type { ViewDefinition } from './model'
import { PinMigration } from './PinMigration'

const FORM = '77777777-7777-4777-8777-777777777771'
const DESK = '77777777-7777-4777-8777-777777777773'

describe('reviewed pin migration (S114)', () => {
  it('lists every pin with its status, moves the selected draft after review and refuses a plan that changed', async () => {
    signInAs('dana.designer')
    const form = await getView(WS, FORM)
    const relabeled = structuredClone(form.definition) as ViewDefinition
    relabeled.sections[0]!.label = 'Purchase details'
    await saveView(WS, FORM, { expectedRevision: 1, name: form.name, definition: relabeled })
    setCurrentUser('paul.publisher')
    await publishView(WS, FORM, 2)
    setCurrentUser('dana.designer')
    const reviewed = await listViewPins(WS, FORM, 2)

    const user = userEvent.setup()
    const { container } = renderInWorkspace(<PinMigration viewId={FORM} versions={[1, 2]} />)
    const draft = await screen.findByRole('checkbox', { name: 'Purchase desk page' })
    const panel = screen.getByRole('region', { name: 'Where this view is used' })
    expect(within(panel).getByText('Can update')).toBeInTheDocument()
    expect(within(panel).getByText('Fixed version')).toBeInTheDocument()
    expect(within(panel).getByText(/Published version 1 keeps its pins/)).toBeInTheDocument()

    await user.click(draft)
    await user.click(screen.getByRole('button', { name: 'Review update of 1 pin' }))
    const dialog = screen.getByRole('dialog')
    const update = within(dialog).getByRole('button', { name: 'Update 1 pin' })
    expect(update).toBeDisabled()
    await user.type(within(dialog).getByLabelText('Reason (recorded with the update)'), 'Clearer section label')
    await user.click(update)
    expect(await screen.findByText('Moved 1 pin to version 2')).toBeInTheDocument()
    expect((await getView(WS, DESK)).draftRevision).toBe(2)
    expect(await screen.findByText(/Earlier updates \(1\)/)).toBeInTheDocument()
    expect((await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })).violations).toEqual([])

    // The reviewed plan no longer matches: the server refuses it instead of applying stale choices.
    const stale = await migrateViewPins(WS, FORM, { targetVersion: 2, planHash: reviewed.planHash, items: [`page:${DESK}`], reason: 'Again' }).catch((error: unknown) => error)
    expect(isProblemError(stale) && stale.code).toBe('WORKFLOW_VERSION_CONFLICT')
  }, 30_000)

  it('lets the page itself move its route only after a newer page version is published', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    renderInWorkspace(<PinMigration viewId={DESK} versions={[1]} />)
    expect(await screen.findByText('/apps/purchase-desk')).toBeInTheDocument()
    expect(screen.getByText('Already on this version')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Review update/ })).not.toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Version to move pins to'), '1')
  })
})
