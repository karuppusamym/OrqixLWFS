import { useQueryClient } from '@tanstack/react-query'
import { Gauge } from 'lucide-react'
import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import type { ProblemError } from '@/api/problem'
import { keys, useReleaseAnalytics, useWorkflowRollout } from '@/api/queries'
import type { ReleaseSummary } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input, Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'

const DAYS = 30

/** Canary share and per-version request figures (PL02, docs/build/S54). Counts only: no request data is shown. */
export function ReleaseRollout({ workflowId, releases }: { workflowId: string; releases: ReleaseSummary[] }) {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const client = useQueryClient()
  const canRead = can('workflow.read')
  const canPublish = can('workflow.publish')
  const rollout = useWorkflowRollout(ws, workflowId, canRead)
  const analytics = useReleaseAnalytics(ws, workflowId, DAYS, can('run.read'))
  const [releaseId, setReleaseId] = useState('')
  const [percent, setPercent] = useState('10')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ProblemError | null>(null)

  if (!canRead && !can('run.read')) return null
  const candidates = releases.filter((r) => !r.isActive)
  const canary = rollout.data?.canary ?? null

  const refresh = async () => {
    await Promise.all([
      client.invalidateQueries({ queryKey: keys.rollout(ws, workflowId) }),
      client.invalidateQueries({ queryKey: keys.workflow(ws, workflowId) }),
      client.invalidateQueries({ queryKey: keys.releaseAnalytics(ws, workflowId, DAYS) }),
    ])
  }

  const run = async (action: () => Promise<unknown>) => {
    setPending(true)
    setError(null)
    try {
      await action()
      await refresh()
    } catch (cause) {
      const problem = toProblem(cause)
      setError(problem)
      if (problem.code === 'WORKFLOW_VERSION_CONFLICT') await refresh()
    } finally {
      setPending(false)
    }
  }

  const start = () => {
    if (!rollout.data || !releaseId) return
    const share = Number(percent)
    void run(() => endpoints.setWorkflowCanary(ws, workflowId, { releaseId, percent: share, expectedVersion: rollout.data.version }))
  }

  return (
    <section aria-labelledby="rollout-heading" className="mt-8">
      <h2 id="rollout-heading" className="mb-3 flex items-center gap-2">
        <Gauge className="size-5" aria-hidden="true" /> Gradual rollout
      </h2>
      {error ? <ProblemAlert error={error} className="mb-3" context={{ action: 'change the rollout' }} /> : null}
      {canRead ? (
        rollout.isPending ? (
          <LoadingBlock rows={1} label="Loading the rollout" />
        ) : rollout.isError ? (
          <QueryError error={rollout.error} onRetry={() => void rollout.refetch()} what="the rollout" />
        ) : (
          <div className="mb-4 rounded-card border border-border bg-surface p-4">
            {canary ? (
              <div className="flex flex-wrap items-center justify-between gap-3">
                <p data-testid="canary-status">
                  <span className="font-semibold">{canary.percent}%</span> of new requests use version {canary.releaseNumber}; the rest use version{' '}
                  {rollout.data.activeReleaseNumber}. Existing requests are unchanged.
                </p>
                {canPublish ? (
                  <Button variant="secondary" loading={pending} onClick={() => void run(() => endpoints.clearWorkflowCanary(ws, workflowId, rollout.data.version))}>
                    Stop the rollout
                  </Button>
                ) : null}
              </div>
            ) : (
              <>
                <p className="mb-3 text-sm text-muted-foreground">All new requests use the active version. A version can receive 1 to 50 percent of new requests only when its request form matches the active version.</p>
                {canPublish && rollout.data.activeReleaseNumber !== null && candidates.length > 0 ? (
                  <div className="flex flex-wrap items-end gap-3">
                    <div className="space-y-1.5">
                      <Label htmlFor="canary-release">Version to try</Label>
                      <Select id="canary-release" value={releaseId} onChange={(e) => setReleaseId(e.target.value)} className="w-48">
                        <option value="">Choose a version</option>
                        {candidates.map((r) => (
                          <option key={r.id} value={r.id}>
                            Version {r.releaseNumber}
                          </option>
                        ))}
                      </Select>
                    </div>
                    <div className="space-y-1.5">
                      <Label htmlFor="canary-percent">Share of new requests (%)</Label>
                      <Input id="canary-percent" type="number" min={1} max={50} value={percent} onChange={(e) => setPercent(e.target.value)} className="w-32" />
                    </div>
                    <Button variant="primary" loading={pending} disabled={!releaseId || !(Number(percent) >= 1 && Number(percent) <= 50)} onClick={start}>
                      Start rollout
                    </Button>
                  </div>
                ) : null}
              </>
            )}
          </div>
        )
      ) : null}

      {can('run.read') ? (
        analytics.isPending ? (
          <LoadingBlock rows={2} label="Loading request figures" />
        ) : analytics.isError ? (
          <QueryError error={analytics.error} onRetry={() => void analytics.refetch()} what="the request figures" />
        ) : (
          <div className="overflow-hidden rounded-card border border-border bg-surface">
            <Table>
              <caption className="sr-only">Requests by version in the last {DAYS} days</caption>
              <THead>
                <TR>
                  <TH>Version</TH>
                  <TH>Started</TH>
                  <TH>In progress</TH>
                  <TH>Needs attention</TH>
                  <TH>Approved</TH>
                  <TH>Rejected</TH>
                  <TH>Cancelled</TH>
                </TR>
              </THead>
              <TBody>
                {analytics.data.releases.map((r) => (
                  <TR key={r.releaseId}>
                    <TD>
                      Version {r.releaseNumber} {r.active ? <Badge tone="success">Active</Badge> : null}
                      {r.canary ? <Badge tone="info">Rollout</Badge> : null}
                    </TD>
                    <TD>{r.started}</TD>
                    <TD>{r.open}</TD>
                    <TD>{r.needsAttention}</TD>
                    <TD>{r.succeeded}</TD>
                    <TD>{r.rejected}</TD>
                    <TD>{r.cancelled}</TD>
                  </TR>
                ))}
              </TBody>
            </Table>
          </div>
        )
      ) : null}
    </section>
  )
}
