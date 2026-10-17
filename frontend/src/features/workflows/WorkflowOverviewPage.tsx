import { useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, Download, History, Pencil, Rocket } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import * as endpoints from '@/api/endpoints'
import type { ProblemError } from '@/api/problem'
import { keys, useReleases, useWorkflow } from '@/api/queries'
import type { ReleaseSummary } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { useAnnounce } from '@/components/common/Announcer'
import { ConfirmDialog } from '@/components/common/ConfirmDialog'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { formatDateTime } from '@/lib/dates'
import { downloadBlob } from '@/lib/download'
import { describeProblem } from '@/lib/problemMessages'
import { VersionComparison } from './VersionComparison'
import { PinWorkflowButton } from './PinnedWorkflows'
import { ReleaseRollout } from './ReleaseRollout'

/** Workflow overview: draft status, releases, make active (rollback), export (docs/build/S7-frontend.md section 4). */
export function WorkflowOverviewPage() {
  const { workflowId = '' } = useParams()
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const client = useQueryClient()
  const announce = useAnnounce()
  const workflow = useWorkflow(ws, workflowId)
  const releases = useReleases(ws, workflowId)
  const [target, setTarget] = useState<ReleaseSummary | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ProblemError | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [exporting, setExporting] = useState<string | null>(null)
  const [exportError, setExportError] = useState<ProblemError | null>(null)
  const [comparing, setComparing] = useState(false)

  if (workflow.isPending) return <LoadingBlock rows={4} label="Loading the workflow" />
  if (workflow.isError) return <QueryError error={workflow.error} onRetry={() => void workflow.refetch()} what="this workflow" backTo={{ to: `/w/${ws}/workflows`, label: 'Back to Workflows' }} />
  const wf = workflow.data
  const items = releases.data?.pages.flatMap((p) => p.items) ?? []
  const latestNumber = Math.max(0, ...items.map((r) => r.releaseNumber))
  const canPublish = can('workflow.publish')

  const makeActive = async () => {
    if (!target) return
    setPending(true)
    setError(null)
    try {
      await endpoints.setActiveRelease(ws, workflowId, target.id, wf.version)
      await Promise.all([client.invalidateQueries({ queryKey: keys.workflow(ws, workflowId) }), client.invalidateQueries({ queryKey: ['ws', ws, 'workflows'] }), client.invalidateQueries({ queryKey: keys.rollout(ws, workflowId) })])
      const text = `Version ${target.releaseNumber} is now used for new requests. Existing requests are unchanged.`
      setNotice(text)
      announce(text)
      setTarget(null)
    } catch (cause) {
      const problem = toProblem(cause)
      setError(problem)
      if (problem.code === 'WORKFLOW_VERSION_CONFLICT') void client.invalidateQueries({ queryKey: keys.workflow(ws, workflowId) })
    } finally {
      setPending(false)
    }
  }

  const exportRelease = async (r: ReleaseSummary) => {
    setExporting(r.id)
    setExportError(null)
    try {
      const { blob, filename } = await endpoints.exportRelease(ws, workflowId, r.id)
      downloadBlob(blob, filename)
      announce(`Downloaded ${filename}`)
    } catch (cause) {
      setExportError(toProblem(cause))
    } finally {
      setExporting(null)
    }
  }

  return (
    <>
      <PageHeader
        title={wf.name}
        description={wf.description || 'No description yet.'}
        actions={
          <>
            {can('workflow.read') ? <PinWorkflowButton id={wf.id} name={wf.name} /> : null}
            <Button asChild variant="secondary">
              <Link to={`/w/${ws}/workflows/${wf.id}/edit`}>
                <Pencil aria-hidden="true" /> {can('workflow.edit') ? 'Edit draft' : 'View draft'}
              </Link>
            </Button>
            {canPublish ? (
              <Button asChild variant="primary">
                <Link to={`/w/${ws}/workflows/${wf.id}/publish?revision=${wf.draftRevision}`}>
                  <Rocket aria-hidden="true" /> Review and publish
                </Link>
              </Button>
            ) : null}
          </>
        }
      />
      {notice ? (
        <Alert tone="success" className="mb-4" data-testid="overview-notice">
          {notice}
        </Alert>
      ) : null}
      <div className="mb-6 grid gap-4 md:grid-cols-3">
        <Card>
          <CardHeader>
            <h2 className="text-sm text-muted-foreground uppercase">Draft</h2>
          </CardHeader>
          <CardContent>
            <p className="text-2xl font-semibold">Revision {wf.draftRevision}</p>
            <p className="text-sm text-muted-foreground">Last changed {formatDateTime(wf.updatedAt)}</p>
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <h2 className="text-sm text-muted-foreground uppercase">Used for new requests</h2>
          </CardHeader>
          <CardContent>
            {wf.activeRelease ? (
              <>
                <p className="text-2xl font-semibold">Version {wf.activeRelease.releaseNumber}</p>
                <p className="text-sm text-muted-foreground">Published {formatDateTime(wf.activeRelease.publishedAt)}</p>
                {wf.draftRevision > wf.activeRelease.draftRevision ? <p className="mt-1 text-sm font-semibold text-warning-foreground">The draft has changes that are not published yet.</p> : null}
              </>
            ) : (
              <>
                <p className="text-2xl font-semibold">Not published</p>
                <p className="text-sm text-muted-foreground">Nobody can start this workflow yet.</p>
              </>
            )}
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <h2 className="text-sm text-muted-foreground uppercase">Next version</h2>
          </CardHeader>
          <CardContent>
            <p className="text-2xl font-semibold">Version {latestNumber + 1}</p>
            <p className="text-sm text-muted-foreground">Existing requests keep their original version.</p>
          </CardContent>
        </Card>
      </div>

      <section aria-labelledby="releases-heading">
        <h2 id="releases-heading" className="mb-3 flex items-center gap-2">
          <History className="size-5" aria-hidden="true" /> Versions
        </h2>
        {exportError ? <ProblemAlert error={exportError} className="mb-3" context={{ action: 'export this version' }} /> : null}
        {items.length >= 2 ? <Button variant="secondary" className="mb-3" aria-expanded={comparing} onClick={() => setComparing((value) => !value)}>{comparing ? 'Close comparison' : 'Compare versions'}</Button> : null}
        {comparing && items.length >= 2 ? <VersionComparison workspaceId={ws} workflowId={workflowId} releases={items} /> : null}
        {releases.isPending ? (
          <LoadingBlock rows={2} label="Loading versions" />
        ) : releases.isError ? (
          <QueryError error={releases.error} onRetry={() => void releases.refetch()} what="the versions" />
        ) : items.length === 0 ? (
          <p className="rounded-card border border-dashed border-input-border bg-surface p-6 text-sm text-muted-foreground">No version has been published yet.</p>
        ) : (
          <>
            <div className="overflow-hidden rounded-card border border-border bg-surface">
              <Table>
                <caption className="sr-only">Published versions</caption>
                <THead>
                  <TR>
                    <TH>Version</TH>
                    <TH>Published</TH>
                    <TH>Note</TH>
                    <TH>
                      <span className="sr-only">Actions</span>
                    </TH>
                  </TR>
                </THead>
                <TBody>
                  {items.map((r) => (
                    <TR key={r.id}>
                      <TD>
                        <span className="font-semibold">Version {r.releaseNumber}</span>{' '}
                        {r.isActive ? (
                          <Badge tone="success">
                            <CheckCircle2 aria-hidden="true" /> Active
                          </Badge>
                        ) : null}
                        <span className="block text-xs text-muted-foreground">From draft revision {r.draftRevision}</span>
                      </TD>
                      <TD>
                        {formatDateTime(r.publishedAt)}
                        <span className="block text-xs text-muted-foreground">by {r.publishedBy.displayName}</span>
                      </TD>
                      <TD className="max-w-xs text-muted-foreground">{r.releaseNote || '—'}</TD>
                      <TD>
                        <div className="flex flex-wrap justify-end gap-2">
                          {canPublish && !r.isActive ? (
                            <Button size="sm" variant="secondary" onClick={() => { setError(null); setTarget(r) }} aria-label={`Make version ${r.releaseNumber} active`}>
                              Make active
                            </Button>
                          ) : null}
                          {can('workflow.export') ? (
                            <Button size="sm" variant="secondary" onClick={() => void exportRelease(r)} loading={exporting === r.id} aria-label={`Export version ${r.releaseNumber}`}>
                              <Download aria-hidden="true" /> Export
                            </Button>
                          ) : null}
                        </div>
                      </TD>
                    </TR>
                  ))}
                </TBody>
              </Table>
            </div>
            {releases.hasNextPage ? (
              <div className="mt-4 flex justify-center">
                <Button variant="secondary" onClick={() => void releases.fetchNextPage()} loading={releases.isFetchingNextPage}>
                  Load more
                </Button>
              </div>
            ) : null}
          </>
        )}
      </section>

      <ReleaseRollout workflowId={workflowId} releases={items} />

      <ConfirmDialog
        open={target !== null}
        title={target ? `Make version ${target.releaseNumber} active?` : 'Make active?'}
        confirmLabel={target ? `Make version ${target.releaseNumber} active` : 'Make active'}
        pending={pending}
        onConfirm={() => void makeActive()}
        onCancel={() => setTarget(null)}
        error={error ? <ProblemAlert error={error} context={{ action: 'change the active version' }} /> : null}
      >
        <p>{target ? `New requests will use version ${target.releaseNumber}. Existing requests are unchanged.` : null}</p>
        {error && describeProblem(error).behavior === 'message' && error.code === 'WORKFLOW_VERSION_CONFLICT' ? <p>The workflow was changed by someone else. Close this dialog and try again.</p> : null}
      </ConfirmDialog>
    </>
  )
}
