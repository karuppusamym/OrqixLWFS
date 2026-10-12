import { CalendarClock, Inbox } from 'lucide-react'
import { useEffect } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { useTask, useTaskCounters, useTasks } from '@/api/queries'
import type { TaskSummary, TaskView } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { EmptyState } from '@/components/common/EmptyState'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { QueryError } from '@/components/common/QueryError'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/input'
import { taskAge } from './taskAge'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { cn } from '@/lib/cn'
import { dueInfo, formatDay, formatDateTime } from '@/lib/dates'
import { requestReference, taskTitle } from '@/lib/runText'
import { useIsDesktop } from '@/lib/useMediaQuery'
import { TaskStage } from '@/features/runs/StageProgress'
import { TaskPanel } from './TaskPanel'

const VIEWS: Array<{ id: TaskView; label: string }> = [
  { id: 'assigned', label: 'Assigned to me' },
  { id: 'claimable', label: 'Available to claim' },
  { id: 'completed', label: 'Completed' },
]

function parseView(value: string | null): TaskView {
  return value === 'claimable' || value === 'completed' ? value : 'assigned'
}

/**
 * My tasks (docs/build/S7-frontend.md section 4): tabs with counts, a table, and a task panel.
 * Desktop shows the panel beside the list; on narrow screens a task opens on its own page (/tasks/:taskId).
 */
export function TasksPage() {
  const { workspace, me } = useWorkspace()
  const ws = workspace.id
  const { taskId } = useParams()
  const [params, setParams] = useSearchParams()
  const desktop = useIsDesktop()
  const navigate = useNavigate()
  const counters = useTaskCounters(ws, true)
  // A link straight to a task (a notification, a bookmark) has no list in it: open the list the task belongs to, so the
  // panel never shows a task that is missing from the selected tab.
  const deepLink = !!taskId && !params.has('view')
  const linked = useTask(ws, deepLink ? taskId : undefined)
  const inferred: TaskView | null = linked.data ? (linked.data.status !== 'open' ? 'completed' : linked.data.assignee?.id === me.subject.id ? 'assigned' : 'claimable') : null
  useEffect(() => {
    if (deepLink && inferred) navigate(`/w/${ws}/tasks/${taskId}?view=${inferred}`, { replace: true })
  }, [deepLink, inferred, navigate, taskId, ws])
  const view = params.has('view') ? parseView(params.get('view'))
    : taskId ? (inferred ?? 'assigned')
      : counters.data && counters.data.assigned === 0 && counters.data.claimable > 0 ? 'claimable' : 'assigned'
  const sort = params.get('sort') === 'oldest' ? 'oldest' : params.get('sort') === 'newest' ? 'newest' : 'priority'
  const minPriority = params.get('minPriority') === '50' ? 50 : 0
  const list = useTasks(ws, view, true, sort, minPriority)
  const loadedTasks: TaskSummary[] = list.data?.pages.flatMap((p) => p.items) ?? []
  const queue = params.get('queue') ?? ''
  const queueOptions = [...new Map(loadedTasks.map(task => [task.candidateGroup.key, task.candidateGroup])).values()]
  const tasks = queue ? loadedTasks.filter(task => task.candidateGroup.key === queue) : loadedTasks

  const showList = desktop || !taskId
  const showPanel = !!taskId

  const countFor = (v: TaskView): number | null => (v === 'completed' ? null : (counters.data?.[v] ?? null))
  const openTask = (id: string) => { const next = new URLSearchParams(params); next.set('view',view); navigate(`/w/${ws}/tasks/${id}?${next}`) }

  return (
    <>
      {showList ? (
        <PageHeader title="My tasks" description="Make a decision. Keep work moving." />
      ) : (
        <div className="mb-4">
          <Button asChild variant="secondary">
            <Link to={`/w/${ws}/tasks?view=${view}`}>Back to My tasks</Link>
          </Button>
        </div>
      )}
      <div className={cn('grid gap-6', desktop && showPanel ? 'lg:grid-cols-[minmax(0,1fr)_minmax(0,30rem)]' : '')}>
        {showList ? (
          <div className="min-w-0">
            <Tabs value={view} onValueChange={(v) => navigate(`/w/${ws}/tasks?view=${v}`)}>
              <TabsList aria-label="Task lists">
                {VIEWS.map((v) => (
                  <TabsTrigger key={v.id} value={v.id}>
                    {v.label}
                    {countFor(v.id) !== null ? <span className="rounded-full bg-white/80 px-2 text-xs font-bold">{countFor(v.id)}</span> : null}
                  </TabsTrigger>
                ))}
              </TabsList>
            {view !== 'completed' ? <div className="my-4 flex flex-wrap gap-4">
              <label htmlFor="task-queue-filter">Work queue <Select id="task-queue-filter" aria-label="Work queue" value={queue} onChange={e => { const next = new URLSearchParams(params); if (e.target.value) next.set('queue',e.target.value); else next.delete('queue'); next.set('view',view); setParams(next) }}>
                <option value="">All queues</option>
                {queue && !queueOptions.some(group => group.key === queue) ? <option value={queue}>{queue}</option> : null}
                {queueOptions.map(group => <option key={group.key} value={group.key}>{group.name}</option>)}
              </Select><span className="block text-xs text-muted-foreground">Filters loaded tasks; Load more includes older work.</span></label>
              <label htmlFor="task-sort">Sort tasks <Select id="task-sort" aria-label="Sort tasks" value={sort} onChange={e => { const next = new URLSearchParams(params); next.set('sort',e.target.value); next.set('view',view); setParams(next) }}>
                <option value="priority">Priority, then oldest</option><option value="oldest">Oldest first</option><option value="newest">Newest first</option>
              </Select></label>
              <label htmlFor="task-priority-filter">Priority filter <Select id="task-priority-filter" aria-label="Priority filter" value={minPriority} onChange={e => { const next = new URLSearchParams(params); next.set('minPriority',e.target.value); next.set('view',view); setParams(next) }}>
                <option value={0}>All priorities</option><option value={50}>Priority 50 and above</option>
              </Select></label>
            </div> : null}
            <TabsContent value={view} aria-label={VIEWS.find((v) => v.id === view)?.label}>
              {list.isPending ? (
                <LoadingBlock rows={4} label="Loading tasks" />
              ) : list.isError ? (
                <QueryError error={list.error} onRetry={() => void list.refetch()} what="your tasks" />
              ) : tasks.length === 0 ? (
                <EmptyState icon={Inbox} title={view === 'assigned' ? 'No tasks are assigned to you' : view === 'claimable' ? 'Nothing is available to claim' : 'You have not completed any tasks yet'}>
                  {view === 'claimable' ? 'When a request needs a decision from your group, it appears here.' : 'Check the other tabs, or come back later.'}
                  {queue && list.hasNextPage ? <Button variant="secondary" onClick={() => void list.fetchNextPage()} loading={list.isFetchingNextPage}>Load more</Button> : null}
                </EmptyState>
              ) : (
                <>
                  <TaskList tasks={tasks} ws={ws} view={view} taskId={taskId} desktop={desktop} onOpen={openTask} />
                  {list.hasNextPage ? (
                    <div className="mt-4 flex justify-center">
                      <Button variant="secondary" onClick={() => void list.fetchNextPage()} loading={list.isFetchingNextPage}>
                        Load more
                      </Button>
                    </div>
                  ) : null}
                </>
              )}
            </TabsContent>
            </Tabs>
          </div>
        ) : null}
        {showPanel ? (
          <aside aria-label="Task details" className={cn('min-w-0 overflow-hidden rounded-card border border-border bg-surface', desktop && 'sticky top-20 max-h-[calc(100dvh-6rem)] self-start overflow-y-auto')}>
            <TaskPanel key={taskId} taskId={taskId as string} onClaimed={() => setParams({ view: 'assigned' }, { replace: true })} />
          </aside>
        ) : null}
      </div>
    </>
  )
}

/** The task list: a table on desktop, cards below 1024 px so the Due and Action columns never scroll off screen. */
function TaskList({ tasks, ws, view, taskId, desktop, onOpen }: { tasks: TaskSummary[]; ws: string; view: TaskView; taskId: string | undefined; desktop: boolean; onOpen: (id: string) => void }) {
  const [params] = useSearchParams()
  const query = new URLSearchParams(params); query.set('view',view)
  const label = VIEWS.find((v) => v.id === view)?.label
  const dueText = (t: TaskSummary) => {
    const due = dueInfo(t.dueAt)
    if (t.status === 'completed' && t.decision) return <span className="text-muted-foreground">{t.decision.action === 'approve' ? 'Approved' : 'Rejected'} {formatDay(t.decision.decidedAt)}</span>
    if (due) {
      return (
        <span className={cn('inline-flex items-center gap-1', due.overdue ? 'font-semibold text-danger' : 'text-muted-foreground')}>
          <CalendarClock className="size-3.5" aria-hidden="true" /> {due.label}
        </span>
      )
    }
    return <span className="text-muted-foreground">No due date</span>
  }
  if (!desktop) {
    return (
      <ul aria-label={label} className="space-y-3">
        {tasks.map((t) => (
          <li key={t.id} className="rounded-card border border-border bg-surface p-4 shadow-card">
            <Link to={`/w/${ws}/tasks/${t.id}?${query}`} className="text-base font-semibold wrap-anywhere">
              {taskTitle(t)}
            </Link>
            <p className="text-sm text-muted-foreground tabular-nums">{requestReference(t)}</p>
            <p className="mt-2 text-sm">Priority {t.priority ?? 0} ? {t.status === 'open' ? taskAge(t.createdAt) + ' ? ' : ''}Created {formatDateTime(t.createdAt)}</p>
            <p className="mt-2 text-sm">
              {t.label} <span className="text-muted-foreground">&middot; requested by {t.requester.displayName}</span>
            </p>
            <TaskStage stage={t.stage} />
            <div className="mt-3 flex flex-wrap items-center justify-between gap-2 text-sm">
              {dueText(t)}
              <Button variant="secondary" size="sm" onClick={() => onOpen(t.id)} aria-label={`Open ${taskTitle(t)}`}>
                Open
              </Button>
            </div>
          </li>
        ))}
      </ul>
    )
  }
  return (
    <div className="overflow-hidden rounded-card border border-border bg-surface shadow-card">
      <Table>
        <caption className="sr-only">{label}</caption>
        <THead>
          <TR>
            <TH>Request</TH>
            <TH>Step</TH><TH>Priority / age</TH>
            <TH>Requested by</TH>
            <TH>{view === 'completed' ? 'Decision' : 'Due'}</TH>
            <TH>Action</TH>
          </TR>
        </THead>
        <TBody>
          {tasks.map((t) => {
            const selected = t.id === taskId
            return (
              <TR key={t.id} className={cn(selected && 'bg-neutral-soft')} aria-current={selected ? 'true' : undefined}>
                <TD>
                  <Link to={`/w/${ws}/tasks/${t.id}?${query}`} className="font-semibold wrap-anywhere">
                    {taskTitle(t)}
                  </Link>
                  <span className="block text-xs text-muted-foreground tabular-nums">{requestReference(t)}</span>
                </TD>
                <TD>{t.label}<TaskStage stage={t.stage} /></TD><TD><span>Priority {t.priority ?? 0}</span><span className="block text-xs text-muted-foreground">{t.status === 'open' ? taskAge(t.createdAt) + ' ? ' : ''}Created {formatDateTime(t.createdAt)}</span></TD>
                <TD>{t.requester.displayName}</TD>
                <TD className="whitespace-nowrap">{dueText(t)}</TD>
                <TD>
                  <Button variant="secondary" size="sm" onClick={() => onOpen(t.id)} aria-label={`Open ${taskTitle(t)}`}>
                    Open
                  </Button>
                </TD>
              </TR>
            )
          })}
        </TBody>
      </Table>
    </div>
  )
}
