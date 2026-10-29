import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import type { components } from '@/api/schema'
import { useWorkspace } from '@/app/workspace'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { JobReview } from './JobReview'

type Job = components['schemas']['MigrationJob']
type Archive = components['schemas']['PegaRuleArchive']
type Start = components['schemas']['PegaRuleStartingPoint']
type Result = components['schemas']['PegaRuleResult']

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
  finalOverridden: 'Final rule overridden', outsideContext: 'Outside the case class',
}
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
        {archive.startingPoints.map(s => <option key={s.rule} value={s.rule}>{s.kind === 'caseType' ? 'Case type' : 'Flow'} {s.label} ({s.rule})</option>)}
      </Select>
    </div> : null}
    {start ? <p className="text-sm">Converting the {start.kind === 'caseType' ? 'case type' : 'starting flow'} <strong>{start.label}</strong> in {start.context}{start.blocked ? ' — blocked until the findings below are resolved.' : '.'}</p> : null}
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
    <td className="p-2"><div className="font-medium">{r.ruleType} {r.className}.{r.ruleName}</div><div className="break-all font-mono text-xs text-muted-foreground">{r.rule}</div></td>
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
        <td className="p-2"><ul className="space-y-1">{k.candidates.map(cd => <li key={cd.rule}><span className="break-all font-mono text-xs">{cd.rule}</span> — {OUTCOME_LABEL[cd.outcome] ?? cd.outcome}</li>)}</ul></td>
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
      <td className="p-2">{e.status === 'rule' ? `${e.ruleType} ${e.className}.${e.ruleName} ${e.ruleset}:${e.version} (${e.availability}${e.circumstanced ? ', circumstanced' : ''})` : e.status === 'refused' ? `${e.reason}: ${e.detail ?? ''}` : e.ruleType ?? '—'}</td>
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
  return <div className="space-y-4">
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
    <JobReview job={{ ...job, report: start.report }} editable={editable} native={{ pegaStartRule: start.rule, approvalRoutes: routes }} />
  </div>
}
