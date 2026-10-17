import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { useWorkspace } from '@/app/workspace'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { completeCaseTask, getCaseInstance } from './api'

export function CaseDetailPage() {
  const { instanceId = '' } = useParams()
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const queryClient = useQueryClient()
  const instance = useQuery({ queryKey: ['ws', ws, 'case', instanceId], queryFn: () => getCaseInstance(ws, instanceId), enabled: can('run.read', 'workflow.start', 'task.act') && !!instanceId })
  const [pendingTask, setPendingTask] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const complete = async (taskId: string, expectedVersion: number) => {
    setPendingTask(taskId); setError(null)
    try {
      const updated = await completeCaseTask(ws, instanceId, taskId, expectedVersion)
      queryClient.setQueryData(['ws', ws, 'case', instanceId], updated)
      void queryClient.invalidateQueries({ queryKey: ['ws', ws, 'cases'] })
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'The task could not be completed. Refresh the case and try again.')
      void instance.refetch()
    } finally { setPendingTask(null) }
  }

  if (!can('run.read', 'workflow.start', 'task.act')) return <Alert tone="warning">You need case read access to view this case.</Alert>
  if (instance.isPending) return <LoadingBlock label="Loading case" />
  if (instance.isError) return <QueryError error={instance.error} onRetry={() => void instance.refetch()} what="this case" backTo={{ to: `/w/${ws}/cases`, label: 'Back to Cases' }} />
  const data = instance.data
  return <div className="space-y-6">
    <PageHeader title="Case detail" description={`Case ${data.id}`} actions={<Button asChild><Link to={`/w/${ws}/cases`}>Back to Cases</Link></Button>} />
    {error ? <Alert tone="danger">{error}</Alert> : null}
    <div className="flex flex-wrap gap-3"><Badge>{data.status}</Badge><span className="text-sm text-muted-foreground">Definition version {data.definitionVersion} · Case version {data.version}</span></div>
    <section className="space-y-3" aria-label="Available case tasks"><h2>Available tasks</h2>
      {data.tasks.length ? <div className="grid gap-3 md:grid-cols-2">{data.tasks.map(task => <Card key={task.id}><CardHeader><h3>{task.name}</h3></CardHeader><CardContent className="space-y-3"><p className="text-sm text-muted-foreground">Eligible groups: {task.candidateGroups.length ? task.candidateGroups.join(', ') : 'None listed'}</p><Button variant="primary" disabled={!can('task.act') || !task.eligible || pendingTask !== null} loading={pendingTask === task.id} onClick={() => void complete(task.id, data.version)}>Complete task</Button>{!task.eligible ? <p className="text-sm text-muted-foreground">You are not eligible for this task.</p> : null}</CardContent></Card>)}</div> : <p className="text-sm text-muted-foreground">No tasks are currently available.</p>}
    </section>
  </div>
}
