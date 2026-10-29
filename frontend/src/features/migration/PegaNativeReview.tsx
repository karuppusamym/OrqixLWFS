import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import type { components } from '@/api/schema'
import { isProblemError } from '@/api/problem'
import { useConnections, useGroups } from '@/api/queries'
import { useWorkspace } from '@/app/workspace'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input, Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { JobReview } from './JobReview'

type Job = components['schemas']['MigrationJob']
type Archive = components['schemas']['PegaRuleArchive']
type Start = components['schemas']['PegaRuleStartingPoint']
type Result = components['schemas']['PegaRuleResult']
type CaseResult = components['schemas']['PegaCaseDefinitionResult']

const CONVERSION_TONE: Record<string, 'success' | 'info' | 'danger' | 'neutral' | 'warning'> = {
  converted: 'success', review: 'info', blocked: 'danger', notUsed: 'warning', shadowed: 'neutral', supported: 'neutral',
}
const CONVERSION_LABEL: Record<string, string> = {
  converted: 'Converted', review: 'Converted, review', blocked: 'Blocked', notUsed: 'Not used', shadowed: 'Shadowed', supported: 'Used for resolution',
}
const OUTCOME_LABEL: Record<string, string> = {
  selected: 'Effective rule', rulesetNotInApplication: 'Ruleset not in the application', versionNotInApplication: 'Version not in the application',
  notAvailable: 'Not available', withdrawn: 'Withdrawn', maskedByWithdrawn: 'Hidden by a withdrawn version', lowerRanked: 'Lower ranked',
  blocked: 'Blocked (stops resolution)', belowBlocked: 'Below a blocked rule', circumstanced: 'Circumstanced (not evaluated)',
  baseFallback: 'Base rule of a circumstanced key', duplicate: 'Same rank as another rule', overridesFinal: 'Overrides a Final rule',
  finalOverridden: 'Final rule overridden', outsideContext: 'Outside the case class', variant: 'Circumstance or date variant',
}

type Qualifier = { kind?: string; property?: string; value?: string; date?: string; from?: string | null; to?: string | null }
const qualifierText = (q?: Qualifier | null) => !q ? '' : q.kind === 'property' ? `${q.property} = ${q.value}` : q.kind === 'date' ? `${q.property} on or after ${q.date}`
  : q.kind === 'dateRange' ? `from ${q.from ?? 'the beginning'} until ${q.to ?? 'no end'}` : 'several qualifiers'
const short = (hash?: string | null) => (hash ? hash.slice(0, 12) : '—')

/**
 * Native Pega rule archive workbench (docs/build/S112): archive inventory, effective-rule resolution with the shadowed
 * candidates, per-rule conversion results with source and target hashes, explicit assignment mapping, then the shared
 * review and conversion form. Conversion creates a draft only; publication stays a separate action.
 */
export function PegaNativeReview({ job, editable }: { job: Job; editable: boolean }) {
  const { workspace } = useWorkspace()
  const view = useQuery({ queryKey: ['ws', workspace.id, 'migration', job.id, 'pega-rules', job.status], queryFn: () => endpoints.getPegaRuleArchive(workspace.id, job.id) })
  if (view.isPending) return <LoadingBlock label="Decoding the rule archive" />
  if (view.isError) return <QueryError error={view.error} onRetry={() => void view.refetch()} what="the rule archive" />
  return <ArchiveView job={job} archive={view.data} editable={editable} />
}

function ArchiveView({ job, archive, editable }: { job: Job; archive: Archive; editable: boolean }) {
  const [startRule, setStartRule] = useState(archive.convertedStartRule ?? archive.startingPoints[0]?.rule ?? '')
  const start = archive.startingPoints.find(s => s.rule === startRule)
  const refused = archive.entries.filter(e => e.status === 'refused')
  return <section className="space-y-5" aria-label="Native Pega rule archive">
    <Alert tone="info" live="off" title="Synthetic grammar">Rule pages are read with a strict grammar built from Pega's documented page structure and synthetic fixtures. Fidelity against genuine Pega exports still needs customer qualification. Anything outside the grammar is refused with its source path; nothing in the archive is executed.</Alert>
    <dl className="grid gap-x-6 gap-y-1 text-sm sm:grid-cols-[max-content_1fr]">
      <dt className="font-medium">Application</dt><dd>{archive.application ?? 'Not found'}</dd>
      <dt className="font-medium">Ruleset stack</dt><dd>{archive.stack.length ? archive.stack.map(r => `${r.name}:${r.version}`).join(' → ') : 'Unknown'}</dd>
      <dt className="font-medium">Entries</dt><dd>{archive.entries.length} ({archive.entries.filter(e => e.status === 'rule').length} rules, {refused.length} refused)</dd>
      <dt className="font-medium">Archive SHA-256</dt><dd className="break-all font-mono text-xs">{archive.sourceSha256}</dd>
      <dt className="font-medium">Decoder</dt><dd>{archive.decoder}</dd>
    </dl>
    {archive.archiveBlockers.length ? <Alert tone="warning" title="The archive cannot be fully resolved">
      <ul className="list-disc pl-5">{archive.archiveBlockers.map(b => <li key={b.code}>{b.code}{b.paths.length ? `: ${b.paths.join(', ')}` : ''}</li>)}</ul>
    </Alert> : null}
    {archive.startingPoints.length > 1 && !archive.convertedStartRule ? <div>
      <Label htmlFor="pega-start">Starting point to convert</Label>
      <Select id="pega-start" value={startRule} onChange={e => setStartRule(e.target.value)}>
        {archive.startingPoints.map(s => <option key={s.rule} value={s.rule}>{s.kind === 'caseType' ? 'Case type' : s.kind === 'process' ? 'Process' : 'Flow'} {s.label} ({s.rule})</option>)}
      </Select>
    </div> : null}
    {start ? <p className="text-sm">Converting the {start.kind === 'caseType' ? 'case type' : start.kind === 'process' ? 'process flow' : 'starting flow'} <strong>{start.label}</strong> in {start.context}{start.blocked ? ' — blocked until the findings below are resolved.' : '.'}</p> : null}
    <Tabs defaultValue="rules">
      <TabsList>
        <TabsTrigger value="rules">Per-rule results</TabsTrigger>
        <TabsTrigger value="effective">Effective rules</TabsTrigger>
        <TabsTrigger value="inventory">Archive inventory</TabsTrigger>
      </TabsList>
      <TabsContent value="rules">{start ? <RuleResults start={start} recorded={archive.conversion} /> : <p className="text-sm text-muted-foreground">No starting point can be planned; see the archive findings.</p>}</TabsContent>
      <TabsContent value="effective"><EffectiveRules archive={archive} /></TabsContent>
      <TabsContent value="inventory"><Inventory archive={archive} /></TabsContent>
    </Tabs>
    {start?.caseTarget ? <CaseDefinitionPanel key={`case-${start.rule}`} job={job} start={start} archive={archive} editable={editable} /> : null}
    {start ? <Conversion key={start.rule} job={job} start={start} editable={editable} /> : null}
  </section>
}

function RuleResults({ start, recorded }: { start: Start; recorded: Archive['conversion'] }) {
  const [filter, setFilter] = useState('all')
  const rows = start.rules.filter(r => filter === 'all' || r.conversion === filter)
  const counts = start.rules.reduce<Record<string, number>>((c, r) => ({ ...c, [r.conversion]: (c[r.conversion] ?? 0) + 1 }), {})
  const recordedHash = new Map(recorded.map(r => [r.rule, r.targetSha256]))
  return <div className="space-y-3">
    <div className="flex flex-wrap items-end gap-3">
      <div><Label htmlFor="pega-rule-filter">Show</Label><Select id="pega-rule-filter" value={filter} onChange={e => setFilter(e.target.value)}>
        <option value="all">All rules ({start.rules.length})</option>
        {Object.keys(CONVERSION_LABEL).filter(k => counts[k]).map(k => <option key={k} value={k}>{CONVERSION_LABEL[k]} ({counts[k]})</option>)}
      </Select></div>
      {recorded.length ? <p className="text-sm text-muted-foreground">Recorded at conversion: {recorded.length} rules.</p> : null}
    </div>
    <div className="overflow-x-auto"><table className="w-full text-left text-sm">
      <thead><tr><th scope="col" className="p-2">Rule</th><th scope="col" className="p-2">Version</th><th scope="col" className="p-2">Resolution</th><th scope="col" className="p-2">Result</th><th scope="col" className="p-2">Target</th><th scope="col" className="p-2">Source SHA-256</th><th scope="col" className="p-2">Target SHA-256</th></tr></thead>
      <tbody>{rows.map(r => <RuleRow key={r.rule} r={r} recorded={recordedHash.get(r.rule)} />)}</tbody>
    </table></div>
  </div>
}

function RuleRow({ r, recorded }: { r: Result; recorded?: string | null }) {
  return <tr className="border-t border-border align-top">
    <td className="p-2"><div className="font-medium">{r.ruleType} {r.className}.{r.ruleName}</div><div className="break-all font-mono text-xs text-muted-foreground">{r.rule}</div>
      {r.circumstance ? <div className="text-xs">Variant: {r.circumstance}</div> : null}
      {r.delegatedTo.length ? <div className="text-xs">Delegated to {r.delegatedTo.join(', ')} (provenance only)</div> : null}</td>
    <td className="p-2 whitespace-nowrap">{r.ruleset}:{r.version}<div className="text-xs text-muted-foreground">{r.availability}</div></td>
    <td className="p-2">{OUTCOME_LABEL[r.resolution] ?? r.resolution}</td>
    <td className="p-2"><Badge tone={CONVERSION_TONE[r.conversion]}>{CONVERSION_LABEL[r.conversion] ?? r.conversion}</Badge>{r.reasons.length ? <div className="mt-1 font-mono text-xs text-muted-foreground">{r.reasons.join(', ')}</div> : null}</td>
    <td className="p-2">{r.targetKind ? <><div>{r.targetKind}</div><div className="break-all font-mono text-xs text-muted-foreground">{r.targets.join(', ')}</div></> : '—'}</td>
    <td className="p-2 font-mono text-xs" title={r.sourceSha256}>{short(r.sourceSha256)}</td>
    <td className="p-2 font-mono text-xs" title={recorded ?? r.targetSha256 ?? undefined}>{short(recorded ?? r.targetSha256)}</td>
  </tr>
}

function EffectiveRules({ archive }: { archive: Archive }) {
  if (!archive.contexts.length) return <p className="text-sm text-muted-foreground">No case context could be resolved.</p>
  return <div className="space-y-5">{archive.contexts.map(c => <section key={c.className} className="space-y-2" aria-label={`Rules resolved for ${c.className}`}>
    <h3>{c.className}</h3>
    <p className="text-sm text-muted-foreground">Classes searched, closest first: {c.chain.join(' → ')}</p>
    <div className="overflow-x-auto"><table className="w-full text-left text-sm">
      <thead><tr><th scope="col" className="p-2">Rule</th><th scope="col" className="p-2">Status</th><th scope="col" className="p-2">Candidates</th></tr></thead>
      <tbody>{c.keys.map(k => <tr key={`${k.ruleType} ${k.ruleName}`} className="border-t border-border align-top">
        <td className="p-2">{k.ruleType} {k.ruleName}</td>
        <td className="p-2"><Badge tone={k.status === 'resolved' ? 'success' : 'danger'}>{k.status}</Badge></td>
        <td className="p-2"><ul className="space-y-1">{k.candidates.map(cd => <li key={cd.rule}><span className="break-all font-mono text-xs">{cd.rule}</span> — {OUTCOME_LABEL[cd.outcome] ?? cd.outcome}</li>)}</ul>
          {k.variants?.length ? <p className="mt-1 text-xs">Variants: {k.variants.map(v => `${qualifierText(v as Qualifier)} (${v.rule})`).join('; ')}</p> : null}</td>
      </tr>)}</tbody>
    </table></div>
  </section>)}</div>
}

function Inventory({ archive }: { archive: Archive }) {
  return <div className="overflow-x-auto"><table className="w-full text-left text-sm">
    <thead><tr><th scope="col" className="p-2">Entry</th><th scope="col" className="p-2">Kind</th><th scope="col" className="p-2">Identity or reason</th><th scope="col" className="p-2">SHA-256</th></tr></thead>
    <tbody>{archive.entries.map(e => <tr key={e.path} className="border-t border-border align-top">
      <td className="p-2 break-all font-mono text-xs">{e.path}</td>
      <td className="p-2"><Badge tone={e.status === 'refused' ? 'danger' : 'neutral'}>{e.status}</Badge></td>
      <td className="p-2">{e.status === 'rule' ? `${e.ruleType} ${e.className}.${e.ruleName} ${e.ruleset}:${e.version} (${e.availability}${e.qualifier ? `, ${qualifierText(e.qualifier as Qualifier)}` : ''})` : e.status === 'refused' ? `${e.reason}: ${e.detail ?? ''}` : e.status === 'delegation' ? 'Rule delegation (provenance only)' : e.ruleType ?? '—'}</td>
      <td className="p-2 font-mono text-xs" title={e.sha256}>{short(e.sha256)}</td>
    </tr>)}</tbody>
  </table></div>
}

/** Explicit approve/reject choice per assignment, then the shared mapping and conversion form. */
function Conversion({ job, start, editable }: { job: Job; start: Start; editable: boolean }) {
  const [approving, setApproving] = useState<Record<string, string>>({})
  const routes = Object.fromEntries(start.assignments.filter(a => a.actions.length === 2 && approving[a.id]).map(a => {
    const approved = approving[a.id]!
    const rejected = a.actions.find(x => x.connectorId !== approved)!.connectorId
    return [a.id, { approvedFlowId: approved, rejectedFlowId: rejected }]
  }))
  const converted = !!job.convertedWorkflowId
  const { workspace } = useWorkspace()
  const connections = useConnections(workspace.id, editable && start.calls.length > 0)
  const [connection, setConnection] = useState<Record<string, { connectionRef: string; operation: string; definitionVersion?: number }>>({})
  const mapped = Object.fromEntries(Object.entries(connection).filter(([, c]) => c.connectionRef && c.operation))
  return <div className="space-y-4">
    {start.calls.length && editable && !converted ? <fieldset className="space-y-3 rounded-card border border-border bg-surface p-4">
      <legend className="px-1 font-semibold">Workspace connection for each call</legend>
      <p className="text-sm text-muted-foreground">Each connector becomes a call step. Choose the approved workspace connection and operation it runs; request and result fields come from the connector. The source endpoint is shown for comparison only, and credentials are never migrated.</p>
      {connections.isError ? <QueryError error={connections.error} onRetry={() => void connections.refetch()} what="workspace connections" /> : null}
      {start.calls.map(c => {
        const chosen = connection[c.id]
        const summary = connections.data?.items.find(x => x.key === chosen?.connectionRef)
        return <div key={c.id} className="grid gap-2 sm:grid-cols-2">
          <p className="text-sm sm:col-span-2"><span className="font-semibold">{c.label}</span> ({c.flow}): {c.dataPage ? `data page ${c.dataPage} via ` : ''}connector {c.connector}, {c.method} {c.endpoint ?? ''}. Sends {Object.keys(c.inputMapping).join(', ') || 'nothing'}; keeps {Object.keys(c.resultMapping).join(', ') || 'nothing'}.</p>
          <div><Label htmlFor={`connection-${c.id}`}>Connection for {c.label}</Label>
            <Select id={`connection-${c.id}`} value={chosen?.connectionRef ?? ''} onChange={e => {
              const s = connections.data?.items.find(x => x.key === e.target.value)
              setConnection({ ...connection, [c.id]: { connectionRef: e.target.value, operation: '', ...(s ? { definitionVersion: s.latestVersion } : {}) } })
            }}>
              <option value="">{connections.isPending ? 'Loading connections…' : 'Choose a connection'}</option>
              {connections.data?.items.map(x => <option key={x.key} value={x.key}>{x.name}</option>)}
            </Select></div>
          <div><Label htmlFor={`operation-${c.id}`}>Operation for {c.label}</Label>
            <Select id={`operation-${c.id}`} value={chosen?.operation ?? ''} disabled={!summary} onChange={e => setConnection({ ...connection, [c.id]: { ...chosen!, operation: e.target.value } })}>
              <option value="">Choose an operation</option>
              {summary?.operations.map(o => <option key={o.name} value={o.name}>{o.label}</option>)}
            </Select></div>
        </div>
      })}
    </fieldset> : null}
    {start.assignments.length && editable && !converted ? <fieldset className="space-y-3 rounded-card border border-border bg-surface p-4">
      <legend className="px-1 font-semibold">Approval meaning of each assignment</legend>
      <p className="text-sm text-muted-foreground">Each assignment becomes an approval step. Choose the flow action that means approved; the other one means rejected. Nothing is inferred from action names.</p>
      {start.assignments.map(a => <div key={a.id}>
        <Label htmlFor={`approve-${a.id}`}>{a.label} ({a.flow}, work queue {a.workQueue})</Label>
        <Select id={`approve-${a.id}`} value={approving[a.id] ?? ''} onChange={e => setApproving({ ...approving, [a.id]: e.target.value })} disabled={a.actions.length !== 2}>
          <option value="">Choose the approving flow action</option>
          {a.actions.map(x => <option key={x.connectorId} value={x.connectorId}>{x.flowAction} (connector {x.connectorId})</option>)}
        </Select>
      </div>)}
    </fieldset> : null}
    {start.subflows.length && editable && !converted ? <section className="space-y-2 rounded-card border border-border bg-surface p-4" aria-label="Child workflows to map">
      <h3>Child workflows to map</h3>
      <p className="text-sm text-muted-foreground">Each subflow runs as a separate child request. Publish the child workflow first (a starting flow of this archive can be converted on its own), then map every call below under child workflow mappings with its workflowRef, inputMapping and resultMapping.</p>
      <ul className="list-disc pl-5 text-sm">{start.subflows.map(s => <li key={s.id}><span className="font-mono">{s.id}</span>: subflow {s.flow} in {s.source}</li>)}</ul>
    </section> : null}
    <JobReview job={{ ...job, report: start.report }} editable={editable} native={{ pegaStartRule: start.rule, approvalRoutes: routes, connectionMappings: mapped }} />
  </div>
}

/**
 * A case type as a CMMN case definition (docs/build/S117): name the published workflow converted from each process flow,
 * choose the groups that plan optional processes and move the case to alternate stages, preview, then create it through
 * the case definition API. Creating it is the publication; nothing is created by the preview.
 */
function CaseDefinitionPanel({ job, start, archive, editable }: { job: Job; start: Start; archive: Archive; editable: boolean }) {
  const { workspace } = useWorkspace()
  const queryClient = useQueryClient()
  const target = start.caseTarget!
  const flows = [...new Set(target.processes.map(p => p.flow))]
  const roleNames = [...target.processes.filter(p => p.optional).map(p => ({ key: p.flow, label: `Group that plans ${p.flow}` })),
    ...target.alternates.map(a => ({ key: a.stageId, label: `Group that moves the case to ${a.label}` }))]
  const groups = useGroups(workspace.id, editable && roleNames.length > 0)
  const [key, setKey] = useState(start.name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60) || 'pega-case')
  const [name, setName] = useState(start.label)
  const [workflows, setWorkflows] = useState<Record<string, string>>({})
  const [roles, setRoles] = useState<Record<string, string>>({})
  const [ack, setAck] = useState(false)
  const [result, setResult] = useState<CaseResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [openItems, setOpenItems] = useState<string[]>([])
  const [pending, setPending] = useState(false)
  const done = archive.caseConversion
  if (done) return <Alert tone="success" title="Converted to a case definition">Case definition {done.caseKey} version {done.caseVersion} was created from {done.startRule} (CMMN SHA-256 {short(done.sourceSha256)}).</Alert>
  const send = async (dryRun: boolean) => {
    setPending(true); setError(null); setOpenItems([])
    try {
      const r = await endpoints.convertPegaCaseDefinition(workspace.id, job.id, { key, name, pegaStartRule: start.rule, workflowMappings: workflows, roleMappings: roles, dryRun, acknowledgeInfo: ack, acknowledgeReview: ack })
      setResult(r)
      if (!dryRun) void queryClient.invalidateQueries({ queryKey: ['ws', workspace.id, 'migration', job.id] })
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'The case definition could not be built.')
      if (isProblemError(cause)) setOpenItems(cause.openItems.map(i => `${i.sourceElementId}: ${i.explanation}`))
    } finally { setPending(false) }
  }
  return <section className="space-y-3 rounded-card border border-border bg-surface p-4" aria-label="Convert to a case definition">
    <h3>Convert to a case definition</h3>
    <p className="text-sm text-muted-foreground">Stages become CMMN stages, each process a workflow task that runs the published workflow converted from that flow, optional processes discretionary items and alternate stages stages entered by a user event. Deadlines become timers that reach a milestone. Convert and publish each process flow first (they are starting points of this archive).</p>
    <ul className="list-disc pl-5 text-sm">{target.processes.map(p => <li key={p.taskId}>{p.stageLabel}: {p.flow}{p.optional ? ' (optional)' : ''}{p.alternate ? ' (alternate stage)' : ''}</li>)}</ul>
    {editable ? <>
      <div className="grid gap-3 sm:grid-cols-2">
        <div><Label htmlFor="case-key">Case key</Label><Input id="case-key" value={key} onChange={e => setKey(e.target.value)} /></div>
        <div><Label htmlFor="case-name">Case name</Label><Input id="case-name" value={name} onChange={e => setName(e.target.value)} /></div>
        {flows.map(f => <div key={f}><Label htmlFor={`case-workflow-${f}`}>Published workflow for {f}</Label>
          <Input id={`case-workflow-${f}`} value={workflows[f] ?? ''} placeholder="workflow key" onChange={e => setWorkflows({ ...workflows, [f]: e.target.value.trim() })} /></div>)}
        {roleNames.map(r => <div key={r.key}><Label htmlFor={`case-role-${r.key}`}>{r.label}</Label>
          <Select id={`case-role-${r.key}`} value={roles[r.key] ?? ''} onChange={e => setRoles({ ...roles, [r.key]: e.target.value })}>
            <option value="">{groups.isPending ? 'Loading workspace groups…' : 'Choose workspace group'}</option>
            {groups.data?.items.map(g => <option key={g.key} value={g.key}>{g.name}</option>)}
          </Select></div>)}
      </div>
      <Label className="flex items-start gap-2"><input type="checkbox" checked={ack} onChange={e => setAck(e.target.checked)} />I reviewed the case definition findings: timers, manual stage completion and the items not reproduced.</Label>
      <div className="flex flex-wrap gap-2">
        <Button onClick={() => void send(true)} loading={pending}>Preview case definition</Button>
        <Button variant="primary" onClick={() => void send(false)} loading={pending} disabled={!result?.dryRun}>Create case definition</Button>
      </div>
    </> : null}
    {error ? <Alert tone="danger">{error}</Alert> : null}
    {openItems.length ? <ul aria-label="Open case definition findings" className="list-disc space-y-1 pl-5 text-sm">{openItems.map((m, i) => <li key={i}>{m}</li>)}</ul> : null}
    {result ? <div className="space-y-2" aria-label="Case definition proposal">
      {result.definition ? <Alert tone="success" title="Case definition created">{result.definition.key} version {result.definition.version} is published and pins the mapped workflows.</Alert>
        : <Alert tone="info" title="Preview">The proposal passed the case designer's checks. Nothing has been created yet.</Alert>}
      <p className="text-sm">Data fields: {result.dataFields.map(f => `${f.label} (${f.type}${f.required ? ', required' : ''})`).join('; ')}</p>
      <p className="text-sm">Workflow tasks: {result.workflowTasks.map(t => t.taskId).join(', ')}</p>
      <details><summary className="text-sm">Generated CMMN (SHA-256 {short(result.sourceSha256)})</summary><pre className="max-h-80 overflow-auto text-xs">{result.sourceXml}</pre></details>
    </div> : null}
  </section>
}
