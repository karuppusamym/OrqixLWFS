import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api, call } from '@/api/client'
import type { components } from '@/api/schema'
import type { ProblemError } from '@/api/problem'
import { useWorkflows } from '@/api/queries'
import { useWorkspace } from '@/app/workspace'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'

type Adapter = components['schemas']['Adapter']
type Kind = Adapter['kind']

export const KIND_LABEL: Record<Kind, string> = { email: 'Email', teams: 'Microsoft Teams', jdbc: 'Database read (JDBC)', kafka: 'Kafka topic' }

export const SAMPLE_PAYLOAD: Record<Kind, unknown> = {
  email: { to: ['person@example.com'], subject: 'Subject', body: 'Message' },
  teams: { text: 'Message for the channel' },
  jdbc: { parameters: {} },
  kafka: { topic: 'topic.name', key: 'optional-key', value: {} },
}

export interface AdapterFields { from: string; domains: string; baseUrl: string; statement: string; parameters: string; maxRows: string; topics: string }

const list = (text: string) => text.split(/[\s,]+/).map(item => item.trim()).filter(Boolean)

/** Builds the closed configuration object for a kind; the server remains the authority and refuses anything else. */
export function buildAdapterConfig(kind: Kind, f: AdapterFields): Record<string, unknown> {
  switch (kind) {
    case 'email': return { from: f.from.trim(), allowedRecipientDomains: list(f.domains) }
    case 'teams': return { baseUrl: f.baseUrl.trim() }
    case 'jdbc': return { statement: f.statement.trim(), parameters: list(f.parameters), ...(f.maxRows.trim() ? { maxRows: Number(f.maxRows) } : {}) }
    case 'kafka': return { allowedTopics: list(f.topics) }
  }
}

/** Parses "title=/value/title" lines into the mapping object. */
export function parseMapping(text: string): Record<string, string> | null {
  const mapping: Record<string, string> = {}
  for (const line of text.split('\n').map(l => l.trim()).filter(Boolean)) {
    const at = line.indexOf('=')
    if (at < 1) return null
    mapping[line.slice(0, at).trim()] = line.slice(at + 1).trim()
  }
  return Object.keys(mapping).length ? mapping : null
}

const EMPTY: AdapterFields = { from: '', domains: '', baseUrl: '', statement: '', parameters: '', maxRows: '', topics: '' }
const KEY = /^[a-z][a-z0-9-]{0,62}[a-z0-9]$/
const SECRET = /^[a-z0-9][a-z0-9-]{0,62}$/

/** Adapters (email, Teams, database read, Kafka) and signed inbound triggers. Only the local stub exists in this build. */
export function AdaptersPanel() {
  const { workspace, can } = useWorkspace()
  const manage = can('connection.manage')
  const workflowQuery = useWorkflows(workspace.id, {}, manage)
  const workflows = workflowQuery.data?.pages.flatMap(page => page.items) ?? []
  const [error, setError] = useState<ProblemError | null>(null)
  const [notice, setNotice] = useState('')
  const [pending, setPending] = useState(false)
  const [kind, setKind] = useState<Kind>('kafka')
  const [key, setKey] = useState('')
  const [name, setName] = useState('')
  const [secretRef, setSecretRef] = useState('')
  const [fields, setFields] = useState<AdapterFields>(EMPTY)
  const [testing, setTesting] = useState<string | null>(null)
  const [payload, setPayload] = useState('')
  const [tKey, setTKey] = useState('')
  const [tName, setTName] = useState('')
  const [tSource, setTSource] = useState<Kind>('kafka')
  const [tWorkflow, setTWorkflow] = useState('')
  const [tSecret, setTSecret] = useState('')
  const [tFilter, setTFilter] = useState('')
  const [tMapping, setTMapping] = useState('')

  const client = useQueryClient()
  const adapterQuery = useQuery({ queryKey: ['ws', workspace.id, 'adapters'], queryFn: () => call(api.GET('/workspaces/{workspaceId}/adapters', { params: { path: { workspaceId: workspace.id } } })) })
  const triggerQuery = useQuery({ queryKey: ['ws', workspace.id, 'inbound-triggers'], queryFn: () => call(api.GET('/workspaces/{workspaceId}/inbound-triggers', { params: { path: { workspaceId: workspace.id } } })) })
  const adapters = adapterQuery.data?.items ?? []
  const triggers = triggerQuery.data?.items ?? []
  const load = () => Promise.all([client.invalidateQueries({ queryKey: ['ws', workspace.id, 'adapters'] }), client.invalidateQueries({ queryKey: ['ws', workspace.id, 'inbound-triggers'] })])
  const loadError = adapterQuery.error ?? triggerQuery.error

  const set = (field: keyof AdapterFields) => (value: string) => setFields(previous => ({ ...previous, [field]: value }))
  const run = async (action: () => Promise<void>) => {
    setPending(true); setError(null); setNotice('')
    try { await action() } catch (cause) { setError(toProblem(cause)) } finally { setPending(false) }
  }
  const adapterValid = KEY.test(key) && name.trim() !== '' && SECRET.test(secretRef)
  const saveAdapter = () => run(async () => {
    await call(api.POST('/workspaces/{workspaceId}/adapters', {
      params: { path: { workspaceId: workspace.id } },
      body: { key, kind, name: name.trim(), secretRef, config: buildAdapterConfig(kind, fields) },
    }))
    setNotice(`Adapter ${key} saved. It can only deliver to the local test stub in this build.`)
    setKey(''); setName(''); setSecretRef(''); setFields(EMPTY)
    await load()
  })
  const sendTest = (adapter: Adapter) => run(async () => {
    let parsed: Record<string, unknown>
    try { parsed = JSON.parse(payload) as Record<string, unknown> } catch { throw new Error('The test message must be valid JSON.') }
    const result = await call(api.POST('/workspaces/{workspaceId}/adapters/{adapterKey}/test-send', {
      params: { path: { workspaceId: workspace.id, adapterKey: adapter.key } }, body: { payload: parsed },
    }))
    setNotice(`Test message for ${adapter.key} accepted by the local stub (${result.outcome}). Nothing was sent to a remote system.`)
  })
  const mapping = parseMapping(tMapping)
  const triggerValid = KEY.test(tKey) && tName.trim() !== '' && SECRET.test(tSecret) && tWorkflow !== '' && mapping !== null
  const saveTrigger = () => run(async () => {
    const filter = tSource === 'email' ? { allowedSenderDomains: list(tFilter) } : tSource === 'kafka' ? { allowedTopics: list(tFilter) } : {}
    await call(api.POST('/workspaces/{workspaceId}/inbound-triggers', {
      params: { path: { workspaceId: workspace.id } },
      body: { key: tKey, source: tSource, name: tName.trim(), workflowId: tWorkflow, secretRef: tSecret, filter, mapping: mapping ?? {} },
    }))
    setNotice(`Trigger ${tKey} saved. Events wait for a person with start rights; they never start a request by themselves.`)
    setTKey(''); setTName(''); setTSecret(''); setTFilter(''); setTMapping('')
    await load()
  })

  return <section aria-labelledby="adapters-title" className="space-y-6 rounded-card border border-border bg-surface p-5">
    <div>
      <h2 id="adapters-title">Adapters and inbound triggers</h2>
      <p className="text-sm text-muted-foreground">Email, Teams, database read and Kafka adapters use exact allowlists and a named secret; no password or key is typed here. This build delivers only to a local test stub, so nothing reaches a remote system until live acceptance is completed.</p>
    </div>
    <div className="space-y-2">
      <h3>Adapters</h3>
      {adapters.length === 0 ? <p className="text-sm">No adapters are defined in this workspace.</p> : <ul className="space-y-2">
        {adapters.map(adapter => <li key={adapter.id} className="rounded-card border border-border p-3">
          <p><strong>{adapter.name}</strong> <span className="text-sm text-muted-foreground">({adapter.key}, {KIND_LABEL[adapter.kind]})</span></p>
          {manage ? <div className="mt-2 space-y-2">
            <Button variant="secondary" disabled={pending} onClick={() => { setTesting(adapter.key); setPayload(JSON.stringify(SAMPLE_PAYLOAD[adapter.kind], null, 2)) }}>Prepare test message for {adapter.key}</Button>
            {testing === adapter.key ? <div className="space-y-2">
              <Label htmlFor={`test-${adapter.key}`}>Test message for {adapter.key}</Label>
              <Textarea id={`test-${adapter.key}`} value={payload} onChange={event => setPayload(event.target.value)} />
              <Button disabled={pending} onClick={() => void sendTest(adapter)}>Send to local stub</Button>
            </div> : null}
          </div> : null}
        </li>)}
      </ul>}
    </div>
    {manage ? <div className="space-y-3">
      <h3>Add an adapter</h3>
      <div><Label htmlFor="adapter-kind">Adapter type</Label><Select id="adapter-kind" value={kind} onChange={event => { setKind(event.target.value as Kind); setFields(EMPTY) }}>
        {(Object.keys(KIND_LABEL) as Kind[]).map(k => <option key={k} value={k}>{KIND_LABEL[k]}</option>)}</Select></div>
      <div><Label htmlFor="adapter-key">Adapter key</Label><Input id="adapter-key" value={key} maxLength={64} onChange={event => setKey(event.target.value)} /></div>
      <div><Label htmlFor="adapter-name">Adapter name</Label><Input id="adapter-name" value={name} maxLength={100} onChange={event => setName(event.target.value)} /></div>
      <div><Label htmlFor="adapter-secret">Secret reference name</Label><Input id="adapter-secret" value={secretRef} maxLength={63} onChange={event => setSecretRef(event.target.value)} aria-describedby="adapter-secret-help" />
        <p id="adapter-secret-help" className="text-sm text-muted-foreground">The name of a secret your platform team stored for this adapter. Never type the secret itself.</p></div>
      {kind === 'email' ? <>
        <div><Label htmlFor="f-from">Sender address</Label><Input id="f-from" value={fields.from} onChange={event => set('from')(event.target.value)} /></div>
        <div><Label htmlFor="f-domains">Approved recipient domains</Label><Input id="f-domains" value={fields.domains} onChange={event => set('domains')(event.target.value)} /></div></> : null}
      {kind === 'teams' ? <div><Label htmlFor="f-base">Teams service address</Label><Input id="f-base" value={fields.baseUrl} onChange={event => set('baseUrl')(event.target.value)} /></div> : null}
      {kind === 'jdbc' ? <>
        <div><Label htmlFor="f-statement">Read-only SELECT statement</Label><Textarea id="f-statement" value={fields.statement} onChange={event => set('statement')(event.target.value)} /></div>
        <div><Label htmlFor="f-params">Parameter names</Label><Input id="f-params" value={fields.parameters} onChange={event => set('parameters')(event.target.value)} /></div>
        <div><Label htmlFor="f-rows">Maximum rows (up to 500)</Label><Input id="f-rows" type="number" min={1} max={500} value={fields.maxRows} onChange={event => set('maxRows')(event.target.value)} /></div></> : null}
      {kind === 'kafka' ? <div><Label htmlFor="f-topics">Approved topics to send to</Label><Input id="f-topics" value={fields.topics} onChange={event => set('topics')(event.target.value)} /></div> : null}
      <Button disabled={pending || !adapterValid} onClick={() => void saveAdapter()}>Save adapter</Button>
    </div> : null}

    <div className="space-y-2">
      <h3>Inbound triggers</h3>
      {triggers.length === 0 ? <p className="text-sm">No inbound triggers are defined in this workspace.</p> : <ul className="space-y-2">
        {triggers.map(trigger => <li key={trigger.id} className="rounded-card border border-border p-3">
          <p><strong>{trigger.name}</strong> <span className="text-sm text-muted-foreground">({trigger.key}, {KIND_LABEL[trigger.source]})</span></p>
          {trigger.receivePath ? <p className="text-sm">Senders post signed events to <code>{trigger.receivePath}</code></p> : null}
        </li>)}
      </ul>}
    </div>
    {manage ? <div className="space-y-3">
      <h3>Add an inbound trigger</h3>
      <div><Label htmlFor="trigger-source">Event source</Label><Select id="trigger-source" value={tSource} onChange={event => { setTSource(event.target.value as Kind); setTFilter('') }}>
        {(Object.keys(KIND_LABEL) as Kind[]).map(k => <option key={k} value={k}>{KIND_LABEL[k]}</option>)}</Select></div>
      <div><Label htmlFor="trigger-key">Trigger key</Label><Input id="trigger-key" value={tKey} maxLength={64} onChange={event => setTKey(event.target.value)} /></div>
      <div><Label htmlFor="trigger-name">Trigger name</Label><Input id="trigger-name" value={tName} maxLength={100} onChange={event => setTName(event.target.value)} /></div>
      <div><Label htmlFor="trigger-workflow">Workflow to start</Label><Select id="trigger-workflow" value={tWorkflow} onChange={event => setTWorkflow(event.target.value)}>
        <option value="">Choose a workflow</option>{workflows.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}</Select></div>
      <div><Label htmlFor="trigger-secret">Signing secret reference name</Label><Input id="trigger-secret" value={tSecret} maxLength={63} onChange={event => setTSecret(event.target.value)} /></div>
      {tSource === 'email' || tSource === 'kafka' ? <div><Label htmlFor="trigger-filter">{tSource === 'email' ? 'Approved sender domains' : 'Approved topics to receive from'}</Label><Input id="trigger-filter" value={tFilter} onChange={event => setTFilter(event.target.value)} /></div> : null}
      <div><Label htmlFor="trigger-mapping">Start form fields, one per line as field=/path/in/event</Label><Textarea id="trigger-mapping" value={tMapping} onChange={event => setTMapping(event.target.value)} /></div>
      <Button disabled={pending || !triggerValid} onClick={() => void saveTrigger()}>Save trigger</Button>
    </div> : null}
    {error ? <><ProblemAlert error={error} />{error.detail ? <p className="text-sm text-danger">{error.detail}</p> : null}</> : loadError ? <ProblemAlert error={toProblem(loadError)} /> : null}
    {notice ? <Alert tone="info">{notice}</Alert> : null}
  </section>
}
