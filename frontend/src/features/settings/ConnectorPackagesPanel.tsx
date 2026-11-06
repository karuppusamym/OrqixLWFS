import { Package, ShieldAlert, ShieldCheck } from 'lucide-react'
import { useState } from 'react'
import type { ProblemError } from '@/api/problem'
import { useWorkflows } from '@/api/queries'
import { useWorkspace } from '@/app/workspace'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { formatDateTime } from '@/lib/dates'
import {
  EFFECT_LABEL,
  STATUS_LABEL,
  STATUS_TONE,
  VERIFICATION_LABEL,
  fileToBase64,
  useConfigureTrigger,
  useConnectorCatalog,
  useConnectorPackage,
  useConnectorPackages,
  useCreateFromStarter,
  useDecidePackage,
  useImportPackage,
  useInstallPackage,
  usePackageTriggers,
  useRetirePackage,
  type ConnectorPackageDetail,
  type PackageTrigger,
} from './connectorPackagesApi'

/**
 * Connector packages (CG-C03, CG-C04, CG-C05, CG-C10; docs/build/S133). Import a signed .orqpkg, review what it would
 * do (hosts, operations by effect class, data leaving Orqix, auth, triggers, changes against the installed version and
 * where it is used), have a different administrator approve the exact hash, install it, set up its triggers, and see
 * the organization's catalog with outdated pins. Approval needs the grant-only connector.approve; everything else
 * connector.manage. The server enforces every rule; this page only hides what the person cannot do.
 */
export function ConnectorPackagesPanel() {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const visible = can('connector.manage', 'connector.approve', 'connection.read')
  const list = useConnectorPackages(ws, visible)
  const [selected, setSelected] = useState<string | null>(null)
  if (!visible) return null
  return (
    <div className="space-y-8">
      <section aria-labelledby="packages-heading" className="space-y-4">
        <div>
          <h2 id="packages-heading" className="flex items-center gap-2"><Package className="size-5" aria-hidden="true" /> Connector packages</h2>
          <p className="text-sm text-muted-foreground">
            Packages bring ready-made connections, triggers, step templates and starter workflows. Nothing is active until a second
            administrator approves the exact package and someone installs it. Installing never creates credentials.
          </p>
        </div>
        {can('connector.manage') ? <ImportForm onImported={setSelected} /> : null}
        {list.isError ? <QueryError error={list.error} onRetry={() => void list.refetch()} what="the packages" /> : null}
        {list.data ? (
          list.data.items.length === 0 ? (
            <p className="text-sm text-muted-foreground">No package has been imported in this organization yet.</p>
          ) : (
            <div className="overflow-x-auto rounded-card border border-border">
              <Table>
                <caption className="sr-only">Package versions of the organization</caption>
                <THead>
                  <TR><TH>Package</TH><TH>Version</TH><TH>Signature</TH><TH>Status</TH><TH>Here</TH><TH><span className="sr-only">Review</span></TH></TR>
                </THead>
                <TBody>
                  {list.data.items.map((p) => (
                    <TR key={p.id} data-testid={`package-row-${p.packageId}-${p.version}`}>
                      <TD><span className="font-semibold">{p.name}</span><span className="block text-xs text-muted-foreground">{p.packageId} · {p.publisher}</span></TD>
                      <TD>{p.version}</TD>
                      <TD>{p.signatureStatus === 'verified'
                        ? <Badge tone="success"><ShieldCheck aria-hidden="true" /> Verified</Badge>
                        : <Badge tone="warning"><ShieldAlert aria-hidden="true" /> Not verified</Badge>}</TD>
                      <TD><Badge tone={STATUS_TONE[p.status]}>{STATUS_LABEL[p.status]}</Badge></TD>
                      <TD>{p.installStatus === 'active' ? 'Installed' : p.installStatus === 'superseded' ? 'Replaced' : p.installStatus === 'retired' ? 'Retired' : '–'}</TD>
                      <TD><Button size="sm" variant="secondary" onClick={() => setSelected(p.id)} aria-label={`Review ${p.name} ${p.version}`}>Review</Button></TD>
                    </TR>
                  ))}
                </TBody>
              </Table>
            </div>
          )
        ) : null}
        {selected ? <PackageReview id={selected} onClose={() => setSelected(null)} /> : null}
      </section>
      <PackageTriggersSection />
      <CatalogSection />
    </div>
  )
}

function ImportForm({ onImported }: { onImported: (id: string) => void }) {
  const { workspace } = useWorkspace()
  const importer = useImportPackage(workspace.id)
  const [file, setFile] = useState<File | null>(null)
  const [reason, setReason] = useState('')
  const [error, setError] = useState<ProblemError | null>(null)
  const submit = async () => {
    if (!file) return
    setError(null)
    try {
      const archive = await fileToBase64(file)
      const detail = await importer.mutateAsync({ archive, ...(reason.trim() ? { unsignedReason: reason.trim() } : {}) })
      setFile(null)
      setReason('')
      onImported(detail.summary.id)
    } catch (cause) {
      setError(toProblem(cause))
    }
  }
  const unsigned = error?.fieldErrors.some((e) => e.code === 'PKG_UNSIGNED') ?? false
  return (
    <div className="space-y-3 rounded-card border border-border p-4">
      <div className="space-y-1.5">
        <Label htmlFor="package-file">Package file (.orqpkg)</Label>
        <Input id="package-file" type="file" accept=".orqpkg,application/zip" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
      </div>
      {unsigned || reason ? (
        <div className="space-y-1.5">
          <Label htmlFor="package-unsigned-reason">Why import a package that is not signed by a trusted publisher?</Label>
          <Textarea id="package-unsigned-reason" value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} />
          <p className="text-xs text-muted-foreground">Your reason is stored with the package and the import is audited as an override.</p>
        </div>
      ) : null}
      {error ? <ProblemAlert error={error} /> : null}
      <Button variant="primary" disabled={!file || importer.isPending} onClick={() => void submit()}>Import and review</Button>
    </div>
  )
}

function PackageReview({ id, onClose }: { id: string; onClose: () => void }) {
  const { workspace } = useWorkspace()
  const query = useConnectorPackage(workspace.id, id)
  if (query.isPending) return <p className="text-sm text-muted-foreground">Loading the review…</p>
  if (query.isError) return <QueryError error={query.error} onRetry={() => void query.refetch()} what="the package review" />
  return <ReviewBody detail={query.data} onClose={onClose} />
}

function ReviewBody({ detail, onClose }: { detail: ConnectorPackageDetail; onClose: () => void }) {
  const { summary, review, comparison } = detail
  const operations = review.operations ?? []
  const triggers = (review.triggers ?? []) as unknown as Array<{ key: string; label: string; kind: string; action: string; verification?: string; weaker?: boolean; every?: string; message?: string }>
  return (
    <section aria-labelledby="review-heading" className="space-y-5 rounded-card border border-border p-5" data-testid="package-review">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 id="review-heading">{summary.name} {summary.version}</h3>
          <p className="text-xs text-muted-foreground break-all">Package hash {summary.sha256}</p>
          <p className="text-xs text-muted-foreground">Imported by {summary.importedBy.displayName ?? 'someone'} on {formatDateTime(summary.importedAt)}</p>
        </div>
        <Button variant="ghost" size="sm" onClick={onClose}>Close review</Button>
      </div>
      {summary.signatureStatus === 'unsigned' ? (
        <Alert tone="warning" title="Not signed by a trusted publisher">Imported with the reason: {detail.unsignedReason}</Alert>
      ) : null}
      {summary.status === 'retired' ? <Alert tone="info" title="Retired">{detail.retireReason}</Alert> : null}
      <div className="grid gap-4 md:grid-cols-2">
        <div>
          <h4 className="text-sm font-semibold">Where it connects</h4>
          <ul className="text-sm">{(review.hosts ?? []).map((h) => <li key={h.pattern}><code>{h.pattern}</code> — {h.purpose}</li>)}</ul>
          <h4 className="mt-3 text-sm font-semibold">Sign-in methods</h4>
          <p className="text-sm">{(review.authSchemes ?? []).join(', ')}</p>
        </div>
        <div>
          <h4 className="text-sm font-semibold">Data that leaves Orqix</h4>
          <ul className="list-disc pl-5 text-sm">{(review.dataLeavingOrqix ?? []).map((d) => <li key={d}>{d}</li>)}</ul>
        </div>
      </div>
      <div>
        <h4 className="text-sm font-semibold">Operations by effect</h4>
        <div className="overflow-x-auto rounded-card border border-border">
          <Table>
            <caption className="sr-only">Operations of the package</caption>
            <THead><TR><TH>Operation</TH><TH>Request</TH><TH>Effect</TH></TR></THead>
            <TBody>
              {operations.map((o) => (
                <TR key={`${o.connection}.${o.operation}`}>
                  <TD>{o.label}<span className="block text-xs text-muted-foreground">{o.connection}.{o.operation}{o.asynchronous ? ' · finishes later' : ''}</span></TD>
                  <TD><code className="text-xs">{o.method} {o.path}</code></TD>
                  <TD><Badge tone={o.effectClass === 'readOnly' ? 'neutral' : o.effectClass === 'nonIdempotent' ? 'warning' : 'info'}>{EFFECT_LABEL[o.effectClass ?? ''] ?? o.effectClass}</Badge></TD>
                </TR>
              ))}
            </TBody>
          </Table>
        </div>
      </div>
      {triggers.length ? (
        <div>
          <h4 className="text-sm font-semibold">Triggers that can start or resume requests</h4>
          <ul className="space-y-1 text-sm">
            {triggers.map((t) => (
              <li key={t.key} className="flex flex-wrap items-center gap-2">
                <span>{t.label}</span>
                <Badge>{t.kind === 'poll' ? `Checks every ${t.every ?? ''}` : VERIFICATION_LABEL[t.verification ?? ''] ?? t.verification}</Badge>
                {t.weaker ? <Badge tone="warning">Weaker verification</Badge> : null}
                <span className="text-xs text-muted-foreground">{t.action === 'start' ? 'starts a request' : `resumes requests waiting for ${t.message ?? 'a message'}`}</span>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
      <div>
        <h4 className="text-sm font-semibold">Compared with what is installed here</h4>
        {comparison.installedVersion === null ? (
          <p className="text-sm text-muted-foreground">Nothing of this package is installed in this workspace.</p>
        ) : comparison.installedVersion === summary.version ? (
          <p className="text-sm text-muted-foreground">This is the installed version.</p>
        ) : (
          <div className="space-y-1 text-sm" data-testid="package-comparison">
            <p>Installed: {comparison.installedVersion}. {comparison.compatible
              ? 'No breaking change.' : <strong>Some changes can break workflows that use it.</strong>}</p>
            {comparison.removedOperations.length ? <p>Removed: {comparison.removedOperations.join(', ')}</p> : null}
            {comparison.addedOperations.length ? <p>Added: {comparison.addedOperations.join(', ')}</p> : null}
            <ul className="list-disc pl-5">{comparison.changes.map((c) => <li key={c.operation + c.change}>{c.operation}: {c.change}{c.breaking ? ' (breaking)' : ''}</li>)}</ul>
          </div>
        )}
      </div>
      {detail.whereUsed.length ? (
        <div>
          <h4 className="text-sm font-semibold">Used by</h4>
          <ul className="text-sm">
            {detail.whereUsed.map((u) => (
              <li key={u.workflowId + u.connectionKey}>{u.workflowName} — {u.connectionKey}
                {u.releaseVersion !== null ? ` (published release pins version ${u.releaseVersion})` : ''}
                {u.outdated ? <Badge tone="warning" className="ml-2">Update available</Badge> : null}</li>
            ))}
          </ul>
        </div>
      ) : null}
      <ReviewActions detail={detail} />
    </section>
  )
}

function ReviewActions({ detail }: { detail: ConnectorPackageDetail }) {
  const { workspace, can } = useWorkspace()
  const id = detail.summary.id
  const decide = useDecidePackage(workspace.id, id)
  const install = useInstallPackage(workspace.id, id)
  const retire = useRetirePackage(workspace.id, id)
  const starter = useCreateFromStarter(workspace.id, id)
  const [note, setNote] = useState('')
  const [reason, setReason] = useState('')
  const [error, setError] = useState<ProblemError | null>(null)
  const [notice, setNotice] = useState('')
  const run = async (task: () => Promise<string>) => {
    setError(null)
    setNotice('')
    try { setNotice(await task()) } catch (cause) { setError(toProblem(cause)) }
  }
  const { summary } = detail
  const starters = (detail.review.workflowTemplates ?? []) as unknown as Array<{ key: string; label: string }>
  return (
    <div className="space-y-3 border-t border-border pt-4">
      {notice ? <Alert tone="success" live="polite">{notice}</Alert> : null}
      {error ? <ProblemAlert error={error} /> : null}
      {detail.canDecide ? (
        <div className="space-y-2">
          <Label htmlFor="package-decision-note">Decision note</Label>
          <Textarea id="package-decision-note" value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} />
          <p className="text-xs text-muted-foreground">You approve exactly the package with the hash shown above.</p>
          <div className="flex gap-2">
            <Button variant="primary" disabled={!note.trim() || decide.isPending} onClick={() => void run(async () => {
              await decide.mutateAsync({ decision: 'approve', sha256: summary.sha256, note: note.trim(), expectedRevision: summary.revision })
              return `${summary.name} ${summary.version} approved. It can now be installed.`
            })}>Approve this package</Button>
            <Button variant="danger" disabled={!note.trim() || decide.isPending} onClick={() => void run(async () => {
              await decide.mutateAsync({ decision: 'reject', sha256: summary.sha256, note: note.trim(), expectedRevision: summary.revision })
              return `${summary.name} ${summary.version} rejected.`
            })}>Reject</Button>
          </div>
        </div>
      ) : summary.status === 'pending' && detail.canApprove ? (
        <p className="text-sm text-muted-foreground">You imported this package, so another administrator must approve it.</p>
      ) : null}
      {detail.canManage && summary.status === 'approved' && summary.installStatus !== 'active' ? (
        <Button variant="primary" disabled={install.isPending} onClick={() => void run(async () => {
          const result = await install.mutateAsync()
          return `Installed ${result.version}${result.previousVersion ? ` (replacing ${result.previousVersion})` : ''}. Next: bind ${result.connections.map((c) => c.key).join(' and ')} to an address matching ${result.hostsToAllow.join(', ')} in Connections, and allow that host in the egress list.`
        })}>{detail.comparison.installedVersion ? `Upgrade to ${summary.version}` : 'Install in this workspace'}</Button>
      ) : null}
      {summary.installStatus === 'active' && starters.length > 0 && can('workflow.edit') ? (
        <div className="flex flex-wrap gap-2">
          {starters.map((s) => (
            <Button key={s.key} size="sm" disabled={starter.isPending} onClick={() => void run(async () => {
              const key = `${s.key}-${Math.random().toString(36).slice(2, 6)}`
              const wf = await starter.mutateAsync({ templateKey: s.key, key, name: s.label })
              return `Workflow "${wf.name}" created from the starter. Open it in Studio to adapt and publish it.`
            })}>Create workflow: {s.label}</Button>
          ))}
        </div>
      ) : null}
      {detail.canManage && summary.status !== 'retired' ? (
        <details>
          <summary className="cursor-pointer text-sm">Retire this version</summary>
          <div className="mt-2 space-y-2">
            <Label htmlFor="package-retire-reason">Reason</Label>
            <Input id="package-retire-reason" value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} />
            <p className="text-xs text-muted-foreground">No new installs; here its connections take no new use. Published releases keep working until their requests end.</p>
            <Button variant="danger" disabled={!reason.trim() || retire.isPending} onClick={() => void run(async () => {
              await retire.mutateAsync({ reason: reason.trim(), expectedRevision: summary.revision })
              return `${summary.version} retired.`
            })}>Retire {summary.version}</Button>
          </div>
        </details>
      ) : null}
    </div>
  )
}

function PackageTriggersSection() {
  const { workspace, can: has } = useWorkspace()
  const ws = workspace.id
  const visible = has('connector.manage', 'run.operate')
  const triggers = usePackageTriggers(ws, visible)
  const packages = useConnectorPackages(ws, has('connector.manage'))
  const workflows = useWorkflows(ws, {}, has('connector.manage') && has('workflow.read'))
  const configure = useConfigureTrigger(ws)
  const [packageId, setPackageId] = useState('')
  const [triggerKey, setTriggerKey] = useState('')
  const [workflowId, setWorkflowId] = useState('')
  const [secretRef, setSecretRef] = useState('')
  const [issuer, setIssuer] = useState('')
  const [audience, setAudience] = useState('')
  const [error, setError] = useState<ProblemError | null>(null)
  const installedVersion = packages.data?.items.find((p) => p.packageId === packageId && p.installStatus === 'active')
  const detail = useConnectorPackage(ws, installedVersion?.id ?? null)
  if (!visible) return null
  const installed = (packages.data?.items ?? []).filter((p) => p.installStatus === 'active')
  const available = (detail.data?.review.triggers ?? []) as unknown as Array<{ key: string; label: string; kind: string; action: string; verification?: string }>
  const chosen = available.find((t) => t.key === triggerKey)
  const save = async () => {
    setError(null)
    try {
      await configure.mutateAsync({ body: {
        packageId, triggerKey,
        ...(chosen?.action === 'start' ? { workflowId } : {}),
        ...(chosen?.kind === 'webhook' ? { secretRef } : {}),
        ...(chosen?.verification === 'jwt' ? { jwtIssuer: issuer, jwtAudience: audience } : {}),
      } })
      setTriggerKey('')
    } catch (cause) { setError(toProblem(cause)) }
  }
  const toggle = async (t: PackageTrigger) => {
    setError(null)
    try {
      await configure.mutateAsync({ id: t.id, body: { workflowId: t.workflowId, secretRef: t.secretRef, jwtIssuer: t.jwtIssuer,
        jwtAudience: t.jwtAudience, enabled: !t.enabled, expectedVersion: t.version } })
    } catch (cause) { setError(toProblem(cause)) }
  }
  return (
    <section aria-labelledby="package-triggers-heading" className="space-y-3">
      <h2 id="package-triggers-heading">Package triggers</h2>
      <p className="text-sm text-muted-foreground">Webhooks and polling from installed packages. A trigger starts requests or sends messages as the person who set it up.</p>
      {error ? <ProblemAlert error={error} /> : null}
      {triggers.data?.items.length ? (
        <div className="overflow-x-auto rounded-card border border-border">
          <Table>
            <caption className="sr-only">Package triggers and their status</caption>
            <THead><TR><TH>Trigger</TH><TH>Verification or schedule</TH><TH>Last run</TH><TH>Handled</TH><TH>Repeats</TH><TH>Could not use</TH><TH><span className="sr-only">Actions</span></TH></TR></THead>
            <TBody>
              {triggers.data.items.map((t) => (
                <TR key={t.id} data-testid={`package-trigger-${t.triggerKey}`}>
                  <TD>{t.label}<span className="block text-xs text-muted-foreground">{t.packageId} {t.packageVersion}{t.receivePath ? ` · ${t.receivePath}` : ''}</span></TD>
                  <TD>{t.kind === 'poll' ? `Every ${t.every ?? ''}${t.status.watermark ? `, since ${t.status.watermark}` : ''}` : VERIFICATION_LABEL[t.verification ?? ''] ?? t.verification}
                    {t.weaker ? <Badge tone="warning" className="ml-2">Weaker</Badge> : null}</TD>
                  <TD>{t.status.lastRunAt ? formatDateTime(t.status.lastRunAt) : '–'}{t.status.lastErrorCode ? <span className="block text-xs text-danger">{t.status.lastErrorCode}</span> : null}
                    {t.status.lagSeconds ? <span className="block text-xs text-muted-foreground">{t.status.lagSeconds} s behind</span> : null}</TD>
                  <TD>{t.status.recordsHandled}</TD>
                  <TD>{t.status.duplicates}</TD>
                  <TD>{t.status.deadRecords}</TD>
                  <TD>{has('connector.manage') ? <Button size="sm" onClick={() => void toggle(t)}>{t.enabled ? 'Pause' : 'Resume'}</Button> : null}</TD>
                </TR>
              ))}
            </TBody>
          </Table>
        </div>
      ) : <p className="text-sm text-muted-foreground">No package trigger is set up.</p>}
      {has('connector.manage') && installed.length ? (
        <div className="grid gap-3 rounded-card border border-border p-4 md:grid-cols-2">
          <div className="space-y-1.5">
            <Label htmlFor="pt-package">Package</Label>
            <Select id="pt-package" value={packageId} onChange={(e) => { setPackageId(e.target.value); setTriggerKey('') }}>
              <option value="">Choose…</option>
              {installed.map((p) => <option key={p.id} value={p.packageId}>{p.name} {p.version}</option>)}
            </Select>
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="pt-trigger">Trigger</Label>
            <Select id="pt-trigger" value={triggerKey} onChange={(e) => setTriggerKey(e.target.value)} disabled={!available.length}>
              <option value="">Choose…</option>
              {available.map((t) => <option key={t.key} value={t.key}>{t.label}</option>)}
            </Select>
          </div>
          {chosen?.action === 'start' ? (
            <div className="space-y-1.5">
              <Label htmlFor="pt-workflow">Workflow it starts</Label>
              <Select id="pt-workflow" value={workflowId} onChange={(e) => setWorkflowId(e.target.value)}>
                <option value="">Choose…</option>
                {(workflows.data?.pages.flatMap((p) => p.items) ?? []).map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
              </Select>
            </div>
          ) : null}
          {chosen?.kind === 'webhook' ? (
            <div className="space-y-1.5">
              <Label htmlFor="pt-secret">Secret reference</Label>
              <Input id="pt-secret" value={secretRef} onChange={(e) => setSecretRef(e.target.value)} placeholder="servicenow-incident-hook" />
            </div>
          ) : null}
          {chosen?.verification === 'jwt' ? (
            <>
              <div className="space-y-1.5"><Label htmlFor="pt-issuer">Expected issuer</Label><Input id="pt-issuer" value={issuer} onChange={(e) => setIssuer(e.target.value)} /></div>
              <div className="space-y-1.5"><Label htmlFor="pt-audience">Expected audience</Label><Input id="pt-audience" value={audience} onChange={(e) => setAudience(e.target.value)} /></div>
            </>
          ) : null}
          <div className="md:col-span-2">
            <Button variant="primary" disabled={!chosen || configure.isPending} onClick={() => void save()}>Set up trigger</Button>
          </div>
        </div>
      ) : null}
    </section>
  )
}

function CatalogSection() {
  const { workspace } = useWorkspace()
  const catalog = useConnectorCatalog(workspace.id)
  if (!catalog.data?.items.length) return null
  return (
    <section aria-labelledby="catalog-heading" className="space-y-3">
      <h2 id="catalog-heading">Organization catalog</h2>
      {catalog.data.items.map((entry) => (
        <div key={entry.packageId} className="space-y-2 rounded-card border border-border p-4" data-testid={`catalog-${entry.packageId}`}>
          <p className="font-semibold">{entry.name} <span className="text-sm font-normal text-muted-foreground">{entry.packageId} · {entry.publisher}{entry.latestVersion ? ` · latest approved ${entry.latestVersion}` : ''}</span></p>
          <p className="text-sm">Versions: {entry.versions.map((v) => `${v.version} (${STATUS_LABEL[v.status].toLowerCase()}${v.signatureStatus === 'unsigned' ? ', not verified' : ''})`).join(', ')}</p>
          <p className="text-sm">Installed in: {entry.installs.length ? entry.installs.map((i) => `${i.workspaceName} (${i.version}${i.status === 'retired' ? ', retired' : ''})`).join(', ') : 'no workspace'}</p>
          {entry.outdatedPins.length ? (
            <div>
              <p className="text-sm font-semibold">Workflows on an older or retired version</p>
              <ul className="list-disc pl-5 text-sm">
                {entry.outdatedPins.map((p) => (
                  <li key={`${p.workflowId}-${p.connectionKey}-${p.pinnedIn}`}>{p.workspaceName}: {p.workflowName} ({p.pinnedIn === 'release' ? 'published release' : 'draft'}) uses {p.pinnedVersion}{p.retired ? ', retired' : ''}</li>
                ))}
              </ul>
            </div>
          ) : null}
        </div>
      ))}
    </section>
  )
}
