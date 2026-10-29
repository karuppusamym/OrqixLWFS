import { useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Plus, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { useNavigate } from 'react-router'
import * as endpoints from '@/api/endpoints'
import { isProblemError, type ProblemError } from '@/api/problem'
import { useGroups } from '@/api/queries'
import { useWorkspace } from '@/app/workspace'
import { FormField } from '@/components/common/FormField'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Input, Select, Textarea } from '@/components/ui/input'
import { toKey } from '@/lib/text'
import { MAX_APPROVALS, MAX_QUESTIONS, QUESTION_TYPES, quickGuideDefinition, stepProblem, type QuickAnswers } from './quickGuide'

const STEPS = ['Name it', 'What the requester fills in', 'Who approves', 'Check and create'] as const

/**
 * "Answer a few questions" (deterministic, no AI): name, questions for the requester, approvers. The result is an
 * ordinary draft that opens in the studio, where deadlines, emails, rules and integrations can be added.
 */
export function QuickGuideDialog({ onClose, onBack }: { onClose: () => void; onBack: () => void }) {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const groups = useGroups(ws)
  const navigate = useNavigate()
  const client = useQueryClient()
  const [step, setStep] = useState(1)
  const [problem, setProblem] = useState<string | null>(null)
  const [error, setError] = useState<ProblemError | null>(null)
  const [pending, setPending] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  // The workflow, once created: if saving its draft fails, "Create draft" retries only the save (no second workflow).
  const [created, setCreated] = useState<{ id: string; key: string } | null>(null)
  const [a, setA] = useState<QuickAnswers>({
    name: '',
    purpose: '',
    questions: [{ label: 'Request title', type: 'text', required: true }],
    approvals: [],
  })
  const patch = (p: Partial<QuickAnswers>) => setA((old) => ({ ...old, ...p }))
  const groupName = (key: string) => groups.data?.items.find((g) => g.key === key)?.name ?? key
  const numberQuestions = a.questions.map((q, i) => ({ q, i })).filter(({ q }) => q.type === 'number' && q.required && q.label.trim())

  const next = () => {
    const found = stepProblem(step, a)
    setProblem(found)
    if (!found) { setNotice(null); setStep((s) => s + 1) }
  }
  const back = () => {
    setProblem(null)
    setError(null)
    setNotice(null)
    if (step === 1) onBack()
    else setStep((s) => s - 1)
  }

  const create = async () => {
    setPending(true)
    setError(null)
    setProblem(null)
    try {
      const key = created?.key ?? (toKey(a.name) || 'workflow')
      const definition = quickGuideDefinition(a, key)
      let target = created
      if (!target) {
        const made = await endpoints.createWorkflow(ws, { key, name: a.name.trim(), ...(a.purpose.trim() ? { description: a.purpose.trim() } : {}) })
        target = { id: made.id, key }
        setCreated(target)
      }
      await endpoints.saveDraft(ws, target.id, definition, 1)
      await client.invalidateQueries({ queryKey: ['ws', ws, 'workflows'] })
      navigate(`/w/${ws}/workflows/${target.id}/edit`)
      onClose()
    } catch (cause) {
      if (cause instanceof Error && !isProblemError(cause)) {
        // The answers themselves are incomplete (checked again before saving).
        setProblem(cause.message)
      } else {
        const p = toProblem(cause)
        if (p.code === 'DUPLICATE_KEY') {
          setStep(1)
          setProblem('A workflow with this name already exists. Choose a different name.')
        } else setError(p)
      }
    } finally {
      setPending(false)
    }
  }

  return (
    <Dialog
      open
      onClose={onClose}
      size="lg"
      title="Answer a few questions"
      description={`Step ${step} of ${STEPS.length}: ${STEPS[step - 1]}. No AI is used; you can change everything afterwards.`}
      dismissible={!pending}
      footer={
        <>
          <Button variant="ghost" onClick={back} disabled={pending || created !== null} className="mr-auto">
            <ArrowLeft aria-hidden="true" /> {step === 1 ? 'Other ways to start' : 'Back'}
          </Button>
          <Button variant="secondary" onClick={onClose} disabled={pending}>Cancel</Button>
          {step < STEPS.length ? (
            <Button variant="primary" onClick={next}>Next</Button>
          ) : (
            <Button variant="primary" onClick={() => void create()} loading={pending}>Create draft</Button>
          )}
        </>
      }
    >
      <div className="space-y-4" data-testid="quick-guide">
        {step === 1 ? (
          <>
            <FormField label="What is the workflow called?" required id="quick-name" description="For example: Laptop request, Vendor onboarding, Expense approval.">
              {(ids) => <Input id={ids.input} value={a.name} onChange={(e) => patch({ name: e.target.value })} maxLength={200} autoComplete="off" aria-describedby={ids.describedBy} />}
            </FormField>
            <FormField label="What is it for?" optionalText id="quick-purpose" description="One or two sentences people will see in the workflow list.">
              {(ids) => <Textarea id={ids.input} value={a.purpose} onChange={(e) => patch({ purpose: e.target.value })} maxLength={2000} rows={2} aria-describedby={ids.describedBy} />}
            </FormField>
          </>
        ) : null}

        {step === 2 ? (
          <>
            <p className="text-sm text-muted-foreground">These become the questions on the request form, in this order.</p>
            <ol className="space-y-2">
              {a.questions.map((q, i) => (
                <li key={i} className="grid gap-2 rounded-control border border-border p-3 sm:grid-cols-[1fr_10rem_auto_auto] sm:items-end">
                  <FormField label={`Question ${i + 1}`} id={`quick-q-${i}`}>
                    {(ids) => <Input id={ids.input} value={q.label} maxLength={120} placeholder="For example: Amount in USD" onChange={(e) => patch({ questions: a.questions.map((v, j) => (j === i ? { ...v, label: e.target.value } : v)) })} />}
                  </FormField>
                  <FormField label="Answer type" id={`quick-type-${i}`}>
                    {(ids) => (
                      <Select id={ids.input} value={q.type} onChange={(e) => patch({ questions: a.questions.map((v, j) => (j === i ? { ...v, type: e.target.value as typeof q.type } : v)) })}>
                        {QUESTION_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
                      </Select>
                    )}
                  </FormField>
                  <label className="flex h-9 items-center gap-2 text-sm">
                    <input type="checkbox" checked={q.required} onChange={(e) => patch({ questions: a.questions.map((v, j) => (j === i ? { ...v, required: e.target.checked } : v)) })} />
                    Required
                  </label>
                  <Button
                    variant="ghost"
                    size="icon"
                    aria-label={`Remove question ${i + 1}`}
                    disabled={a.questions.length === 1}
                    onClick={() => {
                      // Approvals that depended on the removed question now run always (said below); later indexes shift.
                      const loose = a.approvals.filter((s) => s.whenQuestion === i).map((s) => s.label.trim() || 'An approval')
                      setNotice(loose.length > 0 ? `${loose.join(', ')} depended on "${q.label.trim() || `Question ${i + 1}`}" and now ${loose.length === 1 ? 'runs' : 'run'} for every request. Change that in the next step if needed.` : null)
                      patch({
                        questions: a.questions.filter((_, j) => j !== i),
                        approvals: a.approvals.map((s) => s.whenQuestion === undefined ? s : s.whenQuestion === i ? { label: s.label, group: s.group } : s.whenQuestion > i ? { ...s, whenQuestion: s.whenQuestion - 1 } : s),
                      })
                    }}
                  >
                    <Trash2 aria-hidden="true" />
                  </Button>
                </li>
              ))}
            </ol>
            <Button variant="secondary" disabled={a.questions.length >= MAX_QUESTIONS} onClick={() => patch({ questions: [...a.questions, { label: '', type: 'text', required: true }] })}>
              <Plus aria-hidden="true" /> Add a question
            </Button>
          </>
        ) : null}

        {step === 3 ? (
          <>
            <p className="text-sm text-muted-foreground">
              Approvals happen in this order. Approving moves the request on; rejecting ends it. Leave this empty if the request only needs to be recorded.
            </p>
            {groups.isError ? <p role="alert" className="text-sm text-danger">Approver groups could not be loaded. Try again, or add approvals later in the studio.</p> : null}
            {groups.data && groups.data.items.length === 0 ? (
              <p className="text-sm">This workspace has no approver groups yet. Create the draft now and add approvals in the studio once groups exist.</p>
            ) : null}
            <ol className="space-y-2">
              {a.approvals.map((s, i) => (
                <li key={i} className="space-y-2 rounded-control border border-border p-3">
                  <div className="grid gap-2 sm:grid-cols-[1fr_1fr_auto] sm:items-end">
                    <FormField label={`Approval ${i + 1}`} id={`quick-a-${i}`}>
                      {(ids) => <Input id={ids.input} value={s.label} maxLength={80} placeholder="For example: Manager approval" onChange={(e) => patch({ approvals: a.approvals.map((v, j) => (j === i ? { ...v, label: e.target.value } : v)) })} />}
                    </FormField>
                    <FormField label="Who approves?" id={`quick-g-${i}`}>
                      {(ids) => (
                        <Select id={ids.input} value={s.group} onChange={(e) => patch({ approvals: a.approvals.map((v, j) => (j === i ? { ...v, group: e.target.value } : v)) })}>
                          <option value="">Choose a group</option>
                          {groups.data?.items.map((g) => <option key={g.key} value={g.key}>{g.name}</option>)}
                        </Select>
                      )}
                    </FormField>
                    <Button variant="ghost" size="icon" aria-label={`Remove approval ${i + 1}`} onClick={() => patch({ approvals: a.approvals.filter((_, j) => j !== i) })}>
                      <Trash2 aria-hidden="true" />
                    </Button>
                  </div>
                  {numberQuestions.length > 0 ? (
                    <div className="grid gap-2 sm:grid-cols-[1fr_10rem]">
                      <FormField label="Needed" id={`quick-w-${i}`}>
                        {(ids) => (
                          <Select
                            id={ids.input}
                            value={s.whenQuestion === undefined ? '' : String(s.whenQuestion)}
                            onChange={(e) => patch({ approvals: a.approvals.map((v, j) => (j === i ? (e.target.value === '' ? { label: v.label, group: v.group } : { ...v, whenQuestion: Number(e.target.value), atLeast: v.atLeast ?? 0 }) : v)) })}
                          >
                            <option value="">Always</option>
                            {numberQuestions.map(({ q, i: qi }) => <option key={qi} value={qi}>Only when {q.label.trim()} is at least…</option>)}
                          </Select>
                        )}
                      </FormField>
                      {s.whenQuestion !== undefined ? (
                        <FormField label="At least" id={`quick-n-${i}`}>
                          {(ids) => <Input id={ids.input} type="number" value={Number.isFinite(s.atLeast) ? s.atLeast : ''} onChange={(e) => patch({ approvals: a.approvals.map((v, j) => (j === i ? { ...v, atLeast: e.target.value === '' ? Number.NaN : Number(e.target.value) } : v)) })} />}
                        </FormField>
                      ) : null}
                    </div>
                  ) : null}
                </li>
              ))}
            </ol>
            <Button variant="secondary" disabled={a.approvals.length >= MAX_APPROVALS || !groups.data?.items.length} onClick={() => patch({ approvals: [...a.approvals, { label: '', group: '' }] })}>
              <Plus aria-hidden="true" /> Add an approval
            </Button>
          </>
        ) : null}

        {step === 4 ? (
          <div className="space-y-3 text-sm" data-testid="quick-summary">
            <p><strong>{a.name.trim()}</strong>{a.purpose.trim() ? `: ${a.purpose.trim()}` : ''}</p>
            <div>
              <p className="font-semibold">The requester fills in</p>
              <ul className="list-disc pl-5">
                {a.questions.map((q, i) => <li key={i}>{q.label.trim()} ({QUESTION_TYPES.find((t) => t.value === q.type)?.label.toLowerCase()}{q.required ? ', required' : ', optional'})</li>)}
              </ul>
            </div>
            <div>
              <p className="font-semibold">Then</p>
              {a.approvals.length === 0 ? (
                <p>The request is recorded and finishes straight away.</p>
              ) : (
                <ol className="list-decimal pl-5">
                  {a.approvals.map((s, i) => (
                    <li key={i}>
                      {s.label.trim()}: {groupName(s.group)}
                      {s.whenQuestion !== undefined ? ` (only when ${a.questions[s.whenQuestion]?.label.trim()} is at least ${s.atLeast})` : ''}
                    </li>
                  ))}
                </ol>
              )}
            </div>
            <p className="rounded-control border border-info-border bg-info-soft p-3 text-info-foreground">
              This creates a draft only. It opens in the studio, where you can add deadlines, emails, rules and connections. Nobody can start it until you publish.
            </p>
          </div>
        ) : null}

        {notice ? <p role="status" className="text-sm text-warning-foreground" data-testid="quick-notice">{notice}</p> : null}
        {created && !pending ? <p className="text-sm text-muted-foreground">The workflow was created; only its draft still needs saving. Choose Create draft to try again.</p> : null}
        {problem ? <p role="alert" className="text-sm text-danger">{problem}</p> : null}
        {error ? <ProblemAlert error={error} /> : null}
      </div>
    </Dialog>
  )
}
