import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api, call } from '@/api/client'
import { keys } from '@/api/queries'
import type { TaskDetail } from '@/api/types'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input, Textarea } from '@/components/ui/input'

export function TaskPriority({ ws, task, busy, onBusy }: { ws: string; task: TaskDetail; busy: boolean; onBusy: (value: boolean) => void }) {
  const client = useQueryClient()
  const [open, setOpen] = useState(false)
  const [priority, setPriority] = useState(String(task.priority ?? 0))
  const [expectedVersion, setExpectedVersion] = useState(task.version)
  const [reason, setReason] = useState('')
  const valid = priority.trim() !== '' && Number.isInteger(Number(priority)) && Number(priority) >= 0 && Number(priority) <= 100 && !!reason.trim()
  const update = useMutation({ mutationFn: () => call(api.PUT('/workspaces/{workspaceId}/tasks/{taskId}/priority', {
    params: { path: { workspaceId: ws, taskId: task.id } }, body: { priority: Number(priority), expectedVersion, reason: reason.trim() },
  })), onMutate: () => onBusy(true), onSuccess: async fresh => {
    client.setQueryData(keys.task(ws, task.id), fresh)
    await Promise.all([client.invalidateQueries({ queryKey: keys.tasks(ws) }), client.invalidateQueries({queryKey: keys.run(ws, task.runId)})])
    setOpen(false)
  }, onSettled: () => onBusy(false) })
  if (!open) return <Button variant="secondary" disabled={busy} onClick={() => { setPriority(String(task.priority ?? 0)); setExpectedVersion(task.version); setReason(''); update.reset(); setOpen(true) }}>Change priority ({task.priority ?? 0})</Button>
  return <form className="space-y-3 rounded-card border border-border p-4" onSubmit={e => {e.preventDefault(); if(valid) update.mutate()}}>
    <p>Higher priorities appear first. Your reason is retained in the request history.</p>
    <label htmlFor={`priority-${task.id}`}>Task priority (0 to 100)<Input id={`priority-${task.id}`} aria-label="Task priority (0 to 100)" type="number" min={0} max={100} step={1} value={priority} disabled={busy} onChange={e => setPriority(e.target.value)} /></label>
    <label htmlFor={`priority-reason-${task.id}`}>Reason for priority change<Textarea id={`priority-reason-${task.id}`} aria-label="Reason for priority change" maxLength={500} value={reason} disabled={busy} onChange={e => setReason(e.target.value)} /></label>
    {update.isError ? <Alert tone="danger">Priority could not be changed. Reload the task before trying again.</Alert> : null}
    <Button type="submit" variant="primary" disabled={busy || !valid}>Confirm priority change</Button><Button variant="secondary" disabled={busy} onClick={() => setOpen(false)}>Cancel</Button>
  </form>
}
