import { useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertOctagon, AlertTriangle, CheckCircle2, Rocket } from 'lucide-react'
import { useMemo, useRef, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import * as endpoints from '@/api/endpoints'
import { fingerprintOf, useIdempotencyKey } from '@/api/idempotency'
import type { ProblemError } from '@/api/problem'
import { keys, useConnections, useGroups, useReleases, useWorkflow } from '@/api/queries'
import type { ValidationIssue } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { useAnnounce } from '@/components/common/Announcer'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/input'
import { describeProblem } from '@/lib/problemMessages'
import { normalizeDefinition } from '@/features/studio/model/types'
import { locateIssue } from '@/features/studio/model/issues'
import { groupIssues } from '@/features/studio/CheckPanel'
import { diffDefinitions, type Change } from './diff'

const KIND_LABEL = { added: 'Added', removed: 'Removed', changed: 'Changed' } as const
const KIND_TONE = { added: 'success', removed: 'danger', changed: 'warning' } as const

/**
 * Publish review (docs/build/S7-frontend.md section 5): summary, changed steps compared with the active release,
 * dependencies, warnings with an acknowledgement, "make active" (on by default) and the publish command with an
 * idempotency key. Everything shown here is assistance; the server revalidates the exact draft revision.
 */
export function PublishReviewPage() {
  const { workflowId = '' } = useParams()
  const [params] = useSearchParams()
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const client = useQueryClient()
  const announce = useAnnounce()
  const idempotency = useIdempotencyKey()

  const workflow = useWorkflow(ws, workflowId)
  const releases = useReleases(ws, workflowId)
  const draft = useQuery({ queryKey: keys.draft(ws, workflowId), queryFn: () => endpoints.getDraft(ws, workflowId), staleTime: 0, retry: false })
  const requested = Number(params.get('revision')) || draft.data?.draft.revision || 0
  const reviewRevision = draft.data ? (draft.data.draft.revision === requested ? requested : null) : null
  const report = useQuery({
    queryKey: ['ws', ws, 'workflow', workflowId, 'validate', requested],
    queryFn: () => endpoints.validateDraft(ws, workflowId, requested),
    enabled: !!draft.data && requested > 0,
    staleTime: 0,
    retry: false,
  })
  const activeId = workflow.data?.activeRelease?.id
  const active = useQuery({ queryKey: keys.release(ws, workflowId, activeId ?? 'none'), queryFn: () => endpoints.getRelease(ws, workflowId, activeId as string), enabled: !!activeId, staleTime: Infinity })
  const connections = useConnections(ws, can('connection.read'))
  const groups = useGroups(ws)

  const [acknowledged, setAcknowledged] = useState(false)
  const [activate, setActivate] = useState(true)
  const [note, setNote] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ProblemError | null>(null)
  const [published, setPublished] = useState<{ number: number; active: boolean } | null>(null)
  const ackRef = useRef<HTMLButtonElement>(null)

  const after = useMemo(() => (draft.data ? normalizeDefinition(draft.data.draft.definition) : null), [draft.data])
  const before = useMemo(() => (active.data ? normalizeDefinition(active.data.definition) : null), [active.data])
  const changes: Change[] = useMemo(() => (after && (before || !workflow.data?.activeRelease) ? diffDefinitions(before, after) : []), [after, before, workflow.data?.activeRelease])

  if (workflow.isPending || draft.isPending) return <LoadingBlock rows={5} label="Preparing the review" />
  if (workflow.isError) return <QueryError error={workflow.error} onRetry={() => void workflow.refetch()} what="this workflow" backTo={{ to: `/w/${ws}/workflows`, label: 'Back to Workflows' }} />
  if (draft.isError) return <QueryError error={draft.error} onRetry={() => void draft.refetch()} what="the draft" backTo={{ to: `/w/${ws}/workflows/${workflowId}`, label: 'Back to the workflow' }} />

  const wf = workflow.data
  const latestNumber = Math.max(0, ...(releases.data?.pages.flatMap((p) => p.items.map((r) => r.releaseNumber)) ?? []), wf.activeRelease?.releaseNumber ?? 0)
  const nextNumber = latestNumber + 1
  const issues: ValidationIssue[] = report.data?.issues ?? error?.issues ?? []
  const blockers = issues.filter((i) => i.severity === 'blocker')
  const warnings = issues.filter((i) => i.severity === 'warning')
  const stale = reviewRevision === null
  const reportReady = report.isSuccess
  const canPublishNow = can('workflow.publish') && !stale && reportReady && blockers.length === 0 && (warnings.length === 0 || acknowledged) && !published
  const groupNames = Object.fromEntries((groups.data?.items ?? []).map((g) => [g.key, g.name]))

  const usedGroups = after ? [...new Set(after.nodes.filter((n) => n.type === 'approval').map((n) => n.config.candidateGroupRef as string).filter(Boolean))] : []
  const usedConnections = after?.dependencies.connections ?? []

  const publish = async () => {
    if (!after || reviewRevision === null) return
    setPending(true)
    setError(null)
    try {
      const body = { draftRevision: reviewRevision, acknowledgeWarnings: acknowledged, activate, ...(note.trim() ? { releaseNote: note.trim() } : {}) }
      const key = idempotency.keyFor(fingerprintOf(workflowId, body))
      const { data } = await endpoints.publishDraft(ws, workflowId, body, key)
      await Promise.all([client.invalidateQueries({ queryKey: ['ws', ws, 'workflow', workflowId] }), client.invalidateQueries({ queryKey: ['ws', ws, 'workflows'] })])
      setPublished({ number: data.releaseNumber, active: data.isActive })
      announce(`Version ${data.releaseNumber} published. Existing requests continue on their original version.`)
    } catch (cause) {
      const problem = toProblem(cause)
      const ui = describeProblem(problem)
      if (ui.behavior === 'open-validation') {
        // Blockers found by the server: show them here and do not retry with the same key.
        idempotency.reset()
        void report.refetch()
      } else if (ui.behavior === 'acknowledge-warnings') {
        idempotency.reset()
        ackRef.current?.focus()
      }
      setError(problem)
    } finally {
      setPending(false)
    }
  }

  if (published) {
    return (
      <div className="mx-auto max-w-2xl">
        <PageHeader title={`Version ${published.number} published`} />
        <Alert tone="success" data-testid="publish-success" live="polite">
          <p className="text-base font-semibold">Version {published.number} published. Existing requests continue on their original version.</p>
          <p className="mt-1">{published.active ? `New requests now use version ${published.number}.` : `New requests still use the previous version. Make version ${published.number} active from the workflow page when you are ready.`}</p>
        </Alert>
        <div className="mt-6 flex flex-wrap gap-3">
          <Button asChild variant="primary" size="lg">
            <Link to={`/w/${ws}/workflows/${workflowId}`}>Open the workflow</Link>
          </Button>
          <Button asChild variant="secondary" size="lg">
            <Link to={`/w/${ws}/workflows/${workflowId}/edit`}>Back to the studio</Link>
          </Button>
        </div>
      </div>
    )
  }

  return (
    <>
      <PageHeader
        title={`Publish ${wf.name}`}
        description={
          <>
            Draft revision {requested} will become <strong>version {nextNumber}</strong>.
          </>
        }
        actions={
          <Button asChild variant="secondary">
            <Link to={`/w/${ws}/workflows/${workflowId}/edit`}>Back to the studio</Link>
          </Button>
        }
      />

      {stale && draft.data ? (
        <Alert tone="warning" className="mb-4" title="A newer draft exists">
          You asked to review revision {requested}, but the latest draft is revision {draft.data.draft.revision}.{' '}
          <Link className="font-semibold text-current underline underline-offset-2" to={`/w/${ws}/workflows/${workflowId}/publish?revision=${draft.data.draft.revision}`}>Review the latest draft</Link>
        </Alert>
      ) : null}

      <div className="space-y-6">
        <Card>
          <CardHeader>
            <h2>Check results</h2>
          </CardHeader>
          <CardContent className="space-y-3">
            {report.isPending ? (
              <LoadingBlock rows={1} label="Checking the draft" />
            ) : report.isError ? (
              <ProblemAlert error={report.error} onRetry={() => void report.refetch()} />
            ) : issues.length === 0 ? (
              <p className="flex items-center gap-2 font-semibold text-success" data-testid="check-clean">
                <CheckCircle2 className="size-5" aria-hidden="true" /> No problems found. This draft can be published.
              </p>
            ) : (
              <>
                <p className="font-semibold" data-testid="check-counts">
                  {blockers.length} {blockers.length === 1 ? 'blocker' : 'blockers'} and {warnings.length} {warnings.length === 1 ? 'warning' : 'warnings'}.
                </p>
                {after
                  ? groupIssues(after, issues).map((g) => (
                      <div key={g.nodeId ?? g.label}>
                        <h3 className="mb-1.5 text-sm">{g.label}</h3>
                        <ul className="space-y-2">
                          {g.issues.map((i, n) => (
                            <li key={`${i.code}-${i.path}-${n}`} className={i.severity === 'blocker' ? 'flex gap-3 rounded-control border border-danger-border bg-danger-soft p-3 text-sm' : 'flex gap-3 rounded-control border border-warning-border bg-warning-soft p-3 text-sm'} data-severity={i.severity}>
                              {i.severity === 'blocker' ? <AlertOctagon className="mt-0.5 size-4 shrink-0 text-danger" aria-hidden="true" /> : <AlertTriangle className="mt-0.5 size-4 shrink-0 text-warning-foreground" aria-hidden="true" />}
                              <span className="flex-1">
                                <span className="font-semibold">{i.severity === 'blocker' ? 'Blocker' : 'Warning'}:</span> {i.message} <span className="font-mono text-xs text-muted-foreground">{i.code}</span>
                              </span>
                              {i.severity === 'blocker' && locateIssue(after, i).nodeId ? (
                                <Link to={`/w/${ws}/workflows/${workflowId}/edit`} className="text-sm font-semibold">
                                  Open the studio
                                </Link>
                              ) : null}
                            </li>
                          ))}
                        </ul>
                      </div>
                    ))
                  : null}
                {blockers.length > 0 ? <p className="text-sm text-muted-foreground">Fix the blockers in the studio, then come back to publish.</p> : null}
              </>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <h2>What changed</h2>
            <p className="text-sm text-muted-foreground">{wf.activeRelease ? `Compared with version ${wf.activeRelease.releaseNumber}, the version new requests use now.` : 'There is no published version yet.'}</p>
          </CardHeader>
          <CardContent>
            {wf.activeRelease && active.isPending ? (
              <LoadingBlock rows={2} label="Loading the active version" />
            ) : wf.activeRelease && active.isError ? (
              <ProblemAlert error={active.error} onRetry={() => void active.refetch()} />
            ) : changes.length === 0 ? (
              <p className="text-sm text-muted-foreground" data-testid="no-changes">
                Nothing behaves differently from the active version. Only the layout may have changed.
              </p>
            ) : (
              <ul className="space-y-3" data-testid="changes">
                {changes.map((c, i) => (
                  <li key={i} className="rounded-control border border-border p-3 text-sm">
                    <p className="flex flex-wrap items-center gap-2 font-semibold">
                      <Badge tone={KIND_TONE[c.kind]}>{KIND_LABEL[c.kind]}</Badge> {c.title}
                    </p>
                    {c.details.length > 0 ? (
                      <ul className="mt-1.5 list-disc space-y-0.5 pl-6 text-muted-foreground">
                        {c.details.map((d, j) => (
                          <li key={j}>{d}</li>
                        ))}
                      </ul>
                    ) : null}
                  </li>
                ))}
              </ul>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <h2>What this version depends on</h2>
          </CardHeader>
          <CardContent className="grid gap-6 md:grid-cols-3">
            <div>
              <h3 className="mb-1.5 text-sm text-muted-foreground uppercase">Approver groups</h3>
              {usedGroups.length === 0 ? <p className="text-sm text-muted-foreground">None</p> : <ul className="list-disc pl-5 text-sm">{usedGroups.map((g) => <li key={g}>{groupNames[g] ?? g}</li>)}</ul>}
            </div>
            <div>
              <h3 className="mb-1.5 text-sm text-muted-foreground uppercase">Connections</h3>
              {usedConnections.length === 0 ? (
                <p className="text-sm text-muted-foreground">None</p>
              ) : (
                <ul className="list-disc pl-5 text-sm">
                  {usedConnections.map((c) => {
                    const s = connections.data?.items.find((x) => x.key === c.key)
                    return (
                      <li key={c.key}>
                        {s?.name ?? c.key}, version {c.definitionVersion}
                        <span className="block text-xs text-muted-foreground">{s ? (s.binding.host ? `sends to ${s.binding.host}` : 'no address configured') : 'address not visible to you'}</span>
                      </li>
                    )
                  })}
                </ul>
              )}
            </div>
            <div>
              <h3 className="mb-1.5 text-sm text-muted-foreground uppercase">Rule tables</h3>
              {(after?.decisions.length ?? 0) === 0 ? <p className="text-sm text-muted-foreground">None</p> : <ul className="list-disc pl-5 text-sm">{after?.decisions.map((d) => <li key={d.key}>{d.key}, version {d.version}</li>)}</ul>}
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <h2>Publish</h2>
          </CardHeader>
          <CardContent className="space-y-4">
            {warnings.length > 0 && blockers.length === 0 ? (
              <div className="flex items-start gap-3 rounded-control border border-warning-border bg-warning-soft p-3">
                <Checkbox ref={ackRef} id="ack-warnings" checked={acknowledged} onCheckedChange={(v) => setAcknowledged(v === true)} aria-invalid={error?.code === 'WARNINGS_NOT_ACKNOWLEDGED' ? true : undefined} />
                <Label htmlFor="ack-warnings" className="font-medium">
                  I have read the {warnings.length === 1 ? 'warning' : `${warnings.length} warnings`} above and want to publish anyway.
                  <span className="block text-xs font-normal text-muted-foreground">Warnings are kept with this version.</span>
                </Label>
              </div>
            ) : null}
            <div className="flex items-start gap-3">
              <Checkbox id="activate" checked={activate} onCheckedChange={(v) => setActivate(v === true)} />
              <Label htmlFor="activate" className="font-medium">
                Make this the version used for new requests
                <span className="block text-xs font-normal text-muted-foreground">Existing requests are never moved. You can switch back to an older version later.</span>
              </Label>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="release-note">
                Note for this version <span className="font-normal text-muted-foreground">(optional)</span>
              </Label>
              <Textarea id="release-note" value={note} onChange={(e) => setNote(e.target.value)} maxLength={1000} rows={2} />
            </div>
            {error && describeProblem(error).behavior !== 'open-validation' ? <ProblemAlert error={error} onRetry={describeProblem(error).retryable ? () => void publish() : undefined} retrying={pending} /> : null}
            {error && describeProblem(error).behavior === 'open-validation' ? (
              <Alert tone="danger" title="The server found problems">
                {describeProblem(error).message} The list above shows what to fix.
              </Alert>
            ) : null}
            {!can('workflow.publish') ? <Alert tone="warning">You can review this draft, but you need permission to publish it.</Alert> : null}
            <div className="flex flex-wrap gap-3">
              <Button variant="primary" size="lg" onClick={() => void publish()} loading={pending} disabled={!canPublishNow}>
                <Rocket aria-hidden="true" /> Publish version {nextNumber}
              </Button>
            </div>
          </CardContent>
        </Card>
      </div>
    </>
  )
}
