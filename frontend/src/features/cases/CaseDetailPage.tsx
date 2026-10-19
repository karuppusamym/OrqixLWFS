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
import { Dialog } from '@/components/ui/dialog'
import { Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { CasePlan } from './CasePlan'
import { changeCaseLifecycle, completeCaseTask, getCaseInstance, terminateCaseInstance, type CaseLifecycleAction } from './api'

export function CaseDetailPage() {
  const { instanceId = '' } = useParams()
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const queryClient = useQueryClient()
  const instance = useQuery({ queryKey: ['ws', ws, 'case', instanceId], queryFn: () => getCaseInstance(ws, instanceId), enabled: can('run.read', 'workflow.start', 'task.act') && !!instanceId })
  const [pendingTask, setPendingTask] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [operation, setOperation] = useState<CaseLifecycleAction | 'terminate' | null>(null)
  const [reason, setReason] = useState('')

  const actionLabels = { suspend: 'Put case on hold', resume: 'Resume case', close: 'Close case', terminate: 'Terminate case' }
  const operate = async () => {
    if (!instance.data || !reason.trim() || !operation) return
    setPendingTask('terminate'); setError(null)
    try {
      const updated = operation === 'terminate'
        ? await terminateCaseInstance(ws, instanceId, instance.data.version, reason.trim())
        : await changeCaseLifecycle(ws, instanceId, operation, instance.data.version, reason.trim())
      queryClient.setQueryData(['ws', ws, 'case', instanceId], updated)
      void queryClient.invalidateQueries({ queryKey: ['ws', ws, 'cases'] })
      setOperation(null); setReason('')
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'The case could not be changed. Refresh and try again.')
      setOperation(null)
      void instance.refetch()
    } finally { setPendingTask(null) }
  }

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
    {data.status === 'suspended' ? <Alert tone="warning">This case is on hold. Its work can continue after an operator resumes it.</Alert> : null}
    {can('run.operate') && (data.status === 'active' || data.status === 'suspended') ? <div className="flex flex-wrap gap-2">
      {data.status === 'active' ? <><Button variant="secondary" disabled={pendingTask !== null} onClick={() => setOperation('suspend')}>Put case on hold</Button><Button variant="secondary" disabled={pendingTask !== null} onClick={() => setOperation('close')}>Close case</Button></> : <Button variant="secondary" disabled={pendingTask !== null} onClick={() => setOperation('resume')}>Resume case</Button>}
      <Button variant="secondary" disabled={pendingTask !== null} onClick={() => setOperation('terminate')}>Terminate case</Button>
    </div> : null}
    <Dialog open={operation !== null} onClose={() => setOperation(null)} dismissible={pendingTask === null} title={operation === 'terminate' ? 'Terminate this case?' : `${operation ? actionLabels[operation] : 'Change case'}?`} description={operation === 'terminate' ? 'This ends all open case work. The case will be recorded as terminated.' : operation === 'close' ? 'The case can close only after required and active work is resolved.' : 'Record why the case is being held or resumed.'}>
      <div className="space-y-4">
        <Label htmlFor="case-operation-reason">Reason</Label>
        <Textarea id="case-operation-reason" value={reason} maxLength={1000} onChange={event => setReason(event.target.value)} disabled={pendingTask !== null} />
        <div className="flex gap-2"><Button disabled={!reason.trim() || pendingTask !== null} loading={pendingTask === 'terminate'} onClick={() => void operate()}>{operation === 'terminate' ? 'Confirm termination' : 'Confirm change'}</Button><Button variant="secondary" disabled={pendingTask !== null} onClick={() => setOperation(null)}>Keep case open</Button></div>
      </div>
    </Dialog>
    <CasePlan ws={ws} instanceId={instanceId} version={data.version} busy={pendingTask !== null} onPending={value => setPendingTask(value ? 'plan' : null)} />
    <section className="space-y-3" aria-label="Available case tasks"><h2>Available tasks</h2>
      {data.tasks.length ? <div className="grid gap-3 md:grid-cols-2">{data.tasks.map(task => <Card key={task.id}><CardHeader><h3>{task.name}</h3></CardHeader><CardContent className="space-y-3"><p className="text-sm text-muted-foreground">Eligible groups: {task.candidateGroups.length ? task.candidateGroups.join(', ') : 'None listed'}</p><Button variant="primary" disabled={!can('task.act') || !task.eligible || pendingTask !== null} loading={pendingTask === task.id} onClick={() => void complete(task.id, data.version)}>Complete task</Button>{!task.eligible && data.status !== 'suspended' ? <p className="text-sm text-muted-foreground">You are not eligible for this task.</p> : null}</CardContent></Card>)}</div> : <p className="text-sm text-muted-foreground">No tasks are currently available.</p>}
    </section>
  </div>
}
