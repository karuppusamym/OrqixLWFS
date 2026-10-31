import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import { renderAppReady, signInAs } from '@/test/utils'
import { WS_SANDBOX } from '@/mocks/state'
import { listCaseDefinitions } from './api'
import { caseAutosaveTuning } from './CaseDesignerPage'

const ws = WS_SANDBOX
const cmmn = (key: string, group = 'managers') => `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL" xmlns:flowable="http://flowable.org/cmmn" targetNamespace="urn:orqix:test">
  <case id="${key}" name="Uploaded review"><casePlanModel id="plan" name="Uploaded review">
    <planItem id="reviewItem" definitionRef="review"/>
    <humanTask id="review" name="Review the claim" flowable:candidateGroups="${group}"/>
  </casePlanModel></case>
</definitions>`
const file = (text: string) => { const value = new File([text], 'case.cmmn', { type: 'application/xml' }); Object.defineProperty(value, 'text', { value: async () => text }); return value }
const results = () => screen.getByRole('region', { name: 'Check results' })

describe('case designer page (S126)', () => {
  // These tests count draft versions; autosave would add versions between their steps.
  beforeEach(() => { caseAutosaveTuning.delayMs = 600_000 })
  afterEach(() => { caseAutosaveTuning.delayMs = undefined })

  it('creates a case type, shows every problem the check finds and publishes once they are fixed', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    const { router } = await renderAppReady(`/w/${ws}/case-designs`)
    expect(await screen.findByRole('heading', { level: 1, name: 'Case types' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'New case type' }))
    await user.type(screen.getByLabelText('Key'), 'claim-review')
    await user.type(screen.getByLabelText('Name'), 'Claim review')
    await user.click(screen.getByRole('button', { name: 'Create draft' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Claim review' })).toBeInTheDocument()
    expect(router.state.location.pathname).toMatch(new RegExp(`^/w/${ws}/case-designs/[^/]+$`))
    expect(screen.getByText(/Case type claim-review · draft version 1 · Not published yet\./)).toBeInTheDocument()
    expect(await screen.findByTestId('case-canvas')).toBeInTheDocument()

    // A data field, edited with the shared field editor (S125). The starter task has no eligible group: the check says so.
    await user.click(screen.getByRole('tab', { name: 'Data' }))
    await user.click(screen.getByRole('button', { name: 'Add case data field' }))
    const card = within(screen.getByTestId('case-field-card-field1'))
    await user.clear(card.getByLabelText(/^Label/))
    await user.type(card.getByLabelText(/^Label/), 'Claim amount{Enter}')
    await user.click(screen.getByRole('button', { name: 'Check' }))
    await waitFor(() => expect(within(results()).getByText('1 problem blocks publishing draft version 2')).toBeInTheDocument())
    expect(within(results()).getByText('Choose an eligible workspace group for every human task.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Publish version 1' })).toBeDisabled()

    await user.click(screen.getByRole('tab', { name: 'Plan' }))
    await user.click(screen.getByRole('button', { name: 'Form view' }))
    const group = screen.getByLabelText('Eligible group for task1')
    await waitFor(() => expect(group.querySelectorAll('option').length).toBeGreaterThan(1))
    await user.selectOptions(group, 'managers')
    expect(screen.getByRole('button', { name: 'Publish version 1' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Check' }))
    await waitFor(() => expect(within(results()).getByText('No problems found in draft version 3. It can be published as version 1.')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Publish version 1' }))
    expect(await screen.findByText('Published version 1 of Claim review. People can start it from Cases.')).toBeInTheDocument()
    expect(screen.getByText(/Version 1 is published and matches this draft\./)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Publish version 2' })).toBeDisabled()
    const published = (await listCaseDefinitions(ws)).find(definition => definition.key === 'claim-review')
    expect(published).toMatchObject({ name: 'Claim review', version: 1, dataFields: [{ key: 'field1', label: 'Claim amount', type: 'text' }] })

    await user.click(within(screen.getByRole('navigation', { name: 'Breadcrumb' })).getByRole('link', { name: 'Case types' }))
    const row = (await screen.findByRole('cell', { name: 'claim-review' })).closest('tr')!
    expect(within(row).getByText(/Published v1/)).toBeInTheDocument()
    expect(within(row).getByText('No changes since version 1')).toBeInTheDocument()
  })

  it('uses an uploaded CMMN file as it is, reports a wrong case ID and publishes the corrected file', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(`/w/${ws}/case-designs`)
    await user.click(await screen.findByRole('button', { name: 'New case type' }))
    await user.type(screen.getByLabelText('Key'), 'uploaded-review')
    await user.type(screen.getByLabelText('Name'), 'Uploaded review')
    await user.click(screen.getByRole('button', { name: 'Create draft' }))
    await screen.findByRole('heading', { level: 1, name: 'Uploaded review' })
    await user.click(screen.getByRole('tab', { name: 'Source' }))
    await user.upload(screen.getByLabelText('CMMN file (maximum 64 KiB)'), file(cmmn('another-key')))
    expect(await screen.findByRole('region', { name: 'Uploaded CMMN file' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Check' }))
    await waitFor(() => expect(within(results()).getByText('Exactly one case with an ID matching the catalog key is required.')).toBeInTheDocument())
    await user.click(screen.getByRole('tab', { name: 'Source' }))
    await user.upload(screen.getByLabelText('CMMN file (maximum 64 KiB)'), file(cmmn('uploaded-review')))
    await user.click(screen.getByRole('button', { name: 'Check' }))
    await waitFor(() => expect(within(results()).getByText(/No problems found/)).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Publish version 1' }))
    expect(await screen.findByText('Published version 1 of Uploaded review. People can start it from Cases.')).toBeInTheDocument()
  })

  it('opens a published case type in the designer through a new draft with its source and data', async () => {
    signInAs('dana.designer')
    const definition = { id: '00000000-0000-7000-8000-000000000c01', key: 'claim-intake', name: 'Claim intake', version: 2, createdAt: '2026-10-17T12:00:00Z', dataFields: [{ key: 'amount', label: 'Amount', type: 'number' as const, required: true }] }
    let created: unknown
    server.use(
      http.get('*/api/v1/workspaces/:ws/cases/definitions', () => HttpResponse.json([definition])),
      http.get('*/api/v1/workspaces/:ws/cases/definitions/:id', () => HttpResponse.json({ ...definition, sourceXml: cmmn('claim-intake'), workflowTasks: [] })),
      http.post('*/api/v1/workspaces/:ws/cases/drafts', async ({ request }) => {
        created = await request.json()
        return HttpResponse.json({ id: '00000000-0000-7000-8000-000000000c02', version: 1, designerModel: null, viewRef: null, workflowTasks: [], caseTasks: [], decisionTasks: [], updatedAt: '2026-10-30T12:00:00Z', publishedVersion: 2, publishedDraftVersion: null, ...(created as object) }, { status: 201 })
      }),
      http.get('*/api/v1/workspaces/:ws/cases/drafts/:id', () => HttpResponse.json({ id: '00000000-0000-7000-8000-000000000c02', version: 1, viewRef: null, workflowTasks: [], caseTasks: [], decisionTasks: [], updatedAt: '2026-10-30T12:00:00Z', publishedVersion: 2, publishedDraftVersion: null, ...(created as object) })),
    )
    const user = userEvent.setup()
    await renderAppReady(`/w/${ws}/case-designs`)
    const row = (await screen.findByRole('cell', { name: 'claim-intake' })).closest('tr')!
    expect(within(row).getByText('No draft')).toBeInTheDocument()
    await user.click(within(row).getByRole('button', { name: 'Open designer' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Claim intake' })).toBeInTheDocument()
    expect(created).toMatchObject({ key: 'claim-intake', name: 'Claim intake', sourceXml: cmmn('claim-intake'), dataFields: definition.dataFields, viewRef: null })
    expect(screen.getByText(/Version 2 is published; this draft has changes\./)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Publish version 3' })).toBeDisabled()
    // The published source opens in the designer, with its plan item and data fields.
    expect(await screen.findByTestId('case-canvas')).toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: 'Data' }))
    expect(await screen.findByRole('button', { name: /^Field 1: Amount/ })).toBeInTheDocument()
  })

  it('autosaves after a quiet period and undoes and redoes plan changes from the shared header', async () => {
    caseAutosaveTuning.delayMs = 20
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(`/w/${ws}/case-designs`)
    await user.click(await screen.findByRole('button', { name: 'New case type' }))
    await user.type(screen.getByLabelText('Name'), 'Frame review')
    await user.click(screen.getByRole('button', { name: 'Create draft' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Frame review' })).toBeInTheDocument()
    // The starter plan is not stored until saved: autosave stores it as draft version 2.
    await waitFor(() => expect(screen.getByTestId('save-state')).toHaveTextContent(/^Saved/))
    expect(screen.getByText(/draft version 2/)).toBeInTheDocument()
    const history = screen.getByRole('group', { name: 'History' })
    expect(within(history).getByRole('button', { name: 'Undo' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Add milestone' }))
    await waitFor(() => expect(within(history).getByRole('button', { name: 'Undo' })).toBeEnabled())
    expect(screen.getAllByText('New milestone').length).toBeGreaterThan(0)
    await user.click(within(history).getByRole('button', { name: 'Undo' }))
    await waitFor(() => expect(screen.queryAllByText('New milestone')).toHaveLength(0))
    expect(within(history).getByRole('button', { name: 'Redo' })).toBeEnabled()
    await user.click(within(history).getByRole('button', { name: 'Redo' }))
    await waitFor(() => expect(screen.getAllByText('New milestone').length).toBeGreaterThan(0))
    await waitFor(() => expect(screen.getByTestId('save-state')).toHaveTextContent(/^Saved/))
  })

  it('is offered only to people who may author case types', async () => {
    signInAs('rhea.requester')
    await renderAppReady(`/w/${ws}/case-designs`)
    expect(await screen.findByText('You need permission to edit workflows to design case types.')).toBeInTheDocument()
    expect(within(screen.getByRole('navigation', { name: 'Main' })).queryByRole('link', { name: 'Case types' })).not.toBeInTheDocument()
  })
})
