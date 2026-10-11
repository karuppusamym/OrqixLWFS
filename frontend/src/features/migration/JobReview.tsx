import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import * as endpoints from '@/api/endpoints'
import type { components } from '@/api/schema'
import { isProblemError } from '@/api/problem'
import { useGroups } from '@/api/queries'
import { useWorkspace } from '@/app/workspace'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { downloadBlob, downloadJson } from '@/lib/download'
import { reportCsv } from './reportExport'
import { toKey } from '@/lib/text'
import { ServiceMappingGuide } from './ServiceMappingGuide'

type Job = components['schemas']['MigrationJob']
type Request = components['schemas']['ConvertMigrationRequest']
type ProjectConversion = components['schemas']['MigrationProjectConversion']

/** Set when the file belongs to a multi-file project: conversion goes through convert-next and stays on the project page. */
export interface ProjectContext {
  projectId: string
  onConverted: (result: ProjectConversion) => void
  suggestedDecisionMappings?: Request['decisionMappings']
}

/** Compatibility report and mapping form of one source file; converting creates a draft, never a release. */
export function JobReview({ job, editable, project }: { job: Job; editable: boolean; project?: ProjectContext }) {
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
  const [children, setChildren] = useState('{}')
  const [services, setServices] = useState('{}')
  const [serviceGroups, setServiceGroups] = useState('{}')
  const [decisions, setDecisions] = useState(() => JSON.stringify(project?.suggestedDecisionMappings ?? {}, null, 2))
  const [decisionId, setDecisionId] = useState('')
  const [ackReview, setAckReview] = useState(false)
  const [input, setInput] = useState('')
  const [failedItems, setFailedItems] = useState<string[]>([])
  const [ack, setAck] = useState(false)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [findingKind, setFindingKind] = useState('all')
  const [findingSeverity, setFindingSeverity] = useState('all')
  const [onlyOpen, setOnlyOpen] = useState(false)
  const visibleFindings = report.issues.filter(issue => (findingKind === 'all' || issue.sourceKind === findingKind) && (findingSeverity === 'all' || issue.severity === findingSeverity) && (!onlyOpen || issue.resolutionStatus === 'open'))
  const findingGroups = [...new Set(visibleFindings.map(issue => issue.sourceKind))].sort()
  const mappingTemplate = () => downloadJson({ format: 'orqix-pega-mapping-0.1', archiveJobId: job.id,
    sourceArchiveHash: report.sourceHash, sourceVersion: 'ENTER-PEGA-VERSION', definition: {},
    fileMappings: Object.fromEntries(report.issues.map(i => [i.sourceElementId, { disposition: 'unresolved', targetNodeIds: [], explanation: '' }])) }, 'pega-mapping-template.json')
  const convert = async () => {
    setPending(true); setError(null); setFailedItems([])
    try {
      const request: Request = { workflowKey: key, name, groupMappings, approvalRoutes: JSON.parse(routes) as Request['approvalRoutes'],
        serviceTaskMappings: JSON.parse(services) as Request['serviceTaskMappings'], childWorkflowMappings: JSON.parse(children) as Request['childWorkflowMappings'], acknowledgeInfo: ack, acknowledgeReview: ackReview,
        serviceGroupMappings: JSON.parse(serviceGroups) as Request['serviceGroupMappings'], decisionMappings: JSON.parse(decisions) as Request['decisionMappings'],
        ...(decisionId.trim() ? { decisionId: decisionId.trim() } : {}),
        ...(input.trim() ? { inputSchema: JSON.parse(input) as Request['inputSchema'] } : {}) }
      const queryKey = ['ws', workspace.id, 'migration', job.id]
      if (project) {
        // The server converts only the file it expects next; a stale page gets a conflict instead of a wrong draft.
        const result = await endpoints.convertMigrationProjectNext(workspace.id, project.projectId, { expectedMigrationId: job.id, mappings: request })
        queryClient.setQueryData(queryKey, { ...job, status: 'converted', convertedWorkflowId: result.converted.workflow.id, report: result.converted.report })
        project.onConverted(result)
        return
      }
      const result = await endpoints.convertMigration(workspace.id, job.id, request)
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
    <div className="flex flex-wrap items-center justify-between gap-3"><h2>{job.fileName}</h2><div className="flex gap-2"><Button onClick={() => downloadJson(job, 'migration-report.json')}>Download compatibility report</Button><Button onClick={() => downloadBlob(new Blob([reportCsv(report)], { type: 'text/csv;charset=utf-8' }), 'migration-findings.csv')}>Download findings CSV</Button></div></div>
    <p className="break-all text-xs text-muted-foreground">Source: {report.sourceSystem} · adapter {report.adapterVersion} · SHA-256 {report.sourceHash}</p>
    <div className="flex flex-wrap gap-2">{Object.entries(report.elementCounts).map(([kind, count]) => <Badge key={kind}>{kind}: {count}</Badge>)}</div>
    <ul className="list-disc space-y-1 pl-5 text-sm text-muted-foreground">{report.assumptions.map((a, i) => <li key={i}>{a}</li>)}</ul>
    <div className="flex flex-wrap items-end gap-3">
      <div><Label htmlFor="findings-kind">Finding kind</Label><Select id="findings-kind" value={findingKind} onChange={event => setFindingKind(event.target.value)}><option value="all">All kinds</option>{[...new Set(report.issues.map(issue => issue.sourceKind))].sort().map(kind => <option key={kind} value={kind}>{kind}</option>)}</Select></div>
      <div><Label htmlFor="findings-severity">Severity</Label><Select id="findings-severity" value={findingSeverity} onChange={event => setFindingSeverity(event.target.value)}><option value="all">All severities</option>{['blocker','action','review','info','supported'].map(severity => <option key={severity} value={severity}>{severity}</option>)}</Select></div>
      <Label className="flex gap-2 items-center"><input type="checkbox" checked={onlyOpen} onChange={event => setOnlyOpen(event.target.checked)} />Unresolved findings only</Label>
    </div>
    <p className="text-sm text-muted-foreground">Showing {visibleFindings.length} of {report.issues.length} findings. Exports and conversion use the complete report.</p>
    <div className="space-y-5">{findingGroups.map(kind => <section key={kind} className="space-y-3" aria-label={`${kind} findings`}><h3>{kind}</h3>{visibleFindings.filter(issue => issue.sourceKind === kind).map((issue, index) => <article key={index} className="rounded-card border border-border bg-surface p-4">
      <div className="flex flex-wrap items-center gap-2"><Badge tone={issue.severity === 'blocker' ? 'danger' : issue.severity === 'action' ? 'warning' : issue.severity === 'review' ? 'info' : undefined}>{issue.severity}</Badge><span className="break-all font-semibold">{issue.sourceName ?? issue.sourceElementId}</span><span className="text-xs text-muted-foreground">{issue.sourceKind} · {issue.resolutionStatus}</span></div>
      <p className="mt-2 text-sm">{issue.explanation}</p>{issue.suggestedAction ? <p className="mt-1 text-sm text-muted-foreground">{issue.suggestedAction}</p> : null}
      <p className="mt-2 break-all font-mono text-xs text-muted-foreground">{issue.reasonCode} · {issue.sourcePath ?? issue.sourceElementId}</p>
    </article>)}</section>)}</div>
    {report.testCases?.length ? <section className="space-y-2" aria-label="Generated decision checks"><h3>Generated decision checks</h3><p className="text-sm text-muted-foreground">Each case compares the source meaning with the converted table at and around every threshold. A failed case blocks conversion.</p>
      <div className="overflow-x-auto"><table className="w-full text-left text-sm"><thead><tr><th scope="col" className="p-2">Decision</th><th scope="col" className="p-2">Case</th><th scope="col" className="p-2">Input</th><th scope="col" className="p-2">Expected</th><th scope="col" className="p-2">Converted result</th><th scope="col" className="p-2">Status</th></tr></thead>
        <tbody>{report.testCases.map((c, i) => <tr key={i} className="border-t border-border"><td className="p-2">{c.decision}</td><td className="p-2">{c.name}</td><td className="p-2 font-mono text-xs">{JSON.stringify(c.input)}</td><td className="p-2 font-mono text-xs">{JSON.stringify(c.expected)}</td><td className="p-2 font-mono text-xs">{JSON.stringify(c.actual)}</td><td className="p-2"><Badge tone={c.status === 'passed' ? 'success' : 'danger'}>{c.status}</Badge></td></tr>)}</tbody></table></div></section> : null}
    {job.convertedWorkflowId ? <Button asChild variant="primary"><Link to={`/w/${workspace.id}/workflows/${job.convertedWorkflowId}/edit?migration=${job.id}`}>Open imported draft</Link></Button> : archive ? <Alert tone="warning" title="Manual Pega mapping required">Native rules are not automatically executable here. Download a template, map or explicitly exclude every archive file with an explanation, and upload the reviewed JSON package.<div className="mt-3"><Button onClick={mappingTemplate}>Download mapping package template</Button></div></Alert> : editable ? <form onSubmit={(event) => { event.preventDefault(); void convert() }} className="space-y-4 rounded-card border border-border bg-surface p-5">
      <h2>Resolve mappings and create a draft</h2>
      <Label htmlFor="migration-key">Workflow key</Label><Input id="migration-key" required value={key} onChange={e => setKey(e.target.value)} />
      <Label htmlFor="migration-name">Workflow name</Label><Input id="migration-name" required maxLength={200} value={name} onChange={e => setName(e.target.value)} />
      {(report.requiredMappings?.groups ?? []).map(group => <div key={group}><Label htmlFor={`migration-group-${group}`}>Map source assignment: {group}</Label><Select id={`migration-group-${group}`} disabled={groups.isPending} value={groupMappings[group] ?? ''} onChange={e => setGroupMappings({ ...groupMappings, [group]: e.target.value })}><option value="">{groups.isPending ? 'Loading workspace groups…' : 'Choose workspace group'}</option>{groups.data?.items.map(g => <option key={g.key} value={g.key}>{g.name}</option>)}</Select></div>)}
      {groups.isError ? <QueryError error={groups.error} onRetry={() => void groups.refetch()} what="workspace groups" /> : null}
      {(report.requiredMappings?.approvalTasks ?? []).length ? <><Label htmlFor="migration-routes">Approval routes (JSON; optional for recognized approval gateways)</Label><Textarea id="migration-routes" value={routes} onChange={e => setRoutes(e.target.value)} rows={4} spellCheck={false} /><p className="text-sm text-muted-foreground">Task IDs: {report.requiredMappings?.approvalTasks?.join(', ')}. Example: {JSON.stringify({ taskId: { approvedFlowId: 'approvedFlow', rejectedFlowId: 'rejectedFlow' } })}</p></> : null}
      {(report.requiredMappings?.serviceTasks ?? []).length ? <><ServiceMappingGuide tasks={report.requiredMappings?.serviceTasks ?? []} value={services} onChange={setServices} /><Label htmlFor="migration-services">Service task mappings (JSON)</Label><Textarea id="migration-services" value={services} onChange={e => setServices(e.target.value)} rows={7} spellCheck={false} /><p className="text-sm text-muted-foreground">Source tasks: {report.requiredMappings?.serviceTasks?.join(', ')}. Review each approved connectionRef, definitionVersion, operation, inputMapping and resultMapping.</p></> : null}
      {Object.keys(report.requiredMappings?.serviceGroups ?? {}).length ? <><Label htmlFor="migration-service-groups">Service group mappings (JSON)</Label><Textarea id="migration-service-groups" value={serviceGroups} onChange={e => setServiceGroups(e.target.value)} rows={5} spellCheck={false} /><p className="text-sm text-muted-foreground">One mapping covers every task with the same implementation: {Object.entries(report.requiredMappings?.serviceGroups ?? {}).map(([group, tasks]) => `${group} (${tasks.length} tasks)`).join('; ')}. A service task mapping above overrides the group for one task.</p></> : null}
      {(report.requiredMappings?.decisionTasks ?? []).length ? <><Label htmlFor="migration-decisions">Decision mappings (JSON)</Label><Textarea id="migration-decisions" value={decisions} onChange={e => setDecisions(e.target.value)} rows={4} spellCheck={false} /><p className="text-sm text-muted-foreground">Business rule tasks: {report.requiredMappings?.decisionTasks?.join(', ')}. Upload the DMN file first, then map each task to its migration, for example {JSON.stringify({ taskId: { migrationId: 'DMN-MIGRATION-ID' } })}.</p></> : null}
      {report.sourceSystem === 'dmn' ? <><Label htmlFor="migration-decision-id">Decision to convert (optional)</Label><Input id="migration-decision-id" value={decisionId} onChange={e => setDecisionId(e.target.value)} /><p className="text-sm text-muted-foreground">Leave empty when the file has one decision.</p></> : null}
      {report.sourceSystem !== 'pega' && report.sourceSystem !== 'dmn' ? <><Label htmlFor="migration-input">Request schema (optional JSON)</Label><Textarea id="migration-input" value={input} onChange={e => setInput(e.target.value)} rows={5} spellCheck={false} /><p className="text-sm text-muted-foreground">If omitted, branch comparisons and approved service contracts supply field types. Review the inferred request form before publishing.</p></> : null}
      {(report.requiredMappings?.childWorkflows ?? []).length ? <><Label htmlFor="migration-children">Child workflow mappings (JSON)</Label><Textarea id="migration-children" value={children} onChange={e => setChildren(e.target.value)} rows={7} spellCheck={false} /><p className="text-sm text-muted-foreground">Source calls: {report.requiredMappings?.childWorkflows?.join(', ')}. Each mapping needs workflowRef, inputMapping and resultMapping. Publish the child first; the parent is fixed to its published version.</p></> : null}
      {(report.elementCounts.review ?? 0) > 0 ? <Label className="flex items-start gap-2"><input type="checkbox" checked={ackReview} onChange={e => setAckReview(e.target.checked)} />I reviewed the {report.elementCounts.review} converted items marked review. Their original text is kept in the report and each can behave differently in the target.</Label> : null}
      <Label className="flex items-start gap-2"><input type="checkbox" checked={ack} onChange={e => setAck(e.target.checked)} />I reviewed the assumptions, manual mappings and explicitly excluded source behavior.</Label>
      <Alert tone="info" live="off">Conversion must pass compiler validation. A publisher must separately review and publish; no run starts here.</Alert>
      {error ? <Alert tone="danger">{error}</Alert> : null}
      {failedItems.length ? <ul aria-label="Unresolved conversion issues" className="list-disc space-y-1 pl-5 text-sm">{failedItems.map((message, index) => <li key={index}>{message}</li>)}</ul> : null}
      <Button variant="primary" type="submit" loading={pending}>{project ? 'Convert next file' : 'Convert to draft'}</Button>
    </form> : null}
  </section>
}
