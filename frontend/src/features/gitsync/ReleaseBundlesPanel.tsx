import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Package } from 'lucide-react'
import { useMemo, useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { keys } from '@/api/queries'
import type { BundlePromotion, BundlePromotionPlan, ReleaseBundle } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { formatDateTime } from '@/lib/dates'

const TYPE: Record<string, string> = {
  workflow: 'Workflow', ruleTable: 'Rule table', requestForm: 'Request form', sharedDecision: 'Shared decision',
  sharedForm: 'Shared form', documentTemplate: 'Document template', calendar: 'Working calendar', view: 'View',
  connection: 'Connection', childWorkflow: 'Called workflow', aiSkill: 'AI skill', testSuite: 'Test suite',
}
const ACTION: Record<string, string> = {
  reuse: 'Reuse', create: 'Create', newVersion: 'New version', copy: 'Copy', ready: 'Ready', blocked: 'Blocked',
}

/**
 * Release bundles (CG-S02, docs/build/S147): publish a draft with everything it pins as one bundle, see each bundle's
 * components and hash, and promote a bundle to a later-stage workspace as one unit (it arrives there as a draft).
 */
export function ReleaseBundlesPanel({ workflowId, draftRevision }: { workflowId: string; draftRevision: number }) {
  const { workspace, me, can } = useWorkspace()
  const ws = workspace.id
  const client = useQueryClient()
  const bundlesKey = ['ws', ws, 'workflow', workflowId, 'bundles'] as const
  const bundles = useQuery({ queryKey: bundlesKey, queryFn: () => endpoints.listReleaseBundles(ws, workflowId), enabled: can('workflow.read') })
  const [pending, setPending] = useState<string | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [acknowledge, setAcknowledge] = useState(false)
  const [open, setOpen] = useState<string | null>(null)
  const [targetId, setTargetId] = useState('')
  const [plan, setPlan] = useState<BundlePromotionPlan | null>(null)
  const [promoted, setPromoted] = useState<BundlePromotion | null>(null)
  const targets = useMemo(
    () => me.workspaces.filter((w) => w.id !== ws && w.organizationName === workspace.organizationName && w.permissions.includes('workflow.edit')),
    [me.workspaces, ws, workspace.organizationName],
  )

  if (!can('workflow.read')) return null

  const run = async (kind: string, action: () => Promise<void>) => {
    setPending(kind)
    setError(null)
    try {
      await action()
    } catch (cause) {
      setError(toProblem(cause))
    } finally {
      setPending(null)
    }
  }

  const publish = () =>
    void run('publish', async () => {
      await endpoints.publishReleaseBundle(ws, workflowId, { draftRevision, acknowledgeWarnings: acknowledge, activate: true }, crypto.randomUUID())
      await Promise.all([
        client.invalidateQueries({ queryKey: bundlesKey }),
        client.invalidateQueries({ queryKey: keys.workflow(ws, workflowId) }),
        client.invalidateQueries({ queryKey: keys.releases(ws, workflowId) }),
      ])
    })

  const target = targets.find((t) => t.id === targetId)
  const check = (bundle: ReleaseBundle) =>
    void run('check', async () => {
      setPromoted(null)
      setPlan(await endpoints.checkBundlePromotion(ws, bundle.id, { targetWorkspaceId: targetId }))
    })
  const promote = (bundle: ReleaseBundle) =>
    void run('promote', async () => {
      if (!plan) return
      setPromoted(await endpoints.promoteReleaseBundle(ws, bundle.id, {
        targetWorkspaceId: targetId,
        expectedDraftRevision: plan.targetWorkflow.exists ? plan.targetWorkflow.draftRevision : null,
      }))
      setPlan(null)
    })

  return (
    <section aria-labelledby="bundles-heading" className="mt-8">
      <h2 id="bundles-heading" className="mb-1 flex items-center gap-2">
        <Package className="size-5" aria-hidden="true" /> Release bundles
      </h2>
      <p className="mb-3 text-sm text-muted-foreground">
        A bundle is a version published together with the rule tables, forms, views, connections and tests it uses, so it
        moves between stages as one unit.
      </p>
      {error ? <ProblemAlert error={error} className="mb-3" context={{ action: 'work with the bundle' }} /> : null}
      {can('workflow.publish') ? (
        <div className="mb-4 flex flex-wrap items-center gap-3">
          <div className="flex items-center gap-2">
            <Checkbox id="bundle-ack" checked={acknowledge} onCheckedChange={(v) => setAcknowledge(v === true)} />
            <Label htmlFor="bundle-ack">I reviewed the warnings</Label>
          </div>
          <Button variant="primary" onClick={publish} loading={pending === 'publish'}>Publish draft revision {draftRevision} as a bundle</Button>
        </div>
      ) : null}
      {bundles.isPending ? (
        <LoadingBlock rows={1} label="Loading bundles" />
      ) : bundles.isError ? (
        <QueryError error={bundles.error} onRetry={() => void bundles.refetch()} what="the bundles" />
      ) : bundles.data.items.length === 0 ? (
        <p className="text-sm text-muted-foreground">No bundles yet.</p>
      ) : (
        <div className="grid gap-3">
          {bundles.data.items.map((b) => (
            <div key={b.id} className="rounded-card border border-border bg-surface p-4" data-testid="release-bundle">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <p className="font-semibold">Version {b.releaseNumber} bundle</p>
                <span className="font-mono text-xs" title={b.bundleHash}>{b.bundleHash.slice(0, 16)}</span>
              </div>
              <p className="text-sm text-muted-foreground">
                Published {formatDateTime(b.publishedAt)} by {b.publishedBy.displayName}.
                {b.source ? ` Promoted from ${b.source.workspaceKey ?? 'another workspace'}; ${b.source.sameContent ? 'same content as there.' : 'the content differs from there.'}` : ''}
              </p>
              <Table aria-label={`Components of the version ${b.releaseNumber} bundle`} className="mt-2">
                <THead><TR><TH>Component</TH><TH>Key</TH><TH>Version</TH></TR></THead>
                <TBody>
                  {b.components.map((c, i) => (
                    <TR key={i}><TD>{TYPE[c.type] ?? c.type}</TD><TD>{c.key}</TD><TD>{c.version ?? ''}</TD></TR>
                  ))}
                </TBody>
              </Table>
              {can('workflow.export') && targets.length > 0 ? (
                open === b.id ? (
                  <div className="mt-3 grid gap-2">
                    <div className="flex flex-wrap items-end gap-2">
                      <div>
                        <Label htmlFor={`bundle-target-${b.id}`}>Promote to</Label>
                        <Select id={`bundle-target-${b.id}`} value={targetId} onChange={(e) => { setTargetId(e.target.value); setPlan(null) }}>
                          <option value="">Choose a workspace</option>
                          {targets.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
                        </Select>
                      </div>
                      <Button onClick={() => check(b)} loading={pending === 'check'} disabled={!targetId}>Check</Button>
                    </div>
                    {plan ? (
                      <div data-testid="bundle-plan">
                        <Table aria-label="Promotion plan">
                          <THead><TR><TH>Component</TH><TH>Key</TH><TH>In {target?.name}</TH><TH>Note</TH></TR></THead>
                          <TBody>
                            {plan.items.map((item, i) => (
                              <TR key={i}>
                                <TD>{TYPE[item.type] ?? item.type}</TD>
                                <TD>{item.key}</TD>
                                <TD><Badge tone={item.action === 'blocked' ? 'danger' : 'neutral'}>{ACTION[item.action] ?? item.action}</Badge>{item.targetVersion ? ` ${item.targetVersion}` : ''}</TD>
                                <TD>{item.problem ?? ''}</TD>
                              </TR>
                            ))}
                          </TBody>
                        </Table>
                        {plan.approvalRequired ? <Alert tone="warning" className="mt-2">This workspace requires promotion approval; promote the version with an approval request instead.</Alert> : null}
                        {plan.promotable ? (
                          <Button variant="primary" className="mt-2" onClick={() => promote(b)} loading={pending === 'promote'}>Promote bundle</Button>
                        ) : <Alert tone="warning" className="mt-2">The bundle cannot be promoted until the blocked items are resolved in the target.</Alert>}
                      </div>
                    ) : null}
                    {promoted ? (
                      <Alert tone="success">Saved as draft revision {promoted.draftRevision} in {target?.name}. Publish it there as a bundle.</Alert>
                    ) : null}
                  </div>
                ) : (
                  <Button size="sm" className="mt-3" onClick={() => { setOpen(b.id); setPlan(null); setPromoted(null) }}>Promote…</Button>
                )
              ) : null}
            </div>
          ))}
        </div>
      )}
    </section>
  )
}
