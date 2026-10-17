import { useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, ShieldAlert } from 'lucide-react'
import { useId, useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { isProblemError } from '@/api/problem'
import { useWorkspace } from '@/app/workspace'
import { useAnnounce } from '@/components/common/Announcer'
import { RelativeTime } from '@/components/common/RelativeTime'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { approveBlock, publishBlock, type MigrationReview } from './reviewGate'

export const reviewKey = (ws: string, workflowId: string) => ['ws', ws, 'workflow', workflowId, 'migration-review'] as const

/** Review state of a migrated workflow; ordinary workflows answer `migration: false`. */
export function useMigrationReview(ws: string, workflowId: string, enabled: boolean) {
  return useQuery({ queryKey: reviewKey(ws, workflowId), queryFn: () => endpoints.getMigrationReview(ws, workflowId), enabled, staleTime: 0, retry: false })
}

const short = (hash: string | null) => (hash ? `${hash.slice(0, 12)}…` : 'none')

/**
 * Independent review of a migrated workflow (docs/17-migration-workbench.md): who mapped it, who approved which exact
 * draft, and why publishing is or is not blocked. Approval is bound to the draft, the source and the mappings; the
 * server enforces every rule and this panel only explains them.
 */
export function MigrationReviewPanel({ workflowId, review, draftAuthorId, requestedRevision }: { workflowId: string; review: MigrationReview; draftAuthorId: string | undefined; requestedRevision: number }) {
  const { workspace, me, can } = useWorkspace()
  const ws = workspace.id
  const client = useQueryClient()
  const announce = useAnnounce()
  const ids = useId()
  const [reason, setReason] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const who = { meId: me.subject.id, draftAuthorId }
  const publishReason = publishBlock(review, who, requestedRevision)
  const stale = requestedRevision !== review.draftRevision
  const blocked = approveBlock(review, who, can('workflow.publish')) ?? (stale ? 'A newer draft exists. Open the latest draft to review it; an approval only applies to the latest draft.' : null)

  const approve = async () => {
    setPending(true); setError(null)
    try {
      const next = await endpoints.approveMigrationReview(ws, workflowId, { draftRevision: review.draftRevision, expectedVersion: review.reviewVersion, reason: reason.trim() })
      client.setQueryData(reviewKey(ws, workflowId), next)
      setReason('')
      announce('Migration review approved for this exact draft.')
    } catch (cause) {
      setError(isProblemError(cause) ? (cause.detail ?? cause.title) : 'The review could not be saved.')
      // A conflict or refusal means the state moved on; show the current one.
      void client.invalidateQueries({ queryKey: reviewKey(ws, workflowId) })
    } finally { setPending(false) }
  }

  return <Card aria-labelledby={`${ids}-title`} data-testid="migration-review">
    <CardHeader>
      <div className="flex flex-wrap items-center gap-2">
        <h2 id={`${ids}-title`}>Migration review</h2>
        {review.approved ? <Badge tone="success"><CheckCircle2 aria-hidden="true" /> Independently reviewed</Badge> : <Badge tone={review.required ? 'danger' : 'warning'}><ShieldAlert aria-hidden="true" /> {review.required ? 'Review required' : 'Not reviewed'}</Badge>}
        <Badge tone={review.required ? 'primary' : 'neutral'}>{review.required ? 'Workspace policy: independent review required' : 'Workspace policy: review optional'}</Badge>
      </div>
      <p className="text-sm text-muted-foreground">{review.detail}</p>
    </CardHeader>
    <CardContent className="space-y-4">
      <dl className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-[max-content_1fr]">
        <dt className="font-semibold">Mapped by</dt><dd>{review.mapper ? review.mapper.displayName : 'Unknown (historical conversion)'}</dd>
        <dt className="font-semibold">Reviewed by</dt><dd>{review.reviewedBy ? <>{review.reviewedBy.displayName}{review.reviewedAt ? <> <RelativeTime value={review.reviewedAt} /></> : null}{review.reviewReason ? <span className="block text-muted-foreground">Reason: {review.reviewReason}</span> : null}</> : 'Nobody yet'}</dd>
        <dt className="font-semibold">Draft revision</dt><dd>{review.draftRevision}{stale ? <span className="text-muted-foreground"> (you are reviewing revision {requestedRevision}; the review applies to the latest draft only)</span> : null}</dd>
        <dt className="font-semibold">Approval covers</dt><dd>{review.approved ? 'Exactly these hashes' : 'Nothing: no current approval matches these hashes'}</dd>
        <dt className="font-semibold">Hashes</dt>
        <dd><ul aria-label="Reviewed hashes" className="space-y-0.5 font-mono text-xs">
          <li><span className="font-sans font-semibold">Draft</span> <span title={review.definitionHash}>{short(review.definitionHash)}</span></li>
          <li><span className="font-sans font-semibold">Source</span> <span title={review.sourceHash ?? undefined}>{short(review.sourceHash)}</span></li>
          <li><span className="font-sans font-semibold">Mappings</span> <span title={review.mappingsHash ?? undefined}>{short(review.mappingsHash)}</span></li>
        </ul>
        <details className="mt-1 text-xs"><summary className="cursor-pointer font-semibold">Show full hashes</summary>
          <p className="mt-1 break-all font-mono">Draft {review.definitionHash}</p><p className="break-all font-mono">Source {review.sourceHash ?? 'none'}</p><p className="break-all font-mono">Mappings {review.mappingsHash ?? 'none'}</p></details></dd>
      </dl>

      {publishReason ? <Alert tone="warning" title="Publishing is blocked" data-testid="publish-blocked-reason">{publishReason}</Alert>
        : review.required && review.approved ? <Alert tone="success" live="off">The independent review is in place. Anyone who is neither the mapper, the reviewer nor the draft author can publish this draft.</Alert>
        : !review.required ? <Alert tone="info" live="off">Your workspace does not require an independent review. You can still ask a colleague to approve this draft.</Alert> : null}

      {review.approved ? null : <form aria-label="Approve migration review" className="space-y-3" onSubmit={(event) => { event.preventDefault(); void approve() }}>
        <Label htmlFor={`${ids}-reason`}>Review reason</Label>
        <Textarea id={`${ids}-reason`} required maxLength={1000} rows={3} value={reason} disabled={!!blocked} onChange={(e) => setReason(e.target.value)}
          aria-describedby={blocked ? `${ids}-why` : `${ids}-help`} />
        <p id={`${ids}-help`} className="text-sm text-muted-foreground">Say what you checked, for example the mappings against the source report. The approval is recorded with your name and applies to this exact draft only.</p>
        {blocked ? <p id={`${ids}-why`} className="text-sm font-semibold text-danger-foreground" data-testid="approve-disabled-reason">{blocked}</p> : null}
        {error ? <Alert tone="danger">{error}</Alert> : null}
        <Button type="submit" variant="primary" loading={pending} disabled={!!blocked || !reason.trim()} aria-describedby={blocked ? `${ids}-why` : undefined}>Approve migration review</Button>
      </form>}
    </CardContent>
  </Card>
}
