import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Sparkles } from 'lucide-react'
import { useState } from 'react'
import { api, call } from '@/api/client'
import type { components } from '@/api/schema'
import { useAnnounce } from '@/components/common/Announcer'
import { FormField } from '@/components/common/FormField'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Input, Textarea } from '@/components/ui/input'
import { formatDateTime } from '@/lib/dates'

type AiDecision = components['schemas']['AiDecision']

export const AI_STATUS: Record<AiDecision['status'], string> = {
  decided: 'Decided by AI',
  lowConfidence: 'Not confident enough; a person decides',
  needsReview: 'Suggestion for a person',
  unavailable: 'AI unavailable; a person takes over',
  shadow: 'Shadow mode (did not change the path)',
}

/** The AI result in words: label, recommendation, score, extracted fields or summary. */
export function aiResult(d: AiDecision): string {
  const out = (d.output ?? {}) as Record<string, unknown>
  if (typeof out.label === 'string') return out.label
  if (typeof out.recommendation === 'string') return `Recommends ${out.recommendation}`
  if (typeof out.score === 'number') return `Score ${out.score}`
  if (out.fields && typeof out.fields === 'object') return `${Object.keys(out.fields as object).length} fields extracted`
  if (typeof out.summary === 'string') return 'Summary written'
  return 'No result'
}

/** Why the AI answered as it did: the rationale (recommend) and the reasons (classify, score), when kept in the record. */
export function aiExplanation(d: AiDecision): { rationale: string | null; reasons: string[] } {
  const out = (d.output ?? {}) as Record<string, unknown>
  const rationale = typeof out.rationale === 'string' && out.rationale.trim() ? out.rationale : null
  const reasons = Array.isArray(out.reasons) ? out.reasons.filter((r): r is string => typeof r === 'string' && r.trim() !== '') : []
  return { rationale, reasons }
}

/**
 * AI decision records of a request (docs/build/S134, doc 29 section 7.8): pinned skill version, model, input field
 * names and digest (values only when the workspace keeps them and the viewer may see the request data), result,
 * calibrated confidence against the threshold, route, guardrails, cost, and who accepted or overrode it. A participant,
 * an operator or an evaluator records the human outcome; an override needs the final value and a reason.
 */
export function AiDecisionsPanel({ ws, runId, canRecord }: { ws: string; runId: string; canRecord: boolean }) {
  const client = useQueryClient()
  const announce = useAnnounce()
  const key = ['ws', ws, 'run-ai-decisions', runId]
  const query = useQuery({
    queryKey: key,
    retry: false,
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/runs/{runId}/ai-decisions', { params: { path: { workspaceId: ws, runId } } })),
  })
  const [override, setOverride] = useState<AiDecision | null>(null)
  const [value, setValue] = useState('')
  const [reason, setReason] = useState('')
  const [problem, setProblem] = useState<unknown>(null)
  const items = query.data?.items ?? []
  if (items.length === 0) return null

  const record = async (d: AiDecision, outcome: 'accepted' | 'overridden') => {
    setProblem(null)
    try {
      await call(api.POST('/workspaces/{workspaceId}/runs/{runId}/ai-decisions/{decisionId}/outcome', {
        params: { path: { workspaceId: ws, runId, decisionId: d.id } },
        body: outcome === 'accepted' ? { outcome } : { outcome, value: value.trim(), reason: reason.trim() },
      }))
      setOverride(null)
      announce(outcome === 'accepted' ? 'AI decision accepted.' : 'AI decision overridden.')
      await client.invalidateQueries({ queryKey: key })
    } catch (e) {
      setProblem(e)
    }
  }

  return (
    <section aria-labelledby="ai-decisions-heading" className="rounded-card border border-border bg-surface p-4" data-testid="ai-decisions">
      <h2 id="ai-decisions-heading" className="mb-1 flex items-center gap-2 text-base">
        <Sparkles className="size-4" aria-hidden="true" /> AI decisions
      </h2>
      <ul className="divide-y divide-border" aria-label="AI decisions of this request">
        {items.map((d) => (
          <li key={d.id} className="space-y-1.5 py-2 text-sm" data-testid={`ai-decision-${d.nodeId}`}>
            <p className="flex flex-wrap items-center gap-2">
              <Badge>{AI_STATUS[d.status]}</Badge>
              <span className="font-semibold">{aiResult(d)}</span>
              {d.confidence !== null ? <span className="text-muted-foreground">confidence {d.confidence}{d.threshold !== null ? ` (threshold ${d.threshold})` : ''}</span> : null}
            </p>
            <p className="text-muted-foreground">
              Skill {d.skillKey} v{d.skillVersion} · {d.effectiveAutonomy}{d.effectiveAutonomy !== d.autonomy ? ` (designed as ${d.autonomy})` : ''} · model {d.modelUsed ?? 'none'} · route {d.route}
            </p>
            {d.unavailableReason ? <p className="text-muted-foreground">{d.unavailableReason}</p> : null}
            {(() => {
              const { rationale, reasons } = aiExplanation(d)
              return <>
                {rationale ? <p className="wrap-anywhere">Rationale: {rationale}</p> : null}
                {reasons.length > 0 ? <ul className="list-disc pl-5" aria-label={`Reasons for ${aiResult(d)}`}>
                  {reasons.map((r, i) => <li key={i} className="wrap-anywhere">{r}</li>)}
                </ul> : null}
              </>
            })()}
            <p className="text-muted-foreground">
              Sent: {d.inputFields.join(', ') || 'nothing'} · digest {d.inputDigest.slice(0, 12)}…{d.inputValues ? '' : ' (values not kept)'}
            </p>
            {d.human.outcome ? <p>{d.human.outcome === 'accepted' ? 'Accepted' : `Overridden with ${d.human.value ?? ''}`}{d.human.decidedAt ? ` on ${formatDateTime(d.human.decidedAt)}` : ''}{d.human.reason ? `: ${d.human.reason}` : ''}</p>
              : canRecord && d.status !== 'unavailable' ? <div className="flex flex-wrap gap-2">
                <Button size="sm" variant="secondary" onClick={() => void record(d, 'accepted')}>Accept</Button>
                <Button size="sm" variant="secondary" onClick={() => { setOverride(d); setValue(''); setReason(''); setProblem(null) }}>Override</Button>
              </div> : null}
          </li>
        ))}
      </ul>
      {problem && !override ? <ProblemAlert error={problem} /> : null}
      <Dialog open={override !== null} onClose={() => setOverride(null)} title="Override the AI decision"
        description="Name the label or outcome the person chose and why. The override becomes a candidate evaluation case.">
        <div className="space-y-4">
          {problem ? <ProblemAlert error={problem} /> : null}
          <FormField label="Final label or outcome" required id="ai-override-value">
            {(ids) => <Input id={ids.input} value={value} maxLength={40} onChange={(e) => setValue(e.target.value)} />}
          </FormField>
          <FormField label="Reason" required id="ai-override-reason">
            {(ids) => <Textarea id={ids.input} value={reason} maxLength={1000} onChange={(e) => setReason(e.target.value)} />}
          </FormField>
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={() => setOverride(null)}>Cancel</Button>
            <Button onClick={() => override && void record(override, 'overridden')} disabled={!value.trim() || !reason.trim()}>Override</Button>
          </div>
        </div>
      </Dialog>
    </section>
  )
}
