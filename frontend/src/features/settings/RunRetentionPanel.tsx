import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import type { LegalHold, RetentionPolicy, RetentionSweep } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { useAnnounce } from '@/components/common/Announcer'
import { FormField } from '@/components/common/FormField'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input, Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { formatDateTime } from '@/lib/dates'

const policiesKey = (ws: string) => ['ws', ws, 'retention', 'policies'] as const
const holdsKey = (ws: string) => ['ws', ws, 'retention', 'holds'] as const
const sweepsKey = (ws: string) => ['ws', ws, 'retention', 'sweeps'] as const
const archivesKey = (ws: string) => ['ws', ws, 'retention', 'archives'] as const

/**
 * Settings → Run retention (CG-O07, CG-P04, docs/build/S144): keep ended requests of a workflow for N days, then
 * archive each one (evidence pack plus its data, checksum verified) and purge it with its engine history. Legal holds
 * on a request or a workflow stop that. Workspace administrators change it; auditors can read it and download archives.
 */
export function RunRetentionPanel() {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const manage = can('workspace.manage-access')
  const policies = useQuery({ queryKey: policiesKey(ws), queryFn: () => endpoints.listRetentionPolicies(ws) })
  return (
    <section aria-labelledby="run-retention-heading" className="max-w-5xl space-y-6">
      <div>
        <h2 id="run-retention-heading">Run retention</h2>
        <p className="text-sm text-muted-foreground">
          Choose how long ended requests stay in Orqix. When the time is up, each request is archived to the archive store
          as its evidence pack plus its rows (the same layout as the exit export), the stored copy is read back and
          checked, and only then are the request, its tasks, events and engine history removed. The audit log is kept.
          A legal hold stops all of this for a request or a whole workflow.
        </p>
      </div>
      <Alert tone="warning">Purging cannot be undone in Orqix. Run a dry run first; the archive is then the only copy of the request.</Alert>
      {policies.isPending ? (
        <LoadingBlock rows={3} label="Loading retention policies" />
      ) : policies.isError ? (
        <QueryError error={policies.error} onRetry={() => void policies.refetch()} what="the retention policies" />
      ) : (
        <>
          <Policies ws={ws} items={policies.data.items} manage={manage} />
          <Holds ws={ws} manage={manage} />
          <Sweeps ws={ws} manage={manage} />
          <Archives ws={ws} />
        </>
      )}
    </section>
  )
}

function Policies({ ws, items, manage }: { ws: string; items: RetentionPolicy[]; manage: boolean }) {
  const client = useQueryClient()
  const announce = useAnnounce()
  const workflows = useQuery({ queryKey: ['ws', ws, 'workflows', 'retention-picker'], queryFn: () => endpoints.listWorkflows(ws, { limit: 100 }), enabled: manage, retry: false })
  const [workflowId, setWorkflowId] = useState('')
  const [keepDays, setKeepDays] = useState(365)
  const [includeRunData, setIncludeRunData] = useState(true)
  const [includeFiles, setIncludeFiles] = useState(true)
  const save = useMutation({
    mutationFn: () => endpoints.setRetentionPolicy(ws, workflowId, {
      keepDays, includeRunData, includeFiles, expectedVersion: items.find((p) => p.workflowId === workflowId)?.version ?? 0,
    }),
    onSuccess: (saved) => {
      void client.invalidateQueries({ queryKey: policiesKey(ws) })
      announce(`Retention for ${saved.workflowName} saved`)
    },
  })
  const remove = useMutation({
    mutationFn: (p: RetentionPolicy) => endpoints.removeRetentionPolicy(ws, p.workflowId),
    onSuccess: () => void client.invalidateQueries({ queryKey: policiesKey(ws) }),
  })
  return (
    <section aria-labelledby="retention-policies-heading" className="space-y-3">
      <h3 id="retention-policies-heading">Policies</h3>
      {items.length > 0 ? (
        <Table>
          <THead><TR><TH>Workflow</TH><TH>Keep ended requests</TH><TH>Archive contains</TH><TH>Due now</TH><TH><span className="sr-only">Actions</span></TH></TR></THead>
          <TBody>
            {items.map((p) => (
              <TR key={p.workflowId}>
                <TD>{p.workflowName} {p.workflowHeld ? <Badge tone="warning">Legal hold</Badge> : null}</TD>
                <TD>{p.keepDays} days</TD>
                <TD>Evidence pack{p.includeRunData ? ', request data' : ''}{p.includeFiles ? ', files' : ''}</TD>
                <TD>{p.eligibleNow}</TD>
                <TD>{manage ? <Button variant="ghost" size="sm" onClick={() => remove.mutate(p)} aria-label={`Stop retention for ${p.workflowName}`}>Stop</Button> : null}</TD>
              </TR>
            ))}
          </TBody>
        </Table>
      ) : <p className="text-sm text-muted-foreground">No workflow has a retention policy: ended requests are kept.</p>}
      {manage ? (
        <form className="grid gap-3 rounded-card border border-border p-4 sm:grid-cols-2" onSubmit={(e) => { e.preventDefault(); save.mutate() }}>
          {workflows.data && workflows.data.items.length > 0 ? (
            <FormField label="Workflow" id="retention-workflow">
              {(ids) => (
                <Select id={ids.input} value={workflowId} onChange={(e) => setWorkflowId(e.target.value)}>
                  <option value="">Choose a workflow</option>
                  {workflows.data.items.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
                </Select>
              )}
            </FormField>
          ) : (
            <FormField label="Workflow ID" id="retention-workflow" description="Shown on the workflow's page.">
              {(ids) => <Input id={ids.input} value={workflowId} onChange={(e) => setWorkflowId(e.target.value.trim())} />}
            </FormField>
          )}
          <FormField label="Keep ended requests for (days)" id="retention-days" description="1 to 3,650 days after the request ended.">
            {(ids) => <Input id={ids.input} type="number" min={1} max={3650} value={keepDays} onChange={(e) => setKeepDays(Number(e.target.value))} />}
          </FormField>
          <div className="flex items-center gap-2">
            <Checkbox id="retention-data" checked={includeRunData} onCheckedChange={(c) => setIncludeRunData(c === true)} />
            <Label htmlFor="retention-data">Archive request data (input, output, event data)</Label>
          </div>
          <div className="flex items-center gap-2">
            <Checkbox id="retention-files" checked={includeFiles} onCheckedChange={(c) => setIncludeFiles(c === true)} />
            <Label htmlFor="retention-files">Archive request files</Label>
          </div>
          {save.isError ? <div className="sm:col-span-2"><ProblemAlert error={save.error} /></div> : null}
          <div className="sm:col-span-2"><Button type="submit" disabled={save.isPending || !workflowId}>Save policy</Button></div>
        </form>
      ) : null}
    </section>
  )
}

function Holds({ ws, manage }: { ws: string; manage: boolean }) {
  const client = useQueryClient()
  const announce = useAnnounce()
  const query = useQuery({ queryKey: holdsKey(ws), queryFn: () => endpoints.listLegalHolds(ws) })
  const [target, setTarget] = useState<'run' | 'workflow'>('run')
  const [id, setId] = useState('')
  const [reason, setReason] = useState('')
  const place = useMutation({
    mutationFn: () => endpoints.placeLegalHold(ws, { reason: reason.trim(), runId: target === 'run' ? id.trim() : null, workflowId: target === 'workflow' ? id.trim() : null }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: holdsKey(ws) })
      void client.invalidateQueries({ queryKey: policiesKey(ws) })
      setId('')
      setReason('')
      announce('Legal hold placed')
    },
  })
  const release = useMutation({
    mutationFn: (h: LegalHold) => endpoints.releaseLegalHold(ws, h.id, 'Released in settings'),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: holdsKey(ws) })
      void client.invalidateQueries({ queryKey: policiesKey(ws) })
    },
  })
  return (
    <section aria-labelledby="legal-holds-heading" className="space-y-3">
      <h3 id="legal-holds-heading">Legal holds</h3>
      {query.isError ? <QueryError error={query.error} onRetry={() => void query.refetch()} what="the legal holds" /> : null}
      {query.data && query.data.items.length > 0 ? (
        <Table>
          <THead><TR><TH>Holds</TH><TH>Reason</TH><TH>Placed</TH><TH>Status</TH><TH><span className="sr-only">Actions</span></TH></TR></THead>
          <TBody>
            {query.data.items.map((h) => (
              <TR key={h.id}>
                <TD>{h.workflowId ? `Workflow ${h.workflowName ?? h.workflowId}` : `Request ${h.runReference ?? h.runId}`}</TD>
                <TD>{h.reason}</TD>
                <TD>{formatDateTime(h.placedAt)}</TD>
                <TD>{h.active ? <Badge tone="warning">Active</Badge> : `Released ${h.releasedAt ? formatDateTime(h.releasedAt) : ''}`}</TD>
                <TD>{manage && h.active ? <Button variant="ghost" size="sm" onClick={() => release.mutate(h)} aria-label={`Release the hold on ${h.runReference ?? h.workflowName ?? h.id}`}>Release</Button> : null}</TD>
              </TR>
            ))}
          </TBody>
        </Table>
      ) : query.data ? <p className="text-sm text-muted-foreground">No legal holds.</p> : null}
      {manage ? (
        <form className="grid gap-3 rounded-card border border-border p-4 sm:grid-cols-3" onSubmit={(e) => { e.preventDefault(); place.mutate() }}>
          <FormField label="Hold" id="hold-target">
            {(ids) => (
              <Select id={ids.input} value={target} onChange={(e) => setTarget(e.target.value as 'run' | 'workflow')}>
                <option value="run">One request</option><option value="workflow">A whole workflow</option>
              </Select>
            )}
          </FormField>
          <FormField label={target === 'run' ? 'Request ID' : 'Workflow ID'} id="hold-id">{(ids) => <Input id={ids.input} value={id} onChange={(e) => setId(e.target.value)} />}</FormField>
          <FormField label="Reason" id="hold-reason" description="For example the matter or notice number.">{(ids) => <Input id={ids.input} value={reason} onChange={(e) => setReason(e.target.value)} />}</FormField>
          {place.isError ? <div className="sm:col-span-3"><ProblemAlert error={place.error} /></div> : null}
          <div className="sm:col-span-3"><Button type="submit" disabled={place.isPending || !id.trim() || reason.trim().length < 3}>Place hold</Button></div>
        </form>
      ) : null}
    </section>
  )
}

function SweepSummary({ sweep }: { sweep: RetentionSweep }) {
  return (
    <span>
      {sweep.mode === 'dryRun' ? 'Dry run' : 'Archive and purge'}: {sweep.eligible} due, {sweep.purged} purged, {sweep.held} held, {sweep.skipped} skipped, {sweep.failed} failed
    </span>
  )
}

function Sweeps({ ws, manage }: { ws: string; manage: boolean }) {
  const client = useQueryClient()
  const announce = useAnnounce()
  const query = useQuery({ queryKey: sweepsKey(ws), queryFn: () => endpoints.listRetentionSweeps(ws) })
  const [confirm, setConfirm] = useState(false)
  const run = useMutation({
    mutationFn: (dryRun: boolean) => endpoints.startRetentionSweep(ws, { dryRun, confirm: dryRun ? null : confirm }),
    onSuccess: (sweep) => {
      void client.invalidateQueries({ queryKey: sweepsKey(ws) })
      void client.invalidateQueries({ queryKey: archivesKey(ws) })
      void client.invalidateQueries({ queryKey: policiesKey(ws) })
      setConfirm(false)
      announce(sweep.mode === 'dryRun' ? `Dry run: ${sweep.eligible} due` : `${sweep.purged} requests archived and purged`)
    },
  })
  return (
    <section aria-labelledby="retention-sweeps-heading" className="space-y-3">
      <h3 id="retention-sweeps-heading">Sweeps</h3>
      <p className="text-sm text-muted-foreground">A sweep runs every night. You can also check now with a dry run, or archive and purge now.</p>
      {manage ? (
        <div className="flex flex-wrap items-center gap-3">
          <Button onClick={() => run.mutate(true)} disabled={run.isPending}>Dry run</Button>
          <div className="flex items-center gap-2">
            <Checkbox id="retention-confirm" checked={confirm} onCheckedChange={(c) => setConfirm(c === true)} />
            <Label htmlFor="retention-confirm">I understand purged requests can only be read from their archive</Label>
          </div>
          <Button variant="danger" onClick={() => run.mutate(false)} disabled={run.isPending || !confirm}>Archive and purge now</Button>
        </div>
      ) : null}
      {run.isError ? <ProblemAlert error={run.error} /> : null}
      {query.data && query.data.items.length > 0 ? (
        <ul className="space-y-1 text-sm">
          {query.data.items.map((s) => (
            <li key={s.id}>{formatDateTime(s.startedAt)} · <SweepSummary sweep={s} />{s.status === 'failed' ? ' · failed' : ''}</li>
          ))}
        </ul>
      ) : query.data ? <p className="text-sm text-muted-foreground">No sweeps yet.</p> : null}
    </section>
  )
}

function Archives({ ws }: { ws: string }) {
  const [reference, setReference] = useState('')
  const query = useQuery({ queryKey: [...archivesKey(ws), reference], queryFn: () => endpoints.listRunArchives(ws, reference.trim() || undefined) })
  return (
    <section aria-labelledby="run-archives-heading" className="space-y-3">
      <h3 id="run-archives-heading">Archives</h3>
      <FormField label="Find by request reference" id="archive-reference">{(ids) => <Input id={ids.input} value={reference} onChange={(e) => setReference(e.target.value)} placeholder="ORQ-1001" />}</FormField>
      {query.isError ? <QueryError error={query.error} onRetry={() => void query.refetch()} what="the archives" /> : null}
      {query.data && query.data.items.length > 0 ? (
        <Table>
          <THead><TR><TH>Request</TH><TH>Ended</TH><TH>Archived</TH><TH>Status</TH><TH><span className="sr-only">Download</span></TH></TR></THead>
          <TBody>
            {query.data.items.map((a) => (
              <TR key={a.id}>
                <TD className="font-mono text-xs">{a.runReference}</TD>
                <TD>{formatDateTime(a.endedAt)}</TD>
                <TD>{formatDateTime(a.archivedAt)} ({Math.ceil(a.bytes / 1024)} KB, {a.store})</TD>
                <TD>{a.status === 'purged' ? 'Purged' : 'Archived'}{a.historyStatus === 'pending' ? ', engine history pending' : ''}</TD>
                <TD><a className="text-sm underline" href={endpoints.runArchiveUrl(ws, a.id)} download>Download {a.runReference}</a></TD>
              </TR>
            ))}
          </TBody>
        </Table>
      ) : query.data ? <p className="text-sm text-muted-foreground">No archives.</p> : null}
    </section>
  )
}
