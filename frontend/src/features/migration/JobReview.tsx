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
import { FormPreview } from './FormPreview'
import { reasonLabel, sourceLabel } from './reasonLabels'
import { ServiceMappingGuide } from './ServiceMappingGuide'

type Job = components['schemas']['MigrationJob']
type Request = components['schemas']['ConvertMigrationRequest']
type ProjectConversion = components['schemas']['MigrationProjectConversion']

/** Set when the file belongs to a multi-file project: conversion goes through convert-next and stays on the project page. */
export interface ProjectContext {
  projectId: string
  onConverted: (result: ProjectConversion) => void
  suggestedDecisionMappings?: Request['decisionMappings']
  decisionIds?: string[]
}

/**
 * Set for a native Pega rule archive (docs/build/S112): the selected starting point and the approval routes the reviewer
 * chose per assignment. The routes replace the JSON field; they are never inferred from flow action names.
 */
export interface NativeContext {
  pegaStartRule: string
  approvalRoutes: NonNullable<Request['approvalRoutes']>
  /** Call step id to the workspace connection operation it runs (S117). */
  connectionMappings?: NonNullable<Request['connectionMappings']>
}

/** Compatibility report and mapping form of one source file; converting creates a draft, never a release. */
export function JobReview({ job, editable, project, native }: { job: Job; editable: boolean; project?: ProjectContext; native?: NativeContext }) {
  const { workspace } = useWorkspace()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const groups = useGroups(workspace.id, editable && (job.report.requiredMappings?.groups?.length ?? 0) > 0)
  const report = job.report
  const automaticPega = report.adapterVersion.startsWith('pega-xpdl-')
  const archive = report.sourceSystem === 'pega' && report.issues.some(i => i.reasonCode === 'PEGA_RULE_REQUIRES_MAPPING')
  const [key, setKey] = useState(toKey(report.processId ?? job.fileName.replace(/\.[^.]+$/, '')))
  const [name, setName] = useState(report.processName ?? job.fileName.replace(/\.[^.]+$/, ''))
  const [groupMappings, setGroupMappings] = useState<Record<string, string>>({})
  const [routes, setRoutes] = useState('{}')
  const [children, setChildren] = useState('{}')
  const [services, setServices] = useState('{}')
  const [serviceGroups, setServiceGroups] = useState('{}')
  const [formMappings, setFormMappings] = useState<Record<string, { migrationId: string }>>({})
  const [uploadedForms, setUploadedForms] = useState<Record<string, Job>>({})
  const [formError, setFormError] = useState<Record<string, string>>({})
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
  const hints = report.mappingHints
  const linkedForms = Object.entries(hints?.forms ?? {}).filter(([, form]) => form.state === 'upload')
  const embeddedForms = Object.entries(hints?.forms ?? {}).filter(([, form]) => form.state === 'embedded')
  const isForm = report.sourceSystem === 'camundaForm'
  const uploadForm = async (element: string, formId: string, file: File | undefined) => {
    if (!file) return
    setFormError(current => ({ ...current, [element]: '' }))
    try {
      if (file.size > 1_048_576) throw new Error('Choose a form file of at most 1 MiB.')
      const uploaded = await endpoints.uploadMigration(workspace.id, { fileName: file.name, content: await file.text(), sourceFormat: 'camundaForm' })
      if (uploaded.report.processId !== formId) throw new Error(`This file is the form '${uploaded.report.processId ?? 'without an id'}', but this step uses the form '${formId}'.`)
      setUploadedForms(current => ({ ...current, [element]: uploaded }))
      setFormMappings(current => ({ ...current, [element]: { migrationId: uploaded.id } }))
    } catch (cause) { setFormError(current => ({ ...current, [element]: cause instanceof Error ? cause.message : 'The form could not be read.' })) }
  }
  const mappingTemplate = () => downloadJson({ format: 'orqix-pega-mapping-0.1', archiveJobId: job.id,
    sourceArchiveHash: report.sourceHash, sourceVersion: 'ENTER-PEGA-VERSION', definition: {},
    fileMappings: Object.fromEntries(report.issues.map(i => [i.sourceElementId, { disposition: 'unresolved', targetNodeIds: [], explanation: '' }])) }, 'pega-mapping-template.json')
  const convert = async () => {
    setPending(true); setError(null); setFailedItems([])
    try {
      const request: Request = { workflowKey: key, name, groupMappings, approvalRoutes: native ? native.approvalRoutes : JSON.parse(routes) as Request['approvalRoutes'],
        ...(native ? { pegaStartRule: native.pegaStartRule } : {}),
        ...(native?.connectionMappings && Object.keys(native.connectionMappings).length ? { connectionMappings: native.connectionMappings } : {}),
        serviceTaskMappings: JSON.parse(services) as Request['serviceTaskMappings'], childWorkflowMappings: JSON.parse(children) as Request['childWorkflowMappings'], acknowledgeInfo: ack, acknowledgeReview: ackReview,
        ...(Object.keys(formMappings).length ? { formMappings } : {}),
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
    <p className="text-sm font-medium">Source: {sourceLabel(report)}</p>
    <p className="break-all text-xs text-muted-foreground">{report.sourceSystem} · adapter {report.adapterVersion} · SHA-256 {report.sourceHash}</p>
    <div className="flex flex-wrap gap-2">{Object.entries(report.elementCounts).map(([kind, count]) => <Badge key={kind}>{kind}: {count}</Badge>)}</div>
    <ul className="list-disc space-y-1 pl-5 text-sm text-muted-foreground">{report.assumptions.map((a, i) => <li key={i}>{a}</li>)}</ul>
    <div className="flex flex-wrap items-end gap-3">
      <div><Label htmlFor="findings-kind">Finding kind</Label><Select id="findings-kind" value={findingKind} onChange={event => setFindingKind(event.target.value)}><option value="all">All kinds</option>{[...new Set(report.issues.map(issue => issue.sourceKind))].sort().map(kind => <option key={kind} value={kind}>{kind}</option>)}</Select></div>
      <div><Label htmlFor="findings-severity">Severity</Label><Select id="findings-severity" value={findingSeverity} onChange={event => setFindingSeverity(event.target.value)}><option value="all">All severities</option>{['blocker','action','review','info','supported'].map(severity => <option key={severity} value={severity}>{severity}</option>)}</Select></div>
      <Label className="flex gap-2 items-center"><input type="checkbox" checked={onlyOpen} onChange={event => setOnlyOpen(event.target.checked)} />Unresolved findings only</Label>
    </div>
    <p className="text-sm text-muted-foreground">Showing {visibleFindings.length} of {report.issues.length} findings. Exports and conversion use the complete report.</p>
    <div className="space-y-5">{findingGroups.map(kind => <section key={kind} className="space-y-3" aria-label={`${kind} findings`}><h3>{kind}</h3>{visibleFindings.filter(issue => issue.sourceKind === kind).map((issue, index) => <article key={index} className="rounded-card border border-border bg-surface p-4">
      {reasonLabel(issue.reasonCode) ? <h4 className="mb-1 text-sm font-semibold">{reasonLabel(issue.reasonCode)}</h4> : null}
      <div className="flex flex-wrap items-center gap-2"><Badge tone={issue.severity === 'blocker' ? 'danger' : issue.severity === 'action' ? 'warning' : issue.severity === 'review' ? 'info' : undefined}>{issue.severity}</Badge><span className="break-all font-semibold">{issue.sourceName ?? issue.sourceElementId}</span><span className="text-xs text-muted-foreground">{issue.sourceKind} · {issue.resolutionStatus}</span></div>
      <p className="mt-2 text-sm">{issue.explanation}</p>{issue.suggestedAction ? <p className="mt-1 text-sm text-muted-foreground">{issue.suggestedAction}</p> : null}
      <p className="mt-2 break-all font-mono text-xs text-muted-foreground">{issue.reasonCode} · {issue.sourcePath ?? issue.sourceElementId}</p>
    </article>)}</section>)}</div>
    {report.testCases?.length ? <section className="space-y-2" aria-label="Generated decision checks"><h3>Generated decision checks</h3><p className="text-sm text-muted-foreground">Each case compares the source meaning with the converted table at and around every threshold. A failed case blocks conversion.</p>
      {/* A scrollable table must be focusable so keyboard users can scroll it (axe scrollable-region-focusable, WCAG 2.1.1), as with ScrollTable. */}
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex */}
      <div className="overflow-x-auto" tabIndex={0} role="region" aria-label="Table of decision check results"><table className="w-full text-left text-sm"><thead><tr><th scope="col" className="p-2">Decision</th><th scope="col" className="p-2">Case</th><th scope="col" className="p-2">Input</th><th scope="col" className="p-2">Expected</th><th scope="col" className="p-2">Converted result</th><th scope="col" className="p-2">Status</th></tr></thead>
        <tbody>{report.testCases.map((c, i) => <tr key={i} className="border-t border-border"><td className="p-2">{c.decision}</td><td className="p-2">{c.name}</td><td className="p-2 font-mono text-xs">{JSON.stringify(c.input)}</td><td className="p-2 font-mono text-xs">{JSON.stringify(c.expected)}</td><td className="p-2 font-mono text-xs">{JSON.stringify(c.actual)}</td><td className="p-2"><Badge tone={c.status === 'passed' ? 'success' : 'danger'}>{c.status}</Badge></td></tr>)}</tbody></table></div></section> : null}
    {isForm ? Object.entries(hints?.formPreviews ?? {}).map(([id, preview]) => <FormPreview key={id} preview={preview} title={`Form ${id} as a request form`} />) : null}
    {embeddedForms.map(([element, form]) => hints?.formPreviews?.[element] ? <FormPreview key={element} preview={hints.formPreviews[element]!} title={`Form ${form.formId ?? ''} on ${element} (${form.target === 'start' ? 'request form' : 'note on the approval'})`} /> : null)}
    {linkedForms.length && editable && !job.convertedWorkflowId ? <fieldset className="space-y-3 rounded-card border border-border bg-surface p-4"><legend className="px-1 font-semibold">Forms used by this file</legend>
      <p className="text-sm text-muted-foreground">These steps use a separate Camunda form file. Upload each file (.form) to import its fields; a start form becomes the request form, a task form becomes a note on the approval. Without the file the form is left out and you acknowledge that below.</p>
      {linkedForms.map(([element, form]) => <div key={element} className="space-y-2">
        <Label htmlFor={`form-file-${element}`}>Form file for {element} (form id {form.formId})</Label>
        <input id={`form-file-${element}`} type="file" accept=".form,.json" onChange={e => void uploadForm(element, form.formId ?? '', e.target.files?.[0])} />
        {formError[element] ? <p role="alert" className="text-sm">{formError[element]}</p> : null}
        {uploadedForms[element]?.report.mappingHints?.formPreviews?.[form.formId ?? ''] ? <FormPreview preview={uploadedForms[element]!.report.mappingHints!.formPreviews![form.formId ?? '']!} title={`Form ${form.formId} as a request form`} /> : null}
        {uploadedForms[element]?.report.issues.some(i => i.severity === 'blocker') ? <p role="alert" className="text-sm">This form has parts that cannot be imported; see the blocked components in its preview. Conversion stays blocked until the form is fixed or left out.</p> : null}
      </div>)}
    </fieldset> : null}
    {job.convertedWorkflowId ? <Button asChild variant="primary"><Link to={`/w/${workspace.id}/workflows/${job.convertedWorkflowId}/edit?migration=${job.id}`}>Open imported draft</Link></Button> : report.sourceSystem === 'cmmn' ? <Alert tone="warning" title="CMMN source inventoried">The findings show every source construct and any extensions or broken references. Case conversion and publication are still in development; this upload created no case definition or running case.</Alert> : isForm ? <Alert tone="info" title="Attach this form to a BPMN file">A Camunda form has no workflow of its own. Import the BPMN file that uses it and upload this form file in the "Forms used by this file" step; its fields become the request form.</Alert> : archive ? <Alert tone="warning" title="Manual Pega mapping required">Native rules are not automatically executable here. Download a template, map or explicitly exclude every archive file with an explanation, and upload the reviewed JSON package.<div className="mt-3"><Button onClick={mappingTemplate}>Download mapping package template</Button></div></Alert> : editable ? <form onSubmit={(event) => { event.preventDefault(); void convert() }} className="space-y-4 rounded-card border border-border bg-surface p-5">
      <h2>Resolve mappings and create a draft</h2>
      <Label htmlFor="migration-key">Workflow key</Label><Input id="migration-key" required value={key} onChange={e => setKey(e.target.value)} />
      <Label htmlFor="migration-name">Workflow name</Label><Input id="migration-name" required maxLength={200} value={name} onChange={e => setName(e.target.value)} />
      {(report.requiredMappings?.groups ?? []).map(group => <div key={group}><Label htmlFor={`migration-group-${group}`}>Map source assignment: {group}</Label><Select id={`migration-group-${group}`} disabled={groups.isPending} value={groupMappings[group] ?? ''} onChange={e => setGroupMappings({ ...groupMappings, [group]: e.target.value })}><option value="">{groups.isPending ? 'Loading workspace groups…' : 'Choose workspace group'}</option>{groups.data?.items.map(g => <option key={g.key} value={g.key}>{g.name}</option>)}</Select></div>)}
      {groups.isError ? <QueryError error={groups.error} onRetry={() => void groups.refetch()} what="workspace groups" /> : null}
      {(report.requiredMappings?.approvalTasks ?? []).length && !native ? <><Label htmlFor="migration-routes">Approval routes (JSON; optional for recognized approval gateways)</Label><Textarea id="migration-routes" value={routes} onChange={e => setRoutes(e.target.value)} rows={4} spellCheck={false} /><p className="text-sm text-muted-foreground">Task IDs: {report.requiredMappings?.approvalTasks?.join(', ')}. Example: {JSON.stringify({ taskId: { approvedFlowId: 'approvedFlow', rejectedFlowId: 'rejectedFlow' } })}</p></> : null}
      {(report.requiredMappings?.serviceTasks ?? []).length ? <><ServiceMappingGuide tasks={report.requiredMappings?.serviceTasks ?? []} value={services} onChange={setServices} hints={hints?.services} /><Label htmlFor="migration-services">Service task mappings (JSON)</Label><Textarea id="migration-services" value={services} onChange={e => setServices(e.target.value)} rows={7} spellCheck={false} /><p className="text-sm text-muted-foreground">Source tasks: {report.requiredMappings?.serviceTasks?.join(', ')}. Review each approved connectionRef, definitionVersion, operation, inputMapping and resultMapping.</p></> : null}
      {Object.keys(report.requiredMappings?.serviceGroups ?? {}).length ? <><Label htmlFor="migration-service-groups">Service group mappings (JSON)</Label><Textarea id="migration-service-groups" value={serviceGroups} onChange={e => setServiceGroups(e.target.value)} rows={5} spellCheck={false} /><p className="text-sm text-muted-foreground">One mapping covers every task with the same implementation: {Object.entries(report.requiredMappings?.serviceGroups ?? {}).map(([group, tasks]) => `${group} (${tasks.length} tasks)`).join('; ')}. A service task mapping above overrides the group for one task.</p></> : null}
      {(report.requiredMappings?.decisionTasks ?? []).length ? <><Label htmlFor="migration-decisions">Decision mappings (JSON)</Label><Textarea id="migration-decisions" value={decisions} onChange={e => setDecisions(e.target.value)} rows={4} spellCheck={false} /><p className="text-sm text-muted-foreground">Business rule tasks: {report.requiredMappings?.decisionTasks?.join(', ')}. Upload the DMN file first, then map each task to its migration, for example {JSON.stringify({ taskId: { migrationId: 'DMN-MIGRATION-ID' } })}.</p></> : null}
      {report.sourceSystem === 'dmn' ? project?.decisionIds?.length ? <>
        <Label htmlFor="migration-decision-id">Decision to convert{project.decisionIds.length > 1 ? ' (required)' : ' (optional)'}</Label>
        <Select id="migration-decision-id" required={project.decisionIds.length > 1} value={decisionId} onChange={e => setDecisionId(e.target.value)}>
          <option value="">{project.decisionIds.length > 1 ? 'Choose a decision' : 'Use the only decision'}</option>
          {project.decisionIds.map(id => <option key={id} value={id}>{id}</option>)}
        </Select>
        <p className="text-sm text-muted-foreground">One selected decision becomes this file's draft workflow. Other decisions remain in the source report; BPMN callers can embed any supported decision from this same file through their explicit mappings. This does not convert every decision into a separate workflow.</p>
      </> : <><Label htmlFor="migration-decision-id">Decision to convert (optional)</Label><Input id="migration-decision-id" value={decisionId} onChange={e => setDecisionId(e.target.value)} /><p className="text-sm text-muted-foreground">Leave empty when the file has one decision.</p></> : null}
      {(report.sourceSystem !== 'pega' || automaticPega) && report.sourceSystem !== 'dmn' ? <><Label htmlFor="migration-input">Request schema (optional JSON)</Label><Textarea id="migration-input" value={input} onChange={e => setInput(e.target.value)} rows={5} spellCheck={false} /><p className="text-sm text-muted-foreground">If omitted, branch comparisons and approved service contracts supply field types. Review the inferred request form before publishing.</p></> : null}
      {(report.requiredMappings?.childWorkflows ?? []).length ? <><Label htmlFor="migration-children">Child workflow mappings (JSON)</Label><Textarea id="migration-children" value={children} onChange={e => setChildren(e.target.value)} rows={7} spellCheck={false} /><p className="text-sm text-muted-foreground">Source calls: {report.requiredMappings?.childWorkflows?.join(', ')}. Each mapping needs workflowRef, inputMapping and resultMapping. Publish the child first; the parent is fixed to its published version.</p></> : null}
      {(report.elementCounts.review ?? 0) > 0 ? <Label className="flex items-start gap-2"><input type="checkbox" checked={ackReview} onChange={e => setAckReview(e.target.checked)} />I reviewed the {report.elementCounts.review} converted items marked review. Their original text is kept in the report and each can behave differently in the target.</Label> : null}
      <Label className="flex items-start gap-2"><input type="checkbox" checked={ack} onChange={e => setAck(e.target.checked)} />I reviewed the assumptions, manual mappings and explicitly excluded source behavior.</Label>
      <Alert tone="info" live="off">Conversion must pass compiler validation. A publisher must separately review and publish; no run starts here.</Alert>
      {report.issues.some(i => i.severity === 'blocker') ? <Alert tone="warning">This report has blockers. Conversion is refused while any blocker remains; each one names the source element it comes from.</Alert> : null}
      {error ? <Alert tone="danger">{error}</Alert> : null}
      {failedItems.length ? <ul aria-label="Unresolved conversion issues" className="list-disc space-y-1 pl-5 text-sm">{failedItems.map((message, index) => <li key={index}>{message}</li>)}</ul> : null}
      <Button variant="primary" type="submit" loading={pending}>{project ? 'Convert next file' : 'Convert to draft'}</Button>
    </form> : null}
  </section>
}
