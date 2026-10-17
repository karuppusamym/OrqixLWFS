import { useState } from 'react'
import { useRelease } from '@/api/queries'
import type { ReleaseSummary } from '@/api/types'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { QueryError } from '@/components/common/QueryError'
import { Badge } from '@/components/ui/badge'
import { Select } from '@/components/ui/input'
import { normalizeDefinition } from '@/features/studio/model/types'
import { diffDefinitions } from './diff'

/** Compare two immutable releases. Options include all release pages already loaded in the overview. */
export function VersionComparison({ workspaceId, workflowId, releases }: { workspaceId: string; workflowId: string; releases: ReleaseSummary[] }) {
  const ordered = [...releases].sort((a, b) => b.releaseNumber - a.releaseNumber)
  const [beforeId, setBeforeId] = useState(ordered[1]?.id ?? '')
  const [afterId, setAfterId] = useState(ordered[0]?.id ?? '')
  const before = useRelease(workspaceId, workflowId, beforeId)
  const after = useRelease(workspaceId, workflowId, afterId)
  const changes = before.data && after.data ? diffDefinitions(normalizeDefinition(before.data.definition), normalizeDefinition(after.data.definition)) : []
  return <section aria-label="Compare published versions" className="my-4 space-y-4 rounded-card border border-border bg-surface p-4">
    <h3>Compare published versions</h3>
    <p className="text-sm text-muted-foreground">Review changes from the first selected version to the second. Load more versions below to include older releases. Canvas positions are omitted.</p>
    <div className="flex flex-wrap gap-4">
      <label className="space-y-1">From version
        <Select aria-label="From version" value={beforeId} onChange={(event) => setBeforeId(event.target.value)}>
          {ordered.map((release) => <option key={release.id} value={release.id}>Version {release.releaseNumber}</option>)}
        </Select>
      </label>
      <label className="space-y-1">To version
        <Select aria-label="To version" value={afterId} onChange={(event) => setAfterId(event.target.value)}>
          {ordered.map((release) => <option key={release.id} value={release.id}>Version {release.releaseNumber}</option>)}
        </Select>
      </label>
    </div>
    {before.isError ? <QueryError error={before.error} onRetry={() => void before.refetch()} what="the first version" /> : after.isError ? <QueryError error={after.error} onRetry={() => void after.refetch()} what="the second version" /> : before.isPending || after.isPending ? <LoadingBlock rows={2} label="Loading version comparison" /> : changes.length === 0 ? <p role="status">No definition changes between these versions.</p> : <ul className="space-y-3" aria-label="Version changes">
      {changes.map((change, index) => <li key={index} className="rounded-control border border-border p-3 text-sm">
        <p className="font-semibold"><Badge tone={change.kind === 'added' ? 'success' : change.kind === 'removed' ? 'danger' : 'warning'}>{change.kind}</Badge> {change.title}</p>
        {change.details.length > 0 ? <ul className="mt-2 list-disc space-y-1 pl-6">{change.details.map((detail, i) => <li key={i} className="whitespace-pre-wrap break-words">{detail}</li>)}</ul> : null}
      </li>)}
    </ul>}
  </section>
}
