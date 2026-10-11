import { useQueries } from '@tanstack/react-query'
import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { downloadBlob, downloadJson } from '@/lib/download'
import { groupFindings, projectEvidence, projectFindings, projectFindingsCsv, type Project } from './projectFindings'

export function ProjectFindingsPanel({ ws, project }: { ws: string; project: Project }) {
  const queries = useQueries({ queries: project.files.filter(file => file.migrationId).map(file => ({ queryKey: ['ws', ws, 'migration', file.migrationId], queryFn: () => endpoints.getMigration(ws, file.migrationId!), retry: false })) })
  const [by, setBy] = useState<'reasonCode' | 'sourceKind' | 'fileName'>('reasonCode')
  const [severity, setSeverity] = useState('all')
  const [onlyOpen, setOnlyOpen] = useState(false)
  const jobs = queries.flatMap(query => query.data ? [query.data] : [])
  const all = projectFindings(project, jobs)
  const visible = all.filter(f => (severity === 'all' || f.severity === severity) && (!onlyOpen || f.resolutionStatus === 'open'))
  const complete = queries.every(query => query.isSuccess && !query.isFetching)
  return <section aria-label="Cross-file findings" className="mb-6 space-y-3">
    <h2>Cross-file findings</h2>
    <p className="text-sm text-muted-foreground">Adapter analysis only; these findings do not establish source execution equivalence. Exports include every finding, full reports and source hashes regardless of filters. Source bytes and connection mappings are excluded.</p>
    <div className="flex flex-wrap gap-3">
      <Button disabled={!complete} onClick={() => downloadJson(projectEvidence(project, jobs), 'project-findings.json')}>Download project findings JSON</Button>
      <Button disabled={!complete} onClick={() => downloadBlob(new Blob([projectFindingsCsv(project, jobs)], { type: 'text/csv;charset=utf-8' }), 'project-findings.csv')}>Download project findings CSV</Button>
    </div>
    {queries.some(query => query.isPending || query.isFetching) ? <p role="status">Loading all file reports; exports wait for the complete analysis.</p> : null}
    {queries.some(query => query.isError) ? <Alert tone="danger" title="Some file reports could not be loaded">Showing the available findings. Complete exports are disabled.<Button onClick={() => { queries.filter(query => query.isError).forEach(query => void query.refetch()) }}>Retry file reports</Button></Alert> : null}
    <div className="flex flex-wrap gap-3">
      <div><Label htmlFor="project-group">Group project findings by</Label><Select id="project-group" value={by} onChange={e => setBy(e.target.value as typeof by)}><option value="reasonCode">Reason code</option><option value="sourceKind">Source kind</option><option value="fileName">File</option></Select></div>
      <div><Label htmlFor="project-severity">Project finding severity</Label><Select id="project-severity" value={severity} onChange={e => setSeverity(e.target.value)}><option value="all">All severities</option>{['blocker', 'action', 'review', 'info', 'supported'].map(s => <option key={s}>{s}</option>)}</Select></div>
      <Label className="flex items-center gap-2"><input type="checkbox" checked={onlyOpen} onChange={e => setOnlyOpen(e.target.checked)} />Unresolved project findings only</Label>
    </div>
    <p>Showing {visible.length} of {all.length} loaded findings from {jobs.length} of {queries.length} file reports.</p>
    {groupFindings(visible, by).map(([group, findings]) => <details key={group}><summary className="cursor-pointer font-semibold">{group} ({findings.length})</summary><ul className="space-y-3 pl-4">{findings.map((f, index) => <li key={index} className="break-all text-sm"><p className="font-semibold">{f.fileName} · {f.sourceName ?? f.sourceElementId} · {f.severity} · {f.resolutionStatus}</p><p>{f.explanation}</p>{f.suggestedAction ? <p>{f.suggestedAction}</p> : null}<p className="font-mono text-xs">{f.reasonCode} · {f.sourceKind} · {f.sourcePath ?? f.sourceElementId}</p></li>)}</ul></details>)}
    {complete && !visible.length ? <p>No findings match the selected filters.</p> : null}
  </section>
}
