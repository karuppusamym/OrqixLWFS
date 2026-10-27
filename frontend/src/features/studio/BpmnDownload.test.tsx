import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { db, PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import { renderAppReady, signInAs, WS } from '@/test/utils'

/** STU04 (docs/build/S97): Download BPMN (.bpmn) from the studio menu, the Source tab and a release. */
const downloads: Array<{ text: string; filename: string; type: string }> = []
vi.mock('@/lib/download', () => ({
  downloadBlob: (blob: Blob, filename: string) => {
    void blob.text().then((text) => downloads.push({ text, filename, type: blob.type }))
  },
  downloadJson: () => undefined,
}))

const EDIT = `/w/${WS}/workflows/${PURCHASE_WORKFLOW_ID}/edit`
const workflow = () => db.workflows.get(PURCHASE_WORKFLOW_ID)!

describe('BPMN download', () => {
  beforeEach(() => {
    downloads.length = 0
  })

  it('downloads the draft from the More menu, which also links to the case-model XML download', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(EDIT)
    await screen.findByText(/Draft revision/)
    await user.click(screen.getByRole('button', { name: 'More actions' }))
    expect(await screen.findByRole('menuitem', { name: /Case models: CMMN XML download/ })).toHaveAttribute('href', `/w/${WS}/cases`)
    await user.click(screen.getByRole('menuitem', { name: 'Download BPMN (.bpmn)' }))
    await waitFor(() => expect(downloads).toHaveLength(1))
    expect(downloads[0]!.filename).toBe(`${workflow().key}-draft-${workflow().drafts.at(-1)!.revision}.bpmn`)
    expect(downloads[0]!.text).toContain('BPMNDiagram')
    expect(downloads[0]!.text).toContain(`<process id="${workflow().drafts.at(-1)!.definition.workflowKey}"`)
  })

  it('lets a viewer (workflow.read) download the draft from the Source tab', async () => {
    signInAs('vic.viewer')
    const user = userEvent.setup()
    await renderAppReady(EDIT)
    await screen.findByText(/Draft revision/)
    await user.click(screen.getByRole('tab', { name: 'Source' }))
    await user.click(screen.getByRole('button', { name: 'BPMN' }))
    await user.click(await screen.findByRole('button', { name: 'Download BPMN (.bpmn)' }))
    await waitFor(() => expect(downloads).toHaveLength(1))
    expect(downloads[0]!.filename).toMatch(/\.bpmn$/)
  })

  it('offers a release download only with workflow.export', async () => {
    signInAs('dana.designer')
    await renderAppReady(`/w/${WS}/workflows/${PURCHASE_WORKFLOW_ID}`)
    const releases = workflow().releases
    expect(releases.length).toBeGreaterThan(0)
    const button = await screen.findByRole('button', { name: `Download BPMN (.bpmn) of version ${releases[0]!.releaseNumber}` })
    await userEvent.setup().click(button)
    await waitFor(() => expect(downloads).toHaveLength(1))
    expect(downloads[0]!.filename).toBe(`${workflow().key}-r${releases[0]!.releaseNumber}.bpmn`)
  })

  it('does not offer the release download to someone without workflow.export', async () => {
    signInAs('vic.viewer')
    await renderAppReady(`/w/${WS}/workflows/${PURCHASE_WORKFLOW_ID}`)
    await screen.findByText(/Revision \d+/)
    expect(screen.queryByRole('button', { name: /Download BPMN \(\.bpmn\) of version/ })).not.toBeInTheDocument()
  })
})
