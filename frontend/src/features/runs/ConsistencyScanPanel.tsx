import { ScanSearch } from 'lucide-react'
import { Link } from 'react-router'
import { useConsistencyFindings } from '@/api/queries'
import type { ConsistencyFinding } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { Alert } from '@/components/ui/alert'
import { formatDateTime } from '@/lib/dates'

export const FINDING_LABELS: Record<ConsistencyFinding['state'], string> = {
  projectionDrift: 'Open steps are missing from the request',
  strandedToken: 'A step is stuck and nothing can move it',
  emptyRequest: 'The engine holds no work for the request',
  engineMissing: 'The workflow engine has no record of the request',
  unsupported: 'The difference cannot be repaired automatically',
}

/**
 * Findings of the periodic consistency scan (EX09, docs/build/S67). The scan only reports; each finding opens the request,
 * where the guarded "Check consistency" action rebuilds or cancels. Shown to operators only, and only when there is
 * something to say (the scan is opt-in, so a quiet panel is not noise).
 */
export function ConsistencyScanPanel() {
  const { workspace, can } = useWorkspace()
  const operator = can('run.operate')
  const query = useConsistencyFindings(workspace.id, operator)
  if (!operator || !query.data) return null
  const { enabled, items, total, scannedRuns, lastCheckedAt } = query.data
  if (!enabled && items.length === 0) return null
  return (
    <section aria-labelledby="scan-heading" className="mb-4 rounded-card border border-border bg-surface p-4">
      <h2 id="scan-heading" className="mb-1 flex items-center gap-2 text-base">
        <ScanSearch className="size-4" aria-hidden="true" /> Consistency scan
      </h2>
      <p className="mb-3 text-sm text-muted-foreground">
        {enabled ? `Checked ${scannedRuns} active ${scannedRuns === 1 ? 'request' : 'requests'}${lastCheckedAt ? `, last at ${formatDateTime(lastCheckedAt)}` : ''}. ` : 'The periodic scan is switched off; earlier findings are shown. '}
        The scan only reports. Open a request and use Check consistency to rebuild or cancel it.
      </p>
      {items.length === 0 ? (
        <Alert tone="success">Nothing found. Every request checked matches the workflow engine.</Alert>
      ) : (
        <>
          <ul className="divide-y divide-border" aria-label="Requests that do not match the workflow engine">
            {items.map((f) => (
              <li key={f.runId} className="flex flex-wrap items-baseline justify-between gap-2 py-2 text-sm">
                <div className="min-w-0">
                  <Link to={`/w/${workspace.id}/runs/${f.runId}#operator-actions`} className="font-semibold tabular-nums" aria-label={`Review ${f.reference}`}>
                    {f.reference}
                  </Link>{' '}
                  <span>{FINDING_LABELS[f.state] ?? f.state}</span>
                  <p className="text-muted-foreground">{f.message}</p>
                </div>
                <span className="text-muted-foreground">
                  {f.repairable ? 'Can be rebuilt' : 'Needs a decision'} · found {formatDateTime(f.firstSeenAt)}
                </span>
              </li>
            ))}
          </ul>
          {total > items.length ? <p className="mt-2 text-sm text-muted-foreground">Showing {items.length} of {total}.</p> : null}
        </>
      )}
    </section>
  )
}
