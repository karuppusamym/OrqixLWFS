import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import type { AlertDestination, AlertDestinationKind, AlertRule, AlertSignal } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { useAnnounce } from '@/components/common/Announcer'
import { FormField } from '@/components/common/FormField'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { formatDateTime } from '@/lib/dates'

const destinationsKey = (ws: string) => ['ws', ws, 'alerts', 'destinations'] as const
const rulesKey = (ws: string) => ['ws', ws, 'alerts', 'rules'] as const
const incidentsKey = (ws: string) => ['ws', ws, 'alerts', 'incidents'] as const

export const SIGNALS: Record<AlertSignal, { label: string; windowed: boolean; scopes: Array<'workflow' | 'connection'> }> = {
  needsAttention: { label: 'Requests needing attention', windowed: false, scopes: ['workflow'] },
  slaBreaches: { label: 'SLA breaches', windowed: true, scopes: ['workflow'] },
  circuitOpen: { label: 'Paused connections (circuit open)', windowed: false, scopes: ['connection'] },
  tokenRefreshFailing: { label: 'Credential refresh failures', windowed: true, scopes: ['connection'] },
  deadLetters: { label: 'Dead letters (webhooks and external jobs)', windowed: true, scopes: ['workflow'] },
  pollChecksFailing: { label: 'Failing poll checks', windowed: false, scopes: ['workflow', 'connection'] },
  aiDriftFallback: { label: 'AI skills in drift fallback', windowed: false, scopes: [] },
}

const KINDS: Record<AlertDestinationKind, string> = {
  email: 'Email (through an email adapter)',
  teams: 'Microsoft Teams (through a Teams adapter)',
  slack: 'Slack incoming webhook',
  pagerDuty: 'PagerDuty',
  webhook: 'Outbound webhook subscription',
}

/**
 * Settings → Alerts (CG-O06, docs/build/S144): destinations and alert rules on operational signals, with the recent
 * incidents and their notifications. Needs the grant-only alert.manage permission. In this build notifications go to
 * local test stubs (Slack, PagerDuty, adapters) or the outbound webhook queue.
 */
export function AlertRulesPanel() {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const destinations = useQuery({ queryKey: destinationsKey(ws), queryFn: () => endpoints.listAlertDestinations(ws) })
  return (
    <section aria-labelledby="alerts-heading" className="max-w-5xl space-y-6">
      <div>
        <h2 id="alerts-heading">Alerts</h2>
        <p className="text-sm text-muted-foreground">
          Tell people when operations need them: requests needing attention, SLA breaches, paused connections, failing
          credentials, dead letters, failing poll checks and AI skills that fell back. A rule notifies once when it fires
          and once when it resolves; a quiet period stops repeat notifications for a flapping signal. Notifications carry
          the rule, value and threshold only, never request details.
        </p>
      </div>
      <Alert tone="info">In this build Slack, PagerDuty, email and Teams notifications go to local test stubs; webhook notifications use the outbound webhook queue.</Alert>
      {destinations.isPending ? (
        <LoadingBlock rows={3} label="Loading alert destinations" />
      ) : destinations.isError ? (
        <QueryError error={destinations.error} onRetry={() => void destinations.refetch()} what="the alert destinations" />
      ) : (
        <>
          <Destinations ws={ws} items={destinations.data.items} />
          <Rules ws={ws} destinations={destinations.data.items} />
          <Incidents ws={ws} />
        </>
      )}
    </section>
  )
}

function Destinations({ ws, items }: { ws: string; items: AlertDestination[] }) {
  const client = useQueryClient()
  const announce = useAnnounce()
  const [name, setName] = useState('')
  const [kind, setKind] = useState<AlertDestinationKind>('slack')
  const [adapterKey, setAdapterKey] = useState('')
  const [recipients, setRecipients] = useState('')
  const [secretRef, setSecretRef] = useState('')
  const [subscription, setSubscription] = useState('')
  const [tested, setTested] = useState<Record<string, string>>({})
  const create = useMutation({
    mutationFn: () => endpoints.createAlertDestination(ws, {
      name: name.trim(),
      kind,
      adapterKey: kind === 'email' || kind === 'teams' ? adapterKey.trim() || null : null,
      recipients: kind === 'email' ? recipients.split(/[\s,;]+/).map((r) => r.trim()).filter(Boolean) : null,
      secretRef: kind === 'slack' || kind === 'pagerDuty' ? secretRef.trim() || null : null,
      webhookSubscriptionId: kind === 'webhook' ? subscription.trim() || null : null,
    }),
    onSuccess: (created) => {
      void client.invalidateQueries({ queryKey: destinationsKey(ws) })
      setName('')
      announce(`${created.name} added`)
    },
  })
  const test = useMutation({
    mutationFn: (d: AlertDestination) => endpoints.testAlertDestination(ws, d.id),
    onSuccess: (result) => setTested((t) => ({ ...t, [result.destinationId]: result.status === 'sent' ? `Sent (${result.outcome ?? 'ok'})` : `Failed: ${result.errorCode ?? 'unknown'}` })),
  })
  return (
    <section aria-labelledby="alert-destinations-heading" className="space-y-3">
      <h3 id="alert-destinations-heading">Destinations</h3>
      {items.length > 0 ? (
        <Table>
          <THead><TR><TH>Name</TH><TH>Kind</TH><TH>Target</TH><TH>Status</TH><TH><span className="sr-only">Actions</span></TH></TR></THead>
          <TBody>
            {items.map((d) => (
              <TR key={d.id}>
                <TD>{d.name}</TD>
                <TD>{KINDS[d.kind]}</TD>
                <TD className="font-mono text-xs">{d.adapterKey ?? d.secretRef ?? d.webhookSubscriptionId}{d.recipients ? ` → ${d.recipients.join(', ')}` : ''}</TD>
                <TD><Badge tone={d.enabled ? 'success' : 'neutral'}>{d.enabled ? 'On' : 'Off'}</Badge> {tested[d.id] ? <span className="text-xs">{tested[d.id]}</span> : null}</TD>
                <TD><Button variant="ghost" size="sm" onClick={() => test.mutate(d)} aria-label={`Send a test to ${d.name}`}>Send test</Button></TD>
              </TR>
            ))}
          </TBody>
        </Table>
      ) : <p className="text-sm text-muted-foreground">No destinations yet. Add one before creating a rule.</p>}
      <form className="grid gap-3 rounded-card border border-border p-4 sm:grid-cols-2" onSubmit={(e) => { e.preventDefault(); create.mutate() }}>
        <FormField label="Destination name" id="alert-destination-name">{(ids) => <Input id={ids.input} value={name} onChange={(e) => setName(e.target.value)} required />}</FormField>
        <FormField label="Kind" id="alert-destination-kind">
          {(ids) => (
            <Select id={ids.input} value={kind} onChange={(e) => setKind(e.target.value as AlertDestinationKind)}>
              {(Object.keys(KINDS) as AlertDestinationKind[]).map((k) => <option key={k} value={k}>{KINDS[k]}</option>)}
            </Select>
          )}
        </FormField>
        {kind === 'email' || kind === 'teams' ? (
          <FormField label="Adapter key" id="alert-destination-adapter" description={`An existing ${kind === 'email' ? 'email' : 'Teams'} adapter; its rules apply.`}>
            {(ids) => <Input id={ids.input} value={adapterKey} onChange={(e) => setAdapterKey(e.target.value)} />}
          </FormField>
        ) : null}
        {kind === 'email' ? (
          <FormField label="Recipients" id="alert-destination-recipients" description="Up to 10 addresses in domains the adapter approves.">
            {(ids) => <Textarea id={ids.input} value={recipients} onChange={(e) => setRecipients(e.target.value)} />}
          </FormField>
        ) : null}
        {kind === 'slack' || kind === 'pagerDuty' ? (
          <FormField label="Secret reference" id="alert-destination-secret"
            description={kind === 'slack' ? 'The secret holding the Slack incoming webhook address. Never paste the address itself.' : 'The secret holding the PagerDuty routing key. Never paste the key itself.'}>
            {(ids) => <Input id={ids.input} value={secretRef} onChange={(e) => setSecretRef(e.target.value)} />}
          </FormField>
        ) : null}
        {kind === 'webhook' ? (
          <FormField label="Webhook subscription ID" id="alert-destination-hook" description="Notifications are signed, retried and dead-lettered like request events.">
            {(ids) => <Input id={ids.input} value={subscription} onChange={(e) => setSubscription(e.target.value)} />}
          </FormField>
        ) : null}
        {create.isError ? <div className="sm:col-span-2"><ProblemAlert error={create.error} /></div> : null}
        <div className="sm:col-span-2"><Button type="submit" disabled={create.isPending || !name.trim()}>Add destination</Button></div>
      </form>
    </section>
  )
}

function Rules({ ws, destinations }: { ws: string; destinations: AlertDestination[] }) {
  const client = useQueryClient()
  const announce = useAnnounce()
  const query = useQuery({ queryKey: rulesKey(ws), queryFn: () => endpoints.listAlertRules(ws) })
  const [name, setName] = useState('')
  const [signal, setSignal] = useState<AlertSignal>('needsAttention')
  const [threshold, setThreshold] = useState(1)
  const [windowMinutes, setWindowMinutes] = useState(15)
  const [quietMinutes, setQuietMinutes] = useState(30)
  const [severity, setSeverity] = useState<'info' | 'warning' | 'critical'>('warning')
  const [scope, setScope] = useState('')
  const [chosen, setChosen] = useState<string[]>([])
  const meta = SIGNALS[signal]
  const create = useMutation({
    mutationFn: () => endpoints.createAlertRule(ws, {
      name: name.trim(), signal, threshold, windowMinutes, quietMinutes, severity, destinationIds: chosen,
      workflowId: meta.scopes.includes('workflow') && scope.trim() && !meta.scopes.includes('connection') ? scope.trim() : null,
      connectionKey: meta.scopes.includes('connection') && scope.trim() ? scope.trim() : null,
    }),
    onSuccess: (rule) => {
      void client.invalidateQueries({ queryKey: rulesKey(ws) })
      setName('')
      announce(`${rule.name} created`)
    },
  })
  const silence = useMutation({
    mutationFn: ({ rule, minutes }: { rule: AlertRule; minutes: number }) => endpoints.silenceAlertRule(ws, rule.id, minutes),
    onSuccess: () => void client.invalidateQueries({ queryKey: rulesKey(ws) }),
  })
  const remove = useMutation({
    mutationFn: (rule: AlertRule) => endpoints.deleteAlertRule(ws, rule.id),
    onSuccess: () => void client.invalidateQueries({ queryKey: rulesKey(ws) }),
  })
  return (
    <section aria-labelledby="alert-rules-heading" className="space-y-3">
      <h3 id="alert-rules-heading">Rules</h3>
      {query.isError ? <QueryError error={query.error} onRetry={() => void query.refetch()} what="the alert rules" /> : null}
      {query.data && query.data.items.length > 0 ? (
        <Table>
          <THead><TR><TH>Rule</TH><TH>Signal</TH><TH>Fires at</TH><TH>State</TH><TH><span className="sr-only">Actions</span></TH></TR></THead>
          <TBody>
            {query.data.items.map((rule) => (
              <TR key={rule.id}>
                <TD>{rule.name} <Badge tone={rule.severity === 'critical' ? 'danger' : rule.severity === 'warning' ? 'warning' : 'neutral'}>{rule.severity}</Badge></TD>
                <TD>{SIGNALS[rule.signal].label}{rule.connectionKey ? ` · ${rule.connectionKey}` : ''}</TD>
                <TD>{rule.threshold}{rule.windowed ? ` in ${rule.windowMinutes} min` : ''}</TD>
                <TD>
                  <Badge tone={rule.state === 'firing' ? 'danger' : 'success'}>{rule.state === 'firing' ? 'Firing' : 'OK'}</Badge>
                  {rule.lastValue !== null && rule.lastValue !== undefined ? <span className="ml-2 text-xs">now {rule.lastValue}</span> : null}
                  {rule.silencedUntil ? <span className="ml-2 text-xs">Silenced until {formatDateTime(rule.silencedUntil)}</span> : null}
                </TD>
                <TD className="space-x-1 whitespace-nowrap">
                  {rule.silencedUntil
                    ? <Button variant="ghost" size="sm" onClick={() => silence.mutate({ rule, minutes: 0 })} aria-label={`End the silence of ${rule.name}`}>Unsilence</Button>
                    : <Button variant="ghost" size="sm" onClick={() => silence.mutate({ rule, minutes: 240 })} aria-label={`Silence ${rule.name} for 4 hours`}>Silence 4 h</Button>}
                  <Button variant="ghost" size="sm" onClick={() => remove.mutate(rule)} aria-label={`Delete ${rule.name}`}>Delete</Button>
                </TD>
              </TR>
            ))}
          </TBody>
        </Table>
      ) : query.data ? <p className="text-sm text-muted-foreground">No alert rules yet.</p> : null}
      <form className="grid gap-3 rounded-card border border-border p-4 sm:grid-cols-3" onSubmit={(e) => { e.preventDefault(); create.mutate() }}>
        <FormField label="Rule name" id="alert-rule-name">{(ids) => <Input id={ids.input} value={name} onChange={(e) => setName(e.target.value)} />}</FormField>
        <FormField label="Signal" id="alert-rule-signal">
          {(ids) => (
            <Select id={ids.input} value={signal} onChange={(e) => { setSignal(e.target.value as AlertSignal); setScope('') }}>
              {(Object.keys(SIGNALS) as AlertSignal[]).map((s) => <option key={s} value={s}>{SIGNALS[s].label}</option>)}
            </Select>
          )}
        </FormField>
        <FormField label="Severity" id="alert-rule-severity">
          {(ids) => (
            <Select id={ids.input} value={severity} onChange={(e) => setSeverity(e.target.value as 'info' | 'warning' | 'critical')}>
              <option value="info">Info</option><option value="warning">Warning</option><option value="critical">Critical</option>
            </Select>
          )}
        </FormField>
        <FormField label="Threshold" id="alert-rule-threshold" description="Fires when the value is at least this.">
          {(ids) => <Input id={ids.input} type="number" min={1} max={100000} value={threshold} onChange={(e) => setThreshold(Number(e.target.value))} />}
        </FormField>
        {meta.windowed ? (
          <FormField label="Window (minutes)" id="alert-rule-window" description="Counts events in the last 5 to 1,440 minutes.">
            {(ids) => <Input id={ids.input} type="number" min={5} max={1440} value={windowMinutes} onChange={(e) => setWindowMinutes(Number(e.target.value))} />}
          </FormField>
        ) : null}
        <FormField label="Quiet period (minutes)" id="alert-rule-quiet" description="No new notification this soon after the last one.">
          {(ids) => <Input id={ids.input} type="number" min={0} max={1440} value={quietMinutes} onChange={(e) => setQuietMinutes(Number(e.target.value))} />}
        </FormField>
        {meta.scopes.length > 0 ? (
          <FormField label={meta.scopes.includes('connection') ? 'Connection key (optional)' : 'Workflow ID (optional)'} id="alert-rule-scope">
            {(ids) => <Input id={ids.input} value={scope} onChange={(e) => setScope(e.target.value)} />}
          </FormField>
        ) : null}
        <fieldset className="space-y-1 sm:col-span-3">
          <legend className="text-sm font-medium">Notify</legend>
          {destinations.length === 0 ? <p className="text-sm text-muted-foreground">Add a destination first.</p> : null}
          {destinations.map((d) => (
            <div key={d.id} className="flex items-center gap-2">
              <Checkbox id={`alert-rule-dest-${d.id}`} checked={chosen.includes(d.id)}
                onCheckedChange={(c) => setChosen((list) => (c === true ? [...list, d.id] : list.filter((x) => x !== d.id)))} />
              <Label htmlFor={`alert-rule-dest-${d.id}`}>{d.name}</Label>
            </div>
          ))}
        </fieldset>
        {create.isError ? <div className="sm:col-span-3"><ProblemAlert error={create.error} /></div> : null}
        <div className="sm:col-span-3"><Button type="submit" disabled={create.isPending || !name.trim() || chosen.length === 0}>Create rule</Button></div>
      </form>
    </section>
  )
}

function Incidents({ ws }: { ws: string }) {
  const query = useQuery({ queryKey: incidentsKey(ws), queryFn: () => endpoints.listAlertIncidents(ws) })
  return (
    <section aria-labelledby="alert-incidents-heading" className="space-y-3">
      <h3 id="alert-incidents-heading">Recent incidents</h3>
      {query.isError ? <QueryError error={query.error} onRetry={() => void query.refetch()} what="the alert incidents" /> : null}
      {query.data && query.data.items.length > 0 ? (
        <Table>
          <THead><TR><TH>Rule</TH><TH>Opened</TH><TH>Status</TH><TH>Value</TH><TH>Notifications</TH></TR></THead>
          <TBody>
            {query.data.items.map((i) => (
              <TR key={i.id}>
                <TD>{i.ruleName}</TD>
                <TD>{formatDateTime(i.openedAt)}</TD>
                <TD>{i.status === 'firing' ? 'Firing' : `Resolved ${i.resolvedAt ? formatDateTime(i.resolvedAt) : ''}`}</TD>
                <TD>{i.openingValue} (peak {i.peakValue}, threshold {i.threshold})</TD>
                <TD>
                  {i.suppressed ? <span>Not notified ({i.suppressed === 'quietPeriod' ? 'quiet period' : 'silenced'})</span> : null}
                  {i.deliveries.map((d) => <div key={d.id} className="text-xs">{d.destinationName}: {d.kind} {d.status}{d.errorCode ? ` (${d.errorCode})` : ''}</div>)}
                </TD>
              </TR>
            ))}
          </TBody>
        </Table>
      ) : query.data ? <p className="text-sm text-muted-foreground">No incidents yet.</p> : null}
    </section>
  )
}
