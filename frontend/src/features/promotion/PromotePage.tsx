import { useQueries, useQueryClient } from '@tanstack/react-query'
import { Rocket } from 'lucide-react'
import { useMemo, useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import * as endpoints from '@/api/endpoints'
import { isProblemError } from '@/api/problem'
import { keys, useRelease, useWorkflow, useWorkflowPromotions } from '@/api/queries'
import type { PromotionReport, PromotionRequest, ReleasePromotion } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { formatDateTime } from '@/lib/dates'
import { STAGES, stageLabel, stageRank } from './stages'

const CONNECTION_STATUS = {
  ready: { label: 'Ready', tone: 'success' },
  bindingMissing: { label: 'No address configured here', tone: 'danger' },
  definitionMissing: { label: 'This connection version is not registered here', tone: 'danger' },
  definitionDiffers: { label: 'A different definition of this version exists here', tone: 'danger' },
} as const

/**
 * Promote one release to a workspace at a later stage (PL02, docs/build/S54 and S69). The page runs the compatibility
 * check against the target's own connections, groups and called workflows, then saves a draft there (or, when the
 * target requires approval, asks for it). It never publishes, and it copies no connection address, secret or group.
 */
export function PromotePage() {
  const { workflowId = '', releaseId = '' } = useParams()
  const { workspace, me, can } = useWorkspace()
  const ws = workspace.id
  const client = useQueryClient()
  const workflow = useWorkflow(ws, workflowId)
  const release = useRelease(ws, workflowId, releaseId)
  const history = useWorkflowPromotions(ws, workflowId, can('workflow.read'))
  const [targetId, setTargetId] = useState('')
  const [report, setReport] = useState<PromotionReport | null>(null)
  const [note, setNote] = useState('')
  const [pending, setPending] = useState<'check' | 'promote' | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [done, setDone] = useState<{ kind: 'promoted'; value: ReleasePromotion } | { kind: 'requested'; value: PromotionRequest } | null>(null)

  const candidates = useMemo(
    () =>
      me.workspaces.filter(
        (w) => w.id !== ws && w.organizationName === workspace.organizationName && w.permissions.includes('workflow.edit'),
      ),
    [me.workspaces, ws, workspace.organizationName],
  )
  const environments = useQueries({
    queries: [workspace, ...candidates].map((w) => ({ queryKey: keys.environment(w.id), queryFn: () => endpoints.getWorkspaceEnvironment(w.id) })),
  })
  const stageOf = (id: string) => environments.find((q) => q.data?.workspaceId === id)?.data
  const sourceStage = stageOf(ws)?.stage

  if (!can('workflow.export')) {
    return <Alert tone="warning">You need permission to export releases from this workspace to promote them.</Alert>
  }
  if (workflow.isPending || release.isPending) return <LoadingBlock rows={4} label="Loading the release" />
  if (workflow.isError) return <QueryError error={workflow.error} onRetry={() => void workflow.refetch()} what="this workflow" backTo={{ to: `/w/${ws}/workflows`, label: 'Back to Workflows' }} />
  if (release.isError) return <QueryError error={release.error} onRetry={() => void release.refetch()} what="this version" backTo={{ to: `/w/${ws}/workflows/${workflowId}`, label: 'Back to the workflow' }} />

  const target = candidates.find((c) => c.id === targetId)
  const targetEnvironment = target ? stageOf(target.id) : undefined
  const expectedDraft = report?.targetWorkflow.exists ? report.targetWorkflow.draftRevision : null
  const staleProblem = isProblemError(error) && error.code === 'STALE_REVISION'

  const choose = (id: string) => {
    setTargetId(id)
    setReport(null)
    setError(null)
    setDone(null)
  }

  const check = async () => {
    if (!target) return
    setPending('check')
    setError(null)
    setDone(null)
    try {
      setReport(await endpoints.checkReleasePromotion(ws, workflowId, releaseId, { targetWorkspaceId: target.id }))
    } catch (cause) {
      setReport(null)
      setError(cause)
    } finally {
      setPending(null)
    }
  }

  const submit = async () => {
    if (!target || !report) return
    setPending('promote')
    setError(null)
    const body = { targetWorkspaceId: target.id, ...(expectedDraft !== null ? { expectedDraftRevision: expectedDraft } : {}) }
    try {
      if (report.approvalRequired) {
        setDone({ kind: 'requested', value: await endpoints.requestReleasePromotion(ws, workflowId, releaseId, { ...body, ...(note.trim() ? { note: note.trim() } : {}) }) })
      } else {
        setDone({ kind: 'promoted', value: await endpoints.promoteRelease(ws, workflowId, releaseId, body) })
      }
      setReport(null)
      setNote('')
      await client.invalidateQueries({ queryKey: keys.promotions(ws, workflowId) })
      await client.invalidateQueries({ queryKey: keys.promotionRequests(target.id, 'pending') })
    } catch (cause) {
      setError(cause)
    } finally {
      setPending(null)
    }
  }

  const releaseNumber = release.data.releaseNumber
  return (
    <div className="space-y-6">
      <PageHeader
        title={`Promote version ${releaseNumber} of ${workflow.data.name}`}
        description="Check this version against another workspace, then save it there as a draft. Nothing is published, and connection addresses, secrets and group members are not copied."
        actions={
          <Button asChild variant="secondary">
            <Link to={`/w/${ws}/workflows/${workflowId}`}>Back to the workflow</Link>
          </Button>
        }
      />

      <section aria-labelledby="target-heading" className="rounded-card border border-border bg-surface p-5">
        <h2 id="target-heading" className="mb-3 flex items-center gap-2">
          <Rocket className="size-5" aria-hidden="true" /> Target workspace
        </h2>
        <p className="mb-3 text-sm text-muted-foreground">
          This workspace is {sourceStage ? <strong>{stageLabel(sourceStage)}</strong> : 'loading its stage'}. A release moves to a later stage in the same organization.
          You need to be able to edit workflows in the target.
        </p>
        {candidates.length === 0 ? (
          <Alert tone="info">There is no other workspace in this organization where you can edit workflows.</Alert>
        ) : (
          <div className="flex flex-wrap items-end gap-3">
            <div className="space-y-1.5">
              <Label htmlFor="promotion-target">Promote to</Label>
              <Select id="promotion-target" value={targetId} onChange={(e) => choose(e.target.value)} className="w-72">
                <option value="">Choose a workspace</option>
                {candidates.map((c) => {
                  const stage = stageOf(c.id)?.stage
                  const later = stage !== undefined && sourceStage !== undefined && stageRank(stage) > stageRank(sourceStage)
                  return (
                    <option key={c.id} value={c.id} disabled={stage === undefined || !later}>
                      {c.name} ({stage ? stageLabel(stage) : 'loading'}){stage !== undefined && !later ? ' - not a later stage' : ''}
                    </option>
                  )
                })}
              </Select>
            </div>
            <Button variant="primary" loading={pending === 'check'} disabled={!target || pending !== null} onClick={() => void check()}>
              Run compatibility check
            </Button>
          </div>
        )}
        {targetEnvironment?.requireApproval ? (
          <p className="mt-3 text-sm" data-testid="approval-gate-note">
            <Badge tone="warning">Approval required</Badge> {target?.name} needs a second person to approve a promotion into it.
          </p>
        ) : null}
        <p className="sr-only">Stages in order: {STAGES.map(stageLabel).join(', ')}.</p>
      </section>

      {error ? (
        staleProblem ? (
          <Alert tone="warning" title="The target changed">
            The draft in {target?.name ?? 'the target'} was saved by someone else after this check. Run the compatibility check again to see the current revision.
          </Alert>
        ) : (
          <ProblemAlert error={error} context={{ action: 'promote this release' }} />
        )
      ) : null}

      {done ? <Outcome done={done} targetName={candidates.find((c) => c.id === done.value.targetWorkspaceId)?.name ?? 'the target'} /> : null}

      {report && target ? (
        <section aria-labelledby="report-heading" className="space-y-4 rounded-card border border-border bg-surface p-5">
          <div className="flex flex-wrap items-center gap-3">
            <h2 id="report-heading">Check against {target.name}</h2>
            <Badge tone={report.compatible ? 'success' : 'danger'} data-testid="compatibility">
              {report.compatible ? 'Compatible' : 'Not compatible'}
            </Badge>
          </div>
          <p className="text-sm text-muted-foreground">
            {stageLabel(report.sourceStage)} to {stageLabel(report.targetStage)}.{' '}
            {report.targetWorkflow.exists
              ? `${target.name} already has this workflow. The promotion adds a new draft after its current draft revision ${report.targetWorkflow.draftRevision}.`
              : `${target.name} does not have this workflow yet. The promotion creates it with you as its owner.`}
          </p>

          <Findings title="Connections" empty="This version calls no connections." caption="Connections used by this version, in the target">
            {report.connections.map((c) => (
              <TR key={c.key}>
                <TD>{c.key}</TD>
                <TD>version {c.definitionVersion}</TD>
                <TD>
                  <Badge tone={CONNECTION_STATUS[c.status].tone}>{CONNECTION_STATUS[c.status].label}</Badge>
                </TD>
              </TR>
            ))}
          </Findings>
          <Findings title="Approval groups" empty="This version assigns no approvals to groups." caption="Approval groups used by this version, in the target">
            {report.groups.map((g) => (
              <TR key={g.key}>
                <TD>{g.key}</TD>
                <TD>{g.exists ? `${g.memberCount} ${g.memberCount === 1 ? 'member' : 'members'}` : '-'}</TD>
                <TD>
                  <Badge tone={g.exists ? 'success' : 'warning'}>{g.exists ? 'Exists' : 'Missing'}</Badge>
                </TD>
              </TR>
            ))}
          </Findings>
          <Findings title="Called workflows" empty="This version calls no other workflows." caption="Workflows called by this version, in the target">
            {report.workflows.map((w) => (
              <TR key={w.workflowKey}>
                <TD>{w.workflowKey}</TD>
                <TD>-</TD>
                <TD>
                  <Badge tone={w.publishedInTarget ? 'success' : 'warning'}>{w.publishedInTarget ? 'Published here' : 'Not published here'}</Badge>
                </TD>
              </TR>
            ))}
          </Findings>

          <div>
            <h3 className="mb-2 text-base">Compiler findings</h3>
            {report.issues.length === 0 ? (
              <p className="text-sm text-muted-foreground">The target compiled this version without findings.</p>
            ) : (
              <ul className="space-y-2" aria-label="Compiler findings">
                {report.issues.map((issue, index) => (
                  <li key={`${issue.code}-${issue.path}-${index}`} className="rounded-control border border-border p-3 text-sm">
                    <Badge tone={issue.severity === 'blocker' ? 'danger' : 'warning'}>{issue.severity === 'blocker' ? 'Blocks promotion' : 'Warning'}</Badge>{' '}
                    {issue.message} <span className="font-mono text-xs text-muted-foreground">{issue.code}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>

          {report.compatible ? (
            <div className="space-y-3 border-t border-border pt-4">
              {report.approvalRequired ? (
                <>
                  <Alert tone="info" title="Approval needed">
                    {target.name} is a production workspace that requires approval. Your request goes to people who hold the approval permission there; you cannot approve it yourself.
                  </Alert>
                  <div className="space-y-1.5">
                    <Label htmlFor="promotion-note">Note for the approver (optional)</Label>
                    <Textarea id="promotion-note" value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} className="max-w-xl" />
                  </div>
                </>
              ) : (
                <p className="text-sm text-muted-foreground">
                  This saves a draft in {target.name}. It stays unpublished there until someone with permission publishes it through that workspace&apos;s normal checks.
                </p>
              )}
              <Button variant="primary" loading={pending === 'promote'} disabled={pending !== null} onClick={() => void submit()}>
                {report.approvalRequired ? 'Request approval' : 'Promote as draft'}
              </Button>
            </div>
          ) : (
            <Alert tone="warning">
              This version cannot be promoted yet. Fix the findings in {target.name} (for example, configure the missing connection address there), then run the check again.
            </Alert>
          )}
        </section>
      ) : null}

      {can('workflow.read') ? (
        <section aria-labelledby="history-heading">
          <h2 id="history-heading" className="mb-3">
            Promotion history
          </h2>
          {history.isPending ? (
            <LoadingBlock rows={2} label="Loading promotion history" />
          ) : history.isError ? (
            <QueryError error={history.error} onRetry={() => void history.refetch()} what="the promotion history" />
          ) : history.data.items.length === 0 ? (
            <p className="text-sm text-muted-foreground">This workflow has not been promoted into or out of this workspace.</p>
          ) : (
            <div className="overflow-hidden rounded-card border border-border bg-surface">
              <Table>
                <caption className="sr-only">Promotions of this workflow</caption>
                <THead>
                  <TR>
                    <TH>Direction</TH>
                    <TH>Version</TH>
                    <TH>From to</TH>
                    <TH>Draft</TH>
                    <TH>Promoted by</TH>
                    <TH>Approved by</TH>
                    <TH>When</TH>
                  </TR>
                </THead>
                <TBody>
                  {history.data.items.map((p) => (
                    <TR key={p.id}>
                      <TD>{p.direction === 'incoming' ? 'Received' : 'Sent'}</TD>
                      <TD>{p.releaseNumber}</TD>
                      <TD>
                        {p.sourceWorkspaceKey} to {p.targetWorkspaceKey}
                      </TD>
                      <TD>
                        revision {p.targetDraftRevision}
                        {p.createdWorkflow ? ' (new workflow)' : ''}
                      </TD>
                      <TD>{p.promotedBy}</TD>
                      <TD>{p.approvedBy ?? '-'}</TD>
                      <TD>{formatDateTime(p.promotedAt)}</TD>
                    </TR>
                  ))}
                </TBody>
              </Table>
            </div>
          )}
        </section>
      ) : null}
    </div>
  )
}

function Findings({ title, caption, empty, children }: { title: string; caption: string; empty: string; children: ReactNode[] }) {
  return (
    <div>
      <h3 className="mb-2 text-base">{title}</h3>
      {children.length === 0 ? (
        <p className="text-sm text-muted-foreground">{empty}</p>
      ) : (
        <div className="overflow-hidden rounded-control border border-border">
          <Table>
            <caption className="sr-only">{caption}</caption>
            <TBody>{children}</TBody>
          </Table>
        </div>
      )}
    </div>
  )
}

function Outcome({ done, targetName }: { done: { kind: 'promoted'; value: ReleasePromotion } | { kind: 'requested'; value: PromotionRequest }; targetName: string }) {
  if (done.kind === 'requested') {
    return (
      <Alert tone="success" title="Approval requested">
        <p data-testid="promotion-outcome">
          Your request to promote version {done.value.releaseNumber} into {targetName} is waiting for approval. Nothing has been created there yet.
        </p>
        <p className="mt-1">
          <Link to={`/w/${done.value.targetWorkspaceId}/promotions`}>See the request in {targetName}</Link>
        </p>
      </Alert>
    )
  }
  return (
    <Alert tone="success" title="Saved as a draft">
      <p data-testid="promotion-outcome">
        {done.value.createdWorkflow ? 'Created the workflow' : 'Added a new draft'} in {targetName} at draft revision {done.value.draftRevision}. It is not published.
      </p>
      <p className="mt-1">
        <Link to={`/w/${done.value.targetWorkspaceId}/workflows/${done.value.targetWorkflowId}/edit`}>Open the draft in {targetName}</Link>
      </p>
    </Alert>
  )
}

