import { ArrowRight, ClipboardList, Cable, FileInput, Inbox, LibraryBig, Play, Workflow } from 'lucide-react'
import { Link } from 'react-router'
import { useMyRequests, useTaskCounters, useTasks, useWorkflows } from '@/api/queries'
import type { TaskSummary } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { EmptyState } from '@/components/common/EmptyState'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { QueryError } from '@/components/common/QueryError'
import { RelativeTime } from '@/components/common/RelativeTime'
import { StatusBadge } from '@/components/common/StatusBadge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { cn } from '@/lib/cn'
import { formatDay } from '@/lib/dates'
import { requestId, requestReference, runProgressText, runTitle, taskDueText, taskTitle } from '@/lib/runText'

const shortcut = 'flex items-center gap-3 rounded-card border border-border bg-surface p-4 text-foreground no-underline shadow-card hover:border-primary'

function ShortcutIcon({ icon: Icon }: { icon: typeof Inbox }) {
  return (
    <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-primary-soft text-primary-text" aria-hidden="true">
      <Icon className="size-5" />
    </span>
  )
}

/** Plain-language first steps for people who build workflows or set the workspace up; each card shows only if they may use it. */
function GetStarted({ ws }: { ws: string }) {
  const { can } = useWorkspace()
  const cards = [
    { show: can('workflow.edit'), to: `/w/${ws}/workflows`, icon: Workflow, title: 'Build a workflow', text: 'Describe it to the AI or start from a template, then add steps on the canvas.' },
    { show: can('workflow.edit'), to: `/w/${ws}/workflows?q=Sample`, icon: LibraryBig, title: 'Look at an example', text: 'Open a ready-made sample (approvals, waits, cases, rules) and change it.' },
    { show: can('workflow.edit'), to: `/w/${ws}/migrations`, icon: FileInput, title: 'Bring in a Camunda or Pega file', text: 'Import it, review what was understood, and fix what needs a decision.' },
    { show: can('connection.read'), to: `/w/${ws}/settings`, icon: Cable, title: 'Connect other systems', text: 'Connections, webhooks, Kafka, shared rules and documents.' },
  ].filter((c) => c.show)
  if (cards.length === 0) return null
  return (
    <section aria-labelledby="get-started-heading" className="mb-8" data-testid="get-started">
      <h2 id="get-started-heading" className="mb-1">Get started</h2>
      <p className="mb-3 text-sm text-muted-foreground">New here? These are the usual first steps. Press Ctrl+K anywhere to jump to a page or ask the Assistant.</p>
      <ul className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        {cards.map((c) => (
          <li key={c.title}>
            <Link to={c.to} className={cn(shortcut, 'h-full items-start')}>
              <ShortcutIcon icon={c.icon} />
              <span><span className="block font-semibold">{c.title}</span><span className="text-sm text-muted-foreground">{c.text}</span></span>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  )
}

/** Home: Start a request, Waiting for you, Your recent requests (docs/build/S7-frontend.md section 4). */
export function HomePage() {
  const { workspace, can, me } = useWorkspace()
  const ws = workspace.id
  const canStart = can('workflow.start', 'workflow.read')
  const canAct = can('task.act')

  const startable = useWorkflows(ws, { startable: true }, canStart)
  const counters = useTaskCounters(ws, canAct)
  const assigned = useTasks(ws, 'assigned', canAct)
  const claimable = useTasks(ws, 'claimable', canAct)
  const requests = useMyRequests(ws, {}, 5)

  const workflows = startable.data?.pages.flatMap((p) => p.items) ?? []
  const tasks: TaskSummary[] = [...(assigned.data?.pages[0]?.items ?? []), ...(claimable.data?.pages[0]?.items ?? [])].slice(0, 5)
  const recent = requests.data?.pages[0]?.items.slice(0, 5) ?? []
  const firstName = workflows[0]?.name

  return (
    <>
      <PageHeader title={`Welcome, ${me.subject.displayName.split(' ')[0]}`} description="Start a request, make a decision, and keep work moving." />

      <section aria-label="Workspace shortcuts" className={canAct ? 'mb-8 grid gap-3 sm:grid-cols-3' : 'mb-8 grid gap-3'}>
        {canAct ? <>
          <Link to={`/w/${ws}/tasks?view=assigned`} className={shortcut}>
            <ShortcutIcon icon={Inbox} />
            <span><span className="block text-2xl leading-none font-bold">{counters.data?.assigned ?? '—'}</span><span className="text-sm text-muted-foreground">Assigned to you</span></span>
          </Link>
          <Link to={`/w/${ws}/tasks?view=claimable`} className={shortcut}>
            <ShortcutIcon icon={Play} />
            <span><span className="block text-2xl leading-none font-bold">{counters.data?.claimable ?? '—'}</span><span className="text-sm text-muted-foreground">Available to claim</span></span>
          </Link>
        </> : null}
        <Link to={`/w/${ws}/requests`} className={cn(shortcut, 'justify-between')}>
          <span className="flex items-center gap-3"><ShortcutIcon icon={ClipboardList} /><span><span className="block font-semibold">Track your requests</span><span className="text-sm text-muted-foreground">Progress, decisions and outcomes</span></span></span>
          <ArrowRight className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
        </Link>
      </section>

      <GetStarted ws={ws} />

      {canStart && (can('workflow.start') || workflows.length > 0) ? (
        <section aria-labelledby="start-heading" className="mb-8">
          <h2 id="start-heading" className="mb-3">
            Start a request
          </h2>
          {startable.isPending ? (
            <LoadingBlock rows={1} label="Loading workflows" />
          ) : startable.isError && !startable.data ? (
            <QueryError error={startable.error} onRetry={() => void startable.refetch()} what="the list of workflows" />
          ) : workflows.length === 0 ? (
            <EmptyState icon={Play} title="Nothing to start yet">
              No published workflows are available to you. When a workflow is published, it shows up here.
            </EmptyState>
          ) : (
            <ul className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
              {workflows.map((w) => (
                <li key={w.id}>
                  <Card className="flex h-full flex-col">
                    <CardHeader>
                      <h3>{w.name}</h3>
                    </CardHeader>
                    <CardContent className="flex flex-1 flex-col justify-between gap-4">
                      <p className="text-sm text-muted-foreground">{w.description || 'Start a new request.'}</p>
                      <div className="flex items-center justify-between gap-2">
                        {w.activeRelease ? <span className="text-xs text-muted-foreground">Version {w.activeRelease.releaseNumber}</span> : <span />}
                        <Button asChild variant="primary" size="lg" className="min-w-28">
                          <Link to={`/w/${ws}/start/${w.id}`} aria-label={`Start ${w.name}`}>
                            Start
                          </Link>
                        </Button>
                      </div>
                    </CardContent>
                  </Card>
                </li>
              ))}
            </ul>
          )}
          {startable.hasNextPage ? <div className="mt-4"><Button onClick={() => void startable.fetchNextPage()} loading={startable.isFetchingNextPage}>Load more workflows</Button></div> : null}
          {startable.isFetchNextPageError ? <QueryError error={startable.error} onRetry={() => void startable.fetchNextPage()} what="more workflows" /> : null}
        </section>
      ) : null}

      {canAct ? (
        <section aria-labelledby="waiting-heading" className="mb-8">
          <div className="mb-3 flex items-baseline justify-between gap-3">
            <h2 id="waiting-heading">Waiting for you</h2>
            <Link to={`/w/${ws}/tasks`} className="inline-flex items-center gap-1 text-sm font-semibold">
              Open My tasks <ArrowRight className="size-4" aria-hidden="true" />
            </Link>
          </div>
          {assigned.isPending || claimable.isPending ? (
            <LoadingBlock rows={2} label="Loading your tasks" />
          ) : assigned.isError || claimable.isError ? (
            <QueryError error={assigned.error ?? claimable.error} onRetry={() => { void assigned.refetch(); void claimable.refetch() }} what="your tasks" />
          ) : tasks.length === 0 ? (
            <EmptyState icon={Inbox} title="Nothing is waiting for you">
              New approval work shows up here and in My tasks.
            </EmptyState>
          ) : (
            <ul className="divide-y divide-border rounded-card border border-border bg-surface">
              {tasks.map((t) => {
                const due = taskDueText(t)
                return (
                  <li key={t.id}>
                    <Link to={`/w/${ws}/tasks/${t.id}?view=${t.assignee ? 'assigned' : 'claimable'}`} className="flex flex-wrap items-center justify-between gap-2 px-4 py-3 text-foreground no-underline hover:bg-surface-muted">
                      <span>
                        <span className="block font-semibold">{taskTitle(t)}</span>
                        <span className="block text-sm text-muted-foreground">
                          {requestReference(t)} &middot; {t.label} &middot; requested by {t.requester.displayName}
                          {t.assignee ? '' : ' · available to claim'}
                        </span>
                      </span>
                      {due ? <span className={due.overdue ? 'text-sm font-semibold text-danger' : 'text-sm text-muted-foreground'}>{due.label}</span> : null}
                    </Link>
                  </li>
                )
              })}
            </ul>
          )}
        </section>
      ) : null}

      <section aria-labelledby="recent-heading">
        <div className="mb-3 flex items-baseline justify-between gap-3">
          <h2 id="recent-heading">Your recent requests</h2>
          <Link to={`/w/${ws}/requests`} className="inline-flex items-center gap-1 text-sm font-semibold">
            All my requests <ArrowRight className="size-4" aria-hidden="true" />
          </Link>
        </div>
        {requests.isPending ? (
          <LoadingBlock rows={2} label="Loading your requests" />
        ) : requests.isError ? (
          <QueryError error={requests.error} onRetry={() => void requests.refetch()} what="your requests" />
        ) : recent.length === 0 ? (
          <EmptyState icon={Play} title={firstName ? `No requests yet. Start with ${firstName}.` : 'No requests yet.'}>
            {firstName ? 'Pick a workflow above to submit your first request.' : 'Requests you submit appear here.'}
          </EmptyState>
        ) : (
          <ul className="divide-y divide-border rounded-card border border-border bg-surface">
            {recent.map((r) => (
              <li key={r.id}>
                <Link to={`/w/${ws}/runs/${r.id}`} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3 text-foreground no-underline hover:bg-surface-muted">
                  <span className="min-w-0">
                    <span className="block font-semibold">
                      <span className="wrap-anywhere">{runTitle(r) ?? r.workflow.name}</span> <span className="font-normal text-muted-foreground tabular-nums">&middot; {requestId(r)}</span>
                    </span>
                    <span className="block text-sm text-muted-foreground">
                      {runProgressText(r)} &middot; submitted <RelativeTime value={r.createdAt} /> ({formatDay(r.createdAt)})
                    </span>
                  </span>
                  <StatusBadge lifecycle={r.lifecycle} outcome={r.outcome} outcomeKind={r.outcomeKind} />
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  )
}
