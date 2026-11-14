import { Plus, Trash2 } from 'lucide-react'
import { useMemo, useState, type ReactNode } from 'react'
import { isProblemError } from '@/api/problem'
import { FormField } from '@/components/common/FormField'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useAiStatus } from '@/features/ai/api'
import { AiProblem } from './AiProblem'
import { MODE_LABEL } from './api'
import {
  CLASSIFICATIONS, checkSkillDocument, documentPointer, documentToForm, emptyForm, formToDocument, MODES, SCALAR_TYPES,
  type Problems, type SkillForm,
} from './skillDocument'

/**
 * Authoring of an AI skill draft: a structured form over contracts/ai-skill-v1.schema.json with a JSON view as the
 * fallback for anything the form does not cover. Both views are checked with the cheap contract rules before saving;
 * the server checks the whole contract and its field errors land on the same fields.
 */
export function SkillEditor({
  initial,
  keyLocked,
  saveLabel,
  onSave,
  onCancel,
}: {
  initial: Record<string, unknown> | null
  /** The key of an existing skill never changes. */
  keyLocked: boolean
  saveLabel: string
  onSave: (document: Record<string, unknown>) => Promise<void>
  onCancel: () => void
}) {
  const [form, setForm] = useState<SkillForm>(() => (initial ? documentToForm(initial) : emptyForm()))
  const [view, setView] = useState<'form' | 'json'>('form')
  const [json, setJson] = useState('')
  const [jsonError, setJsonError] = useState<string | null>(null)
  const [problems, setProblems] = useState<Problems>({})
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<unknown>(null)
  const status = useAiStatus()
  const profiles = status.data?.profiles ?? []

  const set = (patch: Partial<SkillForm>) => setForm((f) => ({ ...f, ...patch }))
  const err = (pointer: string) => problems[pointer]

  const toJson = () => {
    const { document } = formToDocument(form)
    setJson(JSON.stringify(document, null, 2))
    setJsonError(null)
    setView('json')
  }
  const parseJson = (): Record<string, unknown> | null => {
    try {
      const parsed: unknown = JSON.parse(json)
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('not an object')
      setJsonError(null)
      return parsed as Record<string, unknown>
    } catch {
      setJsonError('The JSON does not parse. Fix it, or go back to the form to discard the change.')
      return null
    }
  }
  const toForm = () => {
    const doc = parseJson()
    if (!doc) return
    setForm(documentToForm(doc))
    setProblems(checkSkillDocument(doc))
    setView('form')
  }

  const save = async () => {
    let document: Record<string, unknown>
    let found: Problems
    if (view === 'json') {
      const doc = parseJson()
      if (!doc) return
      document = doc
      found = checkSkillDocument(doc)
    } else {
      ({ document, problems: found } = formToDocument(form))
    }
    setProblems(found)
    setSaveError(null)
    if (Object.keys(found).length > 0) return
    setSaving(true)
    try {
      await onSave(document)
    } catch (cause) {
      setSaveError(cause)
      if (isProblemError(cause) && cause.fieldErrors.length > 0) {
        setProblems(Object.fromEntries(cause.fieldErrors.map((f) => [documentPointer(f.pointer), f.message])))
      }
    } finally {
      setSaving(false)
    }
  }

  const problemList = Object.entries(problems)
  const labelKeys = useMemo(() => form.labels.map((l) => l.key.trim()).filter(Boolean), [form.labels])

  return (
    <section aria-label="Skill editor" className="space-y-6 rounded-card border border-border bg-surface p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div role="group" aria-label="Editor view" className="flex gap-2">
          <Button size="sm" variant={view === 'form' ? 'primary' : 'secondary'} aria-pressed={view === 'form'} onClick={() => (view === 'json' ? toForm() : undefined)}>Form</Button>
          <Button size="sm" variant={view === 'json' ? 'primary' : 'secondary'} aria-pressed={view === 'json'} onClick={() => (view === 'form' ? toJson() : undefined)}>JSON</Button>
        </div>
        <p className="text-xs text-muted-foreground">Contract: ai-skill-v1, kind skill. Request data is always sent to the model as data, never as instructions.</p>
      </div>

      {problemList.length > 0 ? (
        <Alert tone="danger" title="Fix these before saving" data-testid="skill-problems">
          <ul className="list-disc pl-5">
            {problemList.map(([pointer, message]) => <li key={pointer}><span className="font-mono text-xs">{pointer}</span> {message}</li>)}
          </ul>
        </Alert>
      ) : null}

      {view === 'json' ? (
        <FormField label="Skill document (JSON)" error={jsonError ?? undefined} description="Everything the contract allows, including members the form does not show.">
          {(ids) => <Textarea id={ids.input} aria-describedby={ids.describedBy} aria-invalid={jsonError ? true : undefined} className="min-h-96 font-mono text-sm" value={json} onChange={(e) => setJson(e.target.value)} spellCheck={false} />}
        </FormField>
      ) : (
        <>
          <Group title="Skill">
            <div className="grid gap-4 md:grid-cols-2">
              <FormField label="Key" required error={err('/key')} description={keyLocked ? 'The key of a skill never changes.' : 'Lowercase letters, digits and dashes, such as dispute-triage.'}>
                {(ids) => <Input id={ids.input} aria-describedby={ids.describedBy} aria-invalid={err('/key') ? true : undefined} value={form.key} disabled={keyLocked} onChange={(e) => set({ key: e.target.value })} />}
              </FormField>
              <FormField label="Name" required error={err('/name')}>
                {(ids) => <Input id={ids.input} aria-describedby={ids.describedBy} aria-invalid={err('/name') ? true : undefined} value={form.name} maxLength={120} onChange={(e) => set({ name: e.target.value })} />}
              </FormField>
              <FormField label="Mode" required error={err('/mode')} description="What the AI answers: one label, typed fields, a score, a recommended outcome or a summary.">
                {(ids) => (
                  <Select id={ids.input} aria-describedby={ids.describedBy} value={form.mode} onChange={(e) => set({ mode: e.target.value as SkillForm['mode'] })}>
                    {MODES.map((m) => <option key={m} value={m}>{MODE_LABEL[m]}</option>)}
                  </Select>
                )}
              </FormField>
              <FormField label="Model profile" required error={err('/modelProfile')} description="The evaluation gate is per version and profile.">
                {(ids) => (
                  <>
                    <Input id={ids.input} list="ai-skill-profiles" aria-describedby={ids.describedBy} aria-invalid={err('/modelProfile') ? true : undefined} value={form.modelProfile} onChange={(e) => set({ modelProfile: e.target.value })} />
                    <datalist id="ai-skill-profiles">{profiles.map((p) => <option key={p.id} value={p.id}>{p.label}</option>)}</datalist>
                  </>
                )}
              </FormField>
            </div>
            <FormField label="Description" optionalText error={err('/description')}>
              {(ids) => <Input id={ids.input} aria-describedby={ids.describedBy} value={form.description} maxLength={2000} onChange={(e) => set({ description: e.target.value })} />}
            </FormField>
            <FormField label="Instructions" required error={err('/instructions')} description="Fixed instructions for every request; the request values are appended as data.">
              {(ids) => <Textarea id={ids.input} aria-describedby={ids.describedBy} aria-invalid={err('/instructions') ? true : undefined} value={form.instructions} maxLength={20000} onChange={(e) => set({ instructions: e.target.value })} />}
            </FormField>
          </Group>

          <Group title="Inputs" error={err('/input')} description="Typed inputs the step maps request data to. The classification drives minimization through the workspace AI policy.">
            {form.input.map((row, i) => {
              const at = `/input/${row.name.trim() || '(empty)'}`
              const update = (patch: Partial<typeof row>) => set({ input: form.input.map((r, j) => (j === i ? { ...r, ...patch } : r)) })
              return (
                <Row key={i} onRemove={form.input.length > 1 ? () => set({ input: form.input.filter((_, j) => j !== i) }) : undefined} removeLabel={`Remove input ${row.name || i + 1}`}>
                  <FormField label="Input name" error={err(at)} className="min-w-40 flex-1">
                    {(ids) => <Input id={ids.input} aria-describedby={ids.describedBy} aria-invalid={err(at) ? true : undefined} value={row.name} onChange={(e) => update({ name: e.target.value })} />}
                  </FormField>
                  <FormField label="Type" className="w-32">
                    {(ids) => <Select id={ids.input} value={row.type} onChange={(e) => update({ type: e.target.value as typeof row.type })}>{SCALAR_TYPES.map((t) => <option key={t}>{t}</option>)}</Select>}
                  </FormField>
                  <FormField label="Classification" className="w-40">
                    {(ids) => (
                      <Select id={ids.input} value={row.classification} onChange={(e) => update({ classification: e.target.value as typeof row.classification })}>
                        <option value="">Not classified</option>
                        {CLASSIFICATIONS.map((c) => <option key={c}>{c}</option>)}
                      </Select>
                    )}
                  </FormField>
                  <FormField label="Max length" optionalText error={err(`${at}/maxLength`)} className="w-32">
                    {(ids) => <Input id={ids.input} inputMode="numeric" aria-describedby={ids.describedBy} value={row.maxLength} onChange={(e) => update({ maxLength: e.target.value })} />}
                  </FormField>
                  <CheckField id={`input-required-${i}`} label="Required" checked={row.required} onChange={(v) => update({ required: v })} />
                </Row>
              )
            })}
            <AddButton label="Add input" onClick={() => set({ input: [...form.input, { name: '', type: 'string', required: false, maxLength: '', classification: '', description: '' }] })} />
          </Group>

          {form.mode === 'classify' ? (
            <Group title="Labels" error={err('/labels')} description="The labels the AI chooses from. Adverse labels are outcomes with legal or similar effect on a person; in a regulated workspace a person always decides them.">
              {form.labels.map((row, i) => {
                const update = (patch: Partial<typeof row>) => set({ labels: form.labels.map((r, j) => (j === i ? { ...r, ...patch } : r)) })
                return (
                  <Row key={i} onRemove={form.labels.length > 2 ? () => set({ labels: form.labels.filter((_, j) => j !== i) }) : undefined} removeLabel={`Remove label ${row.key || i + 1}`}>
                    <FormField label="Label key" error={err(`/labels/${i}/key`)} className="w-44">
                      {(ids) => <Input id={ids.input} aria-describedby={ids.describedBy} aria-invalid={err(`/labels/${i}/key`) ? true : undefined} value={row.key} onChange={(e) => update({ key: e.target.value })} />}
                    </FormField>
                    <FormField label="Meaning" error={err(`/labels/${i}/description`)} className="min-w-48 flex-1">
                      {(ids) => <Input id={ids.input} aria-describedby={ids.describedBy} aria-invalid={err(`/labels/${i}/description`) ? true : undefined} value={row.description} maxLength={500} onChange={(e) => update({ description: e.target.value })} />}
                    </FormField>
                    <FormField label="Risk" className="w-32">
                      {(ids) => (
                        <Select id={ids.input} value={row.risk} onChange={(e) => update({ risk: e.target.value as typeof row.risk })}>
                          <option value="">Not set</option><option value="low">low</option><option value="medium">medium</option><option value="high">high</option>
                        </Select>
                      )}
                    </FormField>
                    <CheckField id={`label-adverse-${i}`} label="Adverse" checked={row.adverse} onChange={(v) => update({ adverse: v })} />
                  </Row>
                )
              })}
              <AddButton label="Add label" onClick={() => set({ labels: [...form.labels, { key: '', description: '', risk: 'low', adverse: false }] })} />
            </Group>
          ) : null}

          {form.mode === 'extract' ? (
            <Group title="Fields" error={err('/fields')} description="The typed fields to extract.">
              {form.fields.map((row, i) => {
                const update = (patch: Partial<typeof row>) => set({ fields: form.fields.map((r, j) => (j === i ? { ...r, ...patch } : r)) })
                return (
                  <Row key={i} onRemove={form.fields.length > 1 ? () => set({ fields: form.fields.filter((_, j) => j !== i) }) : undefined} removeLabel={`Remove field ${row.name || i + 1}`}>
                    <FormField label="Field name" error={err(`/fields/${i}/name`)} className="w-44">
                      {(ids) => <Input id={ids.input} aria-describedby={ids.describedBy} aria-invalid={err(`/fields/${i}/name`) ? true : undefined} value={row.name} onChange={(e) => update({ name: e.target.value })} />}
                    </FormField>
                    <FormField label="Type" className="w-32">
                      {(ids) => <Select id={ids.input} value={row.type} onChange={(e) => update({ type: e.target.value as typeof row.type })}>{SCALAR_TYPES.map((t) => <option key={t}>{t}</option>)}</Select>}
                    </FormField>
                    <FormField label="Meaning" optionalText className="min-w-48 flex-1">
                      {(ids) => <Input id={ids.input} value={row.description} maxLength={500} onChange={(e) => update({ description: e.target.value })} />}
                    </FormField>
                    <CheckField id={`field-required-${i}`} label="Required" checked={row.required} onChange={(v) => update({ required: v })} />
                  </Row>
                )
              })}
              <AddButton label="Add field" onClick={() => set({ fields: [...form.fields, { name: '', type: 'string', required: false, description: '' }] })} />
            </Group>
          ) : null}

          {form.mode === 'score' ? (
            <Group title="Score range" error={err('/score')}>
              <div className="flex flex-wrap gap-4">
                <FormField label="Lowest score" error={err('/score/min')} className="w-36">
                  {(ids) => <Input id={ids.input} inputMode="decimal" aria-describedby={ids.describedBy} aria-invalid={err('/score/min') ? true : undefined} value={form.score.min} onChange={(e) => set({ score: { ...form.score, min: e.target.value } })} />}
                </FormField>
                <FormField label="Highest score" error={err('/score/max')} className="w-36">
                  {(ids) => <Input id={ids.input} inputMode="decimal" aria-describedby={ids.describedBy} aria-invalid={err('/score/max') ? true : undefined} value={form.score.max} onChange={(e) => set({ score: { ...form.score, max: e.target.value } })} />}
                </FormField>
                <FormField label="What the score means" optionalText className="min-w-48 flex-1">
                  {(ids) => <Input id={ids.input} value={form.score.description} maxLength={500} onChange={(e) => set({ score: { ...form.score, description: e.target.value } })} />}
                </FormField>
              </div>
            </Group>
          ) : null}

          {form.mode === 'recommend' ? (
            <Group title="Outcomes" error={err('/outcomes')} description="The outcomes the AI may suggest to the person who decides.">
              {form.outcomes.map((row, i) => {
                const update = (patch: Partial<typeof row>) => set({ outcomes: form.outcomes.map((r, j) => (j === i ? { ...r, ...patch } : r)) })
                return (
                  <Row key={i} onRemove={form.outcomes.length > 2 ? () => set({ outcomes: form.outcomes.filter((_, j) => j !== i) }) : undefined} removeLabel={`Remove outcome ${row.key || i + 1}`}>
                    <FormField label="Outcome key" error={err(`/outcomes/${i}/key`)} className="w-44">
                      {(ids) => <Input id={ids.input} aria-describedby={ids.describedBy} aria-invalid={err(`/outcomes/${i}/key`) ? true : undefined} value={row.key} onChange={(e) => update({ key: e.target.value })} />}
                    </FormField>
                    <FormField label="Meaning" error={err(`/outcomes/${i}/description`)} className="min-w-48 flex-1">
                      {(ids) => <Input id={ids.input} aria-describedby={ids.describedBy} value={row.description} maxLength={500} onChange={(e) => update({ description: e.target.value })} />}
                    </FormField>
                    <CheckField id={`outcome-adverse-${i}`} label="Adverse" checked={row.adverse} onChange={(v) => update({ adverse: v })} />
                  </Row>
                )
              })}
              <AddButton label="Add outcome" onClick={() => set({ outcomes: [...form.outcomes, { key: '', description: '', adverse: false }] })} />
            </Group>
          ) : null}

          {form.mode === 'summarize' ? (
            <Group title="Summary">
              <FormField label="Longest summary (characters)" optionalText error={err('/summary/maxLength')} className="w-56">
                {(ids) => <Input id={ids.input} inputMode="numeric" aria-describedby={ids.describedBy} value={form.summaryMaxLength} onChange={(e) => set({ summaryMaxLength: e.target.value })} />}
              </FormField>
            </Group>
          ) : null}

          <Group title="Examples" error={err('/examples')} description="Approved examples shown to the model. Synthetic or redacted data only.">
            {form.examples.map((row, i) => {
              const update = (patch: Partial<typeof row>) => set({ examples: form.examples.map((r, j) => (j === i ? { ...r, ...patch } : r)) })
              return (
                <Row key={i} onRemove={() => set({ examples: form.examples.filter((_, j) => j !== i) })} removeLabel={`Remove example ${i + 1}`}>
                  <FormField label={`Example ${i + 1} input (JSON)`} error={err(`/examples/${i}/input`)} className="min-w-48 flex-1">
                    {(ids) => <Textarea id={ids.input} aria-describedby={ids.describedBy} className="font-mono text-sm" value={row.input} onChange={(e) => update({ input: e.target.value })} />}
                  </FormField>
                  <FormField label={`Example ${i + 1} answer (JSON)`} error={err(`/examples/${i}/output`)} className="min-w-48 flex-1">
                    {(ids) => <Textarea id={ids.input} aria-describedby={ids.describedBy} className="font-mono text-sm" value={row.output} onChange={(e) => update({ output: e.target.value })} />}
                  </FormField>
                </Row>
              )
            })}
            <AddButton label="Add example" onClick={() => set({ examples: [...form.examples, { input: '{}', output: '{}' }] })} />
          </Group>

          <Group title="Confidence">
            <div className="flex flex-wrap gap-4">
              <FormField label="Method" className="w-56">
                {(ids) => (
                  <Select id={ids.input} value={form.confidence.method} onChange={(e) => set({ confidence: { ...form.confidence, method: e.target.value as SkillForm['confidence']['method'] } })}>
                    <option value="single">One answer</option>
                    <option value="selfConsistency">Self-consistency (several samples)</option>
                  </Select>
                )}
              </FormField>
              <NumberField label="Samples (1 to 5)" pointer="/confidence/samples" err={err} value={form.confidence.samples} onChange={(v) => set({ confidence: { ...form.confidence, samples: v } })} />
            </div>
          </Group>

          <Group title="Evaluation targets" description="A version may decide outside shadow mode only after a passing evaluation run on its profile within 30 days.">
            <div className="flex flex-wrap gap-4">
              <FormField label="Evaluation suite" optionalText error={err('/evaluation/suite')} className="w-56">
                {(ids) => <Input id={ids.input} aria-describedby={ids.describedBy} value={form.evaluation.suite} onChange={(e) => set({ evaluation: { ...form.evaluation, suite: e.target.value } })} />}
              </FormField>
              <NumberField label="Minimum accuracy (0 to 1, default 0.9)" pointer="/evaluation/minAccuracy" err={err} value={form.evaluation.minAccuracy} onChange={(v) => set({ evaluation: { ...form.evaluation, minAccuracy: v } })} />
              <NumberField label="Minimum cases (default 50)" pointer="/evaluation/minCases" err={err} value={form.evaluation.minCases} onChange={(v) => set({ evaluation: { ...form.evaluation, minCases: v } })} />
            </div>
            {form.mode === 'classify' ? (
              <>
                {form.evaluation.minRecall.map((row, i) => {
                  const update = (patch: Partial<typeof row>) => set({ evaluation: { ...form.evaluation, minRecall: form.evaluation.minRecall.map((r, j) => (j === i ? { ...r, ...patch } : r)) } })
                  const at = `/evaluation/minRecall/${row.label.trim()}`
                  return (
                    <Row key={i} onRemove={() => set({ evaluation: { ...form.evaluation, minRecall: form.evaluation.minRecall.filter((_, j) => j !== i) } })} removeLabel={`Remove recall target ${row.label || i + 1}`}>
                      <FormField label="Label" error={err(at)} className="w-44">
                        {(ids) => (
                          <Select id={ids.input} aria-describedby={ids.describedBy} aria-invalid={err(at) ? true : undefined} value={row.label} onChange={(e) => update({ label: e.target.value })}>
                            <option value="">Choose a label</option>
                            {labelKeys.map((k) => <option key={k}>{k}</option>)}
                          </Select>
                        )}
                      </FormField>
                      <FormField label="Minimum recall (0 to 1)" className="w-44">
                        {(ids) => <Input id={ids.input} inputMode="decimal" value={row.min} onChange={(e) => update({ min: e.target.value })} />}
                      </FormField>
                    </Row>
                  )
                })}
                <AddButton label="Add recall target" onClick={() => set({ evaluation: { ...form.evaluation, minRecall: [...form.evaluation.minRecall, { label: '', min: '0.9' }] } })} />
              </>
            ) : null}
          </Group>

          <Group title="Limits">
            <div className="flex flex-wrap gap-4">
              <NumberField label="Input tokens" pointer="/limits/maxInputTokens" err={err} value={form.limits.maxInputTokens} onChange={(v) => set({ limits: { ...form.limits, maxInputTokens: v } })} />
              <NumberField label="Output tokens" pointer="/limits/maxOutputTokens" err={err} value={form.limits.maxOutputTokens} onChange={(v) => set({ limits: { ...form.limits, maxOutputTokens: v } })} />
              <NumberField label="Cost per task (USD, default 0.05)" pointer="/limits/maxCostUsd" err={err} value={form.limits.maxCostUsd} onChange={(v) => set({ limits: { ...form.limits, maxCostUsd: v } })} />
              <NumberField label="Timeout (seconds, at most 600)" pointer="/limits/timeoutSeconds" err={err} value={form.limits.timeoutSeconds} onChange={(v) => set({ limits: { ...form.limits, timeoutSeconds: v } })} />
            </div>
          </Group>

          <Group title="Promotion and drift" description="Raising autonomy needs enough compared shadow cases at the target agreement. A drop in agreement beyond the limit makes the version suggest again.">
            <div className="flex flex-wrap gap-4">
              <NumberField label="Compared cases before promotion (default 200)" pointer="/promotion/minShadowCases" err={err} value={form.promotion.minShadowCases} onChange={(v) => set({ promotion: { ...form.promotion, minShadowCases: v } })} />
              <NumberField label="Target agreement (0 to 1, default 0.9)" pointer="/promotion/targetAgreement" err={err} value={form.promotion.targetAgreement} onChange={(v) => set({ promotion: { ...form.promotion, targetAgreement: v } })} />
              <NumberField label="Largest agreement drop (default 0.1)" pointer="/drift/maxAgreementDrop" err={err} value={form.drift.maxAgreementDrop} onChange={(v) => set({ drift: { ...form.drift, maxAgreementDrop: v } })} />
            </div>
            <CheckField id="drift-fallback" label="Fall back to suggest when agreement drifts" checked={form.drift.fallbackToSuggest} onChange={(v) => set({ drift: { ...form.drift, fallbackToSuggest: v } })} />
          </Group>
        </>
      )}

      {saveError ? <AiProblem error={saveError} action="save this skill" listFieldErrors={false} /> : null}
      <div className="flex flex-wrap gap-2">
        <Button variant="primary" onClick={() => void save()} loading={saving}>{saveLabel}</Button>
        <Button variant="secondary" onClick={onCancel} disabled={saving}>Cancel</Button>
      </div>
    </section>
  )
}

function Group({ title, description, error, children }: { title: string; description?: string; error?: string | undefined; children: ReactNode }) {
  return (
    <fieldset className="space-y-3">
      <legend className="text-base font-semibold">{title}</legend>
      {description ? <p className="text-sm text-muted-foreground">{description}</p> : null}
      {error ? <p role="alert" className="text-sm font-medium text-danger">{error}</p> : null}
      {children}
    </fieldset>
  )
}

function Row({ children, onRemove, removeLabel }: { children: ReactNode; onRemove?: (() => void) | undefined; removeLabel: string }) {
  return (
    <div className="flex flex-wrap items-end gap-3 rounded-control border border-border p-3">
      {children}
      {onRemove ? <Button size="icon" variant="ghost" aria-label={removeLabel} onClick={onRemove}><Trash2 aria-hidden="true" /></Button> : null}
    </div>
  )
}

function AddButton({ label, onClick }: { label: string; onClick: () => void }) {
  return <Button size="sm" variant="secondary" onClick={onClick}><Plus aria-hidden="true" /> {label}</Button>
}

function CheckField({ id, label, checked, onChange }: { id: string; label: string; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <div className="flex min-h-10 items-center gap-2">
      <Checkbox id={id} checked={checked} onCheckedChange={(v) => onChange(v === true)} />
      <Label htmlFor={id}>{label}</Label>
    </div>
  )
}

function NumberField({ label, pointer, err, value, onChange }: { label: string; pointer: string; err: (p: string) => string | undefined; value: string; onChange: (v: string) => void }) {
  return (
    <FormField label={label} optionalText error={err(pointer)} className="w-56">
      {(ids) => <Input id={ids.input} inputMode="decimal" aria-describedby={ids.describedBy} aria-invalid={err(pointer) ? true : undefined} value={value} onChange={(e) => onChange(e.target.value)} />}
    </FormField>
  )
}
