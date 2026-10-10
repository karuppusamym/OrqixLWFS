import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, call } from '@/api/client'
import { keys } from '@/api/queries'
import type { TaskDetail } from '@/api/types'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Select, Textarea } from '@/components/ui/input'

export function TaskDelegation({ ws, task, busy, onBusy }: { ws: string; task: TaskDetail; busy: boolean; onBusy: (value: boolean) => void }) {
  const client = useQueryClient()
  const [open, setOpen] = useState(false)
  const [target, setTarget] = useState('')
  const [reason, setReason] = useState('')
  const colleagues = useQuery({ queryKey: ['ws', ws, 'task-delegates', task.id, task.version], enabled: open, retry: false,
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/tasks/{taskId}/delegates', { params: { path: { workspaceId: ws, taskId: task.id } } })) })
  const delegate = useMutation({ mutationFn: () => call(api.POST('/workspaces/{workspaceId}/tasks/{taskId}/delegate', {
    params: { path: { workspaceId: ws, taskId: task.id } }, body: { expectedVersion: task.version, assigneeSubjectId: target, reason: reason.trim() },
  })), onMutate: () => onBusy(true), onSuccess: async fresh => {
    client.setQueryData(keys.task(ws, task.id), fresh)
    await client.invalidateQueries({ queryKey: keys.tasks(ws) })
    setOpen(false)
  }, onSettled: () => onBusy(false) })
  if (!open) return <Button variant="secondary" disabled={busy} onClick={() => setOpen(true)}>Delegate to a colleague</Button>
  return <form className="space-y-3 rounded-card border border-border p-4" onSubmit={event => { event.preventDefault(); if (target && reason.trim()) delegate.mutate() }}>
    <h3>Delegate your task</h3><p className="text-sm text-muted-foreground">The colleague becomes responsible for the decision. Your reason is saved in the request history.</p>
    {colleagues.isError ? <Alert tone="danger">Eligible colleagues could not be loaded. <Button variant="ghost" onClick={() => void colleagues.refetch()}>Retry</Button></Alert> : null}
    <Label htmlFor="delegate-colleague">Colleague</Label><Select id="delegate-colleague" value={target} disabled={busy || colleagues.isPending} onChange={e => setTarget(e.target.value)}>
      <option value="">{colleagues.isPending ? 'Loading colleagues...' : 'Choose an eligible colleague'}</option>
      {colleagues.data?.map(c => <option key={c.id} value={c.id}>{c.displayName}</option>)}
    </Select>
    {colleagues.data?.length === 0 ? <p>No eligible known colleagues are available.</p> : null}
    <Label htmlFor="delegate-reason">Reason for delegation</Label><Textarea id="delegate-reason" maxLength={1000} value={reason} disabled={busy} onChange={e => setReason(e.target.value)} />
    {delegate.isError ? <Alert tone="danger">Delegation could not be completed. Reload the task to check its current owner before trying again.</Alert> : null}
    <div className="flex gap-2"><Button type="submit" variant="primary" disabled={busy || !target || !reason.trim()}>Confirm delegation</Button><Button variant="secondary" disabled={busy} onClick={() => setOpen(false)}>Cancel delegation</Button></div>
  </form>
}
