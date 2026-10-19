import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { api, call } from '@/api/client'
import type { components } from '@/api/schema'
import type { ProblemError } from '@/api/problem'
import { useWorkflows } from '@/api/queries'
import { useWorkspace } from '@/app/workspace'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { formatDateTime } from '@/lib/dates'
import { humanize } from '@/lib/text'

type InboundEvent = components['schemas']['InboundEvent']

/** "Title: Replacement laptop · Amount: 1200" from the mapped start values. */
export function eventSummary(input: Record<string, unknown>): string {
  const parts = Object.entries(input).slice(0, 4).map(([k, v]) => `${humanize(k)}: ${typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean' ? String(v) : ''}`)
  return parts.join(' · ') || 'No values'
}

/**
 * Received events (docs/build/S68): signed events that inbound triggers accepted for workflows the person may start.
 * An event never starts a request by itself; starting one here uses the person's own start rights, so they become the
 * requester. The server lists only events for workflows the caller may start and starts each event at most once.
 */
export function InboundEventsPanel() {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const allowed = can('workflow.start')
  const client = useQueryClient()
  const query = useQuery({
    queryKey: ['ws', ws, 'inbound-events'],
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/inbound-events', { params: { path: { workspaceId: ws } } })),
    enabled: allowed,
  })
  const workflows = useWorkflows(ws, { startable: true }, allowed)
  const names = new Map((workflows.data?.pages.flatMap((p) => p.items) ?? []).map((w) => [w.id, w.name]))
  const [pending, setPending] = useState<string | null>(null)
  const [error, setError] = useState<ProblemError | null>(null)
  const [started, setStarted] = useState<{ eventId: string; runId: string; already: boolean } | null>(null)
  const events = query.data?.items ?? []
  // Most workspaces have no inbound triggers: the panel stays out of the way until there is something to start.
  if (!allowed || (events.length === 0 && !started && !query.isError)) return null
  const waiting = events.filter((e) => e.status === 'received')

  const start = async (event: InboundEvent) => {
    setPending(event.id); setError(null); setStarted(null)
    try {
      const result = await call(api.POST('/workspaces/{workspaceId}/inbound-events/{eventId}/start', { params: { path: { workspaceId: ws, eventId: event.id } } }))
      setStarted({ eventId: event.id, runId: result.runId, already: result.alreadyStarted })
      await client.invalidateQueries({ queryKey: ['ws', ws, 'inbound-events'] })
    } catch (cause) {
      setError(toProblem(cause))
    } finally {
      setPending(null)
    }
  }

  return <section aria-labelledby="inbound-events-title" className="mb-6 space-y-3 rounded-card border border-border bg-surface p-4">
    <div>
      <h2 id="inbound-events-title" className="text-base font-semibold">Received events</h2>
      <p className="text-sm text-muted-foreground">Events that arrived through an inbound trigger for a workflow you can start. Nothing starts until someone starts it here; you then become the requester.</p>
    </div>
    {query.isError ? <ProblemAlert error={toProblem(query.error)} />
        : events.length === 0 ? <p className="text-sm">No events are waiting.</p>
          : <table className="w-full text-sm">
            <caption className="sr-only">Received events, newest first ({waiting.length} waiting to start)</caption>
            <thead><tr className="text-left text-muted-foreground"><th scope="col">Received</th><th scope="col">Workflow</th><th scope="col">Values</th><th scope="col">Status</th></tr></thead>
            <tbody>
              {events.map((event) => <tr key={event.id} className="border-t border-border align-top" data-testid={`inbound-event-${event.eventId}`}>
                <td className="py-2 pr-3">{formatDateTime(event.receivedAt)}<div className="text-xs text-muted-foreground">{event.triggerKey} · {event.eventId}</div></td>
                <td className="py-2 pr-3">{names.get(event.workflowId) ?? 'Workflow'}</td>
                <td className="py-2 pr-3">{eventSummary(event.input as Record<string, unknown>)}</td>
                <td className="py-2">
                  {event.status === 'started' && event.runId
                    ? <Link to={`/w/${ws}/runs/${event.runId}`}>Started – open request</Link>
                    : <Button variant="secondary" disabled={pending !== null} loading={pending === event.id} onClick={() => void start(event)}
                      aria-label={`Start a request from event ${event.eventId}`}>Start request</Button>}
                </td>
              </tr>)}
            </tbody>
          </table>}
    {started ? <Alert tone="success">{started.already ? 'This event had already been started.' : 'The request was started.'} <Link to={`/w/${ws}/runs/${started.runId}`}>Open the request</Link></Alert> : null}
    {error ? <ProblemAlert error={error} /> : null}
  </section>
}
