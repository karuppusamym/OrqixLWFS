import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { assignWorklistView, getWorklistView } from '@/api/worklists'
import { useWorkspace } from '@/app/workspace'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { QueryError } from '@/components/common/QueryError'

export function WorklistAssignment({ id, version }: { id: string; version: number | null }) {
  const { workspace, can } = useWorkspace(), ws=workspace.id
  const client=useQueryClient()
  const query=useQuery({ queryKey: ['worklist-view',ws], queryFn:()=>getWorklistView(ws) })
  const [busy,setBusy]=useState(false), [message,setMessage]=useState<string>(), [error,setError]=useState<string>()
  const assign=async (reset=false)=>{
    if (!query.data || !can('workflow.publish')) return
    setBusy(true);setError(undefined)
    try {
      const result=await assignWorklistView(ws,query.data.revision,reset?null:{id,version:version!})
      client.setQueryData(['worklist-view',ws],result);setMessage(reset?'Standard worklist restored.':'Published version applied to My tasks.')
    } catch(cause) { setError(cause instanceof Error?cause.message:'The worklist changed. Refresh and try again.');void query.refetch() }
    finally { setBusy(false) }
  }
  if(query.isError) return <QueryError error={query.error} onRetry={()=>void query.refetch()} what="worklist presentation" />
  return <section className="space-y-3 rounded border p-4" aria-label="Worklist runtime binding">
    <h2>Workspace worklist</h2>
    <p className="text-sm">Apply an exact published version to My tasks. Authorized rows, filters and task actions remain controlled by the task API.</p>
    {query.data?.binding ? <p>Current view {query.data.binding.id}, version {query.data.binding.version}</p> : <p>Standard worklist layout</p>}
    {can('workflow.publish') ? <div className="flex flex-wrap gap-2"><Button disabled={!version||!query.data||busy} loading={busy} onClick={()=>void assign()}>Use selected published version</Button><Button variant="secondary" disabled={!query.data?.binding||busy} onClick={()=>void assign(true)}>Restore standard worklist</Button></div> : null}
    {message?<Alert tone="success">{message}</Alert>:null}{error?<Alert tone="danger">{error}</Alert>:null}
  </section>
}
