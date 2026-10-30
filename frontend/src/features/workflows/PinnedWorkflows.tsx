import { useQuery } from '@tanstack/react-query'
import { Pin, PinOff } from 'lucide-react'
import { Link } from 'react-router'
import { getWorkflow } from '@/api/endpoints'
import { useWorkspace } from '@/app/workspace'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { PIN_LIMIT, useWorkflowPins } from './pins'

export function PinWorkflowButton({ id, name }: { id: string; name: string }) {
  const { ids, toggle, error } = useWorkflowPins()
  const pinned = ids.includes(id)
  return <div>
    <Button variant="ghost" size="sm" aria-pressed={pinned} aria-label={`${pinned ? 'Unpin' : 'Pin'} ${name}`}
      onClick={() => toggle(id)}>
      {pinned ? <PinOff aria-hidden="true" /> : <Pin aria-hidden="true" />}{pinned ? 'Unpin' : 'Pin'}
    </Button>
    {error ? <p role="alert" className="max-w-xs text-sm text-danger">{error}</p> : null}
  </div>
}

/** Fetch pins independently of the catalog's search and pagination, using normal server authorization. */
export function PinnedWorkflows() {
  const { can } = useWorkspace()
  if (!can('workflow.read')) return null
  return <PinnedList />
}

function PinnedList() {
  const { ids } = useWorkflowPins()
  if (ids.length === 0) return <p className="mb-4 text-sm text-muted-foreground">Use Pin beside a workflow to keep it here.</p>
  return <section aria-labelledby="pinned-workflows-heading" className="mb-6 rounded-card border border-border bg-surface p-4">
    <h2 id="pinned-workflows-heading" className="text-base">Pinned workflows</h2>
    <p className="mt-1 text-sm text-muted-foreground">Your shortcuts in this browser, for this account and workspace. Up to {PIN_LIMIT} pins; they stay here while you search the catalog.</p>
    {ids.length === 0 ? <p className="mt-3 text-sm text-muted-foreground">Use Pin beside a workflow to keep it here.</p>
      : <ul className="mt-3 grid gap-3 sm:grid-cols-2 xl:grid-cols-3">{ids.map(id => <PinnedItem key={id} id={id} />)}</ul>}
  </section>
}

function PinnedItem({ id }: { id: string }) {
  const { workspace, me } = useWorkspace()
  // Separate cache from the catalog/overview: never render a cached label after a failed authorization refresh.
  const query = useQuery({ queryKey: ['ws', workspace.id, 'pinned-workflow', me.subject.id, id],
    queryFn: () => getWorkflow(workspace.id, id), staleTime: 0, gcTime: 0, retry: false })
  if (query.isPending || query.isFetching) return <li className="rounded-control border border-border p-3"><span role="status">Loading pinned workflow…</span></li>
  if (query.isError) return <li className="rounded-control border border-border p-3">
    <Alert tone="info" live="off">This pinned workflow is unavailable. It may have been removed, access may have changed, or it could not be loaded.</Alert>
    <PinWorkflowButton id={id} name="unavailable workflow" />
    <Button size="sm" variant="secondary" onClick={() => void query.refetch()}>Try again</Button>
  </li>
  return <li className="flex min-w-0 items-start justify-between gap-2 rounded-control border border-border p-3">
    <Link className="min-w-0 wrap-anywhere font-semibold underline" to={`/w/${workspace.id}/workflows/${id}`}>{query.data.name}</Link>
    <PinWorkflowButton id={id} name={query.data.name} />
  </li>
}
