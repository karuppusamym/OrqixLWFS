import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import * as endpoints from '@/api/endpoints'
import type { AgentHumanRequest, AgentKillSwitch, AgentTestSuite } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { formatDateTime } from '@/lib/dates'

const TABS = ['dashboard', 'requests', 'servers', 'trace', 'tests'] as const
type Tab = (typeof TABS)[number]

const KIND_LABEL: Record<AgentHumanRequest['kind'], string> = {
  ask: 'Question',
  approval: 'Approval before a tool runs',
  elicitation: 'The tool server asks for information',
}

/**
 * AI agents control page (docs/build/S135, doc 29 §8): the control-plane figures, the questions and approvals durable
 * agents raised for people, registered MCP tool servers with their pins and kill switches, the opt-in conversation
 * trace, and agent test suites (mock tools from fixtures, tool-sequence assertions, a pinned judge model).
 */
export function AgentToolsPage() {
  const { can } = useWorkspace()
  const [params, setParams] = useSearchParams()
  const requested = params.get('tab') as Tab | null
  const tab: Tab = requested && TABS.includes(requested) ? requested : params.get('run') ? 'trace' : 'dashboard'
  if (!can('run.operate', 'connection.read', 'task.act')) {
    return <Alert tone="warning">You need permission to view AI agents in this workspace.</Alert>
  }
  return (
    <div className="space-y-6">
      <PageHeader
        title="Agents"
        description="What AI agents do, what they wait for, which tool servers they may use, and how they are tested."
      />
      <Tabs value={tab} onValueChange={(value) => setParams({ tab: value })}>
        <TabsList aria-label="Agent views">
          {can('run.operate', 'connection.read') ? <TabsTrigger value="dashboard">Overview</TabsTrigger> : null}
          <TabsTrigger value="requests">Waiting for people</TabsTrigger>
          {can('connection.read') ? <TabsTrigger value="servers">Tool servers and switches</TabsTrigger> : null}
          {can('run.operate', 'workspace.manage-access') ? <TabsTrigger value="trace">Conversation trace</TabsTrigger> : null}
          {can('connection.read') ? <TabsTrigger value="tests">Tests</TabsTrigger> : null}
        </TabsList>
        <TabsContent value="dashboard">{tab === 'dashboard' ? <Dashboard /> : null}</TabsContent>
        <TabsContent value="requests">{tab === 'requests' ? <Requests /> : null}</TabsContent>
        <TabsContent value="servers">{tab === 'servers' ? <Servers /> : null}</TabsContent>
        <TabsContent value="trace">{tab === 'trace' ? <Trace /> : null}</TabsContent>
        <TabsContent value="tests">{tab === 'tests' ? <Tests /> : null}</TabsContent>
      </Tabs>
    </div>
  )
}

function Dashboard() {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const data = useQuery({ queryKey: ['ws', ws, 'agent-dashboard'], queryFn: () => endpoints.getAgentDashboard(ws, 7) })
  if (data.isPending) return <LoadingBlock rows={3} label="Loading the agent overview" />
  if (data.isError) return <QueryError error={data.error} onRetry={() => void data.refetch()} what="the agent overview" />
  const d = data.data
  const refused = Object.entries(d.refusedToolCalls)
  return (
    <div className="space-y-6">
      <p className="text-sm text-muted-foreground">Last {d.days} days. Figures only: Orqix keeps no prompts or tool data here.</p>
      <div className="overflow-hidden rounded-card border border-border bg-surface">
        <Table>
          <caption className="sr-only">Agent calls per agent</caption>
          <THead>
            <TR>
              <TH>Agent</TH>
              <TH>Calls</TH>
              <TH>Failed</TH>
              <TH>Cost (USD)</TH>
              <TH>95% finish within</TH>
              <TH>Failure reasons</TH>
            </TR>
          </THead>
          <TBody>
            {d.agents.length === 0 ? (
              <TR>
                <TD colSpan={6}>No agent ran in this period.</TD>
              </TR>
            ) : (
              d.agents.map((a) => (
                <TR key={a.connectionKey}>
                  <TD className="font-semibold">{a.connectionKey}</TD>
                  <TD>{a.calls}</TD>
                  <TD>{a.failed}</TD>
                  <TD>{a.costUsd.toFixed(4)}</TD>
                  <TD>{a.p95DurationMs == null ? '-' : `${(a.p95DurationMs / 1000).toFixed(1)} s`}</TD>
                  <TD>{Object.entries(a.failureCodes).map(([code, n]) => `${code} (${n})`).join(', ') || '-'}</TD>
                </TR>
              ))
            )}
          </TBody>
        </Table>
      </div>
      <section aria-labelledby="signals-heading" className="space-y-2">
        <h2 id="signals-heading">Security signals</h2>
        <p className="text-sm">
          Refused tool calls:{' '}
          {refused.length === 0 ? 'none' : refused.map(([outcome, n]) => `${outcome} ${n}`).join(', ')}
        </p>
        <p className="text-sm">
          Tools refused because they changed since they were pinned: {d.schemaRefusals.length === 0 ? 'none' : d.schemaRefusals.map((r) => `${r.connectionKey}.${r.toolName} (${r.kind})`).join(', ')}
        </p>
        <p className="text-sm">
          Switched off: {d.killSwitches.length === 0 ? 'nothing' : d.killSwitches.map((s) => `${s.scope} ${s.connectionKey}`).join(', ')}
        </p>
        <p className="text-sm">
          Durable loops: {Object.entries(d.loops).map(([status, n]) => `${status} ${n}`).join(', ') || 'none'}; questions waiting
          for people: {d.openRequests}
        </p>
      </section>
    </div>
  )
}

function Requests() {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const [status, setStatus] = useState<'open' | 'answered'>('open')
  const list = useQuery({ queryKey: ['ws', ws, 'agent-requests', status], queryFn: () => endpoints.listAgentRequests(ws, status) })
  if (list.isPending) return <LoadingBlock rows={2} label="Loading agent requests" />
  if (list.isError) return <QueryError error={list.error} onRetry={() => void list.refetch()} what="the agent requests" />
  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <Checkbox id="show-answered" checked={status === 'answered'} onCheckedChange={(v) => setStatus(v === true ? 'answered' : 'open')} />
        <Label htmlFor="show-answered">Show answered questions instead of open ones</Label>
      </div>
      {list.data.length === 0 ? (
        <p className="text-sm text-muted-foreground">No agent is waiting for you.</p>
      ) : (
        <ul className="space-y-3">
          {list.data.map((r) => (
            <RequestCard key={r.id} request={r} />
          ))}
        </ul>
      )}
    </div>
  )
}

function RequestCard({ request }: { request: AgentHumanRequest }) {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const client = useQueryClient()
  const fields = Object.entries((request.answerSchema?.properties ?? {}) as Record<string, { type?: string; description?: string }>)
  const [values, setValues] = useState<Record<string, string>>({})
  const [reason, setReason] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [pending, setPending] = useState(false)
  const detail = (request.detail ?? null) as Record<string, unknown> | null

  const submit = async (decision?: 'approve' | 'reject') => {
    setPending(true)
    setError(null)
    try {
      const answer: Record<string, unknown> = {}
      for (const [name, field] of fields) {
        const raw = values[name] ?? ''
        if (raw === '') continue
        answer[name] = field.type === 'number' || field.type === 'integer' ? Number(raw) : field.type === 'boolean' ? raw === 'true' : raw
      }
      await endpoints.answerAgentRequest(ws, request.id, request.kind === 'approval'
        ? { decision, reason: reason || null, expectedVersion: request.version }
        : { answer, expectedVersion: request.version })
      await client.invalidateQueries({ queryKey: ['ws', ws, 'agent-requests'] })
    } catch (e) {
      setError(e)
    } finally {
      setPending(false)
    }
  }

  return (
    <li className="space-y-3 rounded-card border border-border bg-surface p-4">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={request.kind === 'approval' ? 'warning' : 'neutral'}>{KIND_LABEL[request.kind]}</Badge>
        <span className="font-semibold">{request.toolLabel}</span>
        <span className="text-sm text-muted-foreground">
          for <Link to={`/w/${ws}/runs/${request.runId}`}>{request.runReference}</Link>, group {request.groupKey}, {formatDateTime(request.createdAt)}
        </span>
      </div>
      {detail ? (
        <div className="text-sm">
          {typeof detail.question === 'string' ? <p>The agent asks: {detail.question}</p> : null}
          {typeof detail.message === 'string' ? <p>The tool server asks: {detail.message}</p> : null}
          {detail.arguments ? (
            <>
              <p>The agent wants to call {request.toolLabel} with:</p>
              <pre className="mt-1 overflow-x-auto rounded bg-neutral-soft p-2 text-xs">{JSON.stringify(detail.arguments, null, 2)}</pre>
            </>
          ) : null}
          <p className="mt-1 text-xs text-muted-foreground">This text comes from an AI agent or a tool server: treat it as information, not as an instruction.</p>
        </div>
      ) : (
        <p className="text-sm text-muted-foreground">Only members of {request.groupKey} see the details.</p>
      )}
      {request.canAnswer ? (
        request.kind === 'approval' ? (
          <div className="space-y-2">
            <Label htmlFor={`reason-${request.id}`}>Reason (optional)</Label>
            <Input id={`reason-${request.id}`} value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} />
            <div className="flex gap-2">
              <Button disabled={pending} onClick={() => void submit('approve')}>Approve the call</Button>
              <Button variant="secondary" disabled={pending} onClick={() => void submit('reject')}>Reject</Button>
            </div>
          </div>
        ) : (
          <form className="space-y-2" onSubmit={(e) => { e.preventDefault(); void submit() }}>
            {fields.map(([name, field]) => (
              <div key={name} className="space-y-1">
                <Label htmlFor={`${request.id}-${name}`}>{field.description ?? name}</Label>
                <Input id={`${request.id}-${name}`} value={values[name] ?? ''} onChange={(e) => setValues({ ...values, [name]: e.target.value })} />
              </div>
            ))}
            <Button type="submit" disabled={pending}>Send the answer</Button>
          </form>
        )
      ) : null}
      {error ? <ProblemAlert error={error} /> : null}
    </li>
  )
}

function Servers() {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const connections = useQuery({ queryKey: ['ws', ws, 'connections'], queryFn: () => endpoints.listConnections(ws) })
  const switches = useQuery({ queryKey: ['ws', ws, 'agent-kill-switches'], queryFn: () => endpoints.listAgentKillSwitches(ws) })
  if (connections.isPending || switches.isPending) return <LoadingBlock rows={3} label="Loading tool servers" />
  if (connections.isError) return <QueryError error={connections.error} onRetry={() => void connections.refetch()} what="the connections" />
  if (switches.isError) return <QueryError error={switches.error} onRetry={() => void switches.refetch()} what="the kill switches" />
  const items = connections.data.items as Array<{ key: string; name?: string; kind?: string }>
  const servers = items.filter((c) => c.kind === 'mcpServer')
  const agents = items.filter((c) => c.kind === 'agent')
  const manage = can('connection.manage')
  return (
    <div className="space-y-6">
      <Alert tone="info">
        Agents use a tool of a registered server only while its input schema matches the pin taken here. A changed or
        missing tool is refused and the person who pinned it is told. A switch turns an agent or a server off at once.
      </Alert>
      <section aria-labelledby="servers-heading" className="space-y-3">
        <h2 id="servers-heading">Tool servers</h2>
        {servers.length === 0 ? <p className="text-sm text-muted-foreground">No MCP tool server is registered.</p> : null}
        {servers.map((s) => (
          <ServerRow key={s.key} serverKey={s.key} name={s.name ?? s.key} manage={manage}
            killSwitch={switches.data.find((k) => k.scope === 'server' && k.connectionKey === s.key)} />
        ))}
      </section>
      <section aria-labelledby="agents-heading" className="space-y-3">
        <h2 id="agents-heading">Agents</h2>
        {agents.map((a) => (
          <div key={a.key} className="flex flex-wrap items-center justify-between gap-2 rounded-card border border-border bg-surface p-3">
            <span className="font-semibold">{a.name ?? a.key}</span>
            <KillSwitchControl scope="agent" connectionKey={a.key} manage={manage}
              current={switches.data.find((k) => k.scope === 'agent' && k.connectionKey === a.key)} />
          </div>
        ))}
      </section>
    </div>
  )
}

function ServerRow({ serverKey, name, manage, killSwitch }: { serverKey: string; name: string; manage: boolean; killSwitch?: AgentKillSwitch }) {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const client = useQueryClient()
  const snapshot = useQuery({ queryKey: ['ws', ws, 'tool-snapshot', serverKey], queryFn: () => endpoints.getMcpToolSnapshot(ws, serverKey) })
  const [error, setError] = useState<unknown>(null)
  const [pending, setPending] = useState(false)
  const take = async () => {
    setPending(true)
    setError(null)
    try {
      await endpoints.takeMcpToolSnapshot(ws, serverKey)
      await client.invalidateQueries({ queryKey: ['ws', ws, 'tool-snapshot', serverKey] })
    } catch (e) {
      setError(e)
    } finally {
      setPending(false)
    }
  }
  return (
    <div className="space-y-2 rounded-card border border-border bg-surface p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="font-semibold">{name}</span>
        <div className="flex items-center gap-2">
          {manage ? <Button variant="secondary" disabled={pending} onClick={() => void take()}>Pin the tools as listed now</Button> : null}
          <KillSwitchControl scope="server" connectionKey={serverKey} manage={manage} current={killSwitch} />
        </div>
      </div>
      {snapshot.isSuccess ? (
        <ul className="text-sm">
          {snapshot.data.tools.map((t) => (
            <li key={t.name}>
              {t.label} ({t.name}):{' '}
              <Badge tone={t.status === 'pinned' ? 'success' : 'warning'}>{t.status === 'pinned' ? 'Pinned' : t.status === 'missing' ? 'Not offered by the server' : 'Not pinned'}</Badge>
            </li>
          ))}
          {snapshot.data.drift.map((d) => (
            <li key={d.id} role="alert" className="text-danger-foreground">
              {d.toolName}: {d.kind === 'changed' ? 'the server changed its schema' : d.kind === 'missing' ? 'the server no longer offers it' : 'no pin'}; refused {d.occurrences} times since {formatDateTime(d.firstSeenAt)}
            </li>
          ))}
        </ul>
      ) : null}
      {error ? <ProblemAlert error={error} /> : null}
    </div>
  )
}

function KillSwitchControl({ scope, connectionKey, manage, current }: { scope: 'agent' | 'server'; connectionKey: string; manage: boolean; current?: AgentKillSwitch }) {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const client = useQueryClient()
  const [reason, setReason] = useState('')
  const [error, setError] = useState<unknown>(null)
  const active = current?.active ?? false
  const flip = async () => {
    setError(null)
    try {
      await endpoints.setAgentKillSwitch(ws, scope, connectionKey, { active: !active, reason, expectedVersion: current?.version ?? null })
      setReason('')
      await client.invalidateQueries({ queryKey: ['ws', ws, 'agent-kill-switches'] })
    } catch (e) {
      setError(e)
    }
  }
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Badge tone={active ? 'danger' : 'success'}>{active ? 'Switched off' : 'On'}</Badge>
      {manage ? (
        <>
          <Label htmlFor={`reason-${scope}-${connectionKey}`} className="sr-only">Reason</Label>
          <Input id={`reason-${scope}-${connectionKey}`} placeholder="Reason" value={reason} onChange={(e) => setReason(e.target.value)} className="w-48" maxLength={500} />
          <Button variant={active ? 'secondary' : 'danger'} disabled={reason.trim() === ''} onClick={() => void flip()}>
            {active ? 'Switch on' : 'Switch off'}
          </Button>
        </>
      ) : null}
      {error ? <ProblemAlert error={error} /> : null}
    </div>
  )
}

function Trace() {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const [params] = useSearchParams()
  const runId = params.get('run')
  const nodeId = params.get('node') ?? undefined
  return (
    <div className="space-y-6">
      {can('workspace.manage-access') ? <TraceSettings /> : null}
      {runId && can('run.operate') ? <TraceView ws={ws} runId={runId} nodeId={nodeId} /> : (
        <p className="text-sm text-muted-foreground">Open the trace of an agent step from the request's timeline.</p>
      )}
    </div>
  )
}

function TraceSettings() {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const client = useQueryClient()
  const settings = useQuery({ queryKey: ['ws', ws, 'agent-trace-settings'], queryFn: () => endpoints.getAgentTraceSettings(ws) })
  const [draft, setDraft] = useState<{ enabled: boolean; days: string; fields: string; patterns: string } | null>(null)
  const [error, setError] = useState<unknown>(null)
  if (settings.isPending) return <LoadingBlock rows={1} label="Loading the trace setting" />
  if (settings.isError) return <QueryError error={settings.error} onRetry={() => void settings.refetch()} what="the trace setting" />
  const s = settings.data
  const form = draft ?? { enabled: s.enabled, days: String(s.retentionDays), fields: s.redactFields.join(', '), patterns: s.redactPatterns.join('\n') }
  const save = async () => {
    setError(null)
    try {
      await endpoints.setAgentTraceSettings(ws, {
        enabled: form.enabled,
        retentionDays: Number(form.days),
        redactFields: form.fields.split(',').map((f) => f.trim()).filter(Boolean),
        redactPatterns: form.patterns.split('\n').map((p) => p.trim()).filter(Boolean),
        expectedVersion: s.version,
      })
      setDraft(null)
      await client.invalidateQueries({ queryKey: ['ws', ws, 'agent-trace-settings'] })
    } catch (e) {
      setError(e)
    }
  }
  return (
    <section aria-labelledby="trace-settings-heading" className="space-y-3 rounded-card border border-border bg-surface p-4">
      <h2 id="trace-settings-heading">Conversation trace</h2>
      <p className="text-sm text-muted-foreground">
        Off by default: Orqix then keeps only names, timings, tokens and cost. On, it keeps what agents and tools said,
        after removing e-mail addresses, long numbers, tokens and the fields and patterns below, for at most 30 days.
      </p>
      <div className="flex items-center gap-2">
        <Checkbox id="trace-enabled" checked={form.enabled} onCheckedChange={(v) => setDraft({ ...form, enabled: v === true })} />
        <Label htmlFor="trace-enabled">Keep conversation traces</Label>
      </div>
      <Label htmlFor="trace-days">Keep for (days)</Label>
      <Input id="trace-days" type="number" min={1} max={30} value={form.days} onChange={(e) => setDraft({ ...form, days: e.target.value })} className="w-24" />
      <Label htmlFor="trace-fields">Fields to remove (comma separated)</Label>
      <Input id="trace-fields" value={form.fields} onChange={(e) => setDraft({ ...form, fields: e.target.value })} />
      <Label htmlFor="trace-patterns">Patterns to remove (one regular expression per line)</Label>
      <Textarea id="trace-patterns" value={form.patterns} onChange={(e) => setDraft({ ...form, patterns: e.target.value })} rows={3} />
      <Button onClick={() => void save()}>Save the trace setting</Button>
      {error ? <ProblemAlert error={error} /> : null}
    </section>
  )
}

function TraceView({ ws, runId, nodeId }: { ws: string; runId: string; nodeId?: string }) {
  const trace = useQuery({ queryKey: ['ws', ws, 'agent-trace', runId, nodeId], queryFn: () => endpoints.getAgentTrace(ws, runId, nodeId) })
  if (trace.isPending) return <LoadingBlock rows={3} label="Loading the trace" />
  if (trace.isError) return <QueryError error={trace.error} onRetry={() => void trace.refetch()} what="the trace" />
  return (
    <section aria-labelledby="trace-heading" className="space-y-3">
      <h2 id="trace-heading">
        Trace of <Link to={`/w/${ws}/runs/${runId}`}>the request</Link>{nodeId ? `, step ${nodeId}` : ''}
      </h2>
      {trace.data.events.length === 0 ? (
        <p className="text-sm text-muted-foreground">{trace.data.enabled ? 'Nothing was traced for this step, or the trace has expired.' : 'The conversation trace is off in this workspace.'}</p>
      ) : (
        <ol className="space-y-2">
          {trace.data.events.map((e) => (
            <li key={e.seq} className="rounded-card border border-border bg-surface p-3 text-sm">
              <span className="font-semibold">{e.kind}</span>
              {e.toolName ? ` ${e.toolName}` : ''}
              {e.outcome ? ` - ${e.outcome}` : ''}
              {e.durationMs != null ? `, ${e.durationMs} ms` : ''}
              {e.costUsd != null ? `, USD ${e.costUsd}` : ''}
              <pre className="mt-1 overflow-x-auto whitespace-pre-wrap text-xs">{JSON.stringify(e.content, null, 2)}</pre>
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}

function Tests() {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const connections = useQuery({ queryKey: ['ws', ws, 'connections'], queryFn: () => endpoints.listConnections(ws) })
  const [selected, setSelected] = useState<string | null>(null)
  if (connections.isPending) return <LoadingBlock rows={2} label="Loading agents" />
  if (connections.isError) return <QueryError error={connections.error} onRetry={() => void connections.refetch()} what="the agents" />
  const agents = (connections.data.items as Array<{ key: string; name?: string; kind?: string }>).filter((c) => c.kind === 'agent')
  const key = selected ?? agents[0]?.key ?? null
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <Label htmlFor="test-agent">Agent</Label>
        <select id="test-agent" className="min-h-10 rounded border border-border bg-surface px-2" value={key ?? ''} onChange={(e) => setSelected(e.target.value)}>
          {agents.map((a) => (
            <option key={a.key} value={a.key}>{a.name ?? a.key}</option>
          ))}
        </select>
      </div>
      {key ? <SuiteEditor key={key} connectionKey={key} manage={can('connection.manage')} /> : <p className="text-sm text-muted-foreground">There is no agent to test.</p>}
    </div>
  )
}

function SuiteEditor({ connectionKey, manage }: { connectionKey: string; manage: boolean }) {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const client = useQueryClient()
  const suite = useQuery({ queryKey: ['ws', ws, 'agent-tests', connectionKey], queryFn: () => endpoints.getAgentTestSuite(ws, connectionKey) })
  const [text, setText] = useState<string | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [pending, setPending] = useState(false)
  if (suite.isPending) return <LoadingBlock rows={2} label="Loading the test suite" />
  if (suite.isError) return <QueryError error={suite.error} onRetry={() => void suite.refetch()} what="the test suite" />
  const current: AgentTestSuite = suite.data
  const value = text ?? JSON.stringify(current.suite, null, 2)
  const act = async (action: () => Promise<unknown>) => {
    setPending(true)
    setError(null)
    try {
      await action()
      await client.invalidateQueries({ queryKey: ['ws', ws, 'agent-tests', connectionKey] })
    } catch (e) {
      setError(e)
    } finally {
      setPending(false)
    }
  }
  const save = () => act(async () => {
    await endpoints.saveAgentTestSuite(ws, connectionKey, { suite: JSON.parse(value), expectedVersion: current.version })
    setText(null)
  })
  const result = current.lastResult as { passed?: number; failed?: number; cases?: Array<{ name: string; passed: boolean; toolSequence: string[]; assertions: Array<{ kind: string; passed: boolean; message: string }> }> } | null
  return (
    <div className="space-y-3">
      <Label htmlFor="suite-json">Test cases (input, tool fixtures, expectations and the pinned judge model)</Label>
      <Textarea id="suite-json" rows={14} className="font-mono text-xs" value={value} onChange={(e) => setText(e.target.value)} readOnly={!manage} />
      {manage ? (
        <div className="flex gap-2">
          <Button variant="secondary" disabled={pending} onClick={() => void save()}>Save the suite</Button>
          <Button disabled={pending || current.version === 0} onClick={() => void act(() => endpoints.runAgentTestSuite(ws, connectionKey))}>
            Run the suite (uses AI)
          </Button>
        </div>
      ) : null}
      {error ? <ProblemAlert error={error} /> : null}
      {result?.cases ? (
        <section aria-labelledby="results-heading" className="space-y-2">
          <h2 id="results-heading">
            Last run: {result.passed} passed, {result.failed} failed{current.lastRunAt ? ` (${formatDateTime(current.lastRunAt)})` : ''}
          </h2>
          <ul className="space-y-2">
            {result.cases.map((c) => (
              <li key={c.name} className="rounded-card border border-border bg-surface p-3 text-sm">
                <Badge tone={c.passed ? 'success' : 'danger'}>{c.passed ? 'Passed' : 'Failed'}</Badge> <span className="font-semibold">{c.name}</span>
                <p className="text-xs text-muted-foreground">Tools called: {c.toolSequence.join(' → ') || 'none'}</p>
                <ul className="mt-1 list-disc pl-5">
                  {c.assertions.map((a, i) => (
                    <li key={i} className={a.passed ? '' : 'text-danger-foreground'}>{a.kind}: {a.message}</li>
                  ))}
                </ul>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </div>
  )
}
