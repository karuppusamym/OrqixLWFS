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
import { formatDateTime } from '@/lib/dates'
import { parseMapping } from './AdaptersPanel'

type Trigger = components['schemas']['KafkaTrigger']

const MODE_TEXT: Record<components['schemas']['KafkaTriggerList']['mode'], string> = {
  off: 'Kafka consumers are switched off in this environment. Triggers can be prepared; nothing is consumed.',
  stub: 'Stub mode: no broker is contacted. Use "Send test message" to push one value through the same handling.',
  live: 'Live: every replica consumes the enabled triggers in their consumer groups.',
}
const KEY = /^[a-z][a-z0-9-]{0,62}[a-z0-9]$/

/**
 * Kafka consumer triggers (IN05 (c), docs/build/S99): configuration (connection.manage), status with lag, replicas and
 * the last error (connection.read), dead letters (run.operate) and, in stub mode, a test message. Brokers and topics come
 * from the installation; only a cluster name, an allow-listed topic and a secret reference are chosen here.
 */
/** Plain words for the consumer's error codes (S106); an unknown code is shown as it is. */
export function kafkaErrorText(code: string): string {
  switch (code) {
    case 'secret_unavailable': return 'the credentials file is missing or not in the expected form. Retrying.'
    case 'authentication_failed': return 'the broker refused the credentials. Check the user name and password in the credentials file. Retrying.'
    case 'consumer_error': return 'the broker could not be reached or the topic could not be read. Retrying.'
    default: return code
  }
}

export function KafkaTriggersPanel() {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const manage = can('connection.manage')
  const read = can('connection.read')
  const operate = can('run.operate')
  const client = useQueryClient()
  const workflowQuery = useWorkflows(ws, {}, manage && can('workflow.read'))
  const workflows = workflowQuery.data?.pages.flatMap((p) => p.items) ?? []
  const list = useQuery({ queryKey: ['ws', ws, 'kafka-triggers'], enabled: read,
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/kafka-triggers', { params: { path: { workspaceId: ws } } })) })
  const dead = useQuery({ queryKey: ['ws', ws, 'kafka-dead-letters'], enabled: operate,
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/kafka-dead-letters', { params: { path: { workspaceId: ws } } })) })
  const [error, setError] = useState<ProblemError | null>(null)
  const [notice, setNotice] = useState('')
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const [key, setKey] = useState('')
  const [name, setName] = useState('')
  const [cluster, setCluster] = useState('')
  const [topic, setTopic] = useState('')
  const [secretRef, setSecretRef] = useState('')
  const [action, setAction] = useState<'start' | 'resume'>('start')
  const [workflowId, setWorkflowId] = useState('')
  const [messageName, setMessageName] = useState('')
  const [correlation, setCorrelation] = useState('')
  const [mapping, setMapping] = useState('')
  const [testValue, setTestValue] = useState<Record<string, string>>({})

  const reload = () => Promise.all([
    client.invalidateQueries({ queryKey: ['ws', ws, 'kafka-triggers'] }),
    client.invalidateQueries({ queryKey: ['ws', ws, 'kafka-dead-letters'] }),
  ])
  const run = async (task: () => Promise<void>) => {
    setPending(true); setError(null); setNotice('')
    try { await task(); await reload() } catch (cause) { setError(toProblem(cause)) } finally { setPending(false) }
  }
  if (!read && !operate) return null
  const data = list.data
  const clusters = data?.clusters ?? []
  const topics = clusters.find((c) => c.name === cluster)?.allowedTopics ?? []
  const parsed = action === 'resume' && mapping.trim() === '' ? {} : parseMapping(mapping)
  const valid = KEY.test(key) && name.trim() !== '' && cluster !== '' && topic !== '' && parsed !== null
    && (action === 'start' ? workflowId !== '' : messageName.trim() !== '' && correlation.startsWith('/'))

  const create = () => run(async () => {
    await call(api.POST('/workspaces/{workspaceId}/kafka-triggers', {
      params: { path: { workspaceId: ws } },
      body: {
        key, name: name.trim(), cluster, topic, action, mapping: parsed ?? {},
        ...(secretRef.trim() ? { secretRef: secretRef.trim() } : {}),
        ...(action === 'start' ? { workflowId } : { messageName: messageName.trim(), correlationPointer: correlation.trim() }),
      },
    }))
    setNotice(`Trigger ${key} saved. It acts as you: requests it starts or messages it sends are yours.`)
    setKey(''); setName(''); setMapping('')
  })
  const toggle = (t: Trigger) => run(async () => {
    await call(api.PUT('/workspaces/{workspaceId}/kafka-triggers/{kafkaTriggerId}', {
      params: { path: { workspaceId: ws, kafkaTriggerId: t.id } },
      body: { name: t.name, secretRef: t.secretRef ?? null, mapping: t.mapping, batchSize: t.batchSize, maxPerSecond: t.maxPerSecond, enabled: !t.enabled, expectedVersion: t.version },
    }))
    setNotice(`Trigger ${t.key} ${t.enabled ? 'paused' : 'resumed'}.`)
  })
  const remove = (t: Trigger) => run(async () => {
    await call(api.DELETE('/workspaces/{workspaceId}/kafka-triggers/{kafkaTriggerId}', { params: { path: { workspaceId: ws, kafkaTriggerId: t.id } } }))
    setConfirmDelete(null)
    setNotice(`Trigger ${t.key} deleted. Its records and dead letters are gone; the audit trail keeps the deletion. Kafka still remembers how far consumer group ${t.consumerGroup} read: give a new trigger a new consumer group to read the topic again.`)
  })
  const sendTest = (t: Trigger) => run(async () => {
    let value: unknown
    try { value = JSON.parse(testValue[t.id] ?? '') } catch { throw new Error('The test value must be a JSON object.') }
    const result = await call(api.POST('/workspaces/{workspaceId}/kafka-triggers/{kafkaTriggerId}/test-message', {
      params: { path: { workspaceId: ws, kafkaTriggerId: t.id } },
      body: { value: value as Record<string, never> },
    }))
    setNotice(`Test message: ${result.outcome}${result.code ? ` (${result.code})` : ''}.`)
  })

  return (
    <section aria-labelledby="kafka-heading" className="space-y-4">
      <div>
        <h2 id="kafka-heading" className="text-base font-semibold">Kafka triggers</h2>
        {data ? <p className="text-sm text-muted-foreground" data-testid="kafka-mode">{MODE_TEXT[data.mode]}</p> : null}
      </div>
      {error ? <ProblemAlert error={error} /> : null}
      {notice ? <Alert tone="info">{notice}</Alert> : null}

      {read ? (
        (data?.items ?? []).length === 0 ? <p className="text-sm text-muted-foreground">No Kafka triggers yet.</p> : (
          <ul className="divide-y divide-border rounded-card border border-border" aria-label="Kafka triggers">
            {(data?.items ?? []).map((t) => (
              <li key={t.id} className="space-y-1 p-3 text-sm" data-testid={`kafka-trigger-${t.key}`}>
                <p className="font-semibold">{t.name} <span className="font-normal text-muted-foreground">({t.key}{t.enabled ? '' : ', paused'})</span></p>
                <p className="text-muted-foreground">{t.cluster} / {t.topic} · group {t.consumerGroup} · {t.action === 'start' ? 'starts requests' : `sends ${t.messageName ?? ''} by ${t.correlationPointer ?? ''}`}</p>
                <p className="text-muted-foreground">
                  Lag {t.status.lag} · {t.status.handled} handled · {t.status.duplicates} duplicates dropped · {t.status.dead} dead letters ·{' '}
                  {t.status.replicas.length === 0 ? 'no replica consuming' : t.status.replicas.map((r) => `${r.instanceId} holds ${r.partitions.length ? r.partitions.join(', ') : 'no partition'}`).join('; ')}
                </p>
                {t.status.lastError ? <p className="text-danger">Last error: {kafkaErrorText(t.status.lastError)}{t.status.lastErrorAt ? ` at ${formatDateTime(t.status.lastErrorAt)}` : ''}</p> : null}
                <div className="flex flex-wrap items-end gap-2">
                  {manage ? <Button variant="secondary" disabled={pending} onClick={() => void toggle(t)} aria-label={`${t.enabled ? 'Pause' : 'Resume'} ${t.key}`}>{t.enabled ? 'Pause' : 'Resume'}</Button> : null}
                  {manage && !t.enabled ? (confirmDelete === t.id
                    ? <><Button variant="danger" disabled={pending} onClick={() => void remove(t)} aria-label={`Confirm: delete ${t.key}`}>Delete for good</Button><Button variant="ghost" disabled={pending} onClick={() => setConfirmDelete(null)}>Keep it</Button></>
                    : <Button variant="ghost" disabled={pending} onClick={() => setConfirmDelete(t.id)} aria-label={`Delete ${t.key}`}>Delete</Button>) : null}
                  {manage && data?.mode === 'stub' ? <>
                    <div className="space-y-1">
                      <Label htmlFor={`kafka-test-${t.id}`}>Test value (JSON) for {t.key}</Label>
                      <Textarea id={`kafka-test-${t.id}`} rows={2} value={testValue[t.id] ?? ''} onChange={(e) => setTestValue((v) => ({ ...v, [t.id]: e.target.value }))} />
                    </div>
                    <Button variant="secondary" disabled={pending} onClick={() => void sendTest(t)} aria-label={`Send test message to ${t.key}`}>Send test message</Button>
                  </> : null}
                </div>
              </li>
            ))}
          </ul>
        )
      ) : null}

      {manage ? (
        <form className="space-y-3 rounded-card border border-border p-4" aria-label="New Kafka trigger" onSubmit={(e) => { e.preventDefault(); if (valid) void create() }}>
          <h3 className="text-sm font-semibold">Add a trigger</h3>
          {clusters.length === 0 ? <p className="text-sm text-muted-foreground">This installation defines no Kafka cluster.</p> : null}
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-1"><Label htmlFor="kafka-key">Key</Label><Input id="kafka-key" value={key} maxLength={64} onChange={(e) => setKey(e.target.value)} /></div>
            <div className="space-y-1"><Label htmlFor="kafka-name">Name</Label><Input id="kafka-name" value={name} maxLength={100} onChange={(e) => setName(e.target.value)} /></div>
            <div className="space-y-1">
              <Label htmlFor="kafka-cluster">Cluster</Label>
              <Select id="kafka-cluster" value={cluster} onChange={(e) => { setCluster(e.target.value); setTopic('') }}>
                <option value="">Choose a cluster</option>
                {clusters.map((c) => <option key={c.name} value={c.name}>{c.name}</option>)}
              </Select>
            </div>
            <div className="space-y-1">
              <Label htmlFor="kafka-topic">Topic</Label>
              <Select id="kafka-topic" value={topic} onChange={(e) => setTopic(e.target.value)}>
                <option value="">Choose an allowed topic</option>
                {topics.map((t) => <option key={t} value={t}>{t}</option>)}
              </Select>
            </div>
            <div className="space-y-1">
              <Label htmlFor="kafka-secret">Credentials secret reference (optional)</Label>
              <Input id="kafka-secret" value={secretRef} maxLength={63} aria-describedby="kafka-secret-help" onChange={(e) => setSecretRef(e.target.value)} />
              <p id="kafka-secret-help" className="text-xs text-muted-foreground">The name of a server-side secret file with the broker user name and password. Never the password itself.</p>
            </div>
            <div className="space-y-1">
              <Label htmlFor="kafka-action">What a record does</Label>
              <Select id="kafka-action" value={action} onChange={(e) => setAction(e.target.value as 'start' | 'resume')}>
                <option value="start">Start a request</option>
                <option value="resume">Send a message to a waiting request</option>
              </Select>
            </div>
            {action === 'start' ? (
              <div className="space-y-1">
                <Label htmlFor="kafka-workflow">Workflow</Label>
                <Select id="kafka-workflow" value={workflowId} onChange={(e) => setWorkflowId(e.target.value)}>
                  <option value="">Choose a workflow</option>
                  {workflows.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
                </Select>
              </div>
            ) : <>
              <div className="space-y-1"><Label htmlFor="kafka-message">Message name</Label><Input id="kafka-message" value={messageName} maxLength={64} onChange={(e) => setMessageName(e.target.value)} /></div>
              <div className="space-y-1"><Label htmlFor="kafka-correlation">Correlation key pointer</Label><Input id="kafka-correlation" value={correlation} placeholder="/orderNumber" maxLength={200} onChange={(e) => setCorrelation(e.target.value)} /></div>
            </>}
          </div>
          <div className="space-y-1">
            <Label htmlFor="kafka-mapping">Field mapping (one field=/pointer per line)</Label>
            <Textarea id="kafka-mapping" rows={3} value={mapping} onChange={(e) => setMapping(e.target.value)} />
          </div>
          <Button type="submit" disabled={!valid || pending}>Save trigger</Button>
        </form>
      ) : null}

      {operate ? (
        <div className="space-y-2">
          <h3 className="text-sm font-semibold">Kafka dead letters</h3>
          {(dead.data?.items ?? []).length === 0 ? <p className="text-sm text-muted-foreground">No dead letters.</p> : (
            <ul className="divide-y divide-border rounded-card border border-border" aria-label="Kafka dead letters">
              {(dead.data?.items ?? []).map((d) => (
                <li key={d.id} className="p-3 text-sm">
                  <span className="font-semibold">{d.triggerKey}</span> · {d.errorCode} · partition {d.partition}, offset {d.offset} · {d.valueBytes} bytes · {formatDateTime(d.receivedAt)}
                </li>
              ))}
            </ul>
          )}
        </div>
      ) : null}
    </section>
  )
}
