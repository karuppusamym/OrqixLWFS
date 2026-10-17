import { useAccessOverview } from '@/api/queries'
import { useWorkspace } from '@/app/workspace'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { humanize } from '@/lib/text'

const ROLE_HELP: Record<string, string> = {
  WORKSPACE_ADMIN: 'Manages who has access. Cannot read requests or approve unless also given those roles.',
  WORKFLOW_DESIGNER: 'Builds and edits workflows.',
  PUBLISHER: 'Publishes workflow versions and chooses the active one.',
  OPERATOR: 'Watches runs and recovers stuck requests.',
  VIEWER: 'Can look at workflows and runs, read only.',
  APPROVER: 'Can claim and decide tasks for groups they belong to.',
}

/** Where a role came from, in plain words. */
const SOURCE_LABEL: Record<string, string> = {
  seed: 'Initial setup',
  bootstrap: 'Initial setup',
  manual: 'Added by an administrator',
}

const PERMISSION_HELP: Record<string, string> = {
  'workflow.start': 'Can start requests',
  'connection.manage': 'Can change connection addresses and test them',
  'document.browse': 'Can browse specifically granted document folders',
  'audit.read': 'Can export control evidence for requests they can access',
  'job.work': 'Can take and complete jobs for external workers',
}

/** Members, roles and groups, read only in R0 (changes go through the API or seed; editing is R1). */
export function AccessPanel() {
  const { workspace } = useWorkspace()
  const query = useAccessOverview(workspace.id)
  if (query.isPending) return <LoadingBlock rows={3} label="Loading access" />
  if (query.isError) return <QueryError error={query.error} onRetry={() => void query.refetch()} what="access settings" />
  const { roleBindings, grants, groups } = query.data

  return (
    <div className="space-y-8">
      <Alert tone="info">Access is shown here read only. For now, changes are made through the API or by your platform team.</Alert>

      <section aria-labelledby="roles-heading">
        <h2 id="roles-heading" className="mb-3">
          Roles
        </h2>
        <div className="overflow-hidden rounded-card border border-border bg-surface">
          <Table>
            <caption className="sr-only">Who holds which role</caption>
            <THead>
              <TR>
                <TH>Person or group</TH>
                <TH>Role</TH>
                <TH>What it allows</TH>
                <TH>Source</TH>
              </TR>
            </THead>
            <TBody>
              {roleBindings.map((b) => (
                <TR key={b.id}>
                  <TD className="font-semibold">
                    {b.principal.displayName ?? b.principal.value}
                    <span className="block text-xs font-normal text-muted-foreground">{b.principal.type === 'idpGroup' ? 'Sign-in group' : 'Person'}</span>
                  </TD>
                  <TD>
                    <Badge>{humanize(b.role)}</Badge>
                  </TD>
                  <TD className="max-w-sm text-muted-foreground">{ROLE_HELP[b.role] ?? ''}</TD>
                  <TD className="text-muted-foreground">{SOURCE_LABEL[b.source] ?? humanize(b.source)}</TD>
                </TR>
              ))}
              {roleBindings.length === 0 ? (
                <TR>
                  <TD colSpan={4} className="text-muted-foreground">
                    No roles are assigned.
                  </TD>
                </TR>
              ) : null}
            </TBody>
          </Table>
        </div>
      </section>

      <section aria-labelledby="grants-heading">
        <h2 id="grants-heading" className="mb-3">
          Extra permissions
        </h2>
        <div className="overflow-hidden rounded-card border border-border bg-surface">
          <Table>
            <caption className="sr-only">Permission grants</caption>
            <THead>
              <TR>
                <TH>Person or group</TH>
                <TH>Permission</TH>
                <TH>Applies to</TH>
              </TR>
            </THead>
            <TBody>
              {grants.map((g) => (
                <TR key={g.id}>
                  <TD className="font-semibold">{g.principal.displayName ?? g.principal.value}</TD>
                  <TD>{PERMISSION_HELP[g.permission] ?? g.permission}</TD>
                  <TD className="text-muted-foreground">{g.workflowId ? 'One workflow' : 'Every workflow in this workspace'}</TD>
                </TR>
              ))}
              {grants.length === 0 ? (
                <TR>
                  <TD colSpan={3} className="text-muted-foreground">
                    No extra permissions are granted.
                  </TD>
                </TR>
              ) : null}
            </TBody>
          </Table>
        </div>
      </section>

      <section aria-labelledby="groups-heading">
        <h2 id="groups-heading" className="mb-3">
          Approval groups
        </h2>
        {groups.length === 0 ? (
          <p className="rounded-card border border-dashed border-input-border bg-surface p-6 text-sm text-muted-foreground">No approval groups exist in this workspace.</p>
        ) : (
          <ul className="grid gap-4 md:grid-cols-2">
            {groups.map((g) => (
              <li key={g.key} className="rounded-card border border-border bg-surface p-4">
                <h3>{g.name}</h3>
                <p className="mb-2 font-mono text-xs text-muted-foreground">{g.key}</p>
                {g.members && g.members.length > 0 ? (
                  <ul className="list-disc space-y-0.5 pl-5 text-sm">
                    {g.members.map((m) => (
                      <li key={`${m.type}:${m.value}`}>{m.displayName ?? m.value}</li>
                    ))}
                  </ul>
                ) : (
                  <p className="text-sm text-muted-foreground">No members configured.</p>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}
