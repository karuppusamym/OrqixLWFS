import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import type { ChatIdentity, TaskActionSettings } from '@/api/types'
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

const settingsKey = (ws: string) => ['ws', ws, 'task-actions', 'settings'] as const
const identitiesKey = (ws: string) => ['ws', ws, 'task-actions', 'identities'] as const
const messagesKey = (ws: string) => ['ws', ws, 'task-actions', 'messages'] as const

type ChannelName = 'email' | 'teams' | 'slack'

interface Draft {
  enabled: boolean
  linkHours: number
  email: { enabled: boolean; sender: string; domains: string }
  teams: { enabled: boolean; secretRef: string }
  slack: { enabled: boolean; secretRef: string }
}

function draftOf(s: TaskActionSettings): Draft {
  const channel = (name: ChannelName) => s.channels.find((c) => c.channel === name)
  const email = channel('email')
  const config = (email?.config ?? {}) as { sender?: string; recipientDomains?: string[] }
  return {
    enabled: s.enabled,
    linkHours: s.linkHours,
    email: { enabled: email?.enabled ?? false, sender: config.sender ?? '', domains: (config.recipientDomains ?? []).join('\n') },
    teams: { enabled: channel('teams')?.enabled ?? false, secretRef: channel('teams')?.secretRef ?? '' },
    slack: { enabled: channel('slack')?.enabled ?? false, secretRef: channel('slack')?.secretRef ?? '' },
  }
}

const SKIPS: Record<string, string> = {
  TASK_NOT_OPEN: 'Task already ended',
  TASK_ALREADY_CLAIMED: 'Someone else holds the task',
  NOT_ELIGIBLE: 'No longer eligible',
  CHANNEL_OFF: 'Channel switched off',
  RECIPIENT_NOT_ALLOWED: 'Email domain not approved',
  NO_CHAT_IDENTITY: 'Chat account not linked',
}

/**
 * Settings → Actionable tasks (CG-T01, docs/build/S141): approval tasks reach people in email, Teams and Slack with
 * Open, Claim, Approve and Reject. Each action runs as the person and is checked exactly like the task page; deciding
 * always asks for a confirmation. In this build messages go to local test stubs, never to a real mail server or tenant.
 */
export function TaskActionsPanel() {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const query = useQuery({ queryKey: settingsKey(ws), queryFn: () => endpoints.getTaskActionSettings(ws) })
  return (
    <section aria-labelledby="task-actions-heading" className="max-w-4xl space-y-4">
      <div>
        <h2 id="task-actions-heading">Actionable tasks</h2>
        <p className="text-sm text-muted-foreground">
          Send approval tasks to people in email, Microsoft Teams and Slack so they can open, claim and decide them there.
          Every action runs as that person with the same checks as the task page, and deciding asks for a confirmation.
          Messages show the task, workflow, request reference and due date only, never request details.
        </p>
      </div>
      <Alert tone="info">In this build messages go to local test stubs; nothing is sent to a real mail server, Teams or Slack.</Alert>
      {query.isPending ? (
        <LoadingBlock rows={4} label="Loading the actionable task settings" />
      ) : query.isError ? (
        <QueryError error={query.error} onRetry={() => void query.refetch()} what="the actionable task settings" />
      ) : (
        <>
          <SettingsForm key={query.data.version} ws={ws} settings={query.data} />
          <Identities ws={ws} />
          {query.data.version > 0 ? <Messages ws={ws} /> : null}
        </>
      )}
    </section>
  )
}

function SettingsForm({ ws, settings }: { ws: string; settings: TaskActionSettings }) {
  const client = useQueryClient()
  const announce = useAnnounce()
  const [draft, setDraft] = useState<Draft>(() => draftOf(settings))
  const save = useMutation({
    mutationFn: () => endpoints.updateTaskActionSettings(ws, {
      enabled: draft.enabled,
      linkHours: draft.linkHours,
      expectedVersion: settings.version,
      // A channel is sent when it is on or has been filled in, so switching one off keeps its settings.
      channels: [
        ...(draft.email.enabled || draft.email.sender.trim()
          ? [{ channel: 'email' as const, enabled: draft.email.enabled, config: { sender: draft.email.sender.trim(), recipientDomains: draft.email.domains.split(/\r?\n/).map((d) => d.trim()).filter(Boolean) } }]
          : []),
        ...(['teams', 'slack'] as const).filter((channel) => draft[channel].enabled || draft[channel].secretRef.trim())
          .map((channel) => ({ channel, enabled: draft[channel].enabled, config: {}, secretRef: draft[channel].secretRef.trim() || null })),
      ],
    }),
    onSuccess: (saved) => {
      client.setQueryData(settingsKey(ws), saved)
      announce('Actionable task settings saved')
    },
  })
  const set = (patch: Partial<Draft>) => setDraft((d) => ({ ...d, ...patch }))
  return (
    <form className="space-y-4 rounded-card border border-border p-4" onSubmit={(e) => { e.preventDefault(); save.mutate() }}>
      <div className="flex items-center gap-2">
        <Checkbox id="task-actions-enabled" checked={draft.enabled} onCheckedChange={(checked) => set({ enabled: checked === true })} />
        <Label htmlFor="task-actions-enabled">Send actionable messages for new approval tasks</Label>
        <Badge tone={settings.enabled ? 'success' : 'neutral'}>{settings.enabled ? 'On' : 'Off'}</Badge>
      </div>
      <FormField label="Links are valid for (hours)" id="task-actions-hours" description="1 to 168 hours. A link works only for the person it was sent to and only for that task.">
        {(ids) => <Input id={ids.input} type="number" min={1} max={168} value={draft.linkHours} onChange={(e) => set({ linkHours: Number(e.target.value) })} />}
      </FormField>
      <fieldset className="space-y-2">
        <legend className="text-sm font-medium">Email</legend>
        <div className="flex items-center gap-2">
          <Checkbox id="task-actions-email" checked={draft.email.enabled} onCheckedChange={(c) => set({ email: { ...draft.email, enabled: c === true } })} />
          <Label htmlFor="task-actions-email">Send email with a signed link</Label>
        </div>
        <FormField label="Sender" id="task-actions-sender">{(ids) => <Input id={ids.input} value={draft.email.sender} onChange={(e) => set({ email: { ...draft.email, sender: e.target.value } })} />}</FormField>
        <FormField label="Approved recipient domains" id="task-actions-domains" description="One exact domain per line, such as example.com. People outside these domains get no email.">
          {(ids) => <Textarea id={ids.input} value={draft.email.domains} onChange={(e) => set({ email: { ...draft.email, domains: e.target.value } })} />}
        </FormField>
      </fieldset>
      {(['teams', 'slack'] as const).map((channel) => (
        <fieldset key={channel} className="space-y-2">
          <legend className="text-sm font-medium">{channel === 'teams' ? 'Microsoft Teams' : 'Slack'}</legend>
          <div className="flex items-center gap-2">
            <Checkbox id={`task-actions-${channel}`} checked={draft[channel].enabled} onCheckedChange={(c) => set({ [channel]: { ...draft[channel], enabled: c === true } })} />
            <Label htmlFor={`task-actions-${channel}`}>Send {channel === 'teams' ? 'Teams' : 'Slack'} cards</Label>
          </div>
          <FormField label={`${channel === 'teams' ? 'Teams' : 'Slack'} signing secret reference`} id={`task-actions-${channel}-secret`}
            description={channel === 'teams' ? 'The secret holding the outgoing webhook HMAC key. Never paste the key itself.' : 'The secret holding the Slack signing secret. Never paste the secret itself.'}>
            {(ids) => <Input id={ids.input} value={draft[channel].secretRef} onChange={(e) => set({ [channel]: { ...draft[channel], secretRef: e.target.value } })} />}
          </FormField>
        </fieldset>
      ))}
      {save.isError ? <ProblemAlert error={save.error} /> : null}
      <div className="flex items-center gap-3">
        <Button type="submit" disabled={save.isPending}>Save</Button>
        {settings.updatedAt ? <span className="text-xs text-muted-foreground">Last changed by {settings.updatedBy?.displayName ?? 'someone'} on {formatDateTime(settings.updatedAt)}</span> : null}
      </div>
    </form>
  )
}

function Identities({ ws }: { ws: string }) {
  const client = useQueryClient()
  const announce = useAnnounce()
  const query = useQuery({ queryKey: identitiesKey(ws), queryFn: () => endpoints.listChatIdentities(ws) })
  const [platform, setPlatform] = useState<'teams' | 'slack'>('slack')
  const [user, setUser] = useState('')
  const [person, setPerson] = useState('')
  const link = useMutation({
    mutationFn: () => endpoints.linkChatIdentity(ws, { platform, platformUserId: user.trim(), subjectId: person.trim() }),
    onSuccess: (row) => {
      void client.invalidateQueries({ queryKey: identitiesKey(ws) })
      setUser('')
      setPerson('')
      announce(`${row.person.displayName} linked`)
    },
  })
  const unlink = useMutation({
    mutationFn: (row: ChatIdentity) => endpoints.unlinkChatIdentity(ws, row.platform, row.platformUserId),
    onSuccess: () => void client.invalidateQueries({ queryKey: identitiesKey(ws) }),
  })
  return (
    <section aria-labelledby="chat-identities-heading" className="space-y-3">
      <h3 id="chat-identities-heading">Linked chat accounts</h3>
      <p className="text-sm text-muted-foreground">A Teams or Slack button acts only for the Orqix person its account is linked to. Unlinked accounts can do nothing.</p>
      {query.isError ? <QueryError error={query.error} onRetry={() => void query.refetch()} what="the linked accounts" /> : null}
      {query.data && query.data.items.length > 0 ? (
        <Table>
          <THead><TR><TH>Platform</TH><TH>Chat account</TH><TH>Person</TH><TH>Linked</TH><TH><span className="sr-only">Actions</span></TH></TR></THead>
          <TBody>
            {query.data.items.map((row) => (
              <TR key={`${row.platform}:${row.platformUserId}`}>
                <TD>{row.platform === 'teams' ? 'Teams' : 'Slack'}</TD>
                <TD className="font-mono text-xs">{row.platformUserId}</TD>
                <TD>{row.person.displayName}</TD>
                <TD>{formatDateTime(row.linkedAt)} by {row.linkedBy.displayName}</TD>
                <TD><Button variant="ghost" size="sm" onClick={() => unlink.mutate(row)} aria-label={`Unlink ${row.platformUserId}`}>Unlink</Button></TD>
              </TR>
            ))}
          </TBody>
        </Table>
      ) : query.data ? <p className="text-sm text-muted-foreground">No chat accounts are linked yet.</p> : null}
      <form className="grid gap-2 sm:grid-cols-4" onSubmit={(e) => { e.preventDefault(); link.mutate() }}>
        <FormField label="Platform" id="chat-platform">{(ids) => <Select id={ids.input} value={platform} onChange={(e) => setPlatform(e.target.value as 'teams' | 'slack')}><option value="slack">Slack</option><option value="teams">Teams</option></Select>}</FormField>
        <FormField label="Chat user ID" id="chat-user" description="Slack member ID or Teams AAD object ID">{(ids) => <Input id={ids.input} value={user} onChange={(e) => setUser(e.target.value)} />}</FormField>
        <FormField label="Orqix person ID" id="chat-person">{(ids) => <Input id={ids.input} value={person} onChange={(e) => setPerson(e.target.value)} />}</FormField>
        <div className="flex items-end"><Button type="submit" disabled={!user.trim() || !person.trim() || link.isPending}>Link account</Button></div>
      </form>
      {link.isError ? <ProblemAlert error={link.error} /> : null}
    </section>
  )
}

function Messages({ ws }: { ws: string }) {
  const query = useQuery({ queryKey: messagesKey(ws), queryFn: () => endpoints.listTaskActionMessages(ws, 50) })
  if (!query.data || query.data.items.length === 0) return null
  return (
    <section aria-labelledby="task-action-messages-heading" className="space-y-2">
      <h3 id="task-action-messages-heading">Recent messages</h3>
      <Table>
        <THead><TR><TH>When</TH><TH>Person</TH><TH>Channel</TH><TH>Result</TH></TR></THead>
        <TBody>
          {query.data.items.map((m) => (
            <TR key={m.id}>
              <TD>{formatDateTime(m.createdAt)}</TD>
              <TD>{m.person.displayName}</TD>
              <TD>{m.channel === 'teams' ? 'Teams' : m.channel === 'slack' ? 'Slack' : 'Email'}</TD>
              <TD>{m.status === 'sent' ? <Badge tone="success">Sent</Badge> : m.status === 'queued' ? <Badge tone="info">Waiting</Badge>
                : <Badge tone="neutral">{SKIPS[m.skipCode ?? ''] ?? 'Not sent'}</Badge>}</TD>
            </TR>
          ))}
        </TBody>
      </Table>
    </section>
  )
}
