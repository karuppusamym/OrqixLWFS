import { useQuery } from '@tanstack/react-query'
import { AppWindow, ArrowLeft } from 'lucide-react'
import { useMemo } from 'react'
import { Link, useParams } from 'react-router'
import { getTask } from '@/api/endpoints'
import { getAppPage, listAppPageDrafts, listAppPages } from '@/api/pages'
import { useWorkspace } from '@/app/workspace'
import { EmptyState } from '@/components/common/EmptyState'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { QueryError } from '@/components/common/QueryError'
import { RelativeTime } from '@/components/common/RelativeTime'
import { Badge } from '@/components/ui/badge'
import { getCaseData } from '@/features/cases/api'
import type { ViewDefinition } from '@/features/views/model'
import { pinKey, type PageDefinition } from './model'
import { PageCanvas } from './PageCanvas'

/** `/w/:ws/apps/:slug/:context?` renders the exact page version pinned to the route. */
export function AppPageRoute() {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const { slug = '', context } = useParams()
  const runtime = useQuery({ queryKey: ['appPage', ws, slug], queryFn: () => getAppPage(ws, slug) })
  const page = runtime.data?.page.definition as PageDefinition | undefined
  const task = useQuery({ queryKey: ['task', ws, context], queryFn: () => getTask(ws, context!), enabled: page?.context === 'task' && !!context })
  const caseData = useQuery({ queryKey: ['caseData', ws, context], queryFn: () => getCaseData(ws, context!), enabled: page?.context === 'case' && !!context })
  const drafts = useQuery({ queryKey: ['appPageDrafts', ws, slug], queryFn: () => listAppPageDrafts(ws, slug), enabled: !!runtime.data })
  const views = useMemo(() => new Map((runtime.data?.views ?? []).map(view => [pinKey(view), view.definition as unknown as ViewDefinition])), [runtime.data])
  const extensions = useMemo(() => new Map((runtime.data?.extensions ?? []).map(extension => [pinKey(extension), extension])), [runtime.data])

  if (runtime.isPending) return <LoadingBlock label="Loading page" />
  if (runtime.isError) return <QueryError error={runtime.error} onRetry={() => void runtime.refetch()} what="page" />
  if (!page) return null
  const contextQuery = page.context === 'task' ? task : page.context === 'case' ? caseData : null
  if (context && contextQuery?.isPending) return <LoadingBlock label={`Loading ${page.context}`} />
  if (context && contextQuery?.isError) return <QueryError error={contextQuery.error} onRetry={() => void contextQuery.refetch()} what={page.context} />
  if (drafts.isPending) return <LoadingBlock label="Loading page" />

  const contextData: Record<string, unknown> = {}
  if (task.data) {
    const t = task.data
    Object.assign(contextData, { 'task.requestTitle': t.requestTitle, 'task.reference': t.runReference, 'task.label': t.label, 'task.workflowName': t.workflowName, 'task.requester': t.requester.displayName, 'task.createdAt': t.createdAt, 'task.dueAt': t.dueAt, 'task.status': t.status, 'task.priority': t.priority })
    for (const [key, value] of Object.entries((t.request?.input ?? {}) as Record<string, unknown>)) contextData[`request.${key}`] = value
  }
  if (caseData.data) for (const [key, value] of Object.entries(caseData.data.values as Record<string, unknown>)) contextData[`case.${key}`] = value

  return <>
    <Link to={`/w/${ws}/apps`} className="mb-4 inline-flex items-center gap-1 text-sm"><ArrowLeft className="size-4" aria-hidden="true" /> All apps</Link>
    <PageHeader title={page.title} description={`Page version ${runtime.data.page.version}${context ? ` for ${page.context} ${task.data?.requestTitle ?? context}` : ''}. Actions are authorized again by the server.`} />
    {/* Not keyed by the task or case version: a chain's outcome must stay visible after its own steps change that version. */}
    <PageCanvas key={`${slug}:${runtime.data.revision}:${context ?? ''}`} page={page} views={views} extensions={extensions}
      runtime={{ ws, slug, revision: runtime.data.revision, context, contextData, task: task.data, caseData: caseData.data, drafts: Object.fromEntries((drafts.data?.items ?? []).map(draft => [draft.componentId, draft.values as Record<string, unknown>])), grants: runtime.data.grants }} />
  </>
}

/** `/w/:ws/apps`: the routed pages a member can open. */
export function AppsPage() {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const query = useQuery({ queryKey: ['appPages', ws], queryFn: () => listAppPages(ws) })
  return <>
    <PageHeader title="Apps" description="Pages designed in Views and published to a route. Each route shows one exact page version." />
    {query.isPending ? <LoadingBlock label="Loading apps" /> : query.isError ? <QueryError error={query.error} onRetry={() => void query.refetch()} what="apps" />
      : query.data.items.length === 0 ? <EmptyState icon={AppWindow} title="No apps yet">Designers publish pages from Views, then a publisher assigns them a route.</EmptyState>
        : <ul className="grid gap-3 sm:grid-cols-2">{query.data.items.map(route => <li key={route.slug} className="rounded-card border border-border bg-surface p-4">
          {route.page ? <Link className="text-lg font-semibold" to={`/w/${ws}/apps/${route.slug}`}>{route.title}</Link> : <span className="text-lg font-semibold text-muted-foreground">{route.slug}</span>}
          <p className="mt-1 text-sm text-muted-foreground">/{route.slug} {route.page ? <Badge tone="success">Version {route.page.version}</Badge> : <Badge>Removed</Badge>} · changed <RelativeTime value={route.changedAt} /></p>
        </li>)}</ul>}
  </>
}
