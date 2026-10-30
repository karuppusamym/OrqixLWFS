import { SavedWorkViews } from '@/components/common/SavedWorkViews'
import { Activity, AlertTriangle, Check, Clock } from 'lucide-react'
import { useListFilters } from '@/lib/useListFilters'
import { useRuns, useWorkflows } from '@/api/queries'
import type { Lifecycle, OutcomeKind } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { EmptyState } from '@/components/common/EmptyState'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { QueryError } from '@/components/common/QueryError'
import { Button } from '@/components/ui/button'
import { Input, Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { LIFECYCLE_LABELS } from '@/lib/status'
import { ConsistencyScanPanel } from './ConsistencyScanPanel'
import { RunsTable } from './RunsTable'

const FILTERS: Array<Lifecycle | ''> = ['', 'waiting', 'running', 'completed', 'cancelled']

/** Runs for operators and viewers, with the "Needs attention" filter (docs/build/S7-frontend.md section 2). */
export function RunsPage() {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const { params, update, lifecycle: selectedLifecycle } = useListFilters()
  const lifecycle = selectedLifecycle || (can('run.operate') && params.size === 0 ? 'needsAttention' : '')
  const workflowId = params.get('workflowId') ?? ''
  const appliedKey = params.get('businessKey') ?? ''
  const rawOutcome = params.get('outcomeKind') ?? ''
  const outcomeKind: OutcomeKind | '' = rawOutcome === 'success' || rawOutcome === 'rejected' || rawOutcome === 'failed' ? rawOutcome : ''
  const clearFilters = () => {
    update({ scope: 'all', lifecycle: '', workflowId: '', businessKey: '', overdue: '', outcomeKind: '' })
  }
  const overdueOnly = params.get('overdue') === 'true'
  const attentionOnly = lifecycle === 'needsAttention'

  const workflows = useWorkflows(ws, {})
  const workflowItems = workflows.data?.pages.flatMap((p) => p.items) ?? []
  const query = useRuns(ws, {
    ...(lifecycle ? { lifecycle } : {}),
    ...(workflowId ? { workflowId } : {}),
    ...(overdueOnly ? { overdue: true } : {}),
    ...(appliedKey ? { businessKey: appliedKey } : {}),
    ...(outcomeKind ? { outcomeKind } : {}),
  })
  const runs = query.data?.pages.flatMap((p) => p.items) ?? []

  return (
    <>
      <PageHeader title="Runs" description="All requests in this workspace. Technical details are available on each run." />
      <SavedWorkViews kind="runs" effectiveFilters={{lifecycle,workflowId,businessKey:appliedKey,outcomeKind,overdue:overdueOnly ? 'true' : ''}} />
      <div className="mb-3 flex gap-2"><Button variant={attentionOnly ? 'primary' : 'secondary'} onClick={() => update({ scope:'attention', lifecycle:'needsAttention', overdue:'' })}>Exception workbench</Button><Button variant={!lifecycle && !overdueOnly ? 'primary' : 'secondary'} onClick={clearFilters}>All runs</Button></div>
      <ConsistencyScanPanel />
      <div className="mb-4 flex flex-wrap items-end gap-4 rounded-card border border-border bg-surface p-4">
        <Button
          variant="secondary"
          // Pressed is shown as a toggle (tinted, red outline, check mark), not as a second primary action next to "Review recovery".
          className={attentionOnly ? 'border-2 border-primary bg-primary-soft text-primary-text hover:bg-primary-soft' : undefined}
          aria-pressed={attentionOnly}
          onClick={() => update({ lifecycle: attentionOnly ? '' : 'needsAttention' })}
        >
          {attentionOnly ? <Check aria-hidden="true" /> : <AlertTriangle aria-hidden="true" />} Needs attention
        </Button>
        <Button
          variant="secondary"
          className={overdueOnly ? 'border-2 border-primary bg-primary-soft text-primary-text hover:bg-primary-soft' : undefined}
          aria-pressed={overdueOnly}
          onClick={() => update({ overdue: overdueOnly ? '' : 'true' })}
        >
          {overdueOnly ? <Check aria-hidden="true" /> : <Clock aria-hidden="true" />} Overdue
        </Button>
        <div className="space-y-1.5">
          <Label htmlFor="runs-status">Status</Label>
          <Select id="runs-status" value={attentionOnly ? '' : lifecycle} onChange={(e) => update({ lifecycle: e.target.value })} className="w-44" disabled={attentionOnly}>
            {FILTERS.map((f) => (
              <option key={f || 'all'} value={f}>
                {f ? LIFECYCLE_LABELS[f] : 'All statuses'}
              </option>
            ))}
          </Select>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="runs-outcome">Business outcome kind</Label>
          <Select id="runs-outcome" value={outcomeKind} onChange={event => update({outcomeKind: event.target.value})}>
            <option value="">All outcome kinds</option><option value="success">Successful</option><option value="rejected">Rejected</option><option value="failed">Failed</option>
          </Select>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="runs-workflow">Workflow</Label>
          <Select id="runs-workflow" value={workflowId} onChange={(e) => update({ workflowId: e.target.value })} className="w-56">
            <option value="">All workflows</option>
            {workflowId && !workflowItems.some((w) => w.id === workflowId) ? <option value={workflowId}>Selected workflow</option> : null}
            {workflowItems.map((w) => (
              <option key={w.id} value={w.id}>
                {w.name}
              </option>
            ))}
          </Select>
          {workflows.hasNextPage ? <Button variant="link" size="sm" loading={workflows.isFetchingNextPage} onClick={() => void workflows.fetchNextPage()}>Load more workflow choices</Button> : null}
        </div>
        <form
          className="flex flex-wrap items-end gap-2"
          role="search"
          aria-label="Find runs by request reference"
          onSubmit={(event) => {
            event.preventDefault()
            const value = new FormData(event.currentTarget).get('businessKey')
            update({ businessKey: typeof value === 'string' ? value.trim() : '' })
          }}
        >
          <div className="space-y-1.5">
            <Label htmlFor="runs-key">Request reference</Label>
            <Input id="runs-key" key={`${ws}:${appliedKey}`} name="businessKey" defaultValue={appliedKey} className="w-52 max-w-full" placeholder="e.g. ORQ-1003" maxLength={100} />
          </div>
          <Button type="submit" variant="secondary">
            Search
          </Button>
        </form>
        {lifecycle || workflowId || appliedKey || overdueOnly || outcomeKind ? <Button variant="ghost" onClick={clearFilters}>Clear filters</Button> : null}
      </div>
      {query.isFetching && !query.isPending ? <p role="status" className="mb-3 text-sm text-muted-foreground">Updating runs…</p> : null}
      {query.isPending ? (
        <LoadingBlock rows={5} label="Loading runs" />
      ) : query.isError ? (
        <QueryError error={query.error} onRetry={() => void query.refetch()} what="runs" />
      ) : runs.length === 0 ? (
        <EmptyState icon={Activity} title={attentionOnly ? 'Nothing needs attention' : overdueOnly ? 'Nothing is overdue' : 'No runs match these filters'} action={lifecycle || workflowId || appliedKey || overdueOnly || outcomeKind ? <Button onClick={clearFilters}>Show all runs</Button> : undefined}>
          {attentionOnly ? 'When a request is stuck on another system, it shows up here with the next step.' : 'Try clearing a filter.'}
        </EmptyState>
      ) : (
        <>
          <RunsTable runs={runs} wsId={ws} showRequester caption="Runs in this workspace" canOperate={can('run.operate')} />
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
