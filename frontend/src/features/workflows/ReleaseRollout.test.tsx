import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { makeRelease } from '@/mocks/seed'
import { db, PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import { server } from '@/test/server'
import { renderAppReady, signInAs, WS } from '@/test/utils'

function twoVersions() {
  const workflow = db.workflows.get(PURCHASE_WORKFLOW_ID)!
  const original = workflow.releases[0]!
  workflow.releases.push(makeRelease(workflow.id, 2, 2, structuredClone(original.definition), original.publishedById))
  return workflow
}

describe('gradual rollout and per-version figures (PL02)', () => {
  it('lets a publisher start and stop a rollout of another version', async () => {
    signInAs('paul.publisher', { published: true, sampleRuns: false })
    const workflow = twoVersions()
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS}/workflows/${workflow.id}`)

    await user.selectOptions(await screen.findByLabelText('Version to try'), `Version 2`)
    const share = screen.getByLabelText('Share of new requests (%)')
    await user.clear(share)
    await user.type(share, '25')
    await user.click(screen.getByRole('button', { name: 'Start rollout' }))
    expect(await screen.findByTestId('canary-status')).toHaveTextContent('25% of new requests use version 2')
    expect(db.canaries.get(workflow.id)?.percent).toBe(25)

    await user.click(screen.getByRole('button', { name: 'Stop the rollout' }))
    await waitFor(() => expect(screen.queryByTestId('canary-status')).not.toBeInTheDocument())
    expect(db.canaries.has(workflow.id)).toBe(false)
    expect(await screen.findByLabelText('Version to try')).toBeInTheDocument()
  })

  it('shows read-only figures to someone who may read runs but not publish', async () => {
    signInAs('olga.operator', { published: true, sampleRuns: true })
    const workflow = twoVersions()
    db.canaries.set(workflow.id, { releaseId: workflow.releases[1]!.id, percent: 10, setAt: new Date().toISOString() })
    await renderAppReady(`/w/${WS}/workflows/${workflow.id}`)

    expect(await screen.findByTestId('canary-status')).toHaveTextContent('10% of new requests use version 2')
    expect(screen.queryByRole('button', { name: 'Stop the rollout' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Version to try')).not.toBeInTheDocument()
    const table = await screen.findByRole('table', { name: /Requests by version/ })
    const row = within(table).getByText('Version 2').closest('tr')!
    expect(within(row).getByText('Rollout')).toBeInTheDocument()
    expect(within(row).getAllByRole('cell')[1]).toHaveTextContent('0')
  })

  it('sends a requester who cannot read workflows to the permission message and never asks for rollout data', async () => {
    signInAs('rhea.requester', { published: true, sampleRuns: false })
    const workflow = twoVersions()
    const requested: string[] = []
    const listener = ({ request }: { request: Request }) => requested.push(new URL(request.url).pathname)
    server.events.on('request:start', listener)
    try {
      await renderAppReady(`/w/${WS}/workflows/${workflow.id}`)
      // The page itself cannot load for this person, so the outcome is an explicit, visible refusal rather than a missing section.
      expect(await screen.findByText("You don't have permission to view this workflow.")).toBeInTheDocument()
      expect(screen.queryByRole('heading', { name: 'Gradual rollout' })).not.toBeInTheDocument()
      expect(screen.queryByTestId('canary-status')).not.toBeInTheDocument()
      expect(requested.filter((path) => /\/(rollout|release-analytics|canary)$/.test(path))).toEqual([])
    } finally {
      server.events.removeListener('request:start', listener)
    }
  })

  it('shows a designer who cannot publish or read runs the status only, with no controls and no figures', async () => {
    signInAs('dana.designer', { published: true, sampleRuns: true })
    const workflow = twoVersions()
    db.canaries.set(workflow.id, { releaseId: workflow.releases[1]!.id, percent: 20, setAt: new Date().toISOString() })
    await renderAppReady(`/w/${WS}/workflows/${workflow.id}`)

    expect(await screen.findByRole('heading', { name: 'Gradual rollout' })).toBeInTheDocument()
    expect(await screen.findByTestId('canary-status')).toHaveTextContent('20% of new requests use version 2')
    expect(screen.queryByRole('button', { name: 'Stop the rollout' })).not.toBeInTheDocument()
    expect(screen.queryByRole('table', { name: /Requests by version/ })).not.toBeInTheDocument()
  })
})
