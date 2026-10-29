import { useQueryClient } from '@tanstack/react-query'
import { ArchiveRestore, FolderCog, FolderOpen } from 'lucide-react'
import { useEffect, useState } from 'react'
import * as endpoints from '@/api/endpoints'
import type { ProblemError } from '@/api/problem'
import { useArchivedWorkflows, useWorkflowOrganization, useWorkflowOwnerCandidates } from '@/api/queries'
import type { components } from '@/api/schema'
import { useWorkspace } from '@/app/workspace'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Input, Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

type Folder = components['schemas']['WorkflowFolder']
type WorkflowSummary = components['schemas']['WorkflowSummary']

/** PL01: folders and owners narrow the catalog. They are labels: they never grant or hide access. */
export function useOrganizationRefresh() {
  const client = useQueryClient()
  const { workspace } = useWorkspace()
  return () => Promise.all([
    client.invalidateQueries({ queryKey: ['ws', workspace.id, 'workflows'] }),
    client.invalidateQueries({ queryKey: ['ws', workspace.id, 'workflow-organization'] }),
    client.invalidateQueries({ queryKey: ['ws', workspace.id, 'workflow-owner-candidates'] }),
    client.invalidateQueries({ queryKey: ['ws', workspace.id, 'archived-workflows'] }),
  ])
}

/** Specific wording for the refusals this feature can cause; anything else falls back to the shared problem alert. */
function Refusal({ error, messages }: { error: ProblemError; messages: Partial<Record<string, string>> }) {
  const message = messages[error.code]
  return message ? <p role="alert" className="rounded-card border border-danger-border bg-danger-soft p-3 text-sm text-danger-foreground">{message}</p> : <ProblemAlert error={error} />
}

const FOLDER_REFUSALS = {
  DUPLICATE_KEY: 'A folder with this name already exists. Choose a different name.',
  WORKFLOW_VERSION_CONFLICT: 'This folder was changed by someone else. Close this window and reopen it to see the latest.',
  VALIDATION_FAILED: 'A workspace can have at most 50 folders, and a name needs 1 to 80 characters.',
  MALFORMED_REQUEST: 'A folder name needs 1 to 80 characters.',
}

const ORGANIZE_REFUSALS = {
  VALIDATION_FAILED: 'Choose an owner who can edit workflows in this workspace.',
  WORKFLOW_VERSION_CONFLICT: 'The folder or workflow was changed by someone else. Close this window, reopen it and try again.',
  NOT_FOUND: 'The folder or workflow no longer exists. Close this window and reload the list.',
}

/** Archive refusals name what is still using the workflow (open requests, parent workflows, triggers): show that. */
function archiveRefusal(error: ProblemError): ProblemError | string {
  if (error.code === 'RESOURCE_IN_USE') return error.detail ?? 'Something still uses this workflow. Remove that use and try again.'
  return error
}

export function folderName(folders: Folder[] | undefined, id: string | undefined): string | null {
  if (!id) return null
  return folders?.find((f) => f.id === id)?.name ?? null
}

export function OrganizationFilters({ folder, owner, onChange }: { folder: string; owner: string; onChange: (values: { folder?: string; owner?: string }) => void }) {
  const { workspace } = useWorkspace()
  const org = useWorkflowOrganization(workspace.id)
  const folders = org.data?.folders ?? []
  const owners = org.data?.owners ?? []
  const folderKnown = folder === '' || folder === 'none' || folders.some((f) => f.id === folder)
  const ownerKnown = owner === '' || owners.some((o) => o.id === owner)
  return (
    <>
      <div className="space-y-1.5">
        <Label htmlFor="wf-folder">Folder</Label>
        <Select id="wf-folder" value={folder} className="w-56 max-w-full" onChange={(e) => onChange({ folder: e.target.value })} disabled={org.isPending && !org.isError}>
          <option value="">All folders</option>
          <option value="none">Unfiled{org.data ? ` (${org.data.unfiledCount})` : ''}</option>
          {folders.map((f) => <option key={f.id} value={f.id}>{f.name} ({f.workflowCount})</option>)}
          {!folderKnown && org.data ? <option value={folder}>Folder no longer exists</option> : null}
        </Select>
      </div>
      <div className="space-y-1.5">
        <Label htmlFor="wf-owner">Owner</Label>
        <Select id="wf-owner" value={owner} className="w-56 max-w-full" onChange={(e) => onChange({ owner: e.target.value })} disabled={org.isPending && !org.isError}>
          <option value="">Any owner</option>
          {owners.map((o) => <option key={o.id} value={o.id}>{o.displayName} ({o.workflowCount})</option>)}
          {!ownerKnown && org.data ? <option value={owner}>Owner has no workflows</option> : null}
        </Select>
      </div>
      {org.isError ? <p role="alert" className="basis-full text-sm text-danger">Folders and owners could not be loaded. Search still works.</p> : null}
    </>
  )
}

export function ManageFoldersButton() {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button variant="secondary" size="lg" onClick={() => setOpen(true)}><FolderCog aria-hidden="true" /> Manage folders</Button>
      {open ? <ManageFoldersDialog onClose={() => setOpen(false)} /> : null}
    </>
  )
}

function ManageFoldersDialog({ onClose }: { onClose: () => void }) {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const org = useWorkflowOrganization(ws)
  const refresh = useOrganizationRefresh()
  const [name, setName] = useState('')
  const [editing, setEditing] = useState<{ id: string; name: string; version: number } | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ProblemError | null>(null)
  const folders = org.data?.folders ?? []
  const limit = org.data?.folderLimit ?? 50

  const run = async (action: () => Promise<unknown>, after?: () => void) => {
    setPending(true)
    setError(null)
    try {
      await action()
      after?.()
    } catch (cause) {
      setError(toProblem(cause))
    } finally {
      setPending(false)
      await refresh()
    }
  }

  return (
    <Dialog open onClose={onClose} title="Manage folders" size="lg" dismissible={!pending}
      description="Folders only help people find workflows. They do not change who can see, edit, publish or start a workflow."
      footer={<Button variant="secondary" onClick={onClose} disabled={pending}>Close</Button>}>
      <div className="space-y-4">
        <form className="flex flex-wrap items-end gap-2" onSubmit={(event) => {
          event.preventDefault()
          if (!name.trim()) return
          void run(() => endpoints.createWorkflowFolder(ws, name.trim()), () => setName(''))
        }}>
          <div className="space-y-1.5">
            <Label htmlFor="folder-new">New folder name</Label>
            <Input id="folder-new" value={name} onChange={(e) => setName(e.target.value)} maxLength={80} className="w-64 max-w-full" autoComplete="off" />
          </div>
          <Button type="submit" variant="primary" loading={pending} disabled={!name.trim() || folders.length >= limit}>Create folder</Button>
          {folders.length >= limit ? <p className="basis-full text-sm text-muted-foreground">This workspace has reached the limit of {limit} folders.</p> : null}
        </form>
        {error ? <Refusal error={error} messages={FOLDER_REFUSALS} /> : null}
        {org.isPending ? <p role="status" className="text-sm text-muted-foreground">Loading folders…</p> : folders.length === 0 ? (
          <p className="text-sm text-muted-foreground">No folders yet.</p>
        ) : (
          <ul className="divide-y divide-border rounded-card border border-border">
            {folders.map((f) => (
              <li key={f.id} className="flex flex-wrap items-center justify-between gap-2 p-3">
                {editing?.id === f.id ? (
                  <form className="flex flex-wrap items-end gap-2" onSubmit={(event) => {
                    event.preventDefault()
                    if (!editing.name.trim()) return
                    void run(() => endpoints.renameWorkflowFolder(ws, f.id, { name: editing.name.trim(), expectedVersion: editing.version }), () => setEditing(null))
                  }}>
                    <div className="space-y-1.5">
                      <Label htmlFor={`folder-rename-${f.id}`}>Rename {f.name}</Label>
                      <Input id={`folder-rename-${f.id}`} value={editing.name} onChange={(e) => setEditing({ ...editing, name: e.target.value })} maxLength={80} autoComplete="off" />
                    </div>
                    <Button type="submit" size="sm" variant="primary" loading={pending}>Save</Button>
                    <Button size="sm" variant="ghost" onClick={() => setEditing(null)} disabled={pending}>Cancel</Button>
                  </form>
                ) : (
                  <>
                    <span><FolderOpen aria-hidden="true" className="mr-1.5 inline size-4" /><span className="font-semibold">{f.name}</span> <span className="text-sm text-muted-foreground">{f.workflowCount} {f.workflowCount === 1 ? 'workflow' : 'workflows'}</span></span>
                    <span className="flex gap-2">
                      <Button size="sm" variant="secondary" aria-label={`Rename ${f.name}`} onClick={() => setEditing({ id: f.id, name: f.name, version: f.version })} disabled={pending}>Rename</Button>
                      <Button size="sm" variant="danger" aria-label={`Delete ${f.name}`} disabled={pending} onClick={() => {
                        if (window.confirm(`Delete the folder "${f.name}"? Its ${f.workflowCount} workflows are kept and become unfiled.`)) void run(() => endpoints.deleteWorkflowFolder(ws, f.id))
                      }}>Delete</Button>
                    </span>
                  </>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
    </Dialog>
  )
}

const RESTORE_REFUSALS = {
  WORKFLOW_VERSION_CONFLICT: 'This workflow was changed by someone else. Close this window and reopen it to see the latest.',
  NOT_FOUND: 'This workflow is no longer archived, or no longer exists. Close this window and reload the list.',
}

/** S104: archived workflows can be brought back with their folder, owner, draft and active release. */
export function ArchivedWorkflowsButton() {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button variant="secondary" size="lg" onClick={() => setOpen(true)}><ArchiveRestore aria-hidden="true" /> Archived workflows</Button>
      {open ? <ArchivedWorkflowsDialog onClose={() => setOpen(false)} /> : null}
    </>
  )
}

function ArchivedWorkflowsDialog({ onClose }: { onClose: () => void }) {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const archived = useArchivedWorkflows(ws)
  const refresh = useOrganizationRefresh()
  const [restoring, setRestoring] = useState<string | null>(null)
  const [reason, setReason] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ProblemError | null>(null)
  const [restoredName, setRestoredName] = useState<string | null>(null)
  const items = archived.data?.items ?? []

  const restore = async (id: string, version: number, name: string) => {
    setPending(true)
    setError(null)
    try {
      await endpoints.restoreWorkflow(ws, id, { expectedVersion: version, reason: reason.trim() })
      setRestoredName(name)
      setRestoring(null)
      setReason('')
      await refresh()
    } catch (cause) {
      setError(toProblem(cause))
      await refresh()
    } finally {
      setPending(false)
    }
  }

  return (
    <Dialog open onClose={onClose} title="Archived workflows" dismissible={!pending}
      description="Restoring a workflow puts it back in the catalog with its folder, owner, draft and published version. The same people can start it again."
      footer={<Button variant="secondary" onClick={onClose} disabled={pending}>Close</Button>}>
      <div className="space-y-3">
        {restoredName ? <p role="status" className="rounded-card border border-border bg-surface p-3 text-sm">{restoredName} is back in the catalog.</p> : null}
        {error ? <Refusal error={error} messages={RESTORE_REFUSALS} /> : null}
        {archived.isPending ? <p className="text-sm text-muted-foreground">Loading archived workflows…</p>
          : archived.isError ? <ProblemAlert error={toProblem(archived.error)} />
          : items.length === 0 ? <p className="text-sm text-muted-foreground">No archived workflows in this workspace.</p>
          : (
            <ul className="divide-y divide-border rounded-control border border-border" aria-label="Archived workflows">
              {items.map((w) => (
                <li key={w.id} className="space-y-2 p-3">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span>
                      <span className="font-semibold">{w.name}</span>{' '}
                      <span className="text-sm text-muted-foreground">
                        {w.activeReleaseNumber != null ? `published v${w.activeReleaseNumber}` : 'never published'}
                        {' · archived '}{new Date(w.archivedAt).toLocaleDateString()}
                        {w.archivedBy ? ` by ${w.archivedBy.displayName}` : ''}
                      </span>
                    </span>
                    {restoring === w.id ? null : (
                      <Button size="sm" variant="secondary" aria-label={`Restore ${w.name}`} disabled={pending}
                        onClick={() => { setRestoring(w.id); setReason(''); setError(null); setRestoredName(null) }}>Restore…</Button>
                    )}
                  </div>
                  {restoring === w.id ? (
                    <div className="space-y-2">
                      <Label htmlFor={`restore-reason-${w.id}`}>Why restore it?</Label>
                      <Input id={`restore-reason-${w.id}`} value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} />
                      <div className="flex gap-2">
                        <Button variant="primary" size="sm" loading={pending} disabled={reason.trim().length < 3}
                          onClick={() => void restore(w.id, w.version, w.name)}>Restore {w.name}</Button>
                        <Button variant="ghost" size="sm" disabled={pending} onClick={() => setRestoring(null)}>Cancel</Button>
                      </div>
                    </div>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
        {archived.data?.truncated ? <p className="text-xs text-muted-foreground">Showing the 200 most recently archived.</p> : null}
      </div>
    </Dialog>
  )
}

/** Move one workflow between folders and, for designers, hand it to another owner. */
export function OrganizeWorkflowDialog({ workflow, onClose }: { workflow: WorkflowSummary; onClose: () => void }) {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const org = useWorkflowOrganization(ws)
  const candidates = useWorkflowOwnerCandidates(ws)
  const refresh = useOrganizationRefresh()
  const [folderId, setFolderId] = useState(workflow.folderId ?? '')
  const [ownerId, setOwnerId] = useState(workflow.owner?.id ?? '')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ProblemError | null>(null)
  const [archiveReason, setArchiveReason] = useState('')
  const [confirmArchive, setConfirmArchive] = useState(false)
  // The version as this dialog opened: archiving sends it, so a change someone makes meanwhile is refused (409)
  // instead of being archived unseen. The list rows carry no version.
  const [archiveVersion, setArchiveVersion] = useState<number | null>(null)
  const [archiveError, setArchiveError] = useState<ProblemError | string | null>(null)
  useEffect(() => {
    let live = true
    endpoints.getWorkflow(ws, workflow.id)
      .then((w) => { if (live) setArchiveVersion(w.version) })
      .catch((cause: unknown) => { if (live) setArchiveError(toProblem(cause)) })
    return () => { live = false }
  }, [ws, workflow.id])
  const folders = org.data?.folders ?? []
  const ownerOptions = [...(candidates.data?.items ?? [])]
  if (workflow.owner && !ownerOptions.some((o) => o.id === workflow.owner!.id)) ownerOptions.unshift(workflow.owner)

  const save = async () => {
    setPending(true)
    setError(null)
    try {
      const folderChanged = folderId !== (workflow.folderId ?? '')
      if (folderChanged) {
        const target = folders.find((f) => f.id === folderId)
        await endpoints.setWorkflowFolder(ws, workflow.id, folderId === '' ? { folderId: null } : { folderId, expectedFolderVersion: target!.version })
      }
      if (ownerId !== (workflow.owner?.id ?? '')) {
        // The list rows carry no version; the workflow itself is the source of truth for the concurrency token.
        const { version } = await endpoints.getWorkflow(ws, workflow.id)
        await endpoints.setWorkflowOwner(ws, workflow.id, { ownerSubjectId: ownerId, expectedVersion: version })
      }
      await refresh()
      onClose()
    } catch (cause) {
      setError(toProblem(cause))
      await refresh()
    } finally {
      setPending(false)
    }
  }

  const archive = async () => {
    if (archiveVersion === null) return
    setPending(true)
    setArchiveError(null)
    try {
      await endpoints.archiveWorkflow(ws, workflow.id, { expectedVersion: archiveVersion, reason: archiveReason.trim() })
      await refresh()
      onClose()
    } catch (cause) {
      setArchiveError(archiveRefusal(toProblem(cause)))
      setConfirmArchive(false)
    } finally {
      setPending(false)
    }
  }

  return (
    <Dialog open onClose={onClose} title={`Organize ${workflow.name}`} dismissible={!pending}
      description="Folders and owners help people find this workflow. They do not change who can use it."
      footer={<><Button variant="secondary" onClick={onClose} disabled={pending}>Cancel</Button><Button variant="primary" onClick={() => void save()} loading={pending}>Save</Button></>}>
      <div className="space-y-4">
        <div className="space-y-1.5">
          <Label htmlFor="organize-folder">Folder</Label>
          <Select id="organize-folder" value={folderId} onChange={(e) => setFolderId(e.target.value)} disabled={org.isPending}>
            <option value="">Unfiled</option>
            {folders.map((f) => <option key={f.id} value={f.id}>{f.name}</option>)}
          </Select>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="organize-owner">Owner</Label>
          <Select id="organize-owner" value={ownerId} onChange={(e) => setOwnerId(e.target.value)} disabled={candidates.isPending}>
            {ownerOptions.map((o) => <option key={o.id} value={o.id}>{o.displayName}</option>)}
          </Select>
          <p className="text-xs text-muted-foreground">Only people who can edit workflows in this workspace can own one.</p>
        </div>
        {error ? <Refusal error={error} messages={ORGANIZE_REFUSALS} /> : null}
        <details className="rounded-control border border-border p-3" data-testid="archive-section">
          <summary className="cursor-pointer text-sm font-semibold">Archive this workflow</summary>
          <div className="mt-3 space-y-2">
            <p className="text-xs text-muted-foreground">Archived workflows leave the catalog and can no longer be started. Finished requests stay readable. This is refused while requests are still open. You can restore it later from Archived workflows.</p>
            <Label htmlFor="archive-reason">Why archive it?</Label>
            <Input id="archive-reason" value={archiveReason} maxLength={300} onChange={(e) => { setArchiveReason(e.target.value); setConfirmArchive(false) }} />
            {confirmArchive
              ? <div className="flex gap-2"><Button variant="danger" onClick={() => void archive()} loading={pending}>Archive it</Button><Button variant="ghost" onClick={() => setConfirmArchive(false)} disabled={pending}>Keep it</Button></div>
              : <Button variant="secondary" onClick={() => setConfirmArchive(true)} disabled={pending || archiveVersion === null || archiveReason.trim().length < 3}>Archive…</Button>}
            {typeof archiveError === 'string'
              ? <p role="alert" className="rounded-card border border-danger-border bg-danger-soft p-3 text-sm text-danger-foreground">{archiveError}</p>
              : archiveError ? <Refusal error={archiveError} messages={ORGANIZE_REFUSALS} /> : null}
          </div>
        </details>
      </div>
    </Dialog>
  )
}
