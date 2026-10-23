import { usePresentedView,ViewLanguage } from '@/features/views/ViewLanguage'
import { viewSurface } from '@/features/views/presentation'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { useWorkspace } from '@/app/workspace'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { CaseDataFields, CaseDataFieldsEditor, typedValues, type DataField, type DataInputs } from './CaseDataFields'
import type { components } from '@/api/schema'
import { getPublishedView } from '@/api/views'
import type { ViewDefinition } from '@/features/views/model'
import { CaseViewPicker } from './CaseViewPicker'
import { CaseModelDesigner } from './CaseModelDesigner'
import { downloadBlob } from '@/lib/download'
import { validateCaseView } from './caseView'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { createCaseDefinition, getCaseDefinition, listCaseDefinitions, listCaseInstances, startCaseInstance } from './api'

/** Initial CMMN case operations surface. Execution is limited to the server's validated model subset. */
export function CasesPage() {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const definitions = useQuery({ queryKey: ['ws', ws, 'case-definitions'], queryFn: () => listCaseDefinitions(ws), enabled: can('workflow.read', 'workflow.start') })
  const cases = useQuery({ queryKey: ['ws', ws, 'cases'], queryFn: () => listCaseInstances(ws), enabled: can('run.read', 'workflow.start', 'task.act') })
  const [designing,setDesigning]=useState(false)
  const [viewRef, setViewRef] = useState<{ id: string; version: number }>()
  const [dataFields, setDataFields] = useState<DataField[]>([])
  const [starting, setStarting] = useState<components['schemas']['CaseDefinition'] | null>(null)
  const appearance=usePresentedView(starting?.viewBinding?.definition as ViewDefinition|undefined)
  const [initialData, setInitialData] = useState<DataInputs>({})
  const [key, setKey] = useState('')
  const [name, setName] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const create = async () => {
    if (!file) return
    setPending(true); setError(null)
    try {
      if (file.size > 65_536) throw new Error('Choose a CMMN file of at most 64 KiB.')
      if (viewRef) {
        if (!Number.isInteger(viewRef.version) || viewRef.version < 1) throw new Error('Choose a valid published case view version.')
        const published = await getPublishedView(ws, viewRef.id, viewRef.version)
        validateCaseView(dataFields, published.definition as ViewDefinition)
      }
      await createCaseDefinition(ws, { key: key.trim(), name: name.trim(), sourceXml: await file.text(), dataFields, viewRef })
      setFile(null); setKey(''); setName(''); setDataFields([]); setViewRef(undefined);setDesigning(false)
      await queryClient.invalidateQueries({ queryKey: ['ws', ws, 'case-definitions'] })
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'The case definition could not be created.') }
    finally { setPending(false) }
  }

  const exportModel = async (definitionId: string) => {
    setPending(true); setError(null)
    try {
      const definition = await getCaseDefinition(ws, definitionId)
      downloadBlob(new Blob([definition.sourceXml], { type: 'application/xml' }), `${definition.key}-v${definition.version}.cmmn`)
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'The case source could not be exported.') }
    finally { setPending(false) }
  }

  const start = async (definitionId: string, fields: DataField[] = []) => {
    setPending(true); setError(null)
    try {
      const instance = await startCaseInstance(ws, definitionId, typedValues(fields, initialData))
      await queryClient.invalidateQueries({ queryKey: ['ws', ws, 'cases'] })
      navigate(`/w/${ws}/cases/${instance.id}`)
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'The case could not be started.') }
    finally { setPending(false) }
  }

  if (!can('workflow.read', 'workflow.start', 'run.read', 'task.act')) return <Alert tone="warning">You need case access to view this page.</Alert>
  return <div className="space-y-6">
    <PageHeader title="Cases" description="Start and work on adaptive cases from validated CMMN definitions." />
    <Alert tone="info" live="off">This case workbench currently executes a validated human-task, nested-stage, sentry and milestone subset. Full CMMN import and discretionary planning are still in development.</Alert>
    {error ? <Alert tone="danger">{error}</Alert> : null}
    {can('workflow.edit') ? <form className="space-y-3 rounded-card border border-border bg-surface p-5" onSubmit={event => { event.preventDefault(); void create() }}>
      <h2>Create case definition</h2>
      <p className="text-sm text-muted-foreground">Build a case model or upload CMMN 1.1. The server checks the executable subset before deployment.</p>
      <div className="grid gap-3 sm:grid-cols-2"><div><Label htmlFor="case-key">Key</Label><Input id="case-key" value={key} onChange={event => { setKey(event.target.value);if(designing)setFile(null) }} required /></div><div><Label htmlFor="case-name">Name</Label><Input id="case-name" value={name} onChange={event => { setName(event.target.value);if(designing)setFile(null) }} required /></div></div>
      <Label htmlFor="case-file">CMMN file (maximum 64 KiB)</Label><Input id="case-file" type="file" accept=".cmmn,.xml" onChange={event => { setFile(event.target.files?.[0] ?? null);setDesigning(false) }} />
      <Button type="button" variant="secondary" disabled={pending} onClick={()=>{setDesigning(value=>!value);setFile(null)}}>{designing?'Close case model designer':'Build a case model'}</Button>
      {designing?<CaseModelDesigner ws={ws} caseKey={key.trim()} name={name.trim()} disabled={pending} onApply={xml=>setFile(xml===null?null:new File([xml],`${key.trim()}.cmmn`,{type:'application/xml'}))}/>:null}
      {designing&&file?<p role="status">Generated CMMN selected for this definition.</p>:null}
      <CaseDataFieldsEditor fields={dataFields} onChange={setDataFields} disabled={pending} />
      <CaseViewPicker ws={ws} value={viewRef} onChange={setViewRef} disabled={pending} />
      <Button type="submit" variant="primary" loading={pending} disabled={!file || !key.trim() || !name.trim()}>Create definition</Button>
    </form> : null}
    {can('workflow.read', 'workflow.start') ? <section className="space-y-3" aria-label="Case definitions">
      <h2>Definitions</h2>
      {definitions.isPending ? <LoadingBlock label="Loading case definitions" /> : definitions.isError ? <QueryError error={definitions.error} onRetry={() => void definitions.refetch()} what="case definitions" /> : definitions.data.length ?
        <div className="overflow-x-auto"><Table><THead><TR><TH>Name</TH><TH>Key</TH><TH>Version</TH><TH>Action</TH></TR></THead><TBody>{definitions.data.map(definition => <TR key={definition.id}><TD>{definition.name}</TD><TD>{definition.key}</TD><TD>{definition.version}</TD><TD>{can('workflow.start') ? <Button disabled={pending} onClick={() => { if (definition.dataFields?.length) { setInitialData({}); setStarting(definition) } else void start(definition.id) }}>Start case</Button> : null}{can('workflow.edit') ? <Button variant="secondary" disabled={pending} onClick={() => void exportModel(definition.id)}>Export CMMN</Button> : null}</TD></TR>)}</TBody></Table></div>
        : <p className="text-sm text-muted-foreground">No case definitions are available in this workspace.</p>}
    </section> : null}
    <Dialog open={starting !== null} onClose={() => setStarting(null)} dismissible={!pending} title="Start case" description={starting?.name}>
      <form lang={appearance.locale} className={`space-y-4 ${viewSurface(appearance.view)}`} onSubmit={event => { event.preventDefault(); if (starting) void start(starting.id, starting.dataFields ?? []) }}>
        <ViewLanguage definition={starting?.viewBinding?.definition as ViewDefinition|undefined} locale={appearance.locale} onChange={appearance.setLocale}/>
        {starting ? <CaseDataFields view={appearance.view} fields={starting.dataFields ?? []} values={initialData} onChange={setInitialData} disabled={pending} /> : null}
        {error ? <Alert tone="danger">{error}</Alert> : null}
        <Button type="submit" loading={pending} disabled={pending}>Start case with data</Button>
      </form>
    </Dialog>
    {can('run.read', 'workflow.start', 'task.act') ? <section className="space-y-3" aria-label="Case instances">
      <h2>Recent cases</h2>
      {cases.isPending ? <LoadingBlock label="Loading cases" /> : cases.isError ? <QueryError error={cases.error} onRetry={() => void cases.refetch()} what="cases" /> : cases.data.length ?
        <div className="overflow-x-auto"><Table><THead><TR><TH>Case</TH><TH>Status</TH><TH>Open tasks</TH></TR></THead><TBody>{cases.data.map(instance => <TR key={instance.id}><TD><Link className="text-link underline" to={`/w/${ws}/cases/${instance.id}`}>{instance.id}</Link></TD><TD><Badge>{instance.status}</Badge></TD><TD>{instance.tasks.length}</TD></TR>)}</TBody></Table></div>
        : <p className="text-sm text-muted-foreground">No cases have started in this workspace.</p>}
    </section> : null}
  </div>
}
