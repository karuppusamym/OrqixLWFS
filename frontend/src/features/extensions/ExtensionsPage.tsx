import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus, Puzzle } from 'lucide-react'
import { useState } from 'react'
import { createExtension, decideExtensionVersion, getExtensionVersion, listExtensions, submitExtensionVersion, type UiExtension, type UiExtensionVersionSummary } from '@/api/extensions'
import { useWorkspace } from '@/app/workspace'
import { EmptyState } from '@/components/common/EmptyState'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { RelativeTime } from '@/components/common/RelativeTime'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Dialog } from '@/components/ui/dialog'
import { Input, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { STEP_ACTIONS, STEP_LABELS, type ChainAction } from '@/features/pages/model'
import { ExtensionHost } from './ExtensionHost'
import { embeddable, frameSource } from './frame'

const TONE = { pending: 'warning', approved: 'success', rejected: 'danger', revoked: 'danger' } as const
type Dialogs = { kind: 'create' } | { kind: 'submit'; extension: UiExtension } | { kind: 'decide'; extension: UiExtension; version: UiExtensionVersionSummary; decision: 'approve' | 'reject' | 'revoke' } | null

/**
 * UI02 / S111 extension registry. Designers register bundles and submit versions with declared capabilities; a workspace
 * administrator other than the submitter approves, rejects or revokes them. Pages can only publish approved versions.
 */
export function ExtensionsPage() {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const queryClient = useQueryClient()
  const query = useQuery({ queryKey: ['uiExtensions', ws], queryFn: () => listExtensions(ws), enabled: can('workflow.read', 'workspace.manage-access') })
  const [dialog, setDialog] = useState<Dialogs>(null)
  const [selected, setSelected] = useState<{ extension: string; version: number } | null>(null)
  const done = async () => { setDialog(null); await queryClient.invalidateQueries({ queryKey: ['uiExtensions', ws] }) }
  if (!can('workflow.read', 'workspace.manage-access')) return <p role="alert">You do not have permission to read extensions.</p>
  return <>
    <PageHeader title="Extensions" description="Custom components for application pages. Each version runs in a sandboxed frame with only the data and actions a page grants it, after an administrator approves it." actions={can('workflow.edit') ? <Button onClick={() => setDialog({ kind: 'create' })}><Plus aria-hidden="true" /> Register extension</Button> : undefined} />
    {query.isPending ? <LoadingBlock label="Loading extensions" /> : query.isError ? <QueryError error={query.error} onRetry={() => void query.refetch()} what="extensions" />
      : query.data.items.length === 0 ? <EmptyState icon={Puzzle} title="No extensions yet">{can('workflow.edit') ? 'Register an extension, then submit a version for approval.' : 'Designers register extensions here.'}</EmptyState>
        : <div className="grid items-start gap-6 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
          <ul className="space-y-4" aria-label="Registered extensions">{query.data.items.map(extension => <li key={extension.id} className="space-y-2 rounded-card border border-border bg-surface p-4">
            <div className="flex flex-wrap items-center gap-2"><h2 className="mr-auto text-lg font-semibold">{extension.name}</h2>{can('workflow.edit') ? <Button size="sm" variant="secondary" onClick={() => setDialog({ kind: 'submit', extension })}>Submit version</Button> : null}</div>
            {extension.description ? <p className="text-sm text-muted-foreground">{extension.description}</p> : null}
            {extension.versions.length === 0 ? <p className="text-sm text-muted-foreground">No versions yet.</p> : <ul className="space-y-2">{extension.versions.map(version => <li key={version.version} className="rounded border border-border p-2 text-sm">
              <div className="flex flex-wrap items-center gap-2">
                <Button size="sm" variant="ghost" aria-pressed={selected?.extension === extension.id && selected.version === version.version} onClick={() => setSelected({ extension: extension.id, version: version.version })}>Version {version.version}</Button>
                <Badge tone={TONE[version.status]}>{version.status}</Badge>
                <span className="text-muted-foreground">by {version.submittedBy.displayName} <RelativeTime value={version.submittedAt} /></span>
                {query.data.canApprove && version.status === 'pending' ? <><Button size="sm" onClick={() => setDialog({ kind: 'decide', extension, version, decision: 'approve' })}>Approve</Button><Button size="sm" variant="danger" onClick={() => setDialog({ kind: 'decide', extension, version, decision: 'reject' })}>Reject</Button></> : null}
                {query.data.canApprove && version.status === 'approved' ? <Button size="sm" variant="danger" onClick={() => setDialog({ kind: 'decide', extension, version, decision: 'revoke' })}>Revoke</Button> : null}
              </div>
              <p className="mt-1 text-xs text-muted-foreground">Data: {version.capabilities.bindings.join(', ') || 'none'} · Actions: {version.capabilities.actions.map(action => STEP_LABELS[action]).join(', ') || 'none'}</p>
              {version.decidedBy ? <p className="text-xs text-muted-foreground">{version.status} by {version.decidedBy.displayName}: {version.decisionNote}</p> : null}
            </li>)}</ul>}
          </li>)}</ul>
          {selected ? <VersionReview key={`${selected.extension}:${selected.version}`} ws={ws} extensionId={selected.extension} version={selected.version} /> : <p className="text-sm text-muted-foreground">Choose a version to review its source and preview it in the sandbox.</p>}
        </div>}
    {dialog?.kind === 'create' ? <CreateDialog ws={ws} onClose={() => setDialog(null)} onDone={done} /> : null}
    {dialog?.kind === 'submit' ? <SubmitDialog ws={ws} extension={dialog.extension} onClose={() => setDialog(null)} onDone={done} /> : null}
    {dialog?.kind === 'decide' ? <DecideDialog ws={ws} extension={dialog.extension} version={dialog.version} decision={dialog.decision} onClose={() => setDialog(null)} onDone={done} /> : null}
  </>
}

function VersionReview({ ws, extensionId, version }: { ws: string; extensionId: string; version: number }) {
  const detail = useQuery({ queryKey: ['uiExtensionVersion', ws, extensionId, version], queryFn: () => getExtensionVersion(ws, extensionId, version) })
  const runnable = detail.data && (detail.data.status === 'approved' || detail.data.status === 'pending')
  const source = useQuery({ queryKey: ['extensionFrame', ws, extensionId, version, 'review'], queryFn: () => frameSource(ws, extensionId, version), enabled: !!runnable, retry: false })
  if (detail.isPending) return <LoadingBlock label="Loading version" />
  if (detail.isError) return <QueryError error={detail.error} onRetry={() => void detail.refetch()} what="extension version" />
  const sample = Object.fromEntries(detail.data.capabilities.bindings.map(binding => [binding, binding.endsWith('amount') ? 1250 : `Sample ${binding.split('.')[1]}`]))
  return <section className="space-y-3 rounded-card border border-border bg-surface p-4" aria-label="Version review">
    <h2 className="text-lg font-semibold">{detail.data.name} version {detail.data.version} <Badge tone={TONE[detail.data.status]}>{detail.data.status}</Badge></h2>
    <p className="break-all text-xs text-muted-foreground">SHA-256 {detail.data.sha256}. The frame admits only this exact source by hash.</p>
    <div><h3 className="text-sm font-semibold">Source</h3><pre className="max-h-72 overflow-auto rounded bg-surface-muted p-3 text-xs"><code>{detail.data.source}</code></pre></div>
    <div><h3 className="text-sm font-semibold">Sandbox preview with sample data</h3>
      {runnable ? <ExtensionHost label={`${detail.data.name} preview`} source={source.data ?? null} blocked={source.isError ? 'its sandboxed document could not be loaded' : null} data={sample} chains={[]} height={detail.data.height} onRun={async () => ({ completed: false, steps: [] })} />
        : <p className="text-sm text-muted-foreground">A {detail.data.status} version is not served to anyone.</p>}
      <p className="mt-1 text-xs text-muted-foreground">Preview never runs actions. Pages decide which data and chains a component receives; the server checks every action.</p></div>
  </section>
}

function CreateDialog({ ws, onClose, onDone }: { ws: string; onClose: () => void; onDone: () => Promise<void> }) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const submit = async () => { setBusy(true); setError(null); try { await createExtension(ws, { name: name.trim(), ...(description.trim() ? { description: description.trim() } : {}) }); await onDone() } catch (cause) { setError(cause) } finally { setBusy(false) } }
  return <Dialog open onClose={onClose} title="Register extension" footer={<><Button variant="secondary" onClick={onClose} disabled={busy}>Cancel</Button><Button onClick={() => void submit()} disabled={!name.trim() || busy} loading={busy}>Register</Button></>}>
    <div className="space-y-4"><div><Label htmlFor="extension-name">Name</Label><Input id="extension-name" value={name} maxLength={80} onChange={event => setName(event.target.value)} /></div>
      <div><Label htmlFor="extension-description">Description</Label><Textarea id="extension-description" value={description} maxLength={300} onChange={event => setDescription(event.target.value)} /></div>
      {error ? <ProblemAlert error={error} /> : null}</div>
  </Dialog>
}

function SubmitDialog({ ws, extension, onClose, onDone }: { ws: string; extension: UiExtension; onClose: () => void; onDone: () => Promise<void> }) {
  const [source, setSource] = useState("orqix.onData(function (data) {\n  document.getElementById('root').textContent = String(data['task.requestTitle'] || '');\n});")
  const [bindings, setBindings] = useState('task.requestTitle')
  const [actions, setActions] = useState<ChainAction[]>([])
  const [height, setHeight] = useState(160)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const markup = !embeddable(source)
  const submit = async () => { setBusy(true); setError(null); try { await submitExtensionVersion(ws, extension.id, { source, height, capabilities: { bindings: bindings.split('\n').map(line => line.trim()).filter(Boolean), actions } }); await onDone() } catch (cause) { setError(cause) } finally { setBusy(false) } }
  return <Dialog open onClose={onClose} title={`Submit a version of ${extension.name}`} description="The version is fixed once submitted and waits for an administrator's approval." footer={<><Button variant="secondary" onClick={onClose} disabled={busy}>Cancel</Button><Button onClick={() => void submit()} disabled={busy || !source.trim() || markup} loading={busy}>Submit for approval</Button></>}>
    <div className="space-y-4">
      <div><Label htmlFor="extension-source">Bundle source (JavaScript using the orqix bridge)</Label><Textarea id="extension-source" className="font-mono text-xs" rows={8} value={source} onChange={event => setSource(event.target.value)} />
        {markup ? <p className="mt-1 text-sm text-danger" role="alert">Remove script or comment markup from the source.</p> : null}</div>
      <div><Label htmlFor="extension-bindings">Data it may receive (one binding per line)</Label><Textarea id="extension-bindings" rows={3} value={bindings} onChange={event => setBindings(event.target.value)} /><p className="mt-1 text-xs text-muted-foreground">task.requestTitle, request.amount, case.status and similar. A page may grant fewer.</p></div>
      <fieldset className="space-y-1"><legend className="text-sm font-semibold">Actions it may request</legend>{STEP_ACTIONS.map(action => <div key={action} className="flex items-center gap-2"><Checkbox id={`extension-action-${action}`} checked={actions.includes(action)} onCheckedChange={checked => setActions(current => checked === true ? [...current, action] : current.filter(item => item !== action))} /><Label htmlFor={`extension-action-${action}`}>{STEP_LABELS[action]}</Label></div>)}</fieldset>
      <div><Label htmlFor="extension-height">Height in pixels</Label><Input id="extension-height" type="number" min={80} max={800} value={height} onChange={event => setHeight(Number(event.target.value))} /></div>
      {error ? <ProblemAlert error={error} /> : null}
    </div>
  </Dialog>
}

function DecideDialog({ ws, extension, version, decision, onClose, onDone }: { ws: string; extension: UiExtension; version: UiExtensionVersionSummary; decision: 'approve' | 'reject' | 'revoke'; onClose: () => void; onDone: () => Promise<void> }) {
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const verb = { approve: 'Approve', reject: 'Reject', revoke: 'Revoke' }[decision]
  const submit = async () => { setBusy(true); setError(null); try { await decideExtensionVersion(ws, extension.id, version.version, { decision, note: note.trim(), expectedStatus: version.status }); await onDone() } catch (cause) { setError(cause) } finally { setBusy(false) } }
  return <Dialog open onClose={onClose} title={`${verb} ${extension.name} version ${version.version}`} description={decision === 'revoke' ? 'Pages using this version show a fallback at once and cannot be routed again until they pin another approved version.' : 'You cannot decide a version you submitted.'} footer={<><Button variant="secondary" onClick={onClose} disabled={busy}>Cancel</Button><Button variant={decision === 'approve' ? 'primary' : 'danger'} onClick={() => void submit()} disabled={!note.trim() || busy} loading={busy}>{verb}</Button></>}>
    <div className="space-y-4"><p className="text-sm">Data: {version.capabilities.bindings.join(', ') || 'none'}. Actions: {version.capabilities.actions.map(action => STEP_LABELS[action]).join(', ') || 'none'}.</p>
      <div><Label htmlFor="decision-note">Decision note</Label><Textarea id="decision-note" value={note} maxLength={500} onChange={event => setNote(event.target.value)} /></div>
      {error ? <ProblemAlert error={error} /> : null}</div>
  </Dialog>
}
