import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import * as endpoints from '@/api/endpoints'
import type { components } from '@/api/schema'
import { isProblemError } from '@/api/problem'
import { useGroups } from '@/api/queries'
import { useWorkspace } from '@/app/workspace'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input, Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { toKey } from '@/lib/text'

type Job = components['schemas']['MigrationJob']
type View = components['schemas']['BlueprintTranscriptionView']
type Transcription = components['schemas']['BlueprintTranscription']
type Issue = components['schemas']['CompatibilityIssue']
type ListName = 'stages' | 'roles' | 'fields' | 'steps' | 'decisions' | 'slas' | 'outcomes' | 'integrations'
interface Meta { id?: string; name: string; sourcePage?: number | null; sourceExcerpt?: string | null; disposition: string; dispositionReason?: string | null }

const LISTS: { list: ListName; label: string }[] = [
  { list: 'steps', label: 'Steps' }, { list: 'decisions', label: 'Decisions' }, { list: 'roles', label: 'Roles' }, { list: 'fields', label: 'Fields' },
  { list: 'slas', label: 'SLAs' }, { list: 'outcomes', label: 'Outcomes' }, { list: 'stages', label: 'Stages' }, { list: 'integrations', label: 'Integrations' },
]
const DISPOSITIONS = [['proposed', 'Not reviewed'], ['mapped', 'Reviewed: matches the source'], ['manuallyResolved', 'Manually resolved'], ['excluded', 'Excluded']] as const

/** Review of a Pega Blueprint PDF transcription: each element next to its source page, findings, role mapping and conversion. */
export function BlueprintReview({ job, editable }: { job: Job; editable: boolean }) {
  const { workspace } = useWorkspace()
  const view = useQuery({ queryKey: ['ws', workspace.id, 'migration', job.id, 'blueprint'], queryFn: () => endpoints.getBlueprintTranscription(workspace.id, job.id) })
  if (view.isPending) return <LoadingBlock label="Loading the blueprint transcription" />
  if (view.isError) return <QueryError error={view.error} onRetry={() => void view.refetch()} what="the blueprint transcription" />
  return <Editor key={view.data.revision} job={job} view={view.data} editable={editable && view.data.status === 'analyzed'} />
}

function Editor({ job, view, editable }: { job: Job; view: View; editable: boolean }) {
  const { workspace } = useWorkspace()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [t, setT] = useState<Transcription>(() => structuredClone(view.transcription))
  const [dirty, setDirty] = useState(false)
  const [message, setMessage] = useState<{ tone: 'danger' | 'success' | 'info'; text: string } | null>(null)
  const [pending, setPending] = useState<string | null>(null)
  const [proposal, setProposal] = useState<Transcription | null>(null)
  const report = view.report
  const roles = report.requiredMappings?.groups ?? []
  const groups = useGroups(workspace.id, editable && roles.length > 0)
  const [key, setKey] = useState(toKey(report.processId ?? job.fileName.replace(/\.[^.]+$/, '')))
  const [name, setName] = useState(report.processName ?? job.fileName.replace(/\.[^.]+$/, ''))
  const [groupMappings, setGroupMappings] = useState<Record<string, string>>({})
  const [ackInfo, setAckInfo] = useState(false)
  const [ackReview, setAckReview] = useState(false)
  const [openItems, setOpenItems] = useState<string[]>([])
  const page = (n?: number | null) => view.pages.find(p => p.number === n)
  const change = (next: Transcription) => { setT(next); setDirty(true) }
  const patch = (list: ListName, index: number, values: Record<string, unknown>) => {
    const items = [...(t[list] ?? [])] as Record<string, unknown>[]
    items[index] = { ...items[index], ...values }
    change({ ...t, [list]: items })
  }
  const targets = [...(t.steps ?? []), ...(t.decisions ?? []), ...(t.outcomes ?? [])].map(e => ({ id: e.id, name: e.name }))
  const elementCount = (tr: Transcription) => LISTS.reduce((n, { list }) => n + (tr[list]?.length ?? 0), tr.caseType ? 1 : 0)
  const unreviewed = LISTS.reduce((n, { list }) => n + ((t[list] ?? []) as Meta[]).filter(e => e.disposition === 'proposed').length, t.caseType?.disposition === 'proposed' ? 1 : 0)
  const blocking = report.issues.filter(i => (i.severity === 'blocker' || i.severity === 'action') && i.resolutionStatus === 'open')

  const run = async (label: string, action: () => Promise<void>) => {
    setPending(label); setMessage(null)
    try { await action() } catch (cause) {
      setMessage({ tone: 'danger', text: cause instanceof Error ? cause.message : 'The request failed. Nothing was changed.' })
      if (isProblemError(cause)) setOpenItems(cause.openItems.map((i: Issue) => `${i.sourceName ?? i.sourceElementId}: ${i.explanation}`))
    } finally { setPending(null) }
  }
  const save = () => run('save', async () => {
    const saved = await endpoints.saveBlueprintTranscription(workspace.id, job.id, { expectedRevision: view.revision, transcription: t })
    queryClient.setQueryData(['ws', workspace.id, 'migration', job.id, 'blueprint'], saved)
    void queryClient.invalidateQueries({ queryKey: ['ws', workspace.id, 'migration', job.id], exact: true })
  })
  const confirmAll = () => {
    const mark = <T extends Meta>(e: T): T => e.disposition === 'proposed' ? { ...e, disposition: 'mapped' } : e
    const next: Transcription = { ...t, ...(t.caseType ? { caseType: mark(t.caseType) } : {}) }
    for (const { list } of LISTS) (next as Record<string, unknown>)[list] = ((t[list] ?? []) as Meta[]).map(mark)
    change(next)
  }
  const askAi = () => run('ai', async () => {
    const result = await endpoints.proposeBlueprintTranscription(workspace.id, job.id)
    setProposal(result.transcription)
    setMessage({ tone: 'info', text: `The AI proposed ${elementCount(result.transcription)} elements (${result.model}). Nothing was changed. Load it into the editor to review it.` })
  })
  const convert = () => run('convert', async () => {
    setOpenItems([])
    const result = await endpoints.convertMigration(workspace.id, job.id, { workflowKey: key, name, groupMappings, acknowledgeInfo: ackInfo, acknowledgeReview: ackReview, transcriptionSha256: view.transcriptionSha256 })
    queryClient.setQueryData(['ws', workspace.id, 'migration', job.id], { ...job, status: 'converted', convertedWorkflowId: result.workflow.id, report: result.report })
    navigate(`/w/${workspace.id}/workflows/${result.workflow.id}/edit?migration=${job.id}`)
  })

  const common = (list: ListName | 'caseType', index: number, e: Meta) => {
    const set = (values: Record<string, unknown>) => list === 'caseType' ? change({ ...t, caseType: { ...t.caseType!, ...values } }) : patch(list, index, values)
    const id = `${list}-${index}`
    return <>
      <div><Label htmlFor={`${id}-name`}>Name</Label><Input id={`${id}-name`} disabled={!editable} value={e.name} maxLength={200} onChange={ev => set({ name: ev.target.value })} /></div>
      <div><Label htmlFor={`${id}-disposition`}>Disposition</Label>
        <Select id={`${id}-disposition`} disabled={!editable} value={e.disposition} onChange={ev => set({ disposition: ev.target.value })}>{DISPOSITIONS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</Select></div>
      {e.disposition === 'excluded' ? <div><Label htmlFor={`${id}-reason`}>Reason for exclusion</Label><Input id={`${id}-reason`} disabled={!editable} value={e.dispositionReason ?? ''} maxLength={500} onChange={ev => set({ dispositionReason: ev.target.value || undefined })} /></div> : null}
    </>
  }
  const choose = (id: string, label: string, value: string | null | undefined, options: { id: string; name: string }[], onChange: (v: string | undefined) => void) =>
    <div><Label htmlFor={id}>{label}</Label><Select id={id} disabled={!editable} value={value ?? ''} onChange={ev => onChange(ev.target.value || undefined)}>
      <option value="">Not stated</option>{options.map(o => <option key={o.id} value={o.id}>{o.name}</option>)}</Select></div>
  const specific = (list: ListName, index: number, e: Record<string, unknown>) => {
    const id = `${list}-${index}`
    const set = (values: Record<string, unknown>) => patch(list, index, values)
    const opts = (values: string[]) => values.map(v => ({ id: v, name: v }))
    switch (list) {
      case 'fields': return <>{choose(`${id}-type`, 'Type', e.type as string, opts(['string', 'number', 'integer', 'boolean', 'date']), v => set({ type: v }))}
        <Label className="flex items-center gap-2"><input type="checkbox" disabled={!editable} checked={!!e.required} onChange={ev => set({ required: ev.target.checked })} />Required</Label></>
      case 'steps': return <>{choose(`${id}-type`, 'Step type', e.type as string, opts(['approval', 'task', 'automated']), v => set({ type: v }))}
        {choose(`${id}-role`, 'Role', e.role as string, (t.roles ?? []).map(r => ({ id: r.id, name: r.name })), v => set({ role: v }))}
        {choose(`${id}-stage`, 'Stage', e.stage as string, (t.stages ?? []).map(s => ({ id: s.id, name: s.name })), v => set({ stage: v }))}
        {choose(`${id}-next`, 'After approval', e.next as string, targets, v => set({ next: v }))}
        {choose(`${id}-reject`, 'After rejection', e.onReject as string, targets, v => set({ onReject: v }))}</>
      case 'decisions': {
        const rules = (e.rules as NonNullable<Transcription['decisions']>[number]['rules']) ?? []
        const setRule = (i: number, values: Record<string, unknown>) => set({ rules: rules.map((r, j) => j === i ? { ...r, ...values } : r) })
        return <>{rules.map((r, i) => <fieldset key={i} className="space-y-2 rounded border border-border p-2"><legend className="px-1 text-sm">Condition {i + 1}</legend>
          {choose(`${id}-r${i}-field`, 'Field', r.field, (t.fields ?? []).map(f => ({ id: f.id, name: f.name })), v => setRule(i, { field: v }))}
          {choose(`${id}-r${i}-op`, 'Operator', r.operator, opts(['>=', '>', '<=', '<', '==', '!=']), v => setRule(i, { operator: v }))}
          <div><Label htmlFor={`${id}-r${i}-value`}>Value</Label><Input id={`${id}-r${i}-value`} disabled={!editable} value={r.value ?? ''} onChange={ev => setRule(i, { value: ev.target.value || undefined })} /></div>
          {choose(`${id}-r${i}-target`, 'Then go to', r.target, targets, v => setRule(i, { target: v }))}</fieldset>)}
          {editable ? <Button type="button" onClick={() => set({ rules: [...rules, {}] })}>Add condition</Button> : null}
          {choose(`${id}-otherwise`, 'Otherwise go to', e.otherwise as string, targets, v => set({ otherwise: v }))}</>
      }
      case 'slas': return <>{choose(`${id}-step`, 'Step', e.step as string, (t.steps ?? []).map(s => ({ id: s.id, name: s.name })), v => set({ step: v }))}
        <div><Label htmlFor={`${id}-amount`}>Duration</Label><Input id={`${id}-amount`} type="number" min={1} disabled={!editable} value={(e.amount as number | undefined) ?? ''} onChange={ev => set({ amount: ev.target.value ? Number(ev.target.value) : undefined })} /></div>
        {choose(`${id}-unit`, 'Unit', e.unit as string, opts(['minutes', 'hours', 'days', 'workingDays']), v => set({ unit: v }))}</>
      case 'outcomes': return choose(`${id}-type`, 'Outcome type', e.type as string, opts(['success', 'rejected']), v => set({ type: v }))
      case 'integrations': return <div><Label htmlFor={`${id}-target`}>Target system</Label><Input id={`${id}-target`} disabled={!editable} value={(e.target as string | undefined) ?? ''} onChange={ev => set({ target: ev.target.value || undefined })} /></div>
      default: return null
    }
  }
  const source = (e: Meta) => {
    const p = page(e.sourcePage)
    if (!p) return <p className="text-sm text-muted-foreground">No source page reference.</p>
    const excerpt = e.sourceExcerpt ?? ''
    const at = excerpt ? p.text.indexOf(excerpt.replace(/^\s+/, '')) : -1
    return <figure className="space-y-1"><figcaption className="text-xs font-semibold">Source page {p.number}</figcaption>
      <pre className="whitespace-pre-wrap break-words rounded bg-muted p-2 text-xs" aria-label={`Text of source page ${p.number}`}>
        {at < 0 ? p.text : <>{p.text.slice(0, at)}<mark>{p.text.slice(at, at + excerpt.length)}</mark>{p.text.slice(at + excerpt.length)}</>}</pre></figure>
  }
  const card = (list: ListName | 'caseType', index: number, e: Meta) =>
    <article key={`${list}-${index}`} aria-label={`${e.name} (${e.disposition})`} className="grid gap-3 rounded-card border border-border bg-surface p-4 md:grid-cols-2">
      <div className="space-y-2">{e.id ? <p className="font-mono text-xs text-muted-foreground">{e.id}</p> : null}{common(list, index, e)}{list === 'caseType' ? null : specific(list, index, e as unknown as Record<string, unknown>)}</div>
      {source(e)}
    </article>

  return <section className="space-y-5" aria-label="Blueprint transcription review">
    <div className="flex flex-wrap items-center justify-between gap-3"><h2>{job.fileName}</h2><Badge>{`Revision ${view.revision}`}</Badge></div>
    <p className="break-all text-xs text-muted-foreground">Pega Blueprint PDF · {view.pageCount} pages · layout {view.layout.id} {view.layout.revision} · SHA-256 {report.sourceHash} · transcription {view.transcriptionSha256}</p>
    <Alert tone="info" live="off" title="Review is mandatory">{view.layout.id === 'pega-blueprint'
      ? <>The transcription was read with the Pega Blueprint export layout: stages and steps from the lifecycle columns, step kinds from their icons, fields from the field table, personas as roles and Live Data as integrations. The export does not state step roles, routes, rejection paths or outcomes; add them from your process knowledge. </>
      : <>The transcription was read from page text with a generic outline layout, not a parser of Pega&apos;s own Blueprint export. </>}
      Compare every element with its source page, correct it, then mark it reviewed or exclude it with a reason. Only the reviewed transcription is converted.</Alert>
    {view.javascript || view.embeddedFiles ? <Alert tone="warning" title="Active content ignored">This PDF contains {view.javascript ? 'scripts or automatic actions' : ''}{view.javascript && view.embeddedFiles ? ' and ' : ''}{view.embeddedFiles ? 'embedded files' : ''}. They were not run or opened.</Alert> : null}
    <section aria-label="Findings" className="space-y-2"><h3>Findings of the saved revision</h3>
      {blocking.length ? <Alert tone="warning" title={`${blocking.length} finding${blocking.length === 1 ? ' needs' : 's need'} a decision before conversion`}>Missing or ambiguous source semantics are listed; nothing was invented.</Alert> : <Alert tone="success" live="polite">No open blocking findings in the saved revision.</Alert>}
      <ul className="space-y-1 text-sm">{report.issues.filter(i => i.severity !== 'supported').map((i, n) => <li key={n} className="flex flex-wrap gap-2"><Badge tone={i.severity === 'blocker' ? 'danger' : i.severity === 'action' ? 'warning' : 'info'}>{i.severity}</Badge><span className="font-semibold">{i.sourceName ?? i.sourceElementId}</span><span>{i.explanation}</span><span className="font-mono text-xs text-muted-foreground">{i.reasonCode} · {i.sourcePath} · {i.resolutionStatus}</span></li>)}</ul>
    </section>
    {editable ? <div className="flex flex-wrap gap-2">
      <Button type="button" disabled={!unreviewed} onClick={confirmAll}>{`Mark ${unreviewed} unreviewed element${unreviewed === 1 ? '' : 's'} as reviewed`}</Button>
      <Button type="button" variant="primary" loading={pending === 'save'} disabled={!dirty} onClick={() => void save()}>Save transcription</Button>
      <Button type="button" loading={pending === 'ai'} onClick={() => void askAi()}>Ask AI for a proposal</Button>
      {proposal ? <Button type="button" onClick={() => { change(proposal); setProposal(null) }}>Load AI proposal into the editor</Button> : null}
    </div> : null}
    {dirty ? <Alert tone="info" live="polite">Unsaved changes. Save to recheck the findings; conversion uses the saved revision only.</Alert> : null}
    {message ? <Alert tone={message.tone}>{message.text}</Alert> : null}
    {openItems.length ? <ul aria-label="Unresolved conversion issues" className="list-disc space-y-1 pl-5 text-sm">{openItems.map((m, n) => <li key={n}>{m}</li>)}</ul> : null}
    {t.caseType ? <section aria-label="Case type" className="space-y-2"><h3>Case type</h3>{card('caseType', 0, t.caseType)}</section> : null}
    {LISTS.map(({ list, label }) => (t[list]?.length ?? 0) ? <section key={list} aria-label={label} className="space-y-2"><h3>{label}</h3>{((t[list] ?? []) as Meta[]).map((e, i) => card(list, i, e))}</section> : null)}
    {job.convertedWorkflowId ? <Button asChild variant="primary"><Link to={`/w/${workspace.id}/workflows/${job.convertedWorkflowId}/edit?migration=${job.id}`}>Open imported draft</Link></Button>
      : editable ? <form onSubmit={ev => { ev.preventDefault(); void convert() }} className="space-y-4 rounded-card border border-border bg-surface p-5" aria-label="Convert to draft">
        <h2>Map roles and create a draft</h2>
        <Label htmlFor="blueprint-key">Workflow key</Label><Input id="blueprint-key" required value={key} onChange={e => setKey(e.target.value)} />
        <Label htmlFor="blueprint-name">Workflow name</Label><Input id="blueprint-name" required maxLength={200} value={name} onChange={e => setName(e.target.value)} />
        {roles.map(role => <div key={role}><Label htmlFor={`blueprint-group-${role}`}>{`Workspace group for role: ${(view.transcription.roles ?? []).find(r => r.id === role)?.name ?? role}`}</Label>
          <Select id={`blueprint-group-${role}`} value={groupMappings[role] ?? ''} onChange={e => setGroupMappings({ ...groupMappings, [role]: e.target.value })}>
            <option value="">{groups.isPending ? 'Loading workspace groups…' : 'Choose workspace group'}</option>{groups.data?.items.map(g => <option key={g.key} value={g.key}>{g.name}</option>)}</Select></div>)}
        {groups.isError ? <QueryError error={groups.error} onRetry={() => void groups.refetch()} what="workspace groups" /> : null}
        <Label className="flex items-start gap-2"><input type="checkbox" checked={ackInfo} onChange={e => setAckInfo(e.target.checked)} />I acknowledge the exclusions, ignored page text and informational stages.</Label>
        <Label className="flex items-start gap-2"><input type="checkbox" checked={ackReview} onChange={e => setAckReview(e.target.checked)} />I checked the manually resolved elements with their owner.</Label>
        <Alert tone="info" live="off">Conversion uses saved revision {view.revision} and must pass compiler validation. It creates a draft only; an independent review and a publisher must still approve and publish it.</Alert>
        <Button type="submit" variant="primary" loading={pending === 'convert'} disabled={dirty}>Convert to draft</Button>
      </form> : null}
  </section>
}
