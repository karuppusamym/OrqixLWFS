import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { makeRelease } from '@/mocks/seed'
import { db, PURCHASE_WORKFLOW_ID } from '@/mocks/state'
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

  it('hides the whole section from someone who can neither read workflows nor runs', async () => {
    signInAs('rhea.requester', { published: true, sampleRuns: false })
    const workflow = twoVersions()
    await renderAppReady(`/w/${WS}/workflows/${workflow.id}`).catch(() => undefined)
    expect(screen.queryByRole('heading', { name: 'Gradual rollout' })).not.toBeInTheDocument()
  })
})
