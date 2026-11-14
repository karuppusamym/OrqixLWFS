import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { useWorkspace } from '@/app/workspace'
import { FormField } from '@/components/common/FormField'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { formatDateTime } from '@/lib/dates'
import { AiProblem } from './AiProblem'
import { aiSkillKeys, getAiEvalSuite, getAiSkill, saveAiEvalSuite, useAiEvalSuites, type AiSkill } from './api'
import { KEY_PATTERN } from './skillDocument'

type CaseSource = 'synthetic' | 'redactedProduction' | 'override'
interface CaseRow { id: string; input: string; label: string; outcome: string; score: string; tolerance: string; fields: string; mustMention: string; source: CaseSource }
interface SuiteForm { key: string; name: string; skillKey: string; cases: CaseRow[] }

const CASE_ID = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/
const blankCase = (n: number): CaseRow => ({ id: `case-${n}`, input: '{}', label: '', outcome: '', score: '', tolerance: '', fields: '{}', mustMention: '', source: 'synthetic' })

function suiteToForm(doc: Record<string, unknown>): SuiteForm {
  const cases = Array.isArray(doc.cases) ? doc.cases as Array<Record<string, unknown>> : []
  return {
    key: String(doc.key ?? ''), name: String(doc.name ?? ''), skillKey: String(doc.skillKey ?? ''),
    cases: cases.map((c) => {
      const e = (c.expected ?? {}) as Record<string, unknown>
      return {
        id: String(c.id ?? ''), input: JSON.stringify(c.input ?? {}, null, 2), label: String(e.label ?? ''), outcome: String(e.outcome ?? ''),
        score: e.score === undefined ? '' : String(e.score), tolerance: e.tolerance === undefined ? '' : String(e.tolerance),
        fields: JSON.stringify(e.fields ?? {}, null, 2), mustMention: Array.isArray(e.mustMention) ? (e.mustMention as string[]).join('\n') : '',
        source: (c.source as CaseSource | undefined) ?? 'synthetic',
      }
    }),
  }
}

/** The suite form as a document plus problems by pointer; checks mirror AiSkillContracts.checkSuite/checkSuiteFor. */
export function suiteToDocument(form: SuiteForm, skill: Record<string, unknown> | null): { document: Record<string, unknown>; problems: Record<string, string> } {
  const p: Record<string, string> = {}
  if (!KEY_PATTERN.test(form.key)) p['/key'] = 'Use lowercase letters, digits and dashes, such as dispute-triage-suite.'
  if (!form.name.trim()) p['/name'] = 'Give the suite a name.'
  if (!KEY_PATTERN.test(form.skillKey)) p['/skillKey'] = 'Choose the skill the suite evaluates.'
  if (form.cases.length === 0) p['/cases'] = 'Add at least one case.'
  const mode = String(skill?.mode ?? '')
  const inputs = new Set(Object.keys((skill?.input ?? {}) as object))
  const labels = new Set(((skill?.labels ?? []) as Array<{ key: string }>).map((l) => l.key))
  const outcomes = new Set(((skill?.outcomes ?? []) as Array<{ key: string }>).map((o) => o.key))
  const ids = new Set<string>()
  const cases = form.cases.map((c, i) => {
    const at = `/cases/${i}`
    if (!CASE_ID.test(c.id)) p[`${at}/id`] = 'Case ids use letters, digits, dot, dash and underscore.'
    else if (ids.has(c.id)) p[`${at}/id`] = `The case id ${c.id} is used more than once.`
    ids.add(c.id)
    let input: unknown = {}
    try {
      input = JSON.parse(c.input || '{}')
      if (!input || typeof input !== 'object' || Array.isArray(input)) throw new Error('object')
      if (skill) for (const name of Object.keys(input)) if (!inputs.has(name)) p[`${at}/input`] = `The skill has no input ${name}.`
    } catch {
      p[`${at}/input`] = 'Write the case input as a JSON object.'
    }
    const expected: Record<string, unknown> = {}
    if (mode === 'classify') {
      expected.label = c.label
      if (!labels.has(c.label)) p[`${at}/expected/label`] = 'Choose a label of the skill.'
    } else if (mode === 'recommend') {
      expected.outcome = c.outcome
      if (!outcomes.has(c.outcome)) p[`${at}/expected/outcome`] = 'Choose an outcome of the skill.'
    } else if (mode === 'score') {
      const score = Number(c.score)
      if (c.score.trim() === '' || Number.isNaN(score)) p[`${at}/expected/score`] = 'Enter the expected score.'
      expected.score = score
      if (c.tolerance.trim()) {
        const tolerance = Number(c.tolerance)
        if (Number.isNaN(tolerance) || tolerance < 0) p[`${at}/expected/tolerance`] = 'The tolerance is a number of at least 0.'
        expected.tolerance = tolerance
      }
    } else if (mode === 'extract') {
      try {
        const fields: unknown = JSON.parse(c.fields || '{}')
        if (!fields || typeof fields !== 'object' || Array.isArray(fields)) throw new Error('object')
        expected.fields = fields
      } catch {
        p[`${at}/expected/fields`] = 'Write the expected fields as a JSON object.'
      }
    } else if (mode === 'summarize') {
      const phrases = c.mustMention.split('\n').map((s) => s.trim()).filter(Boolean)
      if (phrases.length > 10) p[`${at}/expected/mustMention`] = 'List at most 10 phrases.'
      expected.mustMention = phrases
    }
    return { id: c.id, input, expected, source: c.source }
  })
  return { document: { schemaVersion: '1.0', kind: 'evaluationSuite', key: form.key, name: form.name.trim(), skillKey: form.skillKey, cases }, problems: p }
}

/**
 * Evaluation suites: labelled cases (synthetic, or production data redacted on purpose; nothing copies production data
 * in) that evaluation runs put through the same inference path as production. Designers (workflow.edit) edit them.
 */
export function EvalSuitesPanel({ skills }: { skills: AiSkill[] }) {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const suites = useAiEvalSuites(ws)
  const [editing, setEditing] = useState<string | 'new' | null>(null)
  const canEdit = can('workflow.edit')

  return (
    <section aria-label="Evaluation suites" className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-muted-foreground">Cases are synthetic or deliberately redacted. Overrides that reviewers accept are added here too.</p>
        {canEdit && editing === null ? <Button size="sm" variant="primary" onClick={() => setEditing('new')}><Plus aria-hidden="true" /> New suite</Button> : null}
      </div>
      {suites.isPending ? <LoadingBlock rows={2} label="Loading evaluation suites" />
        : suites.isError ? <QueryError error={suites.error} onRetry={() => void suites.refetch()} what="the evaluation suites" />
          : suites.data.items.length === 0 ? <p className="text-sm text-muted-foreground">No evaluation suite yet.</p>
            : (
              <div className="overflow-x-auto rounded-card border border-border bg-surface">
                <Table>
                  <caption className="sr-only">Evaluation suites</caption>
                  <THead><TR><TH>Suite</TH><TH>Skill</TH><TH>Cases</TH><TH>Revision</TH><TH><span className="sr-only">Actions</span></TH></TR></THead>
                  <TBody>
                    {suites.data.items.map((s) => (
                      <TR key={s.key}>
                        <TD><span className="font-semibold">{s.name}</span><span className="block font-mono text-xs text-muted-foreground">{s.key}</span></TD>
                        <TD className="font-mono text-xs">{s.skillKey}</TD>
                        <TD>{s.caseCount}</TD>
                        <TD>{s.revision}, {formatDateTime(s.updatedAt)}</TD>
                        <TD><Button size="sm" variant="secondary" onClick={() => setEditing(s.key)} aria-label={`${canEdit ? 'Edit' : 'Open'} suite ${s.name}`}>{canEdit ? 'Edit cases' : 'Open'}</Button></TD>
                      </TR>
                    ))}
                  </TBody>
                </Table>
              </div>
            )}
      {editing ? <SuiteEditor key={editing} ws={ws} suiteKey={editing === 'new' ? null : editing} skills={skills} readOnly={!canEdit} onClose={() => setEditing(null)} /> : null}
    </section>
  )
}

function SuiteEditor({ ws, suiteKey, skills, readOnly, onClose }: { ws: string; suiteKey: string | null; skills: AiSkill[]; readOnly: boolean; onClose: () => void }) {
  const loaded = useQuery({ queryKey: aiSkillKeys.suite(ws, suiteKey ?? ''), queryFn: () => getAiEvalSuite(ws, suiteKey!), enabled: suiteKey !== null })
  if (suiteKey && loaded.isPending) return <LoadingBlock rows={3} label="Loading the suite" />
  if (suiteKey && loaded.isError) return <QueryError error={loaded.error} onRetry={() => void loaded.refetch()} what="the suite" />
  const initial = suiteKey && loaded.data?.document ? suiteToForm(loaded.data.document) : { key: '', name: '', skillKey: skills[0]?.key ?? '', cases: [blankCase(1)] }
  return <SuiteFormEditor ws={ws} initial={initial} existing={suiteKey !== null} skills={skills} readOnly={readOnly} onClose={onClose} />
}

function SuiteFormEditor({ ws, initial, existing, skills, readOnly, onClose }: { ws: string; initial: SuiteForm; existing: boolean; skills: AiSkill[]; readOnly: boolean; onClose: () => void }) {
  const [form, setForm] = useState<SuiteForm>(initial)
  const [problems, setProblems] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [saved, setSaved] = useState(false)
  const client = useQueryClient()
  const skill = useQuery({ queryKey: aiSkillKeys.one(ws, form.skillKey), queryFn: () => getAiSkill(ws, form.skillKey), enabled: KEY_PATTERN.test(form.skillKey) })
  const versions = skill.data?.versions ?? []
  const reference = (versions.find((v) => v.version === skill.data?.approvedVersion) ?? [...versions].sort((a, b) => b.version - a.version)[0])?.document ?? null
  const mode = String(reference?.mode ?? '')
  const labels = ((reference?.labels ?? []) as Array<{ key: string }>).map((l) => l.key)
  const outcomes = ((reference?.outcomes ?? []) as Array<{ key: string }>).map((o) => o.key)
  const setCase = (i: number, patch: Partial<CaseRow>) => setForm((f) => ({ ...f, cases: f.cases.map((c, j) => (j === i ? { ...c, ...patch } : c)) }))
  const err = (pointer: string) => problems[pointer]

  const save = async () => {
    const { document, problems: found } = suiteToDocument(form, reference)
    setProblems(found)
    setSaved(false)
    setError(null)
    if (Object.keys(found).length > 0) return
    setSaving(true)
    try {
      await saveAiEvalSuite(ws, form.key, document)
      await client.invalidateQueries({ queryKey: aiSkillKeys.suites(ws) })
      setSaved(true)
    } catch (cause) {
      setError(cause)
    } finally {
      setSaving(false)
    }
  }

  return (
    <section aria-label={`Suite ${form.name || 'new'}`} className="space-y-4 rounded-card border border-border bg-surface p-4">
      <fieldset disabled={readOnly} className="space-y-4">
        <div className="grid gap-4 md:grid-cols-3">
          <FormField label="Suite key" required error={err('/key')}>
            {(ids) => <Input id={ids.input} aria-describedby={ids.describedBy} aria-invalid={err('/key') ? true : undefined} value={form.key} disabled={existing} onChange={(e) => setForm({ ...form, key: e.target.value })} />}
          </FormField>
          <FormField label="Suite name" required error={err('/name')}>
            {(ids) => <Input id={ids.input} aria-describedby={ids.describedBy} value={form.name} maxLength={120} onChange={(e) => setForm({ ...form, name: e.target.value })} />}
          </FormField>
          <FormField label="Skill" required error={err('/skillKey')}>
            {(ids) => (
              <Select id={ids.input} aria-describedby={ids.describedBy} value={form.skillKey} disabled={existing} onChange={(e) => setForm({ ...form, skillKey: e.target.value })}>
                {!skills.some((s) => s.key === form.skillKey) ? <option value={form.skillKey}>{form.skillKey || 'Choose a skill'}</option> : null}
                {skills.map((s) => <option key={s.key} value={s.key}>{s.name}</option>)}
              </Select>
            )}
          </FormField>
        </div>
        {err('/cases') ? <p role="alert" className="text-sm font-medium text-danger">{err('/cases')}</p> : null}
        <ol className="space-y-3">
          {form.cases.map((c, i) => {
            const at = `/cases/${i}`
            return (
              <li key={i} className="flex flex-wrap items-end gap-3 rounded-control border border-border p-3" aria-label={`Case ${c.id || i + 1}`}>
                <FormField label="Case id" error={err(`${at}/id`)} className="w-40">
                  {(ids) => <Input id={ids.input} aria-describedby={ids.describedBy} aria-invalid={err(`${at}/id`) ? true : undefined} value={c.id} onChange={(e) => setCase(i, { id: e.target.value })} />}
                </FormField>
                <FormField label="Input (JSON)" error={err(`${at}/input`)} className="min-w-56 flex-1">
                  {(ids) => <Textarea id={ids.input} aria-describedby={ids.describedBy} className="min-h-16 font-mono text-sm" value={c.input} onChange={(e) => setCase(i, { input: e.target.value })} />}
                </FormField>
                {mode === 'classify' ? (
                  <FormField label="Expected label" error={err(`${at}/expected/label`)} className="w-44">
                    {(ids) => <Select id={ids.input} aria-describedby={ids.describedBy} aria-invalid={err(`${at}/expected/label`) ? true : undefined} value={c.label} onChange={(e) => setCase(i, { label: e.target.value })}><option value="">Choose</option>{labels.map((l) => <option key={l}>{l}</option>)}</Select>}
                  </FormField>
                ) : mode === 'recommend' ? (
                  <FormField label="Expected outcome" error={err(`${at}/expected/outcome`)} className="w-44">
                    {(ids) => <Select id={ids.input} aria-describedby={ids.describedBy} value={c.outcome} onChange={(e) => setCase(i, { outcome: e.target.value })}><option value="">Choose</option>{outcomes.map((o) => <option key={o}>{o}</option>)}</Select>}
                  </FormField>
                ) : mode === 'score' ? (
                  <>
                    <FormField label="Expected score" error={err(`${at}/expected/score`)} className="w-32">
                      {(ids) => <Input id={ids.input} inputMode="decimal" aria-describedby={ids.describedBy} value={c.score} onChange={(e) => setCase(i, { score: e.target.value })} />}
                    </FormField>
                    <FormField label="Tolerance" optionalText error={err(`${at}/expected/tolerance`)} className="w-32">
                      {(ids) => <Input id={ids.input} inputMode="decimal" aria-describedby={ids.describedBy} value={c.tolerance} onChange={(e) => setCase(i, { tolerance: e.target.value })} />}
                    </FormField>
                  </>
                ) : mode === 'extract' ? (
                  <FormField label="Expected fields (JSON)" error={err(`${at}/expected/fields`)} className="min-w-56 flex-1">
                    {(ids) => <Textarea id={ids.input} aria-describedby={ids.describedBy} className="min-h-16 font-mono text-sm" value={c.fields} onChange={(e) => setCase(i, { fields: e.target.value })} />}
                  </FormField>
                ) : mode === 'summarize' ? (
                  <FormField label="Must mention (one phrase per line)" error={err(`${at}/expected/mustMention`)} className="min-w-56 flex-1">
                    {(ids) => <Textarea id={ids.input} aria-describedby={ids.describedBy} className="min-h-16" value={c.mustMention} onChange={(e) => setCase(i, { mustMention: e.target.value })} />}
                  </FormField>
                ) : <p className="text-sm text-muted-foreground">Choose a skill to set the expected answer.</p>}
                <FormField label="Source" className="w-48">
                  {(ids) => (
                    <Select id={ids.input} value={c.source} onChange={(e) => setCase(i, { source: e.target.value as CaseSource })}>
                      <option value="synthetic">Synthetic</option>
                      <option value="redactedProduction">Redacted production</option>
                      <option value="override">Accepted override</option>
                    </Select>
                  )}
                </FormField>
                {!readOnly ? <Button size="icon" variant="ghost" aria-label={`Remove case ${c.id || i + 1}`} onClick={() => setForm((f) => ({ ...f, cases: f.cases.filter((_, j) => j !== i) }))}><Trash2 aria-hidden="true" /></Button> : null}
              </li>
            )
          })}
        </ol>
        {!readOnly ? <Button size="sm" variant="secondary" onClick={() => setForm((f) => ({ ...f, cases: [...f.cases, blankCase(f.cases.length + 1)] }))}><Plus aria-hidden="true" /> Add case</Button> : null}
      </fieldset>
      {Object.keys(problems).length > 0 ? <Alert tone="danger" title="Fix the marked cases before saving" data-testid="suite-problems"><p>{Object.keys(problems).length} problem(s) found.</p></Alert> : null}
      {error ? <AiProblem error={error} action="save this suite" /> : null}
      {saved ? <Alert tone="success">Suite saved. Evaluation runs started from now on use these cases.</Alert> : null}
      <div className="flex flex-wrap gap-2">
        {!readOnly ? <Button variant="primary" onClick={() => void save()} loading={saving}>Save suite</Button> : null}
        <Button variant="secondary" onClick={onClose} disabled={saving}>Close</Button>
      </div>
    </section>
  )
}
