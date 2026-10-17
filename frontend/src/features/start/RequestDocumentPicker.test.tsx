import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { db, PURCHASE_WORKFLOW_ID, userByName } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'

const sourceId = 'dddddddd-0000-4000-8000-000000000001'
function fixture() {
  const user = userByName('rhea.requester')
  db.documentBrowseGrants = [{ wsId: WS, subjectId: user.id }]
  db.documentSources = new Map([[sourceId, { wsId: WS, grant: { source: { id: sourceId, label: 'Approved policies', connectionKey: 'microsoft-documents', definitionVersion: 4, driveId: 'drive', folderId: 'folder' }, browseOperation: 'browseFolder', versionsOperation: 'listVersions', audience: [{ type: 'subject', value: user.id }], enabled: true, revision: 1 }, files: [{ itemId: 'item', name: 'Policy.docx', size: 100, contentType: 'application/docx' }], versions: { item: [{ versionId: '1.0', size: 100, lastModifiedDateTime: '2026-10-11T00:00:00Z', newestListed: true }] } }]])
  const release = db.workflows.get(PURCHASE_WORKFLOW_ID)!.releases.find((r) => r.id === db.workflows.get(PURCHASE_WORKFLOW_ID)!.activeReleaseId)!
  for (const [key, title] of Object.entries({ driveId: 'Document drive', itemId: 'Document item', versionId: 'Document version' })) release.definition.inputSchema.properties[key] = { type: 'string', title, maxLength: 200 }
  release.definition.inputUi ??= {}; release.definition.inputUi.order = [...(release.definition.inputUi.order ?? []), 'driveId', 'itemId', 'versionId']
}
describe('requester approved document selection', () => {
  it('requires an explicit file/version and maps distinct compatible reference fields', async () => {
    signInAs('rhea.requester'); fixture(); const user = userEvent.setup(); renderApp(`/w/${WS}/start/${PURCHASE_WORKFLOW_ID}`)
    await screen.findByLabelText('Approved document source', undefined, { timeout: 15000 })
    await screen.findByRole('option', { name: 'Approved policies' })
    await user.selectOptions(screen.getByLabelText('Approved document source'), sourceId)
    await screen.findByRole('option', { name: 'Policy.docx' })
    await user.selectOptions(await screen.findByLabelText('Document'), 'item')
    await screen.findByRole('option', { name: /1.0/ })
    await user.selectOptions(await screen.findByLabelText('Historical version'), '1.0')
    expect(screen.getByRole('button', { name: 'Use document reference' })).toBeDisabled()
    await user.selectOptions(screen.getByLabelText('Request field for drive'), 'driveId')
    await user.selectOptions(screen.getByLabelText('Request field for document'), 'itemId')
    await user.selectOptions(screen.getByLabelText('Request field for version'), 'versionId')
    await user.click(screen.getByRole('button', { name: 'Use document reference' }))
    expect(screen.getByLabelText(/^Document drive/)).toHaveValue('drive'); expect(screen.getByLabelText(/^Document item/)).toHaveValue('item'); expect(screen.getByLabelText(/^Document version/)).toHaveValue('1.0')
  })
  it('does not expose a resource without its audience grant', async () => {
    signInAs('rhea.requester'); fixture(); db.documentSources!.get(sourceId)!.grant.audience = [{ type: 'subject', value: userByName('vic.viewer').id }]
    renderApp(`/w/${WS}/start/${PURCHASE_WORKFLOW_ID}`)
    await screen.findByText('No document sources have been granted to you.', undefined, { timeout: 15000 })
    expect(screen.queryByRole('option', { name: 'Approved policies' })).not.toBeInTheDocument()
  })
})
