import {
  keepPreviousData,
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from '@tanstack/react-query'
import { useEffect, useRef } from 'react'
import { isTerminal } from '@/lib/status'
import * as endpoints from './endpoints'
import type { RunListQuery } from './endpoints'
import type { EventPage, Lifecycle, RunEvent, TaskView } from './types'

/** Query keys follow docs/build/S7-frontend.md section 3: ['ws', ws, <resource>, ...]. */
export const keys = {
  me: ['me'] as const,
  workflows: (ws: string, params?: unknown) => ['ws', ws, 'workflows', params ?? {}] as const,
  workflow: (ws: string, id: string) => ['ws', ws, 'workflow', id] as const,
  releases: (ws: string, id: string) => ['ws', ws, 'workflow', id, 'releases'] as const,
  release: (ws: string, id: string, releaseId: string) => ['ws', ws, 'workflow', id, 'release', releaseId] as const,
  draft: (ws: string, id: string) => ['ws', ws, 'workflow', id, 'draft'] as const,
  startForm: (ws: string, id: string) => ['ws', ws, 'workflow', id, 'start-form'] as const,
  templates: (ws: string) => ['ws', ws, 'templates'] as const,
  groups: (ws: string) => ['ws', ws, 'groups'] as const,
  connections: (ws: string) => ['ws', ws, 'connections'] as const,
  connection: (ws: string, key: string) => ['ws', ws, 'connection', key] as const,
  access: (ws: string) => ['ws', ws, 'access'] as const,
  tasks: (ws: string, view?: TaskView) => (view ? (['ws', ws, 'tasks', view] as const) : (['ws', ws, 'tasks'] as const)),
  task: (ws: string, id: string) => ['ws', ws, 'task', id] as const,
  runs: (ws: string, filter?: unknown) => ['ws', ws, 'runs', filter ?? {}] as const,
  myRequests: (ws: string, filter?: unknown) => ['ws', ws, 'requests', filter ?? {}] as const,
  run: (ws: string, id: string) => ['ws', ws, 'run', id] as const,
  runEvents: (ws: string, id: string) => ['ws', ws, 'run', id, 'events'] as const,
  runMigrationOptions: (ws: string, id: string) => ['ws', ws, 'run', id, 'migration-options'] as const,
  notifications: (ws: string) => ['ws', ws, 'notifications'] as const,
}

/** Polling intervals from docs/build/S3-api.md section 9. TanStack pauses intervals in background tabs by default. */
export const POLL = {
  runningEventsMs: 3_000,
  waitingEventsMs: 15_000,
  taskCountersMs: 30_000,
  notificationsMs: 60_000,
} as const

export function eventsPollInterval(lifecycle: Lifecycle): number | false {
  if (isTerminal(lifecycle)) return false
  return lifecycle === 'running' ? POLL.runningEventsMs : POLL.waitingEventsMs
}

// Identity ---------------------------------------------------------------------------------------

export function useMe() {
  return useQuery({ queryKey: keys.me, queryFn: endpoints.getMe, staleTime: 60_000 })
}

// Workflows --------------------------------------------------------------------------------------

export function useWorkflowOrganization(ws: string, enabled = true) {
  return useQuery({ queryKey: ['ws', ws, 'workflow-organization'] as const, queryFn: () => endpoints.getWorkflowOrganization(ws), enabled })
}

export function useWorkflowOwnerCandidates(ws: string, enabled = true) {
  return useQuery({ queryKey: ['ws', ws, 'workflow-owner-candidates'] as const, queryFn: () => endpoints.listWorkflowOwnerCandidates(ws), enabled, staleTime: 0 })
}

export function useWorkflows(ws: string, params: { q?: string; startable?: boolean; folder?: string; owner?: string } = {}, enabled = true) {
  return useInfiniteQuery({
    queryKey: keys.workflows(ws, params),
    queryFn: ({ pageParam }) => endpoints.listWorkflows(ws, { ...params, cursor: pageParam ?? undefined, limit: 25 }),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled,
  })
}

export function useWorkflow(ws: string, id: string) {
  return useQuery({ queryKey: keys.workflow(ws, id), queryFn: () => endpoints.getWorkflow(ws, id) })
}

export function useReleases(ws: string, id: string, enabled = true) {
  return useInfiniteQuery({
    queryKey: keys.releases(ws, id),
    queryFn: ({ pageParam }) => endpoints.listReleases(ws, id, { cursor: pageParam ?? undefined, limit: 25 }),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled,
  })
}

export function useRelease(ws: string, id: string, releaseId: string | null | undefined) {
  return useQuery({
    queryKey: keys.release(ws, id, releaseId ?? 'none'),
    queryFn: () => endpoints.getRelease(ws, id, releaseId as string),
    enabled: !!releaseId,
    staleTime: Infinity, // releases are immutable
  })
}

export function useTemplates(ws: string, enabled = true) {
  return useQuery({ queryKey: keys.templates(ws), queryFn: () => endpoints.listTemplates(ws), enabled })
}

export function useGroups(ws: string, enabled = true) {
  return useQuery({ queryKey: keys.groups(ws), queryFn: () => endpoints.listGroups(ws), enabled, staleTime: 60_000 })
}

export function useConnections(ws: string, enabled = true) {
  return useQuery({ queryKey: keys.connections(ws), queryFn: () => endpoints.listConnections(ws), enabled, staleTime: 30_000 })
}

export function useConnection(ws: string, key: string | null | undefined, enabled = true) {
  return useQuery({
    queryKey: keys.connection(ws, key ?? 'none'),
    queryFn: () => endpoints.getConnection(ws, key as string),
    enabled: enabled && !!key,
    staleTime: 30_000,
  })
}

export function useAccessOverview(ws: string, enabled = true) {
  return useQuery({ queryKey: keys.access(ws), queryFn: () => endpoints.getAccessOverview(ws), enabled })
}

// Start form -------------------------------------------------------------------------------------

export function useStartForm(ws: string, workflowId: string) {
  return useQuery({
    queryKey: keys.startForm(ws, workflowId),
    queryFn: () => endpoints.getStartForm(ws, workflowId),
    staleTime: 0,
    retry: false,
  })
}

// Tasks ------------------------------------------------------------------------------------------

export function useTasks(ws: string, view: TaskView, enabled = true, sort: "priority" | "oldest" | "newest" = "priority", minPriority = 0) {
  return useInfiniteQuery({
    queryKey: [...keys.tasks(ws, view), sort, minPriority],
    queryFn: ({ pageParam }) => endpoints.listTasks(ws, view, { cursor: pageParam ?? undefined, limit: 25, ...(view === "completed" ? {} : { sort, minPriority }) }),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled,
    refetchInterval: POLL.taskCountersMs,
    refetchOnWindowFocus: true,
  })
}

/** Counters only (first page of "assigned"); used by navigation and tab labels. */
export function useTaskCounters(ws: string, enabled: boolean) {
  return useQuery({
    queryKey: [...keys.tasks(ws), 'counters'] as const,
    queryFn: () => endpoints.listTasks(ws, 'assigned', { limit: 1 }),
    enabled,
    refetchInterval: POLL.taskCountersMs,
    refetchOnWindowFocus: true,
    select: (page) => page.counts,
  })
}

export function useTask(ws: string, taskId: string | undefined) {
  return useQuery({
    queryKey: keys.task(ws, taskId ?? 'none'),
    queryFn: () => endpoints.getTask(ws, taskId as string),
    enabled: !!taskId,
    retry: false,
  })
}

export function invalidateTasks(client: QueryClient, ws: string) {
  return client.invalidateQueries({ queryKey: keys.tasks(ws) })
}

// Runs -------------------------------------------------------------------------------------------

export function useRuns(ws: string, filter: RunListQuery, enabled = true) {
  return useInfiniteQuery({
    queryKey: keys.runs(ws, filter),
    queryFn: ({ pageParam }) => endpoints.listRuns(ws, { ...filter, cursor: pageParam ?? undefined, limit: 25 }),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled,
    placeholderData: keepPreviousData,
    refetchInterval: 30_000,
  })
}

export function useMyRequests(ws: string, filter: { lifecycle?: Lifecycle } = {}, limit = 25) {
  return useInfiniteQuery({
    queryKey: keys.myRequests(ws, { ...filter, limit }),
    queryFn: ({ pageParam }) => endpoints.listMyRequests(ws, { ...filter, cursor: pageParam ?? undefined, limit }),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    placeholderData: keepPreviousData,
  })
}

export function useRun(ws: string, runId: string) {
  return useQuery({ queryKey: keys.run(ws, runId), queryFn: () => endpoints.getRun(ws, runId), retry: false })
}

export function useRunMigrationOptions(ws: string, runId: string, enabled: boolean) {
  return useQuery({ queryKey: keys.runMigrationOptions(ws, runId), queryFn: () => endpoints.getRunMigrationOptions(ws, runId), enabled, retry: false })
}

export interface RunEventsState {
  events: RunEvent[]
  nextAfter: number
  lifecycle: Lifecycle
}

/**
 * Timeline polling with `?after=<seq>` (S3 sections 7 and 9). Events accumulate in the query cache;
 * each poll asks only for newer events and keeps paging while `hasMore`. Polling stops for terminal runs.
 * When new events arrive, or the lifecycle changed, the run detail is refetched so status and steps stay current.
 */
export function useRunEvents(ws: string, runId: string, lifecycle: Lifecycle | undefined) {
  const client = useQueryClient()
  const key = keys.runEvents(ws, runId)
  const known = useRef<Lifecycle | undefined>(lifecycle)

  const query = useQuery({
    queryKey: key,
    queryFn: async (): Promise<RunEventsState> => {
      const previous = client.getQueryData<RunEventsState>(key)
      const events = [...(previous?.events ?? [])]
      let after = previous?.nextAfter ?? 0
      let page: EventPage
      let guard = 0
      do {
        page = await endpoints.listRunEvents(ws, runId, after)
        for (const e of page.items) if (!events.some((x) => x.seq === e.seq)) events.push(e)
        after = page.nextAfter
        guard++
      } while (page.hasMore && guard < 20)
      events.sort((a, b) => a.seq - b.seq)
      return { events, nextAfter: after, lifecycle: page.lifecycle }
    },
    refetchInterval: (q) => {
      const current = q.state.data?.lifecycle ?? lifecycle
      return current ? eventsPollInterval(current) : POLL.waitingEventsMs
    },
    refetchOnWindowFocus: true,
    retry: 1,
  })

  const eventCount = query.data?.events.length ?? 0
  const eventLifecycle = query.data?.lifecycle
  const previousCount = useRef(eventCount)
  useEffect(() => {
    const grew = eventCount > previousCount.current
    const changed = eventLifecycle !== undefined && known.current !== undefined && eventLifecycle !== known.current
    previousCount.current = eventCount
    if (eventLifecycle) known.current = eventLifecycle
    if (grew || changed) void client.invalidateQueries({ queryKey: keys.run(ws, runId), exact: true })
  }, [eventCount, eventLifecycle, client, ws, runId])

  // The run detail and the event feed are fetched independently, so the status can show a new lifecycle (for example
  // Completed) one poll before the feed does. Fetch the feed at once, so the timeline never lags the status above it.
  const syncedLifecycle = useRef<Lifecycle | undefined>(undefined)
  useEffect(() => {
    if (!lifecycle || !eventLifecycle || lifecycle === eventLifecycle || syncedLifecycle.current === lifecycle) return
    syncedLifecycle.current = lifecycle
    void client.invalidateQueries({ queryKey: keys.runEvents(ws, runId), exact: true })
  }, [lifecycle, eventLifecycle, client, ws, runId])

  return query
}

// Notifications ----------------------------------------------------------------------------------

export function useNotifications(ws: string, enabled = true) {
  return useQuery({
    queryKey: keys.notifications(ws),
    queryFn: () => endpoints.listMyNotifications(ws, { limit: 20 }),
    enabled,
    refetchInterval: POLL.notificationsMs,
    refetchOnWindowFocus: true,
  })
}

export function useMarkNotificationRead(ws: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => endpoints.markNotificationRead(ws, id),
    onSuccess: () => client.invalidateQueries({ queryKey: keys.notifications(ws) }),
  })
}
