import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import * as endpoints from '@/api/endpoints'
import type { components } from '@/api/schema'
import { isProblemError } from '@/api/problem'
import { useGroups } from '@/api/queries'
import { useWorkspace } from '@/app/workspace'
import { PageHeader } from '@/components/common/PageHeader'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { downloadJson } from '@/lib/download'
import { toKey } from '@/lib/text'
import { ServiceMappingGuide } from './ServiceMappingGuide'

type Job = components['schemas']['MigrationJob']
type Request = components['schemas']['ConvertMigrationRequest']

export function MigrationPage() {
  const { workspace, can } = useWorkspace()
  const { migrationId } = useParams()
  const navigate = useNavigate()
  const [format, setFormat] = useState<'bpmn' | 'pegaArchive' | 'pegaMapping'>('bpmn')
  const [file, setFile] = useState<File | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const job = useQuery({ queryKey: ['ws', workspace.id, 'migration', migrationId],
    queryFn: () => endpoints.getMigration(workspace.id, migrationId!), enabled: !!migrationId && can('workflow.read') })
  if (!can('workflow.edit') && !can('workflow.read')) return <Alert tone="warning">You need workflow access to view migrations.</Alert>
  const upload = async () => {
    if (!file) return
    setPending(true); setError(null)
    try {
      if (file.size > 1_048_576) throw new Error('Choose a source file of at most 1 MiB.')
      let content: string
      if (format === 'pegaArchive') {
        const bytes = new Uint8Array(await file.arrayBuffer())
        let binary = ''
        for (let offset = 0; offset < bytes.length; offset += 8192) binary += String.fromCharCode(...bytes.subarray(offset, offset + 8192))
        content = btoa(binary)
      } else content = await file.text()
      const result = await endpoints.uploadMigration(workspace.id, { fileName: file.name, content, sourceFormat: format })
      navigate(`/w/${workspace.id}/migrations/${result.id}`)
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'The source could not be analyzed.') }
    finally { setPending(false) }
  }
  return <>
    <PageHeader title="Import workflow" description="Analyze source behavior, resolve mappings, then create a draft for review." actions={<Button asChild><Link to={`/w/${workspace.id}/workflows`}>Back to workflows</Link></Button>} />
    <Alert tone="info" live="off">Camunda 7/8 BPMN can be converted within the supported subset. Pega archives are inventoried; a reviewed, checksum-linked manual mapping package creates the target draft. Live instances, history and credentials stay in the source system.</Alert>
    {can('workflow.edit') ? <form className="my-6 space-y-4 rounded-card border border-border bg-surface p-5" onSubmit={(event) => { event.preventDefault(); void upload() }}>
      <h2>Analyze a source file</h2>
      <Label htmlFor="migration-format">Source format</Label>
      <Select id="migration-format" value={format} onChange={(e) => { setFormat(e.target.value as typeof format); setFile(null); setError(null) }}>
        <option value="bpmn">Camunda 7 / Camunda 8 / BPMN</option>
        <option value="pegaArchive">Pega application archive (.zip)</option>
        <option value="pegaMapping">Reviewed Pega mapping package (.json)</option>
      </Select>
      <Label htmlFor="migration-file">Source file (maximum 1 MiB)</Label>
      <Input key={format} id="migration-file" type="file" accept={format === 'bpmn' ? '.bpmn,.xml' : format === 'pegaArchive' ? '.zip,.rap' : '.json'} onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
      <Button type="submit" variant="primary" loading={pending} disabled={!file}>Analyze source</Button>
      {error ? <Alert tone="danger">{error}</Alert> : null}
    </form> : null}
    {migrationId && job.isPending ? <LoadingBlock label="Loading compatibility report" /> : null}
    {job.isError ? <QueryError error={job.error} onRetry={() => void job.refetch()} what="the migration report" /> : null}
    {job.data ? <JobReview key={job.data.id} job={job.data} editable={can('workflow.edit')} /> : null}
  </>
}

function JobReview({ job, editable }: { job: Job; editable: boolean }) {
  const { workspace } = useWorkspace()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const groups = useGroups(workspace.id, editable && (job.report.requiredMappings?.groups?.length ?? 0) > 0)
  const report = job.report
  const archive = report.sourceSystem === 'pega' && report.issues.some(i => i.reasonCode === 'PEGA_RULE_REQUIRES_MAPPING')
  const [key, setKey] = useState(toKey(report.processId ?? job.fileName.replace(/\.[^.]+$/, '')))
  const [name, setName] = useState(report.processName ?? job.fileName.replace(/\.[^.]+$/, ''))
  const [groupMappings, setGroupMappings] = useState<Record<string, string>>({})
  const [routes, setRoutes] = useState('{}')
  const [services, setServices] = useState('{}')
  const [input, setInput] = useState('')
  const [failedItems, setFailedItems] = useState<string[]>([])
  const [ack, setAck] = useState(false)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const mappingTemplate = () => downloadJson({ format: 'orqix-pega-mapping-0.1', archiveJobId: job.id,
    sourceArchiveHash: report.sourceHash, sourceVersion: 'ENTER-PEGA-VERSION', definition: {},
    fileMappings: Object.fromEntries(report.issues.map(i => [i.sourceElementId, { disposition: 'unresolved', targetNodeIds: [], explanation: '' }])) }, 'pega-mapping-template.json')
  const convert = async () => {
    setPending(true); setError(null); setFailedItems([])
    try {
      const request: Request = { workflowKey: key, name, groupMappings, approvalRoutes: JSON.parse(routes) as Request['approvalRoutes'],
        serviceTaskMappings: JSON.parse(services) as Request['serviceTaskMappings'], acknowledgeInfo: ack,
        ...(input.trim() ? { inputSchema: JSON.parse(input) as Request['inputSchema'] } : {}) }
      const result = await endpoints.convertMigration(workspace.id, job.id, request)
      const queryKey = ['ws', workspace.id, 'migration', job.id]
      queryClient.setQueryData(queryKey, { ...job, status: 'converted', convertedWorkflowId: result.workflow.id, report: result.report })
      // Conversion is committed. A report refresh must not prevent opening the created draft.
      void queryClient.invalidateQueries({ queryKey, refetchType: 'none' })
      navigate(`/w/${workspace.id}/workflows/${result.workflow.id}/edit?migration=${job.id}`)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Conversion failed. No draft was created.')
      if (isProblemError(cause)) setFailedItems([
        ...cause.openItems.map(i => `${i.sourceElementId}: ${i.explanation}`),
        ...cause.issues.map(i => `${i.nodeId ?? i.path}: ${i.message}`),
      ])
    }
    finally { setPending(false) }
  }
  return <section className="space-y-5" aria-label="Compatibility report">
    <div className="flex flex-wrap items-center justify-between gap-3"><h2>{job.fileName}</h2><Button onClick={() => downloadJson(job, 'migration-report.json')}>Download compatibility report</Button></div>
    <p className="break-all text-xs text-muted-foreground">Source: {report.sourceSystem} · adapter {report.adapterVersion} · SHA-256 {report.sourceHash}</p>
    <div className="flex flex-wrap gap-2">{Object.entries(report.elementCounts).map(([kind, count]) => <Badge key={kind}>{kind}: {count}</Badge>)}</div>
    <ul className="list-disc space-y-1 pl-5 text-sm text-muted-foreground">{report.assumptions.map((a, i) => <li key={i}>{a}</li>)}</ul>
    <div className="space-y-3">{report.issues.map((issue, index) => <article key={index} className="rounded-card border border-border bg-surface p-4">
      <div className="flex flex-wrap items-center gap-2"><Badge tone={issue.severity === 'blocker' ? 'danger' : issue.severity === 'action' ? 'warning' : undefined}>{issue.severity}</Badge><span className="break-all font-semibold">{issue.sourceName ?? issue.sourceElementId}</span><span className="text-xs text-muted-foreground">{issue.sourceKind} · {issue.resolutionStatus}</span></div>
      <p className="mt-2 text-sm">{issue.explanation}</p>{issue.suggestedAction ? <p className="mt-1 text-sm text-muted-foreground">{issue.suggestedAction}</p> : null}
      <p className="mt-2 break-all font-mono text-xs text-muted-foreground">{issue.reasonCode} · {issue.sourcePath ?? issue.sourceElementId}</p>
    </article>)}</div>
    {job.convertedWorkflowId ? <Button asChild variant="primary"><Link to={`/w/${workspace.id}/workflows/${job.convertedWorkflowId}/edit?migration=${job.id}`}>Open imported draft</Link></Button> : archive ? <Alert tone="warning" title="Manual Pega mapping required">Native rules are not automatically executable here. Download a template, map or explicitly exclude every archive file with an explanation, and upload the reviewed JSON package.<div className="mt-3"><Button onClick={mappingTemplate}>Download mapping package template</Button></div></Alert> : editable ? <form onSubmit={(event) => { event.preventDefault(); void convert() }} className="space-y-4 rounded-card border border-border bg-surface p-5">
      <h2>Resolve mappings and create a draft</h2>
      <Label htmlFor="migration-key">Workflow key</Label><Input id="migration-key" required value={key} onChange={e => setKey(e.target.value)} />
      <Label htmlFor="migration-name">Workflow name</Label><Input id="migration-name" required maxLength={200} value={name} onChange={e => setName(e.target.value)} />
      {(report.requiredMappings?.groups ?? []).map(group => <div key={group}><Label htmlFor={`migration-group-${group}`}>Map source assignment: {group}</Label><Select id={`migration-group-${group}`} disabled={groups.isPending} value={groupMappings[group] ?? ''} onChange={e => setGroupMappings({ ...groupMappings, [group]: e.target.value })}><option value="">{groups.isPending ? 'Loading workspace groups…' : 'Choose workspace group'}</option>{groups.data?.items.map(g => <option key={g.key} value={g.key}>{g.name}</option>)}</Select></div>)}
      {groups.isError ? <QueryError error={groups.error} onRetry={() => void groups.refetch()} what="workspace groups" /> : null}
      {(report.requiredMappings?.approvalTasks ?? []).length ? <><Label htmlFor="migration-routes">Approval routes (JSON; optional for recognized approval gateways)</Label><Textarea id="migration-routes" value={routes} onChange={e => setRoutes(e.target.value)} rows={4} spellCheck={false} /><p className="text-sm text-muted-foreground">Task IDs: {report.requiredMappings?.approvalTasks?.join(', ')}. Example: {JSON.stringify({ taskId: { approvedFlowId: 'approvedFlow', rejectedFlowId: 'rejectedFlow' } })}</p></> : null}
      {(report.requiredMappings?.serviceTasks ?? []).length ? <><ServiceMappingGuide tasks={report.requiredMappings?.serviceTasks ?? []} value={services} onChange={setServices} /><Label htmlFor="migration-services">Service task mappings (JSON)</Label><Textarea id="migration-services" value={services} onChange={e => setServices(e.target.value)} rows={7} spellCheck={false} /><p className="text-sm text-muted-foreground">Source tasks: {report.requiredMappings?.serviceTasks?.join(', ')}. Review each approved connectionRef, definitionVersion, operation, inputMapping and resultMapping.</p></> : null}
      {report.sourceSystem !== 'pega' ? <><Label htmlFor="migration-input">Request schema (optional JSON)</Label><Textarea id="migration-input" value={input} onChange={e => setInput(e.target.value)} rows={5} spellCheck={false} /><p className="text-sm text-muted-foreground">If omitted, branch comparisons and approved service contracts supply field types. Review the inferred request form before publishing.</p></> : null}
      <Label className="flex items-start gap-2"><input type="checkbox" checked={ack} onChange={e => setAck(e.target.checked)} />I reviewed the assumptions, manual mappings and explicitly excluded source behavior.</Label>
      <Alert tone="info" live="off">Conversion must pass compiler validation. A publisher must separately review and publish; no run starts here.</Alert>
      {error ? <Alert tone="danger">{error}</Alert> : null}
      {failedItems.length ? <ul aria-label="Unresolved conversion issues" className="list-disc space-y-1 pl-5 text-sm">{failedItems.map((message, index) => <li key={index}>{message}</li>)}</ul> : null}
      <Button variant="primary" type="submit" loading={pending}>Convert to draft</Button>
    </form> : null}
  </section>
}
