import { ArrowLeft, Building2, FlaskConical, LayoutGrid, LogOut, Plus, ShieldAlert } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { Link, useSearchParams } from 'react-router'
import { signOut } from '@/api/auth'
import { useMe } from '@/api/queries'
import type { components } from '@/api/schema'
import { useAnnounce } from '@/components/common/Announcer'
import { EmptyState } from '@/components/common/EmptyState'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { formatDayYear } from '@/lib/dates'
import { readLastWorkspace } from '@/lib/storage'
import { NewOrganizationDialog } from './NewOrganizationDialog'
import { NewWorkspaceDialog } from './NewWorkspaceDialog'
import { useAdminOrganizations, useAdminWorkspaces } from './queries'

type WorkspaceAdminView = components['schemas']['WorkspaceAdminView']

/**
 * Platform administration (/admin, platform administrators only): the organizations and workspaces of this Orqix
 * installation, and creating them. It lives outside any workspace, so a platform administrator who belongs to no
 * workspace yet can create the first one. Lists show metadata only, never business data.
 */
export function PlatformAdminPage() {
  const meQuery = useMe()
  const [params, setParams] = useSearchParams()
  const me = meQuery.data
  const isAdmin = me?.platformAdmin === true
  const organizations = useAdminOrganizations(isAdmin)
  const workspaces = useAdminWorkspaces(isAdmin)
  const announce = useAnnounce()
  const [created, setCreated] = useState<{ workspace: WorkspaceAdminView; member: boolean } | null>(null)
  const dialog = params.get('new')
  const openDialog = (which: 'workspace' | 'organization' | null) => {
    const next = new URLSearchParams(params)
    if (which) next.set('new', which)
    else next.delete('new')
    setParams(next, { replace: true })
  }

  if (meQuery.isPending) return <AdminFrame><LoadingBlock label="Loading" className="mx-auto max-w-3xl" /></AdminFrame>
  if (meQuery.isError) return <AdminFrame><ProblemAlert error={meQuery.error} onRetry={() => void meQuery.refetch()} className="mx-auto max-w-3xl" /></AdminFrame>
  if (!me) return null

  const back = backTarget(me.workspaces)
  if (!isAdmin) {
    return (
      <AdminFrame back={back}>
        <PageHeader title="Platform administration" />
        <EmptyState icon={ShieldAlert} title="Only platform administrators can open this page.">
          To get a new workspace or to be added to one, ask your platform administrator.
        </EmptyState>
      </AdminFrame>
    )
  }

  const orgItems = organizations.data?.pages.flatMap((p) => p.items) ?? []
  const wsItems = workspaces.data?.pages.flatMap((p) => p.items) ?? []
  const orgName = new Map(orgItems.map((o) => [o.id, o.name]))
  const memberOf = new Set(me.workspaces.map((w) => w.id))

  return (
    <AdminFrame back={back}>
      <PageHeader
        title="Platform administration"
        description="Create organizations and workspaces. The administrators you name for a workspace then add people, roles and approval groups in its Settings."
        actions={
          <Button variant="primary" onClick={() => { setCreated(null); openDialog('workspace') }} disabled={organizations.isPending}>
            <Plus aria-hidden="true" /> New workspace
          </Button>
        }
      />

      {created ? (
        <Alert tone="success" title={`Workspace ${created.workspace.name} is ready`} className="mb-6" data-testid="workspace-created">
          {created.member ? (
            <>
              <p>Next, add people: give roles to sign-in groups and create the approval groups your workflows assign work to.</p>
              <div className="mt-3 flex flex-wrap gap-2">
                <Button asChild variant="primary" size="sm">
                  <Link to={`/w/${created.workspace.id}/settings`}>Add people in Settings</Link>
                </Button>
                <Button asChild variant="secondary" size="sm">
                  <Link to={`/w/${created.workspace.id}`}>Open workspace</Link>
                </Button>
              </div>
            </>
          ) : (
            <p>The administrators you named can open it the next time they sign in. You are not a member, so it does not appear in your workspace list.</p>
          )}
        </Alert>
      ) : me.workspaces.length === 0 ? (
        <Alert tone="info" title="You are not in any workspace yet" className="mb-6">
          Create a workspace and keep <strong>Make me a workspace administrator</strong> ticked to open it and set it up.
        </Alert>
      ) : null}

      <section aria-labelledby="admin-workspaces" className="mb-8 space-y-3">
        <h2 id="admin-workspaces" className="flex items-center gap-2 text-lg"><LayoutGrid className="size-5" aria-hidden="true" /> Workspaces</h2>
        {workspaces.isPending ? (
          <LoadingBlock rows={3} label="Loading workspaces" />
        ) : workspaces.isError ? (
          <QueryError error={workspaces.error} onRetry={() => void workspaces.refetch()} what="the workspaces" />
        ) : wsItems.length === 0 ? (
          <EmptyState icon={LayoutGrid} title="No workspaces yet" action={<Button variant="primary" onClick={() => openDialog('workspace')}><Plus aria-hidden="true" /> New workspace</Button>}>
            Create the first workspace to start building workflows.
          </EmptyState>
        ) : (
          <>
            <div className="overflow-x-auto rounded-card border border-border bg-surface">
              <Table aria-labelledby="admin-workspaces">
                <THead>
                  <TR>
                    <TH scope="col">Workspace</TH>
                    <TH scope="col">Organization</TH>
                    <TH scope="col">Kind</TH>
                    <TH scope="col">Created</TH>
                    <TH scope="col"><span className="sr-only">Open</span></TH>
                  </TR>
                </THead>
                <TBody>
                  {wsItems.map((w) => (
                    <TR key={w.id} data-testid={`admin-workspace-${w.key}`}>
                      <TD>
                        <span className="font-semibold">{w.name}</span>
                        <span className="block font-mono text-xs text-muted-foreground">{w.key}</span>
                      </TD>
                      <TD>{orgName.get(w.organizationId) ?? <span className="text-muted-foreground">Unknown</span>}</TD>
                      <TD>
                        {w.isTest ? <Badge tone="warning"><FlaskConical aria-hidden="true" /> Test</Badge> : <Badge tone="neutral">Live</Badge>}
                        {w.isTest && w.allowSelfApprovalWorkflows ? <span className="mt-1 block text-xs text-muted-foreground">Self-approval allowed</span> : null}
                      </TD>
                      <TD className="whitespace-nowrap">{formatDayYear(w.createdAt)}</TD>
                      <TD className="text-right">
                        {memberOf.has(w.id) ? (
                          <Button asChild size="sm" variant="secondary">
                            <Link to={`/w/${w.id}`} aria-label={`Open ${w.name}`}>Open</Link>
                          </Button>
                        ) : (
                          <span className="text-xs text-muted-foreground">Not a member</span>
                        )}
                      </TD>
                    </TR>
                  ))}
                </TBody>
              </Table>
            </div>
            {workspaces.hasNextPage ? (
              <Button variant="secondary" onClick={() => void workspaces.fetchNextPage()} loading={workspaces.isFetchingNextPage}>Show more workspaces</Button>
            ) : null}
          </>
        )}
      </section>

      <section aria-labelledby="admin-organizations" className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 id="admin-organizations" className="flex items-center gap-2 text-lg"><Building2 className="size-5" aria-hidden="true" /> Organizations</h2>
          <Button variant="secondary" onClick={() => openDialog('organization')}><Plus aria-hidden="true" /> New organization</Button>
        </div>
        {organizations.isPending ? (
          <LoadingBlock rows={2} label="Loading organizations" />
        ) : organizations.isError ? (
          <QueryError error={organizations.error} onRetry={() => void organizations.refetch()} what="the organizations" />
        ) : orgItems.length === 0 ? (
          <p className="text-sm text-muted-foreground">No organizations yet. Creating the first workspace creates one too.</p>
        ) : (
          <>
            <div className="overflow-x-auto rounded-card border border-border bg-surface">
              <Table aria-labelledby="admin-organizations">
                <THead>
                  <TR>
                    <TH scope="col">Organization</TH>
                    <TH scope="col">Workspaces</TH>
                    <TH scope="col">Created</TH>
                  </TR>
                </THead>
                <TBody>
                  {orgItems.map((o) => (
                    <TR key={o.id} data-testid={`admin-organization-${o.key}`}>
                      <TD>
                        <span className="font-semibold">{o.name}</span>
                        <span className="block font-mono text-xs text-muted-foreground">{o.key}</span>
                      </TD>
                      <TD className="tabular-nums">{wsItems.filter((w) => w.organizationId === o.id).length}{workspaces.hasNextPage ? '+' : ''}</TD>
                      <TD className="whitespace-nowrap">{formatDayYear(o.createdAt)}</TD>
                    </TR>
                  ))}
                </TBody>
              </Table>
            </div>
            {organizations.hasNextPage ? (
              <Button variant="secondary" onClick={() => void organizations.fetchNextPage()} loading={organizations.isFetchingNextPage}>Show more organizations</Button>
            ) : null}
          </>
        )}
      </section>

      {dialog === 'workspace' && !organizations.isPending ? (
        <NewWorkspaceDialog
          organizations={orgItems}
          meId={me.subject.id}
          onClose={() => openDialog(null)}
          onCreated={(workspace, addedMe) => {
            openDialog(null)
            setCreated({ workspace, member: addedMe })
            announce(`Workspace ${workspace.name} was created.`)
          }}
        />
      ) : null}
      {dialog === 'organization' ? (
        <NewOrganizationDialog
          onClose={() => openDialog(null)}
          onCreated={(organization) => {
            openDialog(null)
            announce(`Organization ${organization.name} was created.`)
          }}
        />
      ) : null}
    </AdminFrame>
  )
}

/** Where "Back" goes: the last workspace used, or the first one; none when the person belongs to no workspace. */
function backTarget(workspaces: Array<{ id: string; name: string }>): { to: string; label: string } | undefined {
  const last = readLastWorkspace()
  const target = workspaces.find((w) => w.id === last) ?? workspaces[0]
  return target ? { to: `/w/${target.id}`, label: `Back to ${target.name}` } : undefined
}

/** The top bar of the workspace shell without a workspace: wordmark, page name, the way back and sign out. */
function AdminFrame({ children, back }: { children: ReactNode; back?: { to: string; label: string } | undefined }) {
  const barButton = 'text-topbar-foreground hover:bg-topbar-hover active:bg-topbar-hover'
  return (
    <div className="flex min-h-dvh flex-col">
      <header className="focus-on-bar sticky top-0 z-30 bg-topbar text-topbar-foreground">
        <div className="flex h-14 items-center gap-1 px-3 lg:gap-2 lg:px-4">
          <Link to="/" className="rounded-control px-2 py-1 text-xl font-bold tracking-tight text-topbar-foreground no-underline hover:bg-topbar-hover" aria-label="Orqix home">
            Orqix
          </Link>
          <span className="mx-1 hidden h-6 w-px bg-topbar-foreground/30 sm:block" aria-hidden="true" />
          <p className="min-w-0 flex-1 truncate px-2 font-semibold">Platform administration</p>
          {back ? (
            <Button asChild variant="ghost" className={barButton}>
              <Link to={back.to}><ArrowLeft aria-hidden="true" /> <span className="hidden sm:inline">{back.label}</span><span className="sr-only sm:hidden">{back.label}</span></Link>
            </Button>
          ) : null}
          <Button variant="ghost" className={barButton} onClick={() => signOut()}>
            <LogOut aria-hidden="true" /> <span className="hidden sm:inline">Sign out</span><span className="sr-only sm:hidden">Sign out</span>
          </Button>
        </div>
        <div className="topbar-stripe" aria-hidden="true" />
      </header>
      <main id="main" tabIndex={-1} className="mx-auto w-full max-w-[1200px] flex-1 px-4 py-6 outline-none lg:px-8 lg:py-8">
        {children}
      </main>
    </div>
  )
}
