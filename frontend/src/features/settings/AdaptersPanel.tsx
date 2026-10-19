import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api, call } from '@/api/client'
import type { components } from '@/api/schema'
import type { ProblemError } from '@/api/problem'
import { useWorkflows } from '@/api/queries'
import { useWorkspace } from '@/app/workspace'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'

type Adapter = components['schemas']['Adapter']
type Trigger = components['schemas']['InboundTrigger']
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

const words = (value: unknown): string => (Array.isArray(value) ? value.filter((v) => typeof v === 'string').join(', ') : '')

/** The editable fields of a stored adapter configuration (the reverse of buildAdapterConfig). */
export function adapterFields(kind: Kind, config: Record<string, unknown>): AdapterFields {
  return {
    from: kind === 'email' ? String(config.from ?? '') : '',
    domains: kind === 'email' ? words(config.allowedRecipientDomains) : '',
    baseUrl: kind === 'teams' ? String(config.baseUrl ?? '') : '',
    statement: kind === 'jdbc' ? String(config.statement ?? '') : '',
    parameters: kind === 'jdbc' ? words(config.parameters) : '',
    maxRows: kind === 'jdbc' && config.maxRows !== undefined ? String(config.maxRows) : '',
    topics: kind === 'kafka' ? words(config.allowedTopics) : '',
  }
}

/** "title=/value/title" lines from a stored mapping. */
export function mappingText(mapping: Record<string, string>): string {
  return Object.entries(mapping).map(([field, pointer]) => `${field}=${pointer}`).join('\n')
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
  // The target workflow list needs workflow.read; a connection manager without it must not trigger a refused request.
  const workflowQuery = useWorkflows(workspace.id, {}, manage && can('workflow.read'))
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
  // Editing (S68): one adapter or trigger at a time, with the version it was loaded at.
  const [editAdapter, setEditAdapter] = useState<{ adapter: Adapter; name: string; secretRef: string; enabled: boolean; fields: AdapterFields } | null>(null)
  const [editTrigger, setEditTrigger] = useState<{ trigger: Trigger; name: string; secretRef: string; enabled: boolean; filter: string; mapping: string } | null>(null)
  const [purgeDays, setPurgeDays] = useState('90')
  const [purgeUnstarted, setPurgeUnstarted] = useState(false)

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
  const saveAdapterEdit = () => run(async () => {
    if (!editAdapter) return
    const { adapter } = editAdapter
    const updated = await call(api.PUT('/workspaces/{workspaceId}/adapters/{adapterKey}', {
      params: { path: { workspaceId: workspace.id, adapterKey: adapter.key } },
      body: { name: editAdapter.name.trim(), secretRef: editAdapter.secretRef, config: buildAdapterConfig(adapter.kind, editAdapter.fields), enabled: editAdapter.enabled, expectedVersion: adapter.version },
    }))
    setNotice(`Adapter ${adapter.key} saved (version ${updated.version}${updated.enabled ? '' : ', disabled'}). Workflow steps check it again before every send.`)
    setEditAdapter(null)
    await load()
  })
  const saveTriggerEdit = () => run(async () => {
    if (!editTrigger) return
    const { trigger } = editTrigger
    const parsed = parseMapping(editTrigger.mapping)
    if (!parsed) throw new Error('List at least one start form field as field=/path/in/event.')
    const filter = trigger.source === 'email' ? { allowedSenderDomains: list(editTrigger.filter) } : trigger.source === 'kafka' ? { allowedTopics: list(editTrigger.filter) } : {}
    const updated = await call(api.PUT('/workspaces/{workspaceId}/inbound-triggers/{triggerId}', {
      params: { path: { workspaceId: workspace.id, triggerId: trigger.id } },
      body: { name: editTrigger.name.trim(), secretRef: editTrigger.secretRef, filter, mapping: parsed, enabled: editTrigger.enabled, expectedVersion: trigger.version },
    }))
    setNotice(`Trigger ${trigger.key} saved (version ${updated.version}${updated.enabled ? '' : ', disabled: senders are refused'}).`)
    setEditTrigger(null)
    await load()
  })
  const purgeDaysValue = Number(purgeDays)
  const purgeValid = Number.isInteger(purgeDaysValue) && purgeDaysValue >= 1 && purgeDaysValue <= 3650
  const purge = () => run(async () => {
    const result = await call(api.POST('/workspaces/{workspaceId}/inbound-events/purge', {
      params: { path: { workspaceId: workspace.id } }, body: { olderThanDays: purgeDaysValue, includeUnstarted: purgeUnstarted },
    }))
    setNotice(`${result.deleted} received ${result.deleted === 1 ? 'event' : 'events'} older than ${purgeDaysValue} days deleted.`)
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
          <p><strong>{adapter.name}</strong> <span className="text-sm text-muted-foreground">({adapter.key}, {KIND_LABEL[adapter.kind]}{adapter.enabled ? '' : ', disabled'})</span></p>
          {manage && editAdapter?.adapter.key === adapter.key ? <div className="mt-2 space-y-2" role="group" aria-label={`Edit adapter ${adapter.key}`}>
            <div><Label htmlFor={`edit-name-${adapter.key}`}>Name of {adapter.key}</Label><Input id={`edit-name-${adapter.key}`} value={editAdapter.name} maxLength={100} onChange={event => setEditAdapter({ ...editAdapter, name: event.target.value })} /></div>
            <div><Label htmlFor={`edit-secret-${adapter.key}`}>Secret reference name of {adapter.key}</Label><Input id={`edit-secret-${adapter.key}`} value={editAdapter.secretRef} maxLength={63} onChange={event => setEditAdapter({ ...editAdapter, secretRef: event.target.value })} /></div>
            {adapter.kind === 'email' ? <div><Label htmlFor={`edit-domains-${adapter.key}`}>Approved recipient domains of {adapter.key}</Label><Input id={`edit-domains-${adapter.key}`} value={editAdapter.fields.domains} onChange={event => setEditAdapter({ ...editAdapter, fields: { ...editAdapter.fields, domains: event.target.value } })} /></div> : null}
            {adapter.kind === 'teams' ? <div><Label htmlFor={`edit-base-${adapter.key}`}>Teams service address of {adapter.key}</Label><Input id={`edit-base-${adapter.key}`} value={editAdapter.fields.baseUrl} onChange={event => setEditAdapter({ ...editAdapter, fields: { ...editAdapter.fields, baseUrl: event.target.value } })} /></div> : null}
            {adapter.kind === 'jdbc' ? <div><Label htmlFor={`edit-statement-${adapter.key}`}>Read-only SELECT statement of {adapter.key}</Label><Textarea id={`edit-statement-${adapter.key}`} value={editAdapter.fields.statement} onChange={event => setEditAdapter({ ...editAdapter, fields: { ...editAdapter.fields, statement: event.target.value } })} />
              <Label htmlFor={`edit-params-${adapter.key}`}>Parameter names of {adapter.key}</Label><Input id={`edit-params-${adapter.key}`} value={editAdapter.fields.parameters} onChange={event => setEditAdapter({ ...editAdapter, fields: { ...editAdapter.fields, parameters: event.target.value } })} /></div> : null}
            {adapter.kind === 'kafka' ? <div><Label htmlFor={`edit-topics-${adapter.key}`}>Approved topics of {adapter.key}</Label><Input id={`edit-topics-${adapter.key}`} value={editAdapter.fields.topics} onChange={event => setEditAdapter({ ...editAdapter, fields: { ...editAdapter.fields, topics: event.target.value } })} /></div> : null}
            <div className="flex items-center gap-2"><Checkbox id={`edit-enabled-${adapter.key}`} checked={editAdapter.enabled} onCheckedChange={checked => setEditAdapter({ ...editAdapter, enabled: checked === true })} /><Label htmlFor={`edit-enabled-${adapter.key}`}>{adapter.key} is enabled</Label></div>
            <p className="text-sm text-muted-foreground">Kind and key cannot change. A disabled adapter or a narrower allowlist stops later workflow sends, which then need attention; a wider allowlist adds no recipient or topic to existing steps.</p>
            <div className="flex gap-2"><Button disabled={pending || editAdapter.name.trim() === '' || !SECRET.test(editAdapter.secretRef)} onClick={() => void saveAdapterEdit()}>Save changes to {adapter.key}</Button>
              <Button variant="secondary" onClick={() => setEditAdapter(null)}>Cancel</Button></div>
          </div> : null}
          {manage ? <div className="mt-2 space-y-2">
            {editAdapter?.adapter.key === adapter.key ? null : <Button variant="secondary" disabled={pending} onClick={() => setEditAdapter({ adapter, name: adapter.name, secretRef: adapter.secretRef ?? '', enabled: adapter.enabled, fields: adapterFields(adapter.kind, adapter.config as Record<string, unknown>) })}>Edit {adapter.key}</Button>}
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
          <p><strong>{trigger.name}</strong> <span className="text-sm text-muted-foreground">({trigger.key}, {KIND_LABEL[trigger.source]}{trigger.enabled ? '' : ', disabled'})</span></p>
          {trigger.receivePath ? <p className="text-sm">Senders post signed events to <code>{trigger.receivePath}</code></p> : null}
          {manage && editTrigger?.trigger.id === trigger.id ? <div className="mt-2 space-y-2" role="group" aria-label={`Edit trigger ${trigger.key}`}>
            <div><Label htmlFor={`edit-tname-${trigger.key}`}>Name of {trigger.key}</Label><Input id={`edit-tname-${trigger.key}`} value={editTrigger.name} maxLength={100} onChange={event => setEditTrigger({ ...editTrigger, name: event.target.value })} /></div>
            <div><Label htmlFor={`edit-tsecret-${trigger.key}`}>Signing secret reference name of {trigger.key}</Label><Input id={`edit-tsecret-${trigger.key}`} value={editTrigger.secretRef} maxLength={63} onChange={event => setEditTrigger({ ...editTrigger, secretRef: event.target.value })} /></div>
            {trigger.source === 'email' || trigger.source === 'kafka' ? <div><Label htmlFor={`edit-tfilter-${trigger.key}`}>{trigger.source === 'email' ? 'Approved sender domains' : 'Approved topics'} of {trigger.key}</Label><Input id={`edit-tfilter-${trigger.key}`} value={editTrigger.filter} onChange={event => setEditTrigger({ ...editTrigger, filter: event.target.value })} /></div> : null}
            <div><Label htmlFor={`edit-tmapping-${trigger.key}`}>Start form fields of {trigger.key}</Label><Textarea id={`edit-tmapping-${trigger.key}`} value={editTrigger.mapping} onChange={event => setEditTrigger({ ...editTrigger, mapping: event.target.value })} /></div>
            <div className="flex items-center gap-2"><Checkbox id={`edit-tenabled-${trigger.key}`} checked={editTrigger.enabled} onCheckedChange={checked => setEditTrigger({ ...editTrigger, enabled: checked === true })} /><Label htmlFor={`edit-tenabled-${trigger.key}`}>{trigger.key} accepts events</Label></div>
            <p className="text-sm text-muted-foreground">Source, key and workflow cannot change. Events already received keep the values they arrived with and can still be started.</p>
            <div className="flex gap-2"><Button disabled={pending || editTrigger.name.trim() === '' || !SECRET.test(editTrigger.secretRef) || parseMapping(editTrigger.mapping) === null} onClick={() => void saveTriggerEdit()}>Save changes to {trigger.key}</Button>
              <Button variant="secondary" onClick={() => setEditTrigger(null)}>Cancel</Button></div>
          </div> : manage ? <Button variant="secondary" className="mt-2" disabled={pending} onClick={() => setEditTrigger({ trigger, name: trigger.name, secretRef: trigger.secretRef ?? '', enabled: trigger.enabled,
            filter: words((trigger.filter as Record<string, unknown>).allowedSenderDomains ?? (trigger.filter as Record<string, unknown>).allowedTopics), mapping: mappingText(trigger.mapping) })}>Edit {trigger.key}</Button> : null}
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
        <option value="">Choose a workflow</option>{workflows.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}</Select>
        {!can('workflow.read') ? <p className="text-xs text-muted-foreground">You need permission to read workflows to choose the workflow a trigger starts.</p> : null}</div>
      <div><Label htmlFor="trigger-secret">Signing secret reference name</Label><Input id="trigger-secret" value={tSecret} maxLength={63} onChange={event => setTSecret(event.target.value)} /></div>
      {tSource === 'email' || tSource === 'kafka' ? <div><Label htmlFor="trigger-filter">{tSource === 'email' ? 'Approved sender domains' : 'Approved topics to receive from'}</Label><Input id="trigger-filter" value={tFilter} onChange={event => setTFilter(event.target.value)} /></div> : null}
      <div><Label htmlFor="trigger-mapping">Start form fields, one per line as field=/path/in/event</Label><Textarea id="trigger-mapping" value={tMapping} onChange={event => setTMapping(event.target.value)} /></div>
      <Button disabled={pending || !triggerValid} onClick={() => void saveTrigger()}>Save trigger</Button>
    </div> : null}
    {manage ? <div className="space-y-3">
      <h3>Received event retention</h3>
      <p className="text-sm text-muted-foreground">Delete received events older than a number of days. Started events are always included; their requests are not affected. Events nobody started are only deleted when you include them. A sender that repeats a deleted event is accepted again as a new event.</p>
      <div><Label htmlFor="purge-days">Delete events older than (days)</Label><Input id="purge-days" type="number" min={1} max={3650} value={purgeDays} onChange={event => setPurgeDays(event.target.value)} /></div>
      <div className="flex items-center gap-2"><Checkbox id="purge-unstarted" checked={purgeUnstarted} onCheckedChange={checked => setPurgeUnstarted(checked === true)} /><Label htmlFor="purge-unstarted">Also delete events nobody started</Label></div>
      <Button variant="secondary" disabled={pending || !purgeValid} onClick={() => void purge()}>Delete old events</Button>
    </div> : null}
    {error ? <><ProblemAlert error={error} />{error.detail ? <p className="text-sm text-danger">{error.detail}</p> : null}</> : loadError ? <ProblemAlert error={toProblem(loadError)} /> : null}
    {notice ? <Alert tone="info">{notice}</Alert> : null}
  </section>
}
