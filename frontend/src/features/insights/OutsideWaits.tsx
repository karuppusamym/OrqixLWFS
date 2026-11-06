import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { api, call } from '@/api/client'
import type { components } from '@/api/schema'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { QueryError } from '@/components/common/QueryError'
import { ScrollTable } from '@/components/common/ScrollTable'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { TBody, TD, TH, THead, TR } from '@/components/ui/table'
import type { InsightsQuery } from './api'
import { formatSeconds } from './format'

type OutsideWait = components['schemas']['InsightsOutsideWait']

const KIND: Record<OutsideWait['kind'], string> = {
  poll: 'Checking a system',
  message: 'Waiting for a message',
  callback: 'Waiting for a callback',
  externalJob: 'Outside worker',
}

export function useInsightsOutsideWaits(ws: string, q: InsightsQuery, enabled = true) {
  return useQuery({
    queryKey: ['ws', ws, 'insights', 'outside-waits', q] as const,
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/insights/outside-waits', {
      params: { path: { workspaceId: ws }, query: { from: q.from, to: q.to, ...(q.workflowId ? { workflowId: q.workflowId } : {}) } },
    })),
    enabled, staleTime: 30_000, refetchOnWindowFocus: false, retry: 1, placeholderData: keepPreviousData,
  })
}

/**
 * "Time spent waiting on outside systems" (docs/build/S129, CG-W08): requests waiting now on a connection, a message or
 * a worker, how long they have waited so far, and how long finished checks took in the period.
 */
export function OutsideWaits({ ws, query, enabled }: { ws: string; query: InsightsQuery; enabled: boolean }) {
  const result = useInsightsOutsideWaits(ws, query, enabled)
  const items = result.data?.items ?? []
  return (
    <Card data-testid="outside-waits">
      <CardHeader>
        <h2>Waiting on outside systems</h2>
        <p className="text-sm text-muted-foreground">
          Time requests spend waiting for other systems: checks of a connection, messages and outside workers.
          {result.data ? ` Waiting now in total: ${formatSeconds(result.data.totalOpenSeconds)}.` : ''}
        </p>
      </CardHeader>
      <CardContent>
        {result.isPending ? <LoadingBlock rows={2} label="Loading the table" />
          : result.isError ? <QueryError error={result.error} onRetry={() => void result.refetch()} what="the table" />
            : items.length === 0 ? <p className="text-sm text-muted-foreground">Nothing is waiting on another system, and no check finished in this period.</p>
              : (
                <ScrollTable label="Waiting on outside systems">
                  <THead>
                    <TR>
                      <TH>System</TH>
                      <TH>Kind</TH>
                      <TH>Waiting now</TH>
                      <TH>Longest so far</TH>
                      <TH>Finished</TH>
                      <TH>Average</TH>
                      <TH>90th percentile</TH>
                    </TR>
                  </THead>
                  <TBody>
                    {items.map((w) => (
                      <TR key={`${w.kind}|${w.source}`}>
                        <TD className="font-semibold">{w.source}</TD>
                        <TD>{KIND[w.kind]}</TD>
                        <TD className="tabular-nums">{w.openNow}</TD>
                        <TD className="tabular-nums">{formatSeconds(w.longestOpenSeconds)}</TD>
                        <TD className="tabular-nums">{w.finished}</TD>
                        <TD className="tabular-nums">{formatSeconds(w.averageFinishedSeconds)}</TD>
                        <TD className="tabular-nums">{formatSeconds(w.p90FinishedSeconds)}</TD>
                      </TR>
                    ))}
                  </TBody>
                </ScrollTable>
              )}
      </CardContent>
    </Card>
  )
}
