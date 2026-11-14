import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { approveMockCommit, seedGitConnection, seedReleaseBundle } from '@/mocks/gitBundles'
import { db, PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import { renderApp, renderAppReady, signInAs, WS } from '@/test/utils'

describe('Git sync and release bundles (S147)', () => {
  it('lets a connection manager connect a repository by naming the token secret', async () => {
    signInAs('wade.admin')
    const user = userEvent.setup()
    renderApp(`/w/${WS}/settings`)
    await user.click(await screen.findByRole('tab', { name: /Connections/ }))
    expect(await screen.findByRole('heading', { name: 'Git repository' })).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Provider'), 'gitlab')
    await user.type(screen.getByLabelText('Repository'), 'acme/platform')
    await user.type(screen.getByLabelText('Token secret reference'), 'vault:kv/git#token')
    await user.click(screen.getByRole('button', { name: 'Connect repository' }))
    expect(await screen.findByTestId('git-connection-message')).toHaveTextContent('Saved.')
    expect(await screen.findByRole('button', { name: 'Test Git connection' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Test Git connection' }))
    expect(await screen.findByTestId('git-connection-message')).toHaveTextContent('Connected. Default branch: main.')
  })

  it('shows other members which repository is connected without the secret reference', async () => {
    signInAs('dana.designer')
    seedGitConnection(WS, { repository: 'acme/workflows', credentialRef: 'very-private-ref' })
    const user = userEvent.setup()
    renderApp(`/w/${WS}/settings`)
    await user.click(await screen.findByRole('tab', { name: /Connections/ }))
    const summary = await screen.findByTestId('git-connection-summary')
    expect(summary).toHaveTextContent('acme/workflows')
    expect(summary).not.toHaveTextContent('very-private-ref')
    expect(screen.queryByLabelText('Token secret reference')).not.toBeInTheDocument()
  })

  it('pushes the draft for review and imports a reviewed branch as a new draft', async () => {
    signInAs('dana.designer', { published: true, sampleRuns: false })
    seedGitConnection(WS)
    const workflow = db.workflows.get(PURCHASE_WORKFLOW_ID)!
    const before = workflow.drafts.length
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS}/workflows/${PURCHASE_WORKFLOW_ID}`)

    await user.click(await screen.findByRole('button', { name: /Push draft revision \d+ to Git/ }))
    expect(await screen.findByTestId('git-push-result')).toHaveTextContent(`on orqix/${workflow.key}`)
    expect(screen.getByRole('link', { name: 'Pull request 1' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Publish from reviewed commit' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Check branch' }))
    const preview = await screen.findByTestId('git-import-preview')
    expect(within(preview).getByRole('table', { name: 'Differences from the draft' })).toHaveTextContent('Reviewed in Git')
    await user.click(within(preview).getByRole('button', { name: 'Import as a new draft' }))
    expect(await screen.findByText(`Imported as draft revision ${before + 1}.`)).toBeInTheDocument()
    expect(workflow.drafts).toHaveLength(before + 1)
    expect(await screen.findByRole('table', { name: 'Git history' })).toHaveTextContent('Imported')
  })

  it('publishes only from an approved commit and can publish it as a bundle', async () => {
    signInAs('paul.publisher', { published: true, sampleRuns: false })
    seedGitConnection(WS)
    const workflow = db.workflows.get(PURCHASE_WORKFLOW_ID)!
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS}/workflows/${PURCHASE_WORKFLOW_ID}`)

    await user.click(await screen.findByRole('button', { name: /Push draft revision \d+ to Git/ }))
    await screen.findByTestId('git-push-result')
    const commit = (screen.getByLabelText('Reviewed commit') as HTMLInputElement).value
    expect(commit).toMatch(/^[0-9a-f]{40}$/)
    expect(screen.getByLabelText('Pull request')).toHaveValue('1')

    await user.click(screen.getByRole('button', { name: 'Publish from reviewed commit' }))
    expect(await screen.findByText(/not been approved/)).toBeInTheDocument()

    approveMockCommit(commit)
    const releases = workflow.releases.length
    await user.click(screen.getByRole('checkbox', { name: 'Publish as a bundle' }))
    await user.click(screen.getByRole('button', { name: 'Publish from reviewed commit' }))
    expect(await screen.findByText(/approved by rita-reviewer\. Published as a bundle\./)).toBeInTheDocument()
    expect(workflow.releases).toHaveLength(releases + 1)
    expect(await screen.findAllByTestId('release-bundle')).toHaveLength(1)
  })

  it('plans and promotes a bundle to another workspace as one unit', async () => {
    signInAs('dana.designer', { published: true, sampleRuns: false })
    seedReleaseBundle(PURCHASE_WORKFLOW_ID)
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS}/workflows/${PURCHASE_WORKFLOW_ID}`)

    const card = await screen.findByTestId('release-bundle')
    expect(within(card).getByRole('table', { name: /Components of the version \d+ bundle/ })).toHaveTextContent('procurement')
    expect(screen.queryByRole('button', { name: /as a bundle$/ })).not.toBeInTheDocument()
    await user.click(within(card).getByRole('button', { name: 'Promote…' }))
    await user.selectOptions(within(card).getByLabelText('Promote to'), 'Purchasing sandbox')
    await user.click(within(card).getByRole('button', { name: 'Check' }))
    expect(await within(card).findByRole('table', { name: 'Promotion plan' })).toHaveTextContent('Create')
    await user.click(within(card).getByRole('button', { name: 'Promote bundle' }))
    await waitFor(() => expect(within(card).getByText(/Saved as draft revision 1 in Purchasing sandbox/)).toBeInTheDocument())
  })
})
