import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { useWorkspace } from '@/app/workspace'
import { ConfirmDialog } from '@/components/common/ConfirmDialog'
import { FormField } from '@/components/common/FormField'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { formatDateTime } from '@/lib/dates'
import { AiProblem, useAction } from './AiProblem'
import { aiSkillKeys, listAiEvalCandidates, listAiReviewQueue, percent, reviewAiEvalCandidate, shortId, useAiEvalSuites, type AiEvalCandidate } from './api'

/**
 * AI review in the Operate area: automatic AI decisions sampled for review that nobody reviewed yet (ai.evaluate or
 * run.operate; the review itself is the accept or override in the request's AI decisions panel), and overrides waiting
 * to become evaluation cases (ai.evaluate), accepted into a suite with a redacted or synthetic input, or discarded.
 */
export function AiReviewPage() {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const queueAllowed = can('ai.evaluate', 'run.operate')
  const candidatesAllowed = can('ai.evaluate')
  const queue = useQuery({ queryKey: aiSkillKeys.reviewQueue(ws), queryFn: () => listAiReviewQueue(ws), enabled: queueAllowed })
  const candidates = useQuery({ queryKey: aiSkillKeys.candidates(ws), queryFn: () => listAiEvalCandidates(ws), enabled: candidatesAllowed })
  const [reviewing, setReviewing] = useState<{ candidate: AiEvalCandidate; decision: 'accept' | 'discard' } | null>(null)
  const client = useQueryClient()

  if (!queueAllowed) return <Alert tone="warning">You need permission to operate runs or run AI evaluations to see the AI review queue.</Alert>

  return (
    <div className="space-y-8">
      <PageHeader title="AI review" description="Sampled automatic AI decisions waiting for a person, and overrides that can become evaluation cases." />
      <section aria-labelledby="queue-heading" className="space-y-3">
        <h2 id="queue-heading">Review queue</h2>
        <p className="text-sm text-muted-foreground">Open the request and accept or override the AI decision in its AI decisions panel.</p>
        {queue.isPending ? <LoadingBlock rows={2} label="Loading the review queue" />
          : queue.isError ? <QueryError error={queue.error} onRetry={() => void queue.refetch()} what="the review queue" />
            : queue.data.items.length === 0 ? <p className="text-sm text-muted-foreground">Nothing is waiting for review.</p>
              : (
                <div className="overflow-x-auto rounded-card border border-border bg-surface">
                  <Table>
                    <caption className="sr-only">Sampled automatic AI decisions not reviewed yet</caption>
                    <THead><TR><TH>Request</TH><TH>Skill</TH><TH>AI answer</TH><TH>Confidence</TH><TH>Decided</TH></TR></THead>
                    <TBody>
                      {queue.data.items.map((d) => (
                        <TR key={d.decisionId}>
                          <TD><Link to={`/w/${ws}/runs/${d.runId}`}>Open request</Link><span className="block text-xs text-muted-foreground">step {d.nodeId}</span></TD>
                          <TD><Link to={`/w/${ws}/ai-skills/${d.skillKey}?v=${d.skillVersion}`} className="font-mono text-xs">{d.skillKey}</Link> version {d.skillVersion}</TD>
                          <TD>{d.aiValue ?? <span className="text-muted-foreground">not retained</span>} <span className="text-xs text-muted-foreground">({d.status})</span></TD>
                          <TD>{percent(d.confidence)}</TD>
                          <TD>{formatDateTime(d.createdAt)}</TD>
                        </TR>
                      ))}
                    </TBody>
                  </Table>
                </div>
              )}
      </section>

      <section aria-labelledby="candidates-heading" className="space-y-3">
        <h2 id="candidates-heading">Evaluation candidates</h2>
        {!candidatesAllowed ? <p className="text-sm text-muted-foreground" data-testid="candidates-hint">Reviewing evaluation candidates needs the ai.evaluate permission.</p>
          : candidates.isPending ? <LoadingBlock rows={2} label="Loading evaluation candidates" />
            : candidates.isError ? <QueryError error={candidates.error} onRetry={() => void candidates.refetch()} what="the evaluation candidates" />
              : candidates.data.items.length === 0 ? <p className="text-sm text-muted-foreground">No override is waiting to become an evaluation case.</p>
                : (
                  <ul className="space-y-3">
                    {candidates.data.items.map((c) => (
                      <li key={c.id} className="space-y-2 rounded-card border border-border bg-surface p-4" aria-label={`Candidate ${shortId(c.id)}`}>
                        <p className="text-sm"><span className="font-mono">{c.skillKey}</span>, override of {formatDateTime(c.createdAt)}. Expected: <span className="font-mono text-xs">{JSON.stringify(c.expected)}</span></p>
                        <p className="text-xs text-muted-foreground">{c.input ? 'The decision retained its (minimized) input values. Redact them before accepting.' : 'The decision kept only a digest of its input: write a synthetic input.'}</p>
                        <div className="flex flex-wrap gap-2">
                          <Button size="sm" variant="primary" onClick={() => setReviewing({ candidate: c, decision: 'accept' })}>Accept into a suite</Button>
                          <Button size="sm" variant="secondary" onClick={() => setReviewing({ candidate: c, decision: 'discard' })}>Discard</Button>
                        </div>
                      </li>
                    ))}
                  </ul>
                )}
      </section>

      {reviewing ? <CandidateDialog ws={ws} {...reviewing} onCancel={() => setReviewing(null)} onDone={async () => {
        setReviewing(null)
        await Promise.all([client.invalidateQueries({ queryKey: aiSkillKeys.candidates(ws) }), client.invalidateQueries({ queryKey: aiSkillKeys.suites(ws) })])
      }} /> : null}
    </div>
  )
}

function CandidateDialog({ ws, candidate, decision, onCancel, onDone }: { ws: string; candidate: AiEvalCandidate; decision: 'accept' | 'discard'; onCancel: () => void; onDone: () => Promise<void> }) {
  const suites = useAiEvalSuites(ws, decision === 'accept')
  const own = (suites.data?.items ?? []).filter((s) => s.skillKey === candidate.skillKey)
  const [suiteKey, setSuiteKey] = useState('')
  const [caseId, setCaseId] = useState(`override-${candidate.id.slice(0, 8)}`)
  const [input, setInput] = useState(JSON.stringify(candidate.input ?? {}, null, 2))
  const [expected, setExpected] = useState(JSON.stringify(candidate.expected, null, 2))
  const [errors, setErrors] = useState<Record<string, string>>({})
  const action = useAction()
  const chosen = suiteKey || own[0]?.key || ''

  const confirm = () => {
    if (decision === 'discard') return void action.run(() => reviewAiEvalCandidate(ws, candidate.id, { decision: 'discard' }), onDone)
    const next: Record<string, string> = {}
    const parse = (text: string, field: string) => {
      try {
        const v: unknown = JSON.parse(text)
        if (v && typeof v === 'object' && !Array.isArray(v)) return v as Record<string, unknown>
      } catch { /* reported below */ }
      next[field] = 'Write a JSON object.'
      return undefined
    }
    const parsedInput = parse(input, 'input')
    const parsedExpected = parse(expected, 'expected')
    if (!chosen) next.suite = 'Choose a suite of this skill (create one under AI skills, Evaluation suites).'
    if (!/^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/.test(caseId)) next.caseId = 'Case ids use letters, digits, dot, dash and underscore.'
    setErrors(next)
    if (Object.keys(next).length > 0 || !parsedInput || !parsedExpected) return
    void action.run(() => reviewAiEvalCandidate(ws, candidate.id, { decision: 'accept', suiteKey: chosen, caseId, input: parsedInput, expected: parsedExpected }), onDone)
  }

  return (
    <ConfirmDialog open title={decision === 'accept' ? 'Accept into an evaluation suite' : 'Discard this candidate?'} confirmLabel={decision === 'accept' ? 'Add case' : 'Discard'}
      destructive={decision === 'discard'} pending={action.pending} onConfirm={confirm} onCancel={onCancel}
      error={action.error ? <AiProblem error={action.error} action="review this candidate" /> : null}>
      {decision === 'discard' ? <p>The override stays recorded on its request; it just does not become an evaluation case.</p> : (
        <>
          <Alert tone="warning">Only synthetic or redacted data goes into a suite. Remove names, numbers and anything else that identifies a person.</Alert>
          <FormField label="Suite" required error={errors.suite}>
            {(ids) => (
              <Select id={ids.input} aria-describedby={ids.describedBy} value={chosen} onChange={(e) => setSuiteKey(e.target.value)}>
                {own.length === 0 ? <option value="">No suite for {candidate.skillKey}</option> : null}
                {own.map((s) => <option key={s.key} value={s.key}>{s.name}</option>)}
              </Select>
            )}
          </FormField>
          <FormField label="Case id" required error={errors.caseId}>
            {(ids) => <Input id={ids.input} aria-describedby={ids.describedBy} aria-invalid={errors.caseId ? true : undefined} value={caseId} onChange={(e) => setCaseId(e.target.value)} />}
          </FormField>
          <FormField label="Case input (redacted or synthetic JSON)" required error={errors.input}>
            {(ids) => <Textarea id={ids.input} aria-describedby={ids.describedBy} aria-invalid={errors.input ? true : undefined} className="font-mono text-sm" value={input} onChange={(e) => setInput(e.target.value)} />}
          </FormField>
          <FormField label="Expected answer (JSON)" required error={errors.expected}>
            {(ids) => <Textarea id={ids.input} aria-describedby={ids.describedBy} aria-invalid={errors.expected ? true : undefined} className="font-mono text-sm" value={expected} onChange={(e) => setExpected(e.target.value)} />}
          </FormField>
        </>
      )}
    </ConfirmDialog>
  )
}
