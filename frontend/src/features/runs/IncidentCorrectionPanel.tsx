import { useMemo, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { api, call } from '@/api/client'
import { fingerprintOf, useIdempotencyKey } from '@/api/idempotency'
import { keys } from '@/api/queries'
import type { RunDetail } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { FormField } from '@/components/common/FormField'
import { FormRenderer } from '@/components/form/FormRenderer'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/input'
import { buildFields, parseFormSchema } from '@/lib/form/schema'
import { buildPayload, valuesFromJson } from '@/lib/form/values'
import type { ProblemError } from '@/api/problem'

export function IncidentCorrectionPanel({ run, inputSchema }: { run: RunDetail; inputSchema: unknown }) {
  const { workspace, can }=useWorkspace()
  const client=useQueryClient(); const key=useIdempotencyKey()
  const schema=useMemo(()=>{
    const parsed=parseFormSchema(inputSchema)
    const properties=Object.fromEntries(Object.entries(parsed.properties).filter(([,field])=>field.incidentEditable && field.type!=='array' && field.format!=='attachment'))
    return {...parsed,properties,required:parsed.required.filter(name=>name in properties)}
  },[inputSchema])
  const fields=useMemo(()=>buildFields(schema,{}),[schema])
  const [values,setValues]=useState(()=>valuesFromJson(fields,run.input as Record<string,unknown>))
  const [reason,setReason]=useState(''); const [pending,setPending]=useState(false)
  const [error,setError]=useState<ProblemError|null>(null); const [message,setMessage]=useState('')
  const [errors,setErrors]=useState<Record<string,string>>({})
  if (!can('run.operate') || run.lifecycle!=='needsAttention' || !run.input || fields.length===0) return null
  const blocked=!!run.effects?.length || run.tasks.length>0
  const submit=async()=>{
    const built=buildPayload(fields,values); setErrors(built.parseErrors)
    if (Object.keys(built.parseErrors).length) return
    if (!reason.trim()) {setMessage('Add a reason before saving.');return}
    const changes=Object.fromEntries(Object.entries(built.payload).filter(([name,value])=>JSON.stringify(value)!==JSON.stringify((run.input as Record<string,unknown>)[name]))) as Record<string,string|number|boolean>
    if (!Object.keys(changes).length) {setMessage('Change at least one value.');return}
    setPending(true);setError(null);setMessage('')
    try {
      const body={changes,reason:reason.trim(),expectedVersion:run.version}
      await call(api.POST('/workspaces/{workspaceId}/runs/{runId}/correct-input',{
        params:{path:{workspaceId:workspace.id,runId:run.id},header:{'Idempotency-Key':key.keyFor(fingerprintOf(run.id,body))}},body,
      }))
      await Promise.all([client.invalidateQueries({queryKey:keys.run(workspace.id,run.id)}),client.invalidateQueries({queryKey:keys.runEvents(workspace.id,run.id)})])
      setMessage('Input corrected. The request still needs attention; review it and retry the step separately.')
    } catch(error) {setError(toProblem(error))} finally {setPending(false)}
  }
  return <section aria-labelledby="input-correction-heading" className="mb-6 rounded-card border border-border bg-surface p-4">
    <h2 id="input-correction-heading" className="mb-3">Correct incident input</h2>
    <p className="mb-4 text-sm text-muted-foreground">Only fields explicitly allowed by the workflow designer appear. The server refuses correction after effects or earlier data-consuming steps, and while jobs or timers remain active. Saving does not retry the request. Old and new values are recorded in its protected timeline.</p>
    {blocked ? <Alert tone="warning">This request has an external effect or task history. Start a corrected request instead.</Alert> : <>
      <FormRenderer fields={fields} values={values} onChange={(name,value)=>setValues(previous=>({...previous,[name]:value}))} errors={errors} disabled={pending} idPrefix="incident-input" />
      <FormField label="Correction reason" id="incident-correction-reason" required>{ids=><Textarea id={ids.input} value={reason} maxLength={500} disabled={pending} onChange={event=>setReason(event.target.value)} />}</FormField>
      <Button className="mt-4" loading={pending} onClick={()=>void submit()}>Save corrected input</Button>
    </>}
    {message ? <p role="status" className="mt-3 text-sm">{message}</p> : null}
    {error ? <ProblemAlert error={error} /> : null}
  </section>
}
