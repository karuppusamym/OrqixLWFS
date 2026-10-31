import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Plus, Trash2, Users } from 'lucide-react'
import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { keys, useAccessOverview, useWorkflows } from '@/api/queries'
import type { components } from '@/api/schema'
import { useWorkspace } from '@/app/workspace'
import { useAnnounce } from '@/components/common/Announcer'
import { ConfirmDialog } from '@/components/common/ConfirmDialog'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Input, Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { humanize } from '@/lib/text'

type Role = components['schemas']['WorkspaceRole']
type GrantPermission = components['schemas']['PermissionGrant']['permission']
type PrincipalRef = components['schemas']['PrincipalRef']
type Principal = components['schemas']['Principal']
type Group = components['schemas']['Group']

const ROLE_HELP: Record<Role, string> = {
  WORKSPACE_ADMIN: 'Manages who has access. Cannot read requests or approve unless also given those roles.',
  WORKFLOW_DESIGNER: 'Builds and edits workflows, case types and decisions.',
  PUBLISHER: 'Publishes workflow versions and chooses the active one.',
  OPERATOR: 'Watches runs and recovers stuck requests.',
  VIEWER: 'Can look at workflows and runs, read only.',
  APPROVER: 'Can claim and decide tasks for the approval groups they belong to.',
}
const ROLES = Object.keys(ROLE_HELP) as Role[]

/** Where a role came from, in plain words. */
const SOURCE_LABEL: Record<string, string> = {
  seed: 'Initial setup',
  bootstrap: 'Initial setup',
  manual: 'Added by an administrator',
}

const PERMISSION_HELP: Record<GrantPermission, string> = {
  'workflow.start': 'Can start requests',
  'connection.manage': 'Can change connection addresses and test them',
  'message.send': 'Can send messages to waiting requests',
  'document.browse': 'Can browse specifically granted document folders',
  'audit.read': 'Can export control evidence for requests they can access',
  'job.work': 'Can take and complete jobs for external workers',
  'workflow.approve-promotion': 'Can approve or reject requests to promote a release into this workspace',
  'agent.approve-write-tool': 'Can approve or revoke the write tools an AI agent may use in a workflow (never for their own requests)',
}
const PERMISSIONS = Object.keys(PERMISSION_HELP) as GrantPermission[]

const principalKind = (p: { type: string }) => (p.type === 'idpGroup' ? 'Sign-in group' : 'Person')
const principalName = (p: Principal) => p.displayName ?? p.value

/** People already known in this workspace, so an administrator can pick them instead of typing an ID. */
function knownPeople(overview: components['schemas']['AccessOverview']): { id: string; name: string }[] {
  const people = new Map<string, string>()
  const add = (p: Principal) => { if (p.type === 'subject') people.set(p.value, principalName(p)) }
  overview.roleBindings.forEach((b) => add(b.principal))
  overview.grants.forEach((g) => add(g.principal))
  overview.groups.forEach((g) => g.members?.forEach(add))
  return [...people.entries()].map(([id, name]) => ({ id, name })).sort((a, b) => a.name.localeCompare(b.name))
}

/**
 * Settings → Members and groups. Who may do what is decided by roles and extra permissions given to sign-in groups
 * (the groups your identity provider, for example Active Directory, sends at sign-in) or to people. Approval groups are
 * the work queues: workflow and case steps are assigned to a group key, and members of the group see those tasks.
 */
export function AccessPanel() {
  const { workspace } = useWorkspace()
  const query = useAccessOverview(workspace.id)
  if (query.isPending) return <LoadingBlock rows={3} label="Loading access" />
  if (query.isError) return <QueryError error={query.error} onRetry={() => void query.refetch()} what="access settings" />
  const people = knownPeople(query.data)

  return (
    <div className="space-y-8">
      <Alert tone="info" live="off" title="How access works">
        Give roles and approval-group membership to <strong>sign-in groups</strong> (for example your Active Directory groups, sent by the identity provider at sign-in) rather than to individual people.
        A change in the identity provider reaches Orqix at the person&apos;s next sign-in; a change here applies within 60 seconds.
      </Alert>
      <RolesSection ws={workspace.id} bindings={query.data.roleBindings} people={people} />
      <GroupsSection ws={workspace.id} groups={query.data.groups} people={people} />
      <GrantsSection ws={workspace.id} grants={query.data.grants} people={people} />
    </div>
  )
}

function useAccessChange<T>(ws: string, run: (input: T) => Promise<unknown>, done: string) {
  const client = useQueryClient()
  const announce = useAnnounce()
  return useMutation({
    mutationFn: run,
    onSuccess: async () => {
      // The approval-step pickers read the groups list; the overview holds roles, grants and members.
      await Promise.all([client.invalidateQueries({ queryKey: keys.access(ws) }), client.invalidateQueries({ queryKey: keys.groups(ws) })])
      announce(done)
    },
  })
}

/** A sign-in group (by the name the identity provider sends) or a person already known in this workspace. */
function PrincipalFields({ id, value, onChange, people }: { id: string; value: PrincipalRef; onChange: (next: PrincipalRef) => void; people: { id: string; name: string }[] }) {
  return (
    <div className="grid gap-3 sm:grid-cols-[12rem_minmax(0,1fr)]">
      <div className="space-y-1.5">
        <Label htmlFor={`${id}-type`}>Who</Label>
        <Select id={`${id}-type`} value={value.type} onChange={(e) => onChange({ type: e.target.value as PrincipalRef['type'], value: '' })}>
          <option value="idpGroup">Sign-in group</option>
          <option value="subject">Person</option>
        </Select>
      </div>
      {value.type === 'idpGroup' ? (
        <div className="space-y-1.5">
          <Label htmlFor={`${id}-value`}>Sign-in group name</Label>
          <Input id={`${id}-value`} value={value.value} maxLength={256} placeholder="For example: orqix-purchasing-managers" aria-describedby={`${id}-hint`} onChange={(e) => onChange({ ...value, value: e.target.value })} />
          <p id={`${id}-hint`} className="text-xs text-muted-foreground">Exactly as your identity provider sends it in the groups claim, without a path.</p>
        </div>
      ) : (
        <div className="space-y-1.5">
          <Label htmlFor={`${id}-value`}>Person</Label>
          <Select id={`${id}-value`} value={value.value} onChange={(e) => onChange({ ...value, value: e.target.value })}>
            <option value="">Choose a person</option>
            {people.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </Select>
          <p className="text-xs text-muted-foreground">People appear here once they have signed in and hold access in this workspace. Prefer sign-in groups.</p>
        </div>
      )}
    </div>
  )
}

function RolesSection({ ws, bindings, people }: { ws: string; bindings: components['schemas']['RoleBinding'][]; people: { id: string; name: string }[] }) {
  const [adding, setAdding] = useState(false)
  const [role, setRole] = useState<Role>('APPROVER')
  const [principal, setPrincipal] = useState<PrincipalRef>({ type: 'idpGroup', value: '' })
  const [removing, setRemoving] = useState<components['schemas']['RoleBinding'] | null>(null)
  const add = useAccessChange(ws, () => endpoints.createRoleBinding(ws, role, { type: principal.type, value: principal.value.trim() }), 'Role added.')
  const remove = useAccessChange(ws, (id: string) => endpoints.deleteRoleBinding(ws, id), 'Role removed.')

  return (
    <section aria-labelledby="roles-heading" className="space-y-3">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <h2 id="roles-heading">Roles</h2>
          <p className="text-sm text-muted-foreground">What each person or sign-in group may do in this workspace. Someone can hold several roles.</p>
        </div>
        {!adding ? <Button variant="secondary" onClick={() => { add.reset(); setAdding(true) }}><Plus aria-hidden="true" /> Add role</Button> : null}
      </div>
      {adding ? (
        <form aria-label="Add role" className="space-y-3 rounded-card border border-border bg-surface p-4" onSubmit={(e) => { e.preventDefault(); add.mutate(undefined, { onSuccess: () => { setAdding(false); setPrincipal({ type: 'idpGroup', value: '' }) } }) }}>
          <div className="space-y-1.5 sm:max-w-sm">
            <Label htmlFor="role-new">Role</Label>
            <Select id="role-new" value={role} onChange={(e) => setRole(e.target.value as Role)} aria-describedby="role-new-help">
              {ROLES.map((r) => <option key={r} value={r}>{humanize(r)}</option>)}
            </Select>
            <p id="role-new-help" className="text-xs text-muted-foreground">{ROLE_HELP[role]}</p>
          </div>
          <PrincipalFields id="role-principal" value={principal} onChange={setPrincipal} people={people} />
          {add.isError ? <ProblemAlert error={toProblem(add.error)} context={{ action: 'add the role' }} /> : null}
          <div className="flex flex-wrap gap-2">
            <Button type="submit" variant="primary" loading={add.isPending} disabled={!principal.value.trim()}>Add role</Button>
            <Button type="button" variant="ghost" onClick={() => setAdding(false)}>Cancel</Button>
          </div>
        </form>
      ) : null}
      <div className="overflow-x-auto rounded-card border border-border bg-surface">
        <Table>
          <caption className="sr-only">Who holds which role</caption>
          <THead><TR><TH>Person or group</TH><TH>Role</TH><TH>What it allows</TH><TH>Source</TH><TH><span className="sr-only">Actions</span></TH></TR></THead>
          <TBody>
            {bindings.map((b) => (
              <TR key={b.id}>
                <TD className="font-semibold">{principalName(b.principal)}<span className="block text-xs font-normal text-muted-foreground">{principalKind(b.principal)}</span></TD>
                <TD><Badge>{humanize(b.role)}</Badge></TD>
                <TD className="max-w-sm text-muted-foreground">{ROLE_HELP[b.role] ?? ''}</TD>
                <TD className="text-muted-foreground">{SOURCE_LABEL[b.source] ?? humanize(b.source)}</TD>
                <TD><Button size="sm" variant="ghost" aria-label={`Remove ${humanize(b.role)} from ${principalName(b.principal)}`} onClick={() => { remove.reset(); setRemoving(b) }}><Trash2 aria-hidden="true" /> Remove</Button></TD>
              </TR>
            ))}
            {bindings.length === 0 ? <TR><TD colSpan={5} className="text-muted-foreground">No roles are assigned.</TD></TR> : null}
          </TBody>
        </Table>
      </div>
      <ConfirmDialog open={removing !== null} title="Remove this role?" confirmLabel="Remove role" destructive pending={remove.isPending}
        onConfirm={() => removing && remove.mutate(removing.id, { onSuccess: () => setRemoving(null) })} onCancel={() => setRemoving(null)}
        error={remove.isError ? <ProblemAlert error={toProblem(remove.error)} context={{ action: 'remove the role' }} /> : undefined}>
        {removing ? <p>{principalName(removing.principal)} loses the {humanize(removing.role)} role in this workspace within 60 seconds. Other roles they hold are kept.</p> : null}
      </ConfirmDialog>
    </section>
  )
}

function GrantsSection({ ws, grants, people }: { ws: string; grants: components['schemas']['PermissionGrant'][]; people: { id: string; name: string }[] }) {
  const [adding, setAdding] = useState(false)
  const [permission, setPermission] = useState<GrantPermission>('workflow.start')
  const [workflowId, setWorkflowId] = useState('')
  const [principal, setPrincipal] = useState<PrincipalRef>({ type: 'idpGroup', value: '' })
  const [removing, setRemoving] = useState<components['schemas']['PermissionGrant'] | null>(null)
  const workflows = useWorkflows(ws, {}, adding && permission === 'workflow.start')
  const workflowItems = workflows.data?.pages.flatMap((p) => p.items) ?? []
  const workflowName = (id: string | null) => (id ? workflowItems.find((w) => w.id === id)?.name ?? 'One workflow' : 'Every workflow in this workspace')
  const add = useAccessChange(ws, () => endpoints.createPermissionGrant(ws, permission, { type: principal.type, value: principal.value.trim() }, permission === 'workflow.start' && workflowId ? workflowId : null), 'Permission added.')
  const remove = useAccessChange(ws, (id: string) => endpoints.deletePermissionGrant(ws, id), 'Permission removed.')

  return (
    <section aria-labelledby="grants-heading" className="space-y-3">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <h2 id="grants-heading">Extra permissions</h2>
          <p className="text-sm text-muted-foreground">Permissions no role carries, such as starting requests (requesters) or managing connections.</p>
        </div>
        {!adding ? <Button variant="secondary" onClick={() => { add.reset(); setAdding(true) }}><Plus aria-hidden="true" /> Add permission</Button> : null}
      </div>
      {adding ? (
        <form aria-label="Add permission" className="space-y-3 rounded-card border border-border bg-surface p-4" onSubmit={(e) => { e.preventDefault(); add.mutate(undefined, { onSuccess: () => { setAdding(false); setPrincipal({ type: 'idpGroup', value: '' }); setWorkflowId('') } }) }}>
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label htmlFor="grant-new">Permission</Label>
              <Select id="grant-new" value={permission} onChange={(e) => { setPermission(e.target.value as GrantPermission); setWorkflowId('') }}>
                {PERMISSIONS.map((p) => <option key={p} value={p}>{PERMISSION_HELP[p]}</option>)}
              </Select>
            </div>
            {permission === 'workflow.start' ? (
              workflowItems.length ? (
                <div className="space-y-1.5">
                  <Label htmlFor="grant-workflow">Applies to</Label>
                  <Select id="grant-workflow" value={workflowId} onChange={(e) => setWorkflowId(e.target.value)}>
                    <option value="">Every workflow in this workspace</option>
                    {workflowItems.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
                  </Select>
                </div>
              ) : (
                // Administrators without workflow read access cannot list workflows: they can paste an ID instead.
                <div className="space-y-1.5">
                  <Label htmlFor="grant-workflow">One workflow only (optional)</Label>
                  <Input id="grant-workflow" value={workflowId} placeholder="Workflow ID; empty for every workflow" aria-describedby="grant-workflow-hint" onChange={(e) => setWorkflowId(e.target.value.trim())} />
                  <p id="grant-workflow-hint" className="text-xs text-muted-foreground">Copy the ID from the workflow's address. Leave empty to let them start every workflow.</p>
                </div>
              )
            ) : null}
          </div>
          <PrincipalFields id="grant-principal" value={principal} onChange={setPrincipal} people={people} />
          {add.isError ? <ProblemAlert error={toProblem(add.error)} context={{ action: 'add the permission' }} /> : null}
          <div className="flex flex-wrap gap-2">
            <Button type="submit" variant="primary" loading={add.isPending} disabled={!principal.value.trim()}>Add permission</Button>
            <Button type="button" variant="ghost" onClick={() => setAdding(false)}>Cancel</Button>
          </div>
        </form>
      ) : null}
      <div className="overflow-x-auto rounded-card border border-border bg-surface">
        <Table>
          <caption className="sr-only">Permission grants</caption>
          <THead><TR><TH>Person or group</TH><TH>Permission</TH><TH>Applies to</TH><TH><span className="sr-only">Actions</span></TH></TR></THead>
          <TBody>
            {grants.map((g) => (
              <TR key={g.id}>
                <TD className="font-semibold">{principalName(g.principal)}<span className="block text-xs font-normal text-muted-foreground">{principalKind(g.principal)}</span></TD>
                <TD>{PERMISSION_HELP[g.permission] ?? g.permission}</TD>
                <TD className="text-muted-foreground">{workflowName(g.workflowId)}</TD>
                <TD><Button size="sm" variant="ghost" aria-label={`Remove permission ${PERMISSION_HELP[g.permission] ?? g.permission} from ${principalName(g.principal)}`} onClick={() => { remove.reset(); setRemoving(g) }}><Trash2 aria-hidden="true" /> Remove</Button></TD>
              </TR>
            ))}
            {grants.length === 0 ? <TR><TD colSpan={4} className="text-muted-foreground">No extra permissions are granted.</TD></TR> : null}
          </TBody>
        </Table>
      </div>
      <ConfirmDialog open={removing !== null} title="Remove this permission?" confirmLabel="Remove permission" destructive pending={remove.isPending}
        onConfirm={() => removing && remove.mutate(removing.id, { onSuccess: () => setRemoving(null) })} onCancel={() => setRemoving(null)}
        error={remove.isError ? <ProblemAlert error={toProblem(remove.error)} context={{ action: 'remove the permission' }} /> : undefined}>
        {removing ? <p>{principalName(removing.principal)} can no longer {(PERMISSION_HELP[removing.permission] ?? removing.permission).replace(/^Can /, '').toLowerCase()} unless a role or another grant allows it.</p> : null}
      </ConfirmDialog>
    </section>
  )
}

const suggestKey = (name: string) => name.toLowerCase().normalize('NFKD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^[^a-z]+/, '').slice(0, 63).replace(/-+$/, '')

function GroupsSection({ ws, groups, people }: { ws: string; groups: Group[]; people: { id: string; name: string }[] }) {
  const [editing, setEditing] = useState<Group | 'new' | null>(null)
  return (
    <section aria-labelledby="groups-heading" className="space-y-3">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <h2 id="groups-heading">Approval groups (work queues)</h2>
          <p className="text-sm text-muted-foreground">Workflow and case steps are assigned to a group key; members see those tasks in their queue. A group is shared by every workflow in this workspace. Members also need the Approver role to decide tasks.</p>
        </div>
        <Button variant="secondary" onClick={() => setEditing('new')}><Plus aria-hidden="true" /> New approval group</Button>
      </div>
      {groups.length === 0 ? (
        <p className="rounded-card border border-dashed border-input-border bg-surface p-6 text-sm text-muted-foreground">No approval groups exist in this workspace. Create one before assigning workflow steps to it.</p>
      ) : (
        <ul className="grid gap-4 md:grid-cols-2">
          {groups.map((g) => (
            <li key={g.key} className="space-y-2 rounded-card border border-border bg-surface p-4" aria-label={`Approval group ${g.name}`}>
              <div className="flex items-start justify-between gap-2">
                <div className="min-w-0">
                  <h3 className="flex items-center gap-2"><Users className="size-4 text-muted-foreground" aria-hidden="true" />{g.name}</h3>
                  <p className="font-mono text-xs text-muted-foreground">{g.key}</p>
                </div>
                <Button size="sm" variant="secondary" aria-label={`Edit ${g.name}`} onClick={() => setEditing(g)}>Edit</Button>
              </div>
              {g.members && g.members.length > 0 ? (
                <ul className="space-y-0.5 text-sm">
                  {g.members.map((m) => <li key={`${m.type}:${m.value}`}>{principalName(m)} <span className="text-xs text-muted-foreground">· {principalKind(m)}</span></li>)}
                </ul>
              ) : (
                <p className="text-sm text-muted-foreground">No members yet: nobody sees this group&apos;s tasks.</p>
              )}
            </li>
          ))}
        </ul>
      )}
      {editing ? <GroupDialog ws={ws} group={editing === 'new' ? null : editing} existingKeys={groups.map((g) => g.key)} people={people} onClose={() => setEditing(null)} /> : null}
    </section>
  )
}

function GroupDialog({ ws, group, existingKeys, people, onClose }: { ws: string; group: Group | null; existingKeys: string[]; people: { id: string; name: string }[]; onClose: () => void }) {
  const [name, setName] = useState(group?.name ?? '')
  const [key, setKey] = useState(group?.key ?? '')
  const [keyEdited, setKeyEdited] = useState(false)
  const [members, setMembers] = useState<Principal[]>(group?.members ?? [])
  const [next, setNext] = useState<PrincipalRef>({ type: 'idpGroup', value: '' })
  const keyTaken = !group && existingKeys.includes(key)
  const save = useAccessChange(ws, () => endpoints.putGroup(ws, key, { name: name.trim(), members: members.map(({ type, value }) => ({ type, value })), ...(group ? { expectedRevision: group.revision } : {}) }), group ? 'Approval group saved.' : 'Approval group created.')
  const addMember = () => {
    const value = next.value.trim()
    if (!value || members.some((m) => m.type === next.type && m.value === value)) return
    const displayName = next.type === 'subject' ? people.find((p) => p.id === value)?.name ?? value : value
    setMembers([...members, { type: next.type, value, displayName }])
    setNext({ type: next.type, value: '' })
  }

  return (
    <Dialog open onClose={onClose} dismissible={!save.isPending} title={group ? `Edit ${group.name}` : 'New approval group'} description="Members see this group's tasks. Use sign-in groups so membership follows your identity provider.">
      <form className="space-y-4" onSubmit={(e) => { e.preventDefault(); save.mutate(undefined, { onSuccess: onClose }) }}>
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="space-y-1.5">
            <Label htmlFor="group-name">Name</Label>
            <Input id="group-name" value={name} maxLength={200} required placeholder="For example: Finance approvers" onChange={(e) => { setName(e.target.value); if (!group && !keyEdited) setKey(suggestKey(e.target.value)) }} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="group-key">Key</Label>
            <Input id="group-key" value={key} readOnly={!!group} minLength={2} maxLength={63} pattern="[a-z][a-z0-9\-]{0,61}[a-z0-9]" required aria-describedby="group-key-hint" onChange={(e) => { setKeyEdited(true); setKey(e.target.value) }} />
            <p id="group-key-hint" className="text-xs text-muted-foreground">{group ? 'Workflow steps refer to this key; it cannot change.' : keyTaken ? 'A group with this key already exists.' : 'Workflow steps refer to this key; it cannot change later.'}</p>
          </div>
        </div>
        <fieldset className="space-y-3 rounded-card border border-border p-3">
          <legend className="px-1 text-sm font-semibold">Members ({members.length})</legend>
          {members.length ? (
            <ul className="space-y-1">
              {members.map((m) => (
                <li key={`${m.type}:${m.value}`} className="flex items-center justify-between gap-2 text-sm">
                  <span>{principalName(m)} <span className="text-xs text-muted-foreground">· {principalKind(m)}</span></span>
                  <Button type="button" size="sm" variant="ghost" aria-label={`Remove member ${principalName(m)}`} onClick={() => setMembers(members.filter((x) => !(x.type === m.type && x.value === m.value)))}>Remove</Button>
                </li>
              ))}
            </ul>
          ) : <p className="text-sm text-muted-foreground">No members yet.</p>}
          <PrincipalFields id="group-member" value={next} onChange={setNext} people={people} />
          <Button type="button" variant="secondary" disabled={!next.value.trim() || members.length >= 200} onClick={addMember}><Plus aria-hidden="true" /> Add member</Button>
        </fieldset>
        {save.isError ? <ProblemAlert error={toProblem(save.error)} context={{ action: group ? 'save the group' : 'create the group' }} /> : null}
        <div className="flex flex-wrap gap-2">
          <Button type="submit" variant="primary" loading={save.isPending} disabled={!name.trim() || key.length < 2 || keyTaken}>{group ? 'Save group' : 'Create group'}</Button>
          <Button type="button" variant="ghost" disabled={save.isPending} onClick={onClose}>Cancel</Button>
        </div>
      </form>
    </Dialog>
  )
}
