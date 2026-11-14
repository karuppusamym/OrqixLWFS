import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { setBulkCap } from '@/mocks/bulk'
import { bulkOperators, db, userByName } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'

/** The workflow with the most waiting requests in the seed, and their number. */
function waitingWorkflow(): { workflowId: string; count: number } {
  const counts = new Map<string, number>()
  for (const r of db.runs.values()) if (r.wsId === WS && r.lifecycle === 'waiting') counts.set(r.workflowId, (counts.get(r.workflowId) ?? 0) + 1)
  const [workflowId, count] = [...counts.entries()].sort((a, b) => b[1] - a[1])[0]!
  return { workflowId, count }
}

function grantBulk(username: string) {
  bulkOperators.add(`${WS}:${userByName(username).id}`)
}

describe('Runs page, bulk operations (S142)', () => {
  it('is offered only with the grant-only run.bulk-operate', async () => {
    signInAs('olga.operator')
    renderApp(`/w/${WS}/runs?scope=all`)
    expect(await screen.findByLabelText('Status')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Bulk action on these runs/ })).not.toBeInTheDocument()
  })

  it('needs the dry run count typed again and a reason, then shows progress with pause and cancel', async () => {
    signInAs('olga.operator')
    grantBulk('olga.operator')
    const { workflowId, count } = waitingWorkflow()
    const user = userEvent.setup()
    renderApp(`/w/${WS}/runs?workflowId=${workflowId}&lifecycle=waiting`)
    await user.click(await screen.findByRole('button', { name: /Bulk action on these runs/ }))
    const panel = screen.getByRole('region', { name: 'Bulk action' })
    await user.selectOptions(within(panel).getByLabelText('Action'), 'suspend')
    await user.click(within(panel).getByRole('button', { name: 'Dry run' }))
    expect(await within(panel).findByText(`Suspend: ${count} ${count === 1 ? 'request' : 'requests'} match`)).toBeInTheDocument()
    expect([...db.runs.values()].filter((r) => r.workflowId === workflowId && r.suspension)).toHaveLength(0)
    const start = within(panel).getByRole('button', { name: new RegExp(`^Suspend ${count} `) })
    expect(start).toBeDisabled()
    await user.type(within(panel).getByLabelText(/Type \d+ to confirm/), String(count + 1))
    await user.type(within(panel).getByLabelText(/Reason/), 'Supplier outage')
    expect(start).toBeDisabled()
    await user.clear(within(panel).getByLabelText(/Type \d+ to confirm/))
    await user.type(within(panel).getByLabelText(/Type \d+ to confirm/), String(count))
    expect(start).toBeEnabled()
    await user.click(start)
    expect(await within(panel).findByText('Running')).toBeInTheDocument()
    expect(within(panel).getByText(new RegExp(`of ${count} handled`))).toBeInTheDocument()
    await user.click(within(panel).getByRole('button', { name: 'Pause' }))
    expect(await within(panel).findByText('Paused')).toBeInTheDocument()
    await user.click(within(panel).getByRole('button', { name: 'Cancel remaining' }))
    expect(await within(panel).findByText('Cancelled')).toBeInTheDocument()
    await user.click(within(panel).getByRole('button', { name: 'Show each request' }))
    expect(await within(panel).findAllByText('Cancelled')).not.toHaveLength(0)
  })

  it('refuses to start when the dry run matched more than the cap', async () => {
    signInAs('olga.operator')
    grantBulk('olga.operator')
    setBulkCap(1)
    const { workflowId, count } = waitingWorkflow()
    const user = userEvent.setup()
    renderApp(`/w/${WS}/runs?workflowId=${workflowId}&lifecycle=waiting`)
    await user.click(await screen.findByRole('button', { name: /Bulk action on these runs/ }))
    const panel = screen.getByRole('region', { name: 'Bulk action' })
    await user.selectOptions(within(panel).getByLabelText('Action'), 'cancel')
    await user.click(within(panel).getByRole('button', { name: 'Dry run' }))
    if (count > 1) {
      expect(await within(panel).findByText(/One bulk operation changes at most 1/)).toBeInTheDocument()
      expect(within(panel).queryByRole('button', { name: /^Cancel \d+ / })).not.toBeInTheDocument()
    } else {
      expect(await within(panel).findByText(/1 request match/)).toBeInTheDocument()
    }
  })

  it('filters by searchable request fields and keeps the conditions in the URL', async () => {
    signInAs('olga.operator')
    const { workflowId } = waitingWorkflow()
    const wf = db.workflows.get(workflowId)!
    for (const release of wf.releases) {
      const props = release.definition.inputSchema.properties as unknown as Record<string, Record<string, unknown>>
      if (props.costCenter) props.costCenter.searchable = true
    }
    const user = userEvent.setup()
    const { router } = renderApp(`/w/${WS}/runs?workflowId=${workflowId}`)
    const field = await screen.findByLabelText('Request field')
    expect(within(field).getByRole('option', { name: 'Cost center' })).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Value'), 'OPS')
    await user.click(screen.getByRole('button', { name: 'Add condition' }))
    await waitFor(() => expect(new URLSearchParams(router.state.location.search).getAll('field')).toEqual(['costCenter:eq:OPS']))
    expect(screen.getByRole('list', { name: 'Field conditions' })).toHaveTextContent('Cost center is OPS')
    await user.click(screen.getByRole('button', { name: 'Remove Cost center is OPS' }))
    await waitFor(() => expect(new URLSearchParams(router.state.location.search).getAll('field')).toEqual([]))
  })

  it('does not offer field filters without run.read-data', async () => {
    signInAs('vic.viewer')
    const { workflowId } = waitingWorkflow()
    renderApp(`/w/${WS}/runs?workflowId=${workflowId}`)
    expect(await screen.findByLabelText('Status')).toBeInTheDocument()
    expect(screen.queryByLabelText('Request field')).not.toBeInTheDocument()
  })
})
