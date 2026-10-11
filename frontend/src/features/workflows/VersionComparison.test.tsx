import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { describe, expect, it } from 'vitest'
import { makeRelease } from '@/mocks/seed'
import { db, PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import { server } from '@/test/server'
import { renderApp, signInAs, WS } from '@/test/utils'

function prepareVersions(count = 2) {
  signInAs('dana.designer', { published: true, sampleRuns: false })
  const workflow = db.workflows.get(PURCHASE_WORKFLOW_ID)!
  const original = workflow.releases[0]!
  for (let number = 2; number <= count; number++) {
    const definition = structuredClone(original.definition)
    definition.metadata.stepNotes = { manager: `Version ${number} review rationale <b>plain text</b>` }
    workflow.releases.push(makeRelease(workflow.id, number, number, definition, original.publishedById))
  }
  return workflow
}

describe('published version comparison', () => {
  it('compares selected immutable definitions, renders annotations as text, and supports the same version', async () => {
    const workflow = prepareVersions()
    const user = userEvent.setup()
    renderApp(`/w/${WS}/workflows/${workflow.id}`)
    await user.click(await screen.findByRole('button', { name: 'Compare versions' }))
    const comparison = screen.getByRole('region', { name: 'Compare published versions' })
    expect(await within(comparison).findByText(/Version 2 review rationale <b>plain text<\/b>/)).toBeVisible()
    expect(comparison.querySelector('b')).toBeNull()
    await user.selectOptions(within(comparison).getByLabelText('From version'), workflow.releases[1]!.id)
    expect(await within(comparison).findByRole('status')).toHaveTextContent('No definition changes')
  })

  it('loads older release pages into both selectors', async () => {
    const workflow = prepareVersions(26)
    const user = userEvent.setup()
    renderApp(`/w/${WS}/workflows/${workflow.id}`)
    await user.click(await screen.findByRole('button', { name: 'Compare versions' }))
    expect(within(screen.getByLabelText('From version')).getAllByRole('option')).toHaveLength(25)
    await user.click(screen.getByRole('button', { name: 'Load more' }))
    expect(await within(screen.getByLabelText('From version')).findByRole('option', { name: 'Version 1' })).toBeVisible()
    await user.selectOptions(screen.getByLabelText('From version'), workflow.releases[0]!.id)
    expect(await screen.findByText(/Version 26 review rationale <b>plain text<\/b>/)).toBeVisible()
  })

  it('shows a failed release fetch rather than a misleading empty diff', async () => {
    const workflow = prepareVersions()
    server.use(http.get('*/api/v1/workspaces/:workspaceId/workflows/:workflowId/releases/:releaseId', () => HttpResponse.json({ code: 'NOT_FOUND', title: 'Not found', status: 404 }, { status: 404 })))
    const user = userEvent.setup()
    renderApp(`/w/${WS}/workflows/${workflow.id}`)
    await user.click(await screen.findByRole('button', { name: 'Compare versions' }))
    expect(await screen.findByText(/could not load|couldn.t load|not found/i)).toBeVisible()
    expect(screen.queryByText('No definition changes between these versions.')).not.toBeInTheDocument()
  })
})
