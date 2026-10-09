import { ClipboardList } from 'lucide-react'
import { useListFilters } from '@/lib/useListFilters'
import { Link } from 'react-router'
import { useMyRequests } from '@/api/queries'
import type { Lifecycle } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { EmptyState } from '@/components/common/EmptyState'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { QueryError } from '@/components/common/QueryError'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/input'
import { RunsTable } from '@/features/runs/RunsTable'
import { LIFECYCLE_LABELS } from '@/lib/status'

const FILTERS: Array<Lifecycle | ''> = ['', 'waiting', 'running', 'needsAttention', 'completed', 'cancelled']

/** My requests: runs the caller started (server forces initiator = caller). */
export function MyRequestsPage() {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const { lifecycle, update } = useListFilters()
  const query = useMyRequests(ws, lifecycle ? { lifecycle } : {})
  const runs = query.data?.pages.flatMap((p) => p.items) ?? []

  return (
    <>
      <PageHeader title="My requests" description="Everything you have submitted and where it stands." />
      <div className="mb-4 flex flex-wrap items-end gap-3 rounded-card border border-border bg-surface p-4">
        <div className="space-y-1.5">
          <Label htmlFor="req-status">Show</Label>
          <Select id="req-status" value={lifecycle} onChange={(e) => update({ lifecycle: e.target.value })} className="w-52">
            {FILTERS.map((f) => (
              <option key={f || 'all'} value={f}>
                {f ? LIFECYCLE_LABELS[f] : 'All requests'}
              </option>
            ))}
          </Select>
        </div>
      </div>
      {query.isPending ? (
        <LoadingBlock rows={4} label="Loading your requests" />
      ) : query.isError ? (
        <QueryError error={query.error} onRetry={() => void query.refetch()} what="your requests" />
      ) : runs.length === 0 ? (
        <EmptyState
          icon={ClipboardList}
          title={lifecycle ? 'No requests match this filter' : 'No requests yet'}
          action={
            lifecycle ? (
              <Button variant="secondary" onClick={() => update({ lifecycle: '' })}>
                Show all requests
              </Button>
            ) : (
              <Link to={`/w/${ws}`}>Go to home to start one</Link>
            )
          }
        >
          {lifecycle ? 'Try a different status.' : 'Requests you submit appear here.'}
        </EmptyState>
      ) : (
        <>
          <RunsTable runs={runs} wsId={ws} caption="Your requests" />
          {query.hasNextPage ? (
            <div className="mt-4 flex justify-center">
              <Button variant="secondary" onClick={() => void query.fetchNextPage()} loading={query.isFetchingNextPage}>
                Load more
              </Button>
            </div>
          ) : null}
        </>
      )}
    </>
  )
}
