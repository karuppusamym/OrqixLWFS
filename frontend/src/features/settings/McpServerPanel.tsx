import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import * as endpoints from '@/api/endpoints'
import type { McpCall, McpServer } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { useAnnounce } from '@/components/common/Announcer'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { formatDateTime } from '@/lib/dates'

const serverKey = (ws: string) => ['ws', ws, 'mcp-server'] as const
const callsKey = (ws: string) => ['ws', ws, 'mcp-server', 'calls'] as const

/** Plain words for the audit outcome of a call. */
const OUTCOMES: Record<string, { label: string; tone: 'success' | 'neutral' | 'warning' | 'danger' | 'info' }> = {
  success: { label: 'Done', tone: 'success' },
  confirmationRequired: { label: 'Waiting for confirmation', tone: 'info' },
  replayed: { label: 'Repeated', tone: 'neutral' },
  declined: { label: 'Declined', tone: 'neutral' },
  expired: { label: 'Expired', tone: 'warning' },
  rateLimited: { label: 'Too many calls', tone: 'warning' },
  denied: { label: 'Refused', tone: 'danger' },
  failed: { label: 'Failed', tone: 'danger' },
}

const STEPS: Record<string, string> = {
  read: 'Read',
  prepare: 'Prepared',
  confirm: 'Confirmed',
  elicitationAccepted: 'Confirmed in the assistant',
  elicitationDeclined: 'Declined in the assistant',
}

/**
 * Settings → AI assistants (CG-M04, docs/build/S136): a workspace administrator lets people use Orqix from an AI
 * assistant through MCP. Off until switched on. Assistants act with each person's own permissions; anything that
 * changes something is shown to the person and confirmed first. Recent calls come from the audit log and never show
 * values.
 */
export function McpServerPanel() {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const query = useQuery({ queryKey: serverKey(ws), queryFn: () => endpoints.getMcpServer(ws) })
  return (
    <section aria-labelledby="mcp-heading" className="max-w-4xl space-y-4">
      <div>
        <h2 id="mcp-heading">AI assistants (MCP)</h2>
        <p className="text-sm text-muted-foreground">
          Let people use Orqix from an AI assistant that supports the Model Context Protocol. The assistant signs in as
          the person and can only see and do what that person can in Orqix. Starting a request, deciding a task or sending
          a message is always shown to the person first and needs their confirmation. Every call is recorded in the audit
          log, without the values.
        </p>
      </div>
      {query.isPending ? (
        <LoadingBlock rows={3} label="Loading the MCP server settings" />
      ) : query.isError ? (
        <QueryError error={query.error} onRetry={() => void query.refetch()} what="the MCP server settings" />
      ) : (
        <>
          <SwitchCard ws={ws} state={query.data} />
          {query.data.enabled || query.data.updatedAt ? <RecentCalls ws={ws} /> : null}
        </>
      )}
    </section>
  )
}

function SwitchCard({ ws, state }: { ws: string; state: McpServer }) {
  const client = useQueryClient()
  const announce = useAnnounce()
  const [confirming, setConfirming] = useState(false)
  const change = useMutation({
    mutationFn: (enabled: boolean) => endpoints.setMcpServer(ws, enabled),
    onSuccess: (next, enabled) => {
      setConfirming(false)
      client.setQueryData(serverKey(ws), next)
      void client.invalidateQueries({ queryKey: callsKey(ws) })
      announce(`The MCP server is now ${enabled ? 'on' : 'off'}.`)
    },
  })
  return (
    <div className="rounded-card border border-border bg-surface p-4" data-testid="mcp-server">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 flex-1 space-y-1">
          <h3 id="mcp-switch" className="flex items-center gap-2 text-base font-semibold">
            MCP server
            <Badge tone={state.enabled ? 'success' : 'neutral'} data-testid="mcp-server-state">{state.enabled ? 'On' : 'Off'}</Badge>
          </h3>
          <p className="text-sm">
            {state.enabled
              ? 'Assistants can list the workflows people may start, find requests and tasks, explain where a request is, and prepare actions for people to confirm.'
              : 'No assistant can reach this workspace. People see the workspace in their assistant only after you switch it on.'}
          </p>
          {state.updatedAt ? (
            <p className="text-xs text-muted-foreground">
              Switched {state.enabled ? 'on' : 'off'} by {state.updatedBy?.displayName ?? 'someone'} on {formatDateTime(state.updatedAt)}
            </p>
          ) : null}
        </div>
        {state.enabled ? (
          !confirming ? (
            <Button variant="danger" onClick={() => setConfirming(true)} aria-describedby="mcp-switch">Switch off</Button>
          ) : null
        ) : (
          <Button variant="primary" onClick={() => change.mutate(true)} disabled={change.isPending} aria-describedby="mcp-switch">
            {change.isPending ? 'Switching on…' : 'Switch on'}
          </Button>
        )}
      </div>
      <dl className="mt-3 grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
        <div className="sm:col-span-2">
          <dt className="font-semibold">Server address for the assistant</dt>
          <dd><CopyValue value={state.endpoint} label="server address" testId="mcp-endpoint" /></dd>
        </div>
        <div className="sm:col-span-2">
          <dt className="font-semibold">Sign-in details (OAuth protected resource metadata)</dt>
          <dd><CopyValue value={state.metadataUrl} label="sign-in details address" testId="mcp-metadata" /></dd>
        </div>
        <div>
          <dt className="font-semibold">Sign-in</dt>
          <dd className="text-muted-foreground">
            Through your organization&apos;s sign-in page. Tokens must be issued for this server
            {state.audiences.length > 0 ? <> (audience <span className="font-mono">{state.audiences.join(', ')}</span>)</> : null}.
          </dd>
        </div>
        <div>
          <dt className="font-semibold">Limits</dt>
          <dd className="text-muted-foreground">
            {state.callsPerMinute} calls a minute for each person; a prepared action can be confirmed for {Math.round(state.confirmationSeconds / 60)} minutes.
          </dd>
        </div>
        <div className="sm:col-span-2">
          <dt className="font-semibold">Which workflows assistants can start</dt>
          <dd className="text-muted-foreground">
            Only published workflows marked &ldquo;Offer to AI assistants&rdquo; in their definition, and only for people who may start them.
          </dd>
        </div>
      </dl>
      {confirming ? (
        <Alert tone="warning" className="mt-3" title="Switch off the MCP server?" live="off">
          <p>Assistants lose access to this workspace at once, and actions waiting for confirmation can no longer be confirmed. Nothing in Orqix is changed or deleted.</p>
          <div className="mt-3 flex flex-wrap gap-2">
            <Button variant="danger-solid" onClick={() => change.mutate(false)} disabled={change.isPending}>
              {change.isPending ? 'Switching off…' : 'Switch off the MCP server'}
            </Button>
            <Button variant="ghost" onClick={() => setConfirming(false)} disabled={change.isPending}>Cancel</Button>
          </div>
        </Alert>
      ) : null}
      {change.isError ? <ProblemAlert error={toProblem(change.error)} className="mt-3" context={{ action: `switch the MCP server ${state.enabled ? 'off' : 'on'}` }} /> : null}
    </div>
  )
}

function CopyValue({ value, label, testId }: { value: string; label: string; testId: string }) {
  const announce = useAnnounce()
  const copy = () => {
    void navigator.clipboard?.writeText(value).then(() => announce(`Copied the ${label}.`), () => undefined)
  }
  return (
    <span className="flex flex-wrap items-center gap-2">
      <code className="break-all rounded bg-muted px-1.5 py-0.5 text-xs" data-testid={testId}>{value}</code>
      <Button variant="ghost" size="sm" onClick={copy} aria-label={`Copy the ${label}`}>Copy</Button>
    </span>
  )
}

function RecentCalls({ ws }: { ws: string }) {
  const query = useQuery({ queryKey: callsKey(ws), queryFn: () => endpoints.listMcpCalls(ws, 50) })
  return (
    <section aria-labelledby="mcp-calls-heading" className="space-y-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 id="mcp-calls-heading" className="text-base">Recent calls</h3>
        <Button variant="ghost" size="sm" onClick={() => void query.refetch()} disabled={query.isFetching}>
          {query.isFetching ? 'Refreshing…' : 'Refresh'}
        </Button>
      </div>
      {query.isPending ? (
        <LoadingBlock rows={2} label="Loading recent calls" />
      ) : query.isError ? (
        <QueryError error={query.error} onRetry={() => void query.refetch()} what="the recent calls" />
      ) : query.data.items.length === 0 ? (
        <p className="text-sm text-muted-foreground">No assistant has called Orqix in this workspace yet.</p>
      ) : (
        <div className="overflow-x-auto rounded-card border border-border">
          <Table>
            <caption className="sr-only">The last {query.data.items.length} MCP calls, newest first</caption>
            <THead>
              <TR>
                <TH>When</TH>
                <TH>Person</TH>
                <TH>Tool</TH>
                <TH>Step</TH>
                <TH>Outcome</TH>
                <TH>About</TH>
              </TR>
            </THead>
            <TBody>
              {query.data.items.map((call, i) => <CallRow key={`${call.occurredAt}-${i}`} ws={ws} call={call} />)}
            </TBody>
          </Table>
        </div>
      )}
    </section>
  )
}

function CallRow({ ws, call }: { ws: string; call: McpCall }) {
  const outcome = OUTCOMES[call.outcome] ?? { label: call.outcome, tone: 'neutral' as const }
  return (
    <TR data-testid="mcp-call">
      <TD className="whitespace-nowrap">{formatDateTime(call.occurredAt)}</TD>
      <TD>{call.actor?.displayName ?? 'Unknown person'}</TD>
      <TD><span className="font-mono text-xs">{call.tool}</span></TD>
      <TD>{call.step ? STEPS[call.step] ?? call.step : ''}</TD>
      <TD>
        <Badge tone={outcome.tone}>{outcome.label}</Badge>
        {call.errorCode && call.outcome !== 'rateLimited' ? <span className="ml-2 font-mono text-xs text-muted-foreground">{call.errorCode}</span> : null}
      </TD>
      <TD>
        {call.runId ? <Link to={`/w/${ws}/runs/${call.runId}`}>Request</Link> : null}
        {call.runId && call.taskId ? ' · ' : null}
        {call.taskId ? <Link to={`/w/${ws}/tasks/${call.taskId}`}>Task</Link> : null}
      </TD>
    </TR>
  )
}
