import { Link } from 'react-router'
import type { Run } from '@/api/types'
import { RelativeTime } from '@/components/common/RelativeTime'
import { StatusBadge } from '@/components/common/StatusBadge'
import { Button } from '@/components/ui/button'
import { StageBadge } from './StageProgress'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { formatDateTime } from '@/lib/dates'
import { requestId, runProgressText, runTitle } from '@/lib/runText'
import { useIsDesktop } from '@/lib/useMediaQuery'

function whereItIsNow(run: Run) {
  return run.lifecycle === 'needsAttention' && run.attention ? <span className="text-status-attention-fg">{run.attention.message}</span> : runProgressText(run)
}

/**
 * Shared list of runs: My requests (requester view) and Runs (operator or viewer view). A table from 1024 px; on
 * narrower screens each request is a card, so nothing needs sideways scrolling. Operators get one clear next step on a
 * request that needs attention: "Review recovery" opens the run at its operator actions.
 */
export function RunsTable({ runs, wsId, showRequester, caption, canOperate = false }: { runs: Run[]; wsId: string; showRequester?: boolean; caption: string; canOperate?: boolean }) {
  const desktop = useIsDesktop()
  const recovery = (run: Run) =>
    canOperate && run.lifecycle === 'needsAttention' ? (
      <Button asChild size="sm" variant="primary" className="mt-2">
        <Link to={`/w/${wsId}/runs/${run.id}#operator-actions`} aria-label={`Review recovery for ${requestId(run)}`}>
          Review recovery
        </Link>
      </Button>
    ) : null
  if (!desktop) {
    return (
      <ul aria-label={caption} className="space-y-3">
        {runs.map((run) => (
          <li key={run.id} className="rounded-card border border-border bg-surface p-4 shadow-card">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <Link to={`/w/${wsId}/runs/${run.id}`} className="text-base font-semibold whitespace-nowrap tabular-nums">
                {requestId(run)}
              </Link>
              <StatusBadge lifecycle={run.lifecycle} outcome={run.outcome} outcomeKind={run.outcomeKind} />
            </div>
            {runTitle(run) ? <p className="mt-1 font-semibold wrap-anywhere">{runTitle(run)}</p> : null}
            <p className={runTitle(run) ? 'text-sm text-muted-foreground' : 'mt-1 font-semibold'}>
              {run.workflow.name} <span className="font-normal text-muted-foreground">&middot; Version {run.release.releaseNumber}</span>
            </p>
            <p className="mt-1 text-sm text-muted-foreground">{whereItIsNow(run)}</p>
            {run.stage ? <p className="mt-1.5"><StageBadge stage={run.stage} /></p> : null}
            {recovery(run)}
            <p className="mt-2 text-xs text-muted-foreground">
              {showRequester ? <>Requested by {run.initiator.displayName} &middot; </> : null}
              <span title={formatDateTime(run.createdAt)}>
                Submitted <RelativeTime value={run.createdAt} />
              </span>
            </p>
          </li>
        ))}
      </ul>
    )
  }
  return (
    <div className="overflow-hidden rounded-card border border-border bg-surface shadow-card">
      <Table>
        <caption className="sr-only">{caption}</caption>
        <THead>
          <TR>
            <TH>Request</TH>
            <TH>Status</TH>
            <TH>Where it is now</TH>
            {showRequester ? <TH>Requested by</TH> : null}
            <TH>Submitted</TH>
          </TR>
        </THead>
        <TBody>
          {runs.map((run) => (
            <TR key={run.id} className="hover:bg-surface-muted">
              <TD className="max-w-72">
                <Link to={`/w/${wsId}/runs/${run.id}`} className="font-semibold whitespace-nowrap tabular-nums">
                  {requestId(run)}
                </Link>
                {runTitle(run) ? <span className="block truncate" title={runTitle(run) ?? undefined}>{runTitle(run)}</span> : null}
                <span className="block text-xs whitespace-nowrap text-muted-foreground">
                  {run.workflow.name} &middot; Version {run.release.releaseNumber}
                </span>
              </TD>
              <TD>
                <StatusBadge lifecycle={run.lifecycle} outcome={run.outcome} outcomeKind={run.outcomeKind} />
              </TD>
              <TD className="max-w-96 text-muted-foreground">
                {whereItIsNow(run)}
                {run.stage ? <div className="mt-1"><StageBadge stage={run.stage} /></div> : null}
                {recovery(run) ? <div>{recovery(run)}</div> : null}
              </TD>
              {showRequester ? <TD className="whitespace-nowrap">{run.initiator.displayName}</TD> : null}
              <TD className="whitespace-nowrap text-muted-foreground">
                <span title={formatDateTime(run.createdAt)}>
                  <RelativeTime value={run.createdAt} />
                </span>
              </TD>
            </TR>
          ))}
        </TBody>
      </Table>
    </div>
  )
}
