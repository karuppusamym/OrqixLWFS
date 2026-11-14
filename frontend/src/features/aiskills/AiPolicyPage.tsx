import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { isProblemError } from '@/api/problem'
import { useWorkspace } from '@/app/workspace'
import { FormField } from '@/components/common/FormField'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { formatDateTime } from '@/lib/dates'
import { fieldKeyFromPointer } from '@/lib/problemMessages'
import { AiProblem, useAction } from './AiProblem'
import { aiSkillKeys, updateAiPolicy, useAiPolicy, type AiPolicy } from './api'
import { CLASSIFICATIONS } from './skillDocument'

type Action = 'send' | 'mask' | 'hash' | 'drop'
interface PolicyForm {
  regulated: boolean
  residency: string
  inputRetention: AiPolicy['inputRetention']
  retentionDays: string
  lowRiskLabels: string
  allowAutoWithReview: boolean
  classificationActions: Record<string, Action>
}

const RETENTION_LABEL: Record<AiPolicy['inputRetention'], string> = {
  digest: 'Digest only (default): a fingerprint of the input, no values',
  redactedValues: 'Minimized values: what was sent to the model',
  fullValues: 'Full values',
}
const ACTION_LABEL: Record<Action, string> = { send: 'Send', mask: 'Mask card numbers and e-mails', hash: 'Hash', drop: 'Drop' }
const list = (text: string) => [...new Set(text.split(/[\n,]/).map((s) => s.trim()).filter(Boolean))]

const toForm = (p: AiPolicy): PolicyForm => ({
  regulated: p.regulated, residency: p.residency.join(', '), inputRetention: p.inputRetention, retentionDays: String(p.retentionDays),
  lowRiskLabels: p.lowRiskLabels.join('\n'), allowAutoWithReview: p.allowAutoWithReview,
  classificationActions: Object.fromEntries(CLASSIFICATIONS.map((c) => [c, (p.classificationActions[c] ?? 'send') as Action])),
})

/**
 * Workspace AI policy (S134 section 6): residency, input retention of decision records, the low-risk labels that may be
 * decided fully automatically, whether autoWithReview is allowed, and what happens to each input classification.
 * Everyone who can see AI skills reads it; only workspace administrators (workspace.manage-access) change it, with the
 * version they loaded (a concurrent change is refused and they reload).
 */
export function AiPolicyPage() {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const readable = can('workflow.read', 'ai.evaluate', 'ai.approve-skill', 'workspace.manage-access')
  const policy = useAiPolicy(ws, readable)
  const [savedVersion, setSavedVersion] = useState<number | null>(null)
  if (!readable) return <Alert tone="warning">You need permission to read workflows or manage access to see the AI policy.</Alert>
  return (
    <div className="space-y-6">
      <PageHeader title="AI policy" description="Where AI requests may go, what decision records keep, and what may be decided without a person."
        actions={can('workflow.read', 'ai.evaluate', 'ai.approve-skill') ? <Link className="text-link underline" to={`/w/${ws}/ai-skills`}>AI skills</Link> : undefined} />
      {policy.isPending ? <LoadingBlock rows={4} label="Loading the AI policy" />
        : policy.isError ? <QueryError error={policy.error} onRetry={() => void policy.refetch()} what="the AI policy" />
          : <PolicyEditor key={policy.data.version} policy={policy.data} editable={can('workspace.manage-access')} saved={savedVersion === policy.data.version} onSaved={setSavedVersion} onReload={() => void policy.refetch()} />}
    </div>
  )
}

function PolicyEditor({ policy, editable, saved, onSaved, onReload }: { policy: AiPolicy; editable: boolean; saved: boolean; onSaved: (version: number) => void; onReload: () => void }) {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const client = useQueryClient()
  const [form, setForm] = useState<PolicyForm>(() => toForm(policy))
  const [errors, setErrors] = useState<Record<string, string>>({})
  const save = useAction()
  const conflict = isProblemError(save.error) && save.error.code === 'CONFIGURATION_VERSION_CONFLICT'
  const set = (patch: Partial<PolicyForm>) => setForm((f) => ({ ...f, ...patch }))
  const residency = list(form.residency)

  const submit = () => {
    const next: Record<string, string> = {}
    const days = Number(form.retentionDays)
    if (!Number.isInteger(days) || days < 1 || days > 3650) next.retentionDays = 'Keep decision records between 1 and 3650 days.'
    const badTag = residency.find((t) => !/^[a-z][a-z0-9-]{0,30}$/.test(t))
    if (badTag) next.residency = `The tag ${badTag} is not valid: use lowercase letters, digits and dashes, such as eu.`
    const labels = list(form.lowRiskLabels)
    const badLabel = labels.find((l) => !/^[a-z][a-z0-9-]{0,62}[a-z0-9]:[a-z][A-Za-z0-9]{0,39}$/.test(l))
    if (badLabel) next.lowRiskLabels = `Write each label as skillKey:label, such as dispute-triage:duplicate (${badLabel} is not).`
    if (form.regulated && form.classificationActions.sensitive === 'send') next.sensitive = 'A regulated workspace never sends sensitive fields.'
    setErrors(next)
    if (Object.keys(next).length > 0) return
    void save.run(() => updateAiPolicy(ws, {
      expectedVersion: policy.version, regulated: form.regulated, residency, inputRetention: form.inputRetention, retentionDays: days,
      lowRiskLabels: labels, allowAutoWithReview: form.allowAutoWithReview, classificationActions: form.classificationActions,
    }), async (updated) => {
      onSaved(updated.version)
      client.setQueryData(aiSkillKeys.policy(ws), updated)
    })
  }
  const serverErrors = isProblemError(save.error) ? Object.fromEntries(save.error.fieldErrors.map((f) => [fieldKeyFromPointer(f.pointer), f.message])) : {}
  const err = (k: string) => errors[k] ?? serverErrors[k]

  return (
    <div className="space-y-6">
      {!editable ? <Alert tone="info" data-testid="policy-read-only">Only workspace administrators can change the AI policy. You can read it.</Alert> : null}
      <p className="text-xs text-muted-foreground">Policy version {policy.version}{policy.updatedAt ? `, changed ${formatDateTime(policy.updatedAt)}` : ', the defaults'}.</p>
      <fieldset disabled={!editable} className="space-y-5">
        <div className="space-y-2">
          <div className="flex items-center gap-2">
            <Checkbox id="policy-regulated" checked={form.regulated} disabled={!editable} onCheckedChange={(v) => set({ regulated: v === true })} />
            <Label htmlFor="policy-regulated">Regulated workspace</Label>
          </div>
          <p className="text-sm text-muted-foreground">Adverse outcomes always go to a person and sensitive fields are never sent.</p>
        </div>
        <FormField label="Residency tags" optionalText error={err('residency')} description="Only model endpoints carrying one of these tags may be used. Empty means no residency rule.">
          {(ids) => <Input id={ids.input} aria-describedby={ids.describedBy} aria-invalid={err('residency') ? true : undefined} value={form.residency} onChange={(e) => set({ residency: e.target.value })} placeholder="eu, de" />}
        </FormField>
        <div className="grid gap-4 md:grid-cols-2">
          <FormField label="Decision record input">
            {(ids) => (
              <Select id={ids.input} value={form.inputRetention} onChange={(e) => set({ inputRetention: e.target.value as PolicyForm['inputRetention'] })}>
                {(Object.keys(RETENTION_LABEL) as Array<PolicyForm['inputRetention']>).map((r) => <option key={r} value={r}>{RETENTION_LABEL[r]}</option>)}
              </Select>
            )}
          </FormField>
          <FormField label="Keep decision records (days)" error={err('retentionDays')}>
            {(ids) => <Input id={ids.input} inputMode="numeric" aria-describedby={ids.describedBy} aria-invalid={err('retentionDays') ? true : undefined} value={form.retentionDays} onChange={(e) => set({ retentionDays: e.target.value })} />}
          </FormField>
        </div>
        <FormField label="Low-risk labels" optionalText error={err('lowRiskLabels')} description="AI tasks set to automatic decide only these labels on their own, one skillKey:label per line.">
          {(ids) => <Textarea id={ids.input} aria-describedby={ids.describedBy} aria-invalid={err('lowRiskLabels') ? true : undefined} className="font-mono text-sm" value={form.lowRiskLabels} onChange={(e) => set({ lowRiskLabels: e.target.value })} />}
        </FormField>
        <div className="flex items-center gap-2">
          <Checkbox id="policy-review" checked={form.allowAutoWithReview} disabled={!editable} onCheckedChange={(v) => set({ allowAutoWithReview: v === true })} />
          <Label htmlFor="policy-review">Allow automatic decisions with a review sample</Label>
        </div>
        <fieldset className="space-y-2">
          <legend className="font-semibold">Input classifications</legend>
          <p className="text-sm text-muted-foreground">What happens to an input of each classification before it reaches the model. The step's own redaction applies too; the stricter wins.</p>
          <div className="grid gap-3 md:grid-cols-3">
            {CLASSIFICATIONS.map((c) => (
              <FormField key={c} label={c} error={c === 'sensitive' ? err('sensitive') : undefined}>
                {(ids) => (
                  <Select id={ids.input} aria-describedby={ids.describedBy} value={form.classificationActions[c]} onChange={(e) => set({ classificationActions: { ...form.classificationActions, [c]: e.target.value as Action } })}>
                    {(Object.keys(ACTION_LABEL) as Action[]).map((a) => <option key={a} value={a}>{ACTION_LABEL[a]}</option>)}
                  </Select>
                )}
              </FormField>
            ))}
          </div>
        </fieldset>
      </fieldset>
      {save.error ? (
        <div className="space-y-2">
          <AiProblem error={save.error} action="change the AI policy" listFieldErrors={false} />
          {conflict ? <Button variant="secondary" onClick={onReload}>Reload the policy</Button> : null}
        </div>
      ) : null}
      {saved ? <Alert tone="success">AI policy saved.</Alert> : null}
      {editable ? <Button variant="primary" onClick={submit} loading={save.pending}>Save policy</Button> : null}

      <section aria-labelledby="endpoints-heading" className="space-y-2">
        <h2 id="endpoints-heading">Model endpoints of this deployment</h2>
        {policy.endpoints.length === 0 ? <p className="text-sm text-muted-foreground">No model endpoint is configured.</p> : (
          <div className="overflow-x-auto rounded-card border border-border bg-surface">
            <Table>
              <caption className="sr-only">Model endpoints and their residency tags</caption>
              <THead><TR><TH>Endpoint</TH><TH>Kind</TH><TH>Residency</TH><TH>Allowed by the saved policy</TH></TR></THead>
              <TBody>
                {policy.endpoints.map((e) => {
                  const allowed = policy.residency.length === 0 || e.residency.some((t) => policy.residency.includes(t))
                  return (
                    <TR key={e.id}>
                      <TD className="font-mono text-xs">{e.id}</TD>
                      <TD>{e.kind}</TD>
                      <TD>{e.residency.length === 0 ? <span className="text-muted-foreground">none</span> : <span className="flex flex-wrap gap-1">{e.residency.map((t) => <Badge key={t}>{t}</Badge>)}</span>}</TD>
                      <TD><Badge tone={allowed ? 'success' : 'warning'}>{allowed ? 'Yes' : 'No'}</Badge></TD>
                    </TR>
                  )
                })}
              </TBody>
            </Table>
          </div>
        )}
      </section>
    </div>
  )
}
