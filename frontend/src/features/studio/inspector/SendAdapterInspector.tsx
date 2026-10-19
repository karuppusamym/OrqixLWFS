import { useQuery } from '@tanstack/react-query'
import { Plus, Trash2 } from 'lucide-react'
import { api, call } from '@/api/client'
import type { components } from '@/api/schema'
import { useWorkspace } from '@/app/workspace'
import { FormField } from '@/components/common/FormField'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/input'
import { useStudio, useStudioContext } from '../StudioContext'
import { availableFields, chipLabel } from '../model/fieldRefs'
import { cfg, type WorkflowNode } from '../model/types'
import { Advanced, CommitInput, CommitTextarea, InspectorSection } from './controls'
import { StepIdField } from './SimpleInspectors'

type Adapter = components['schemas']['Adapter']
export type AdapterKind = Adapter['kind']

export const ADAPTER_KIND_LABEL: Record<AdapterKind, string> = { email: 'Email', teams: 'Microsoft Teams', kafka: 'Kafka topic', jdbc: 'Database read' }
const INPUT_NAME = /^[A-Za-z][A-Za-z0-9_]{0,39}$/
const EMAIL = /^[A-Za-z0-9._%+-]{1,64}@([A-Za-z0-9-]{1,63}(?:\.[A-Za-z0-9-]{1,63})+)$/
const TOKEN = /\{\{([A-Za-z][A-Za-z0-9_]{0,39})\}\}/g

/** Members each kind uses; everything else is removed when the kind changes, as the compiler refuses foreign members. */
const MEMBERS: Record<AdapterKind, Record<string, unknown>> = {
  email: { to: [], subject: '', body: '' },
  teams: { text: '' },
  kafka: { topic: '' },
  jdbc: {},
}

/** Placeholder names used in a text, in order of first use. */
export function adapterPlaceholders(text: string): string[] {
  return [...new Set([...text.matchAll(TOKEN)].map((m) => m[1] as string))]
}

/** Recipient lines that are not plain addresses or are outside the adapter's approved domains. */
export function recipientProblems(recipients: string[], approvedDomains: string[] | undefined): string[] {
  return recipients.flatMap((address) => {
    const m = EMAIL.exec(address)
    if (!m) return [`${address} is not a plain email address.`]
    const domain = (m[1] as string).toLowerCase()
    return approvedDomains && !approvedDomains.includes(domain) ? [`${domain} is not an approved domain of this adapter.`] : []
  })
}

const strings = (value: unknown): string[] => (Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string') : [])

/**
 * "Send through an adapter" (docs/build/S68). The step names an adapter set up by an administrator and fixes who receives
 * the message or which topic it goes to; request data only fills the text and values. The server checks the adapter's
 * allowlist again at publish time and before every send. This build delivers only to a local test stub.
 */
export function SendAdapterInspector({ node }: { node: WorkflowNode }) {
  const { store, readOnly, connections } = useStudioContext()
  const { workspace, can } = useWorkspace()
  const def = useStudio((s) => s.definition)
  const query = useQuery({
    queryKey: ['ws', workspace.id, 'adapters'],
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/adapters', { params: { path: { workspaceId: workspace.id } } })),
    enabled: can('connection.read'),
  })
  const adapters = query.data?.items ?? []
  const key = cfg<string>(node, 'adapter') ?? ''
  const kind = (cfg<string>(node, 'kind') ?? 'email') as AdapterKind
  const chosen = adapters.find((a) => a.key === key)
  const config = (chosen?.config ?? {}) as Record<string, unknown>
  const mapping = (cfg<Record<string, string>>(node, 'inputMapping') ?? {}) as Record<string, string>
  const entries = Object.entries(mapping)
  const candidates = availableFields(def, node.id, connections).filter((f) => f.type !== 'array')
  const patch = (p: Record<string, unknown>) => store.getState().updateNodeConfig(node.id, p)
  const setMapping = (next: Record<string, string>) => patch({ inputMapping: next })

  const choose = (nextKey: string) => {
    const adapter = adapters.find((a) => a.key === nextKey)
    const nextKind = adapter?.kind ?? kind
    const cleared: Record<string, unknown> = { to: undefined, subject: undefined, body: undefined, title: undefined, text: undefined, topic: undefined, key: undefined }
    const params = nextKind === 'jdbc' ? strings((adapter?.config as Record<string, unknown> | undefined)?.parameters) : []
    patch({ ...cleared, ...MEMBERS[nextKind], adapter: nextKey, kind: nextKind, inputMapping: nextKind === 'jdbc' ? Object.fromEntries(params.map((p) => [p, mapping[p] ?? ''])) : mapping })
  }

  const recipients = strings(cfg(node, 'to'))
  const domains = chosen ? strings(config.allowedRecipientDomains) : undefined
  const maxRecipients = typeof config.maxRecipients === 'number' ? config.maxRecipients : 5
  const problems = kind === 'email' ? recipientProblems(recipients, domains) : []
  const topics = strings(config.allowedTopics)
  const declared = strings(config.parameters)
  const texts = [cfg<string>(node, 'subject'), cfg<string>(node, 'body'), cfg<string>(node, 'title'), cfg<string>(node, 'text'), cfg<string>(node, 'key')]
  const unmapped = [...new Set(texts.flatMap((t) => adapterPlaceholders(t ?? '')))].filter((name) => !(name in mapping))

  return <>
    <InspectorSection title="Adapter">
      {query.isError || !can('connection.read') ? <>
        <FormField label="Adapter key" required id={`adapter-key-${node.id}`} description="Ask a connection manager for the key of the adapter to use.">
          {(ids) => <CommitInput id={ids.input} value={key} maxLength={64} disabled={readOnly} onCommit={(v) => patch({ adapter: v.trim() })} />}
        </FormField>
        <FormField label="Adapter type" id={`adapter-kind-${node.id}`}>
          {(ids) => <Select id={ids.input} value={kind} disabled={readOnly} onChange={(e) => patch({ ...MEMBERS[e.target.value as AdapterKind], kind: e.target.value })}>
            {(Object.keys(ADAPTER_KIND_LABEL) as AdapterKind[]).map((k) => <option key={k} value={k}>{ADAPTER_KIND_LABEL[k]}</option>)}
          </Select>}
        </FormField>
      </> : <FormField label="Adapter" required id={`adapter-choice-${node.id}`} description="Adapters are set up by a connection manager under Settings.">
        {(ids) => <Select id={ids.input} value={key} disabled={readOnly} onChange={(e) => choose(e.target.value)}>
          <option value="">Choose an adapter</option>
          {key && !chosen ? <option value={key}>{key} (not available)</option> : null}
          {adapters.map((a) => <option key={a.key} value={a.key} disabled={!a.enabled}>{a.name} ({ADAPTER_KIND_LABEL[a.kind]}){a.enabled ? '' : ' - disabled'}</option>)}
        </Select>}
      </FormField>}
      {chosen && !chosen.enabled ? <Alert tone="warning">This adapter is disabled. The workflow cannot be published while it uses it.</Alert> : null}
      <p className="text-sm text-muted-foreground">Recipients and topics are fixed here and must be approved by the adapter. They never come from request data. In this build every adapter delivers only to a local test stub; nothing reaches a real mailbox, channel, topic or database.</p>
    </InspectorSection>

    <InspectorSection title="Message">
      {kind === 'email' ? <>
        <FormField label="Recipients" required id={`adapter-to-${node.id}`} description={`One address per line, at most ${maxRecipients}.${domains ? ` Approved domains: ${domains.join(', ')}.` : ''}`} error={problems[0]}>
          {(ids) => <CommitTextarea id={ids.input} value={recipients.join('\n')} disabled={readOnly} aria-describedby={ids.describedBy} aria-invalid={problems.length > 0 || undefined}
            onCommit={(v) => patch({ to: [...new Set(v.split(/[\s,;]+/).map((s) => s.trim()).filter(Boolean))] })} />}
        </FormField>
        <FormField label="Subject" required id={`adapter-subject-${node.id}`} description="One line. Use {{name}} for a value listed below.">
          {(ids) => <CommitInput id={ids.input} value={cfg<string>(node, 'subject') ?? ''} maxLength={200} disabled={readOnly} aria-describedby={ids.describedBy} onCommit={(v) => patch({ subject: v })} />}
        </FormField>
        <FormField label="Message" required id={`adapter-body-${node.id}`}>
          {(ids) => <CommitTextarea id={ids.input} value={cfg<string>(node, 'body') ?? ''} maxLength={20000} disabled={readOnly} onCommit={(v) => patch({ body: v })} />}
        </FormField>
      </> : null}
      {kind === 'teams' ? <>
        <FormField label="Title" optionalText id={`adapter-title-${node.id}`}>
          {(ids) => <CommitInput id={ids.input} value={cfg<string>(node, 'title') ?? ''} maxLength={200} disabled={readOnly} onCommit={(v) => patch({ title: v.trim() ? v : undefined })} />}
        </FormField>
        <FormField label="Message" required id={`adapter-text-${node.id}`} description="Use {{name}} for a value listed below.">
          {(ids) => <CommitTextarea id={ids.input} value={cfg<string>(node, 'text') ?? ''} maxLength={4000} disabled={readOnly} aria-describedby={ids.describedBy} onCommit={(v) => patch({ text: v })} />}
        </FormField>
      </> : null}
      {kind === 'kafka' ? <>
        <FormField label="Topic" required id={`adapter-topic-${node.id}`} description="Only the topics approved by the adapter can be chosen.">
          {(ids) => topics.length > 0
            ? <Select id={ids.input} value={cfg<string>(node, 'topic') ?? ''} disabled={readOnly} onChange={(e) => patch({ topic: e.target.value })}>
                <option value="">Choose a topic</option>
                {topics.map((t) => <option key={t} value={t}>{t}</option>)}
              </Select>
            : <CommitInput id={ids.input} value={cfg<string>(node, 'topic') ?? ''} maxLength={120} disabled={readOnly} onCommit={(v) => patch({ topic: v.trim() })} />}
        </FormField>
        <FormField label="Message key" optionalText id={`adapter-mkey-${node.id}`} description="Use {{name}} for a value listed below.">
          {(ids) => <CommitInput id={ids.input} value={cfg<string>(node, 'key') ?? ''} maxLength={256} disabled={readOnly} aria-describedby={ids.describedBy} onCommit={(v) => patch({ key: v.trim() ? v : undefined })} />}
        </FormField>
        <p className="text-sm text-muted-foreground">The message value is the values listed below, by name.</p>
      </> : null}
      {kind === 'jdbc' ? <p className="text-sm text-muted-foreground">
        {declared.length > 0 ? `The adapter's read-only query needs exactly these values: ${declared.join(', ')}.` : 'Map exactly the parameters the adapter declares.'} Later steps can use the row count.
      </p> : null}
      {unmapped.length > 0 ? <Alert tone="warning">No value is listed for {unmapped.map((n) => `{{${n}}}`).join(', ')}.</Alert> : null}
    </InspectorSection>

    <InspectorSection title="Values this step uses">
      {entries.length === 0 ? <p className="text-sm text-muted-foreground">Nothing yet.</p> : null}
      <ul className="space-y-3" aria-label="Values used by the adapter step">
        {entries.map(([name, ref], i) => {
          const bad = name !== '' && !INPUT_NAME.test(name)
          return <li key={i} className="space-y-2 rounded-control border border-border p-3" data-testid={`adapter-input-${i}`}>
            <FormField label="Name" required id={`adapter-input-name-${node.id}-${i}`} error={bad ? 'Start with a letter; use letters, digits and underscores.' : undefined}>
              {(ids) => <CommitInput id={ids.input} value={name} maxLength={40} disabled={readOnly || kind === 'jdbc'} aria-describedby={ids.describedBy} aria-invalid={bad || undefined}
                onCommit={(v) => setMapping(Object.fromEntries(entries.map(([k, r]) => [k === name ? v.trim() : k, r])))} />}
            </FormField>
            <FormField label={`Value for ${name || 'this name'}`} required id={`adapter-input-ref-${node.id}-${i}`}>
              {(ids) => <Select id={ids.input} value={ref} disabled={readOnly} onChange={(e) => setMapping({ ...mapping, [name]: e.target.value })}>
                <option value="">Choose a value</option>
                {ref && !candidates.some((f) => f.ref === ref) ? <option value={ref}>{ref} (not available here)</option> : null}
                {candidates.map((f) => <option key={f.ref} value={f.ref}>{chipLabel(f)}</option>)}
              </Select>}
            </FormField>
            {readOnly || kind === 'jdbc' ? null : <Button variant="secondary" aria-label={`Remove ${name || 'the value'}`} onClick={() => setMapping(Object.fromEntries(entries.filter((_, j) => j !== i)))}><Trash2 className="size-4" aria-hidden="true" /> Remove</Button>}
          </li>
        })}
      </ul>
      {readOnly || kind === 'jdbc' || entries.length >= 20 ? null : <Button variant="secondary" onClick={() => {
        const name = unmapped.find((n) => !(n in mapping)) ?? `value${entries.length + 1}`
        setMapping({ ...mapping, [name]: candidates[0]?.ref ?? '' })
      }}><Plus className="size-4" aria-hidden="true" /> Add a value</Button>}
    </InspectorSection>
    <Advanced><StepIdField node={node} /></Advanced>
  </>
}
