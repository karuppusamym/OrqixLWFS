import { LoadingBlock } from '@/components/common/LoadingBlock'
import { QueryError } from '@/components/common/QueryError'
import { ScrollTable } from '@/components/common/ScrollTable'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { formatSeconds, percent } from '@/features/insights/format'
import { useHumanWork, type AnalyticsRange } from './api'

const attainment = (v: number | null) => (v === null ? '—' : percent(v))

/**
 * Human work (CG-N05, docs/build/S145): per group the load now and, for tasks finished in the period, waiting time
 * (nobody assigned) versus working time (someone assigned), SLA attainment and reassignments; then the same per step.
 */
export function HumanWorkCard({ ws, query, enabled }: { ws: string; query: AnalyticsRange & { workflowId?: string }; enabled: boolean }) {
  const result = useHumanWork(ws, query, enabled)
  const groups = result.data?.groups ?? []
  const steps = result.data?.steps ?? []
  return (
    <Card data-testid="human-work">
      <CardHeader>
        <h2>Human work</h2>
        <p className="text-sm text-muted-foreground">
          Waiting is time a task had nobody assigned; working is time someone had it. SLA attainment counts tasks with a due time finished in time.
        </p>
      </CardHeader>
      <CardContent className="space-y-4">
        {result.isPending ? <LoadingBlock rows={2} label="Loading human work" />
          : result.isError ? <QueryError error={result.error} onRetry={() => void result.refetch()} what="human work" />
            : groups.length === 0 && steps.length === 0 ? <p className="text-sm text-muted-foreground">No human tasks finished in this period and none are open.</p>
              : (
                <>
                  <ScrollTable label="Load and figures per group">
                    <THead>
                      <TR>
                        <TH>Group</TH>
                        <TH>Open now</TH>
                        <TH>Not picked up</TH>
                        <TH>Oldest open</TH>
                        <TH>Finished</TH>
                        <TH>Average waiting</TH>
                        <TH>Average working</TH>
                        <TH>SLA attainment</TH>
                        <TH>Reassignments</TH>
                      </TR>
                    </THead>
                    <TBody>
                      {groups.map((g) => (
                        <TR key={g.groupKey} data-testid={`group-${g.groupKey}`}>
                          <TD className="font-semibold">{g.groupName ?? g.groupKey}</TD>
                          <TD className="tabular-nums">{g.openNow}</TD>
                          <TD className="tabular-nums">{g.openUnassigned}</TD>
                          <TD className="tabular-nums">{formatSeconds(g.oldestOpenSeconds)}</TD>
                          <TD className="tabular-nums">{g.finished}</TD>
                          <TD className="tabular-nums">{formatSeconds(g.averageWaitingSeconds)}</TD>
                          <TD className="tabular-nums">{formatSeconds(g.averageWorkingSeconds)}</TD>
                          <TD className="tabular-nums">{attainment(g.slaAttainment)}</TD>
                          <TD className="tabular-nums">{g.reassignments}</TD>
                        </TR>
                      ))}
                    </TBody>
                  </ScrollTable>
                  {steps.length > 0 ? (
                    <ScrollTable label="Waiting and working time per step">
                      <THead>
                        <TR>
                          <TH>Workflow</TH>
                          <TH>Step</TH>
                          <TH>Group</TH>
                          <TH>Finished</TH>
                          <TH>Waiting (average / 95th)</TH>
                          <TH>Working (average / 95th)</TH>
                          <TH>SLA attainment</TH>
                          <TH>Reassignments</TH>
                        </TR>
                      </THead>
                      <TBody>
                        {steps.map((s) => (
                          <TR key={`${s.workflow.id}|${s.nodeId}|${s.groupKey}`}>
                            <TD>{s.workflow.name}</TD>
                            <TD className="font-semibold">{s.label}{s.approximate > 0 ? ' *' : ''}</TD>
                            <TD>{s.groupName ?? s.groupKey}</TD>
                            <TD className="tabular-nums">{s.finished}</TD>
                            <TD className="tabular-nums">{formatSeconds(s.averageWaitingSeconds)} / {formatSeconds(s.p95WaitingSeconds)}</TD>
                            <TD className="tabular-nums">{formatSeconds(s.averageWorkingSeconds)} / {formatSeconds(s.p95WorkingSeconds)}</TD>
                            <TD className="tabular-nums">{attainment(s.slaAttainment)}</TD>
                            <TD className="tabular-nums">{s.reassignments}</TD>
                          </TR>
                        ))}
                      </TBody>
                    </ScrollTable>
                  ) : null}
                  {steps.some((s) => s.approximate > 0) ? (
                    <p className="text-sm text-muted-foreground">* Some tasks of this step were open alongside others of the same step, so their times come from their last pick-up only.</p>
                  ) : null}
                  {result.data?.truncated ? <p className="text-sm text-muted-foreground">Showing the first 200 steps.</p> : null}
                </>
              )}
      </CardContent>
    </Card>
  )
}
