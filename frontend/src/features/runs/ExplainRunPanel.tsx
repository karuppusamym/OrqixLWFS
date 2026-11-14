import { HelpCircle, Sparkles } from 'lucide-react'
import { useState } from 'react'
import { api, call } from '@/api/client'
import type { components } from '@/api/schema'
import { useAnnounce } from '@/components/common/Announcer'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { formatDateTime } from '@/lib/dates'

export type RunExplanation = components['schemas']['RunExplanation']
export type ExplainAction = components['schemas']['ExplainPermittedAction']

const FALLBACK: Record<NonNullable<RunExplanation['fallbackReason']>, string> = {
  aiOff: 'AI explanations are switched off for this deployment.',
  featureOff: 'AI assistance is switched off in this workspace.',
  notConfigured: 'No AI model is configured for explanations.',
  residency: 'The configured AI model runs outside this workspace’s data residency rule.',
  budget: 'The AI budget for this month is used up.',
  unavailable: 'The AI model could not be reached.',
  invalidAnswer: 'The AI answer could not be used.',
}

/**
 * "Explain this request" (CG-A07, docs/build/S146-parts/C-operator-explain.md): why the request is stuck and which of
 * the operator's permitted actions would unblock it. Read-only: a suggested action only opens the existing operator
 * dialog (or the place on the page or in Settings) where it goes through its usual confirmation and permission checks.
 */
export function ExplainRunPanel({ ws, runId, onAction }: { ws: string; runId: string; onAction: (action: ExplainAction) => void }) {
  const announce = useAnnounce()
  const [result, setResult] = useState<RunExplanation | null>(null)
  const [problem, setProblem] = useState<unknown>(null)
  const [pending, setPending] = useState(false)

  const explain = async (preferRules: boolean) => {
    setPending(true)
    setProblem(null)
    try {
      const body = await call(api.POST('/workspaces/{workspaceId}/runs/{runId}/explain', {
        params: { path: { workspaceId: ws, runId } },
        body: preferRules ? { preferRules: true } : {},
      }))
      setResult(body)
      announce('Explanation ready.')
    } catch (e) {
      setProblem(e)
    } finally {
      setPending(false)
    }
  }

  const facts = new Map((result?.facts ?? []).map((f) => [f.id, f]))
  const actions = new Map((result?.permittedActions ?? []).map((a) => [a.id, a]))
  const askOthers = (result?.permittedActions ?? []).filter((a) => a.kind === 'askAdministrator'
    && !(result?.suggestedActions ?? []).some((s) => s.actionId === a.id))

  return (
    <section aria-labelledby="explain-heading" className="mb-6 rounded-card border border-border bg-surface p-4" data-testid="explain-run">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="explain-heading" className="flex items-center gap-2 text-base">
          <HelpCircle className="size-4" aria-hidden="true" /> Why is this request where it is?
        </h2>
        <div className="flex flex-wrap gap-2">
          <Button variant={result ? 'secondary' : 'primary'} onClick={() => void explain(false)} disabled={pending}>
            <Sparkles aria-hidden="true" /> {result ? 'Explain again' : 'Explain this request'}
          </Button>
          {result?.source === 'ai' ? (
            <Button variant="ghost" onClick={() => void explain(true)} disabled={pending}>Show the rule-based explanation</Button>
          ) : null}
        </div>
      </div>
      {problem ? <div className="mt-3"><ProblemAlert error={problem} /></div> : null}
      {result ? (
        <div className="mt-3 space-y-3 text-sm" aria-live="polite">
          <p className="flex flex-wrap items-center gap-2">
            <Badge tone={result.source === 'ai' ? 'info' : 'neutral'} data-testid="explain-source">
              {result.source === 'ai' ? 'AI-generated, read-only' : 'Rule-based, read-only'}
            </Badge>
            <span className="text-muted-foreground">
              {result.source === 'ai' && result.ai ? `Model ${result.ai.model}${result.ai.cached ? ' (reused for the same state)' : ''} · ` : ''}
              Nothing was changed. {formatDateTime(result.generatedAt)}
            </span>
          </p>
          {result.fallbackReason ? <p className="text-muted-foreground">{FALLBACK[result.fallbackReason]} The rules answered instead.</p> : null}
          <p className="font-semibold" data-testid="explain-summary">{result.summary}</p>
          {result.cause ? <p><span className="text-muted-foreground">Likely cause: </span>{result.cause}</p> : null}
          {result.suggestedActions.length > 0 ? (
            <div>
              <h3 className="mb-1 text-sm font-semibold">What you can do</h3>
              <ul className="space-y-2" aria-label="Suggested actions">
                {result.suggestedActions.map((s) => {
                  const a = actions.get(s.actionId)
                  if (!a) return null
                  return (
                    <li key={s.actionId} className="flex flex-wrap items-center gap-2">
                      {a.kind === 'askAdministrator' ? (
                        <span className="font-semibold" data-testid={`explain-ask-${a.id}`}>{a.label}</span>
                      ) : (
                        <Button size="sm" variant="secondary" onClick={() => onAction(a)} data-testid={`explain-action-${a.id}`}>{a.label}</Button>
                      )}
                      <span className="text-muted-foreground">{a.kind === 'askAdministrator' ? a.description : s.why}</span>
                    </li>
                  )
                })}
              </ul>
              <p className="mt-1 text-xs text-muted-foreground">Each action opens its usual dialog and asks for confirmation; the explanation never acts.</p>
            </div>
          ) : <p className="text-muted-foreground">No action is needed now.</p>}
          {askOthers.length > 0 ? (
            <ul className="text-muted-foreground">
              {askOthers.map((a) => <li key={a.id}>{a.label}: {a.description}</li>)}
            </ul>
          ) : null}
          {result.evidence.length > 0 ? (
            <details>
              <summary className="cursor-pointer text-sm">Evidence ({result.evidence.length})</summary>
              <ul className="mt-1 list-disc space-y-1 pl-5" aria-label="Evidence">
                {result.evidence.map((id) => {
                  const f = facts.get(id)
                  if (!f) return null
                  return (
                    <li key={id} data-testid={`explain-fact-${id}`}>
                      {f.eventSeq !== null ? <a href={`#event-${f.eventSeq}`} className="underline underline-offset-2">{f.text}</a> : f.text}
                    </li>
                  )
                })}
              </ul>
            </details>
          ) : null}
        </div>
      ) : (
        <p className="mt-2 text-sm text-muted-foreground">
          Reads the timeline, waits, connections and the published workflow, then says why the request is stuck and which of your permitted actions would help. It never performs an action.
        </p>
      )}
    </section>
  )
}
