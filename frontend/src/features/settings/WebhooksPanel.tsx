import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api, call } from '@/api/client'
import type { components } from '@/api/schema'
import type { ProblemError } from '@/api/problem'
import { useWorkspace } from '@/app/workspace'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { formatDateTime } from '@/lib/dates'

type Subscription = components['schemas']['WebhookSubscription']
type Delivery = components['schemas']['WebhookDelivery']
type EventType = components['schemas']['WebhookEventType']

export const EVENT_LABEL: Record<EventType, string> = {
  'request.started': 'A request started',
  'task.opened': 'A task opened',
  'request.completed': 'A request completed',
  'request.needs-attention': 'A request needs attention',
  'callback.waiting': 'A request waits for an external system',
}
const EVENTS = Object.keys(EVENT_LABEL) as EventType[]
const SECRET = /^[a-z0-9][a-z0-9-]{0,62}$/

/**
 * Outbound webhooks (IN05, docs/build/S99): subscriptions (connection.manage edits, connection.read sees them) and the
 * dead letters operators (run.operate) can replay. Deliveries carry ids and states only, signed with the secret file the
 * reference names; the URL must be on the egress allow-list.
 */
export function WebhooksPanel() {
  const { workspace, can } = useWorkspace()
  const manage = can('connection.manage')
  const read = can('connection.read')
  const operate = can('run.operate')
  const ws = workspace.id
  const client = useQueryClient()
  const [error, setError] = useState<ProblemError | null>(null)
  const [notice, setNotice] = useState('')
  const [pending, setPending] = useState(false)
  const [name, setName] = useState('')
  const [url, setUrl] = useState('')
  const [secretRef, setSecretRef] = useState('')
  const [events, setEvents] = useState<EventType[]>(['request.completed'])

  const subscriptions = useQuery({ queryKey: ['ws', ws, 'webhook-subscriptions'], enabled: read,
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/webhook-subscriptions', { params: { path: { workspaceId: ws } } })) })
  const deadLetters = useQuery({ queryKey: ['ws', ws, 'webhook-deliveries', 'dead'], enabled: operate,
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/webhook-deliveries', { params: { path: { workspaceId: ws }, query: { status: 'dead' } } })) })
  const reload = () => Promise.all([
    client.invalidateQueries({ queryKey: ['ws', ws, 'webhook-subscriptions'] }),
    client.invalidateQueries({ queryKey: ['ws', ws, 'webhook-deliveries'] }),
  ])
  const run = async (action: () => Promise<void>) => {
    setPending(true); setError(null); setNotice('')
    try { await action() } catch (cause) { setError(toProblem(cause)) } finally { setPending(false) }
  }
  const valid = name.trim() !== '' && url.trim() !== '' && SECRET.test(secretRef) && events.length > 0
  const create = () => run(async () => {
    await call(api.POST('/workspaces/{workspaceId}/webhook-subscriptions', {
      params: { path: { workspaceId: ws } },
      body: { name: name.trim(), url: url.trim(), secretRef, events },
    }))
    setNotice(`Subscription ${name.trim()} saved.`)
    setName(''); setUrl(''); setSecretRef(''); setEvents(['request.completed'])
    await reload()
  })
  const toggle = (s: Subscription) => run(async () => {
    await call(api.PUT('/workspaces/{workspaceId}/webhook-subscriptions/{subscriptionId}', {
      params: { path: { workspaceId: ws, subscriptionId: s.id } },
      body: { name: s.name, url: s.url, secretRef: s.secretRef ?? '', events: s.events, enabled: !s.enabled, expectedVersion: s.version },
    }))
    setNotice(`Subscription ${s.name} ${s.enabled ? 'paused' : 'resumed'}.`)
    await reload()
  })
  const replay = (d: Delivery) => run(async () => {
    await call(api.POST('/workspaces/{workspaceId}/webhook-deliveries/{deliveryId}/replay', {
      params: { path: { workspaceId: ws, deliveryId: d.id } },
    }))
    setNotice(`Delivery queued again for ${d.subscriptionName}.`)
    await reload()
  })

  if (!read && !operate) return null
  const items = subscriptions.data?.items ?? []
  const dead = deadLetters.data?.items ?? []
  return (
    <section aria-labelledby="webhooks-heading" className="space-y-4">
      <div>
        <h2 id="webhooks-heading" className="text-base font-semibold">Outbound webhooks</h2>
        <p className="text-sm text-muted-foreground">
          Orqix posts request events to these endpoints, signed with <code>X-Orqix-Signature</code>. Each delivery carries
          ids and states only, never request data. Failed deliveries are retried with growing waits and end up below as dead letters.
        </p>
      </div>
      {error ? <ProblemAlert error={error} /> : null}
      {notice ? <Alert tone="info">{notice}</Alert> : null}

      {read ? (
        items.length === 0 ? <p className="text-sm text-muted-foreground">No subscriptions yet.</p> : (
          <ul className="divide-y divide-border rounded-card border border-border" aria-label="Webhook subscriptions">
            {items.map((s) => (
              <li key={s.id} className="flex flex-wrap items-start justify-between gap-2 p-3 text-sm">
                <div className="min-w-0">
                  <p className="font-semibold">{s.name} {s.enabled ? null : <span className="font-normal text-muted-foreground">(paused)</span>}</p>
                  <p className="break-all text-muted-foreground">{s.url}</p>
                  <p className="text-muted-foreground">{s.events.map((e) => EVENT_LABEL[e] ?? e).join(' · ')}</p>
                  <p className="text-muted-foreground">{s.pending} waiting to be sent · {s.dead} dead letters</p>
                </div>
                {manage ? <Button variant="secondary" disabled={pending} onClick={() => void toggle(s)} aria-label={`${s.enabled ? 'Pause' : 'Resume'} ${s.name}`}>{s.enabled ? 'Pause' : 'Resume'}</Button> : null}
              </li>
            ))}
          </ul>
        )
      ) : null}

      {manage ? (
        <form className="space-y-3 rounded-card border border-border p-4" aria-label="New webhook subscription" onSubmit={(e) => { e.preventDefault(); if (valid) void create() }}>
          <h3 className="text-sm font-semibold">Add a subscription</h3>
          <div className="space-y-1">
            <Label htmlFor="webhook-name">Name</Label>
            <Input id="webhook-name" value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="webhook-url">Endpoint URL</Label>
            <Input id="webhook-url" value={url} maxLength={2000} placeholder="https://hooks.example.com/orqix" aria-describedby="webhook-url-help" onChange={(e) => setUrl(e.target.value)} />
            <p id="webhook-url-help" className="text-xs text-muted-foreground">HTTPS, and the host must be on this installation&apos;s egress allow-list.</p>
          </div>
          <div className="space-y-1">
            <Label htmlFor="webhook-secret">Signing secret reference</Label>
            <Input id="webhook-secret" value={secretRef} maxLength={63} aria-describedby="webhook-secret-help" onChange={(e) => setSecretRef(e.target.value)} />
            <p id="webhook-secret-help" className="text-xs text-muted-foreground">The name of a secret file on the server. The secret itself is never entered here.</p>
          </div>
          <fieldset className="space-y-2">
            <legend className="text-sm font-medium">Events</legend>
            {EVENTS.map((e) => (
              <div key={e} className="flex items-center gap-2">
                <Checkbox id={`webhook-event-${e}`} checked={events.includes(e)} onCheckedChange={(checked) => setEvents((prev) => checked === true ? [...prev.filter((x) => x !== e), e] : prev.filter((x) => x !== e))} />
                <Label htmlFor={`webhook-event-${e}`}>{EVENT_LABEL[e]}</Label>
              </div>
            ))}
          </fieldset>
          <Button type="submit" disabled={!valid || pending}>Save subscription</Button>
        </form>
      ) : null}

      {operate ? (
        <div className="space-y-2">
          <h3 className="text-sm font-semibold">Dead letters</h3>
          {dead.length === 0 ? <p className="text-sm text-muted-foreground">No failed deliveries.</p> : (
            <ul className="divide-y divide-border rounded-card border border-border" aria-label="Dead letters">
              {dead.map((d) => (
                <li key={d.id} className="flex flex-wrap items-center justify-between gap-2 p-3 text-sm">
                  <div className="min-w-0">
                    <p className="font-semibold">{EVENT_LABEL[d.eventType] ?? d.eventType} → {d.subscriptionName}</p>
                    <p className="text-muted-foreground">
                      {d.attempts} {d.attempts === 1 ? 'attempt' : 'attempts'} · {d.lastStatusCode ? `last answer ${d.lastStatusCode}` : d.lastError ?? 'no answer'}
                      {d.deadAt ? ` · gave up ${formatDateTime(d.deadAt)}` : ''}
                    </p>
                  </div>
                  <Button variant="secondary" disabled={pending} onClick={() => void replay(d)} aria-label={`Replay the delivery to ${d.subscriptionName}`}>Replay</Button>
                </li>
              ))}
            </ul>
          )}
        </div>
      ) : null}
    </section>
  )
}
