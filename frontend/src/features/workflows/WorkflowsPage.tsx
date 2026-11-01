import { CheckCircle2, FileClock, FilePlus2, PencilLine, Workflow as WorkflowIcon } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { useWorkflowOrganization, useWorkflows } from '@/api/queries'
import type { components } from '@/api/schema'
import { useWorkspace } from '@/app/workspace'
import { EmptyState } from '@/components/common/EmptyState'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { QueryError } from '@/components/common/QueryError'
import { RelativeTime } from '@/components/common/RelativeTime'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { useListFilters } from '@/lib/useListFilters'
import { NewWorkflowDialog } from './NewWorkflowDialog'
import { PinnedWorkflows, PinWorkflowButton } from './PinnedWorkflows'
import { ArchivedWorkflowsButton, folderName, ManageFoldersButton, OrganizationFilters, OrganizeWorkflowDialog } from './Organization'

/** Workflow list with "New workflow" (docs/build/S7-frontend.md section 2). */
export function WorkflowsPage() {
  const { workspace, can, isOn } = useWorkspace()
  const ws = workspace.id
  const { params, update } = useListFilters()
  const applied = params.get('q') ?? ''
  const folder = params.get('folder') ?? ''
  const owner = params.get('owner') ?? ''
  const filtered = applied !== '' || folder !== '' || owner !== ''
  const clearSearch = () => update({ q: '', folder: '', owner: '' })
  const [creating, setCreating] = useState(false)
  const [organizing, setOrganizing] = useState<components['schemas']['WorkflowSummary'] | null>(null)
  const canRead = can('workflow.read')
  const org = useWorkflowOrganization(ws, canRead)
  const query = useWorkflows(ws, { ...(applied ? { q: applied } : {}), ...(canRead && folder ? { folder } : {}), ...(canRead && owner ? { owner } : {}) })
  const items = query.data?.pages.flatMap((p) => p.items) ?? []

  return (
    <>
      <PageHeader
        title="Workflows"
        description={canRead ? 'Build, publish and roll back the workflows your team uses.' : 'Workflows you can start.'}
        actions={
          can('workflow.edit') ? (
            <div className="flex flex-wrap gap-2">
            <details className="relative self-center"><summary className="cursor-pointer rounded-control border border-border bg-surface px-3 py-2 text-sm font-semibold">Manage library</summary>
              <div className="absolute right-0 z-20 mt-2 flex min-w-48 flex-col gap-2 rounded-card border border-border bg-surface p-3 shadow-card"><ManageFoldersButton /><ArchivedWorkflowsButton /></div>
            </details>
            {isOn('migration') ? <Button asChild size="lg"><Link to={`/w/${ws}/migrations`}>Import workflow</Link></Button> : null}
            <Button variant="primary" size="lg" onClick={() => setCreating(true)}>
              <FilePlus2 aria-hidden="true" /> New workflow
            </Button>
            </div>
          ) : null
        }
      />
      <PinnedWorkflows />
      <form
        className="mb-4 flex flex-wrap items-end gap-2 rounded-card border border-border bg-surface p-4"
        role="search"
        onSubmit={(event) => {
          event.preventDefault()
          const value = new FormData(event.currentTarget).get('q')
          update({ q: typeof value === 'string' ? value.trim() : '' })
        }}
      >
        <div className="space-y-1.5">
          <Label htmlFor="wf-search">Search workflows</Label>
          <Input id="wf-search" key={`${ws}:${applied}`} name="q" defaultValue={applied} className="w-64 max-w-full" placeholder="Search by name" maxLength={100} />
        </div>
        <Button type="submit" variant="secondary">
          Search
        </Button>
        {canRead ? <OrganizationFilters folder={folder} owner={owner} onChange={update} /> : null}
        {filtered ? <Button variant="ghost" onClick={clearSearch}>Clear filters</Button> : null}
      </form>

      {query.isPending ? (
        <LoadingBlock rows={3} label="Loading workflows" />
      ) : query.isError ? (
        <QueryError error={query.error} onRetry={() => void query.refetch()} what="workflows" />
      ) : items.length === 0 ? (
        <EmptyState icon={WorkflowIcon} title={filtered ? 'No workflows match your search' : 'No workflows yet'} action={filtered ? <Button onClick={clearSearch}>Show all workflows</Button> : undefined}>
          {filtered ? 'Try another name, folder or owner, or clear your filters.' : can('workflow.edit') ? 'Choose New workflow to start from a template, a few questions or a blank canvas.' : 'Ask a designer to publish a workflow.'}
        </EmptyState>
      ) : (
        <>
          <div className="overflow-hidden rounded-card border border-border bg-surface">
            <Table>
              <caption className="sr-only">Workflows</caption>
              <THead>
                <TR>
                  <TH>Name</TH>
                  <TH>Status</TH>
                  <TH>Draft</TH>
                  {canRead ? <TH>Folder and owner</TH> : null}
                  <TH>Last change</TH>
                  <TH>
                    <span className="sr-only">Actions</span>
                  </TH>
                </TR>
              </THead>
              <TBody>
                {items.map((w) => (
                  <TR key={w.id}>
                    <TD>
                      {canRead ? (
                        <Link to={`/w/${ws}/workflows/${w.id}`} className="font-semibold">
                          {w.name}
                        </Link>
                      ) : (
                        <span className="font-semibold">{w.name}</span>
                      )}
                      {w.description ? <span className="block max-w-md text-xs text-muted-foreground">{w.description}</span> : null}
                    </TD>
                    <TD>
                      {w.activeRelease ? (
                        <div className="flex flex-wrap gap-1.5">
                          <Badge tone="success" title="New requests use this version">
                            <CheckCircle2 aria-hidden="true" /> Active v{w.activeRelease.releaseNumber}
                          </Badge>
                          {w.draftRevision > w.activeRelease.draftRevision ? (
                            <Badge tone="warning" title="The draft has changes that new requests do not use until you publish">
                              <PencilLine aria-hidden="true" /> Changes not published
                            </Badge>
                          ) : null}
                        </div>
                      ) : (
                        <Badge title="Nobody can start this workflow until it is published">
                          <FileClock aria-hidden="true" /> Draft only
                        </Badge>
                      )}
                    </TD>
                    <TD>Revision {w.draftRevision}</TD>
                    {canRead ? (
                      <TD>
                        <span className="block">{folderName(org.data?.folders, w.folderId) ?? (w.folderId ? 'Folder' : 'Unfiled')}</span>
                        <span className="block text-xs text-muted-foreground">Owner: {w.owner?.displayName ?? 'Unknown'}</span>
                      </TD>
                    ) : null}
                    <TD className="whitespace-nowrap text-muted-foreground">
                      <RelativeTime value={w.updatedAt} />
                    </TD>
                    <TD>
                      <div className="flex flex-wrap justify-end gap-2">
                        {canRead ? <PinWorkflowButton id={w.id} name={w.name} /> : null}
                        {can('workflow.edit') ? <Button size="sm" variant="secondary" aria-label={`Organize ${w.name}`} onClick={() => setOrganizing(w)}>Organize</Button> : null}
                        {w.canStart ? (
                          <Button asChild size="sm" variant="primary">
                            <Link to={`/w/${ws}/start/${w.id}`} aria-label={`Start ${w.name}`}>
                              Start
                            </Link>
                          </Button>
                        ) : null}
                        {canRead ? (
                          <Button asChild size="sm" variant="secondary">
                            <Link to={`/w/${ws}/workflows/${w.id}/edit`} aria-label={`${can('workflow.edit') ? 'Edit draft of' : 'View draft of'} ${w.name}`}>
                              {can('workflow.edit') ? 'Edit draft' : 'View draft'}
                            </Link>
                          </Button>
                        ) : null}
                      </div>
                    </TD>
                  </TR>
                ))}
              </TBody>
            </Table>
          </div>
          {query.hasNextPage ? (
            <div className="mt-4 flex justify-center">
              <Button variant="secondary" onClick={() => void query.fetchNextPage()} loading={query.isFetchingNextPage}>
                Load more
              </Button>
            </div>
          ) : null}
        </>
      )}
      {creating ? <NewWorkflowDialog onClose={() => setCreating(false)} /> : null}
      {organizing ? <OrganizeWorkflowDialog workflow={organizing} onClose={() => setOrganizing(null)} /> : null}
    </>
  )
}
