import { Bot } from 'lucide-react'
import type { components } from '@/api/schema'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'

type AiReview = components['schemas']['TaskAiReview']

const HELD_FOR: Record<string, string> = {
  suggest: 'This step lets the AI reviewer only suggest; a person decides.',
  lowConfidence: 'The AI reviewer is not confident enough, so a person decides.',
  notADecision: 'The AI reviewer did not answer approve or reject, so a person decides.',
  adverseRegulated: 'In this regulated workspace a rejection always needs a person.',
  notLowRisk: 'The workspace AI policy does not let AI decide this answer on its own.',
  notPromoted: 'The skill is not promoted to decide on its own yet.',
  drift: 'AI agreement with people dropped recently, so it only suggests for now.',
  policy: 'The workspace AI policy lets AI only suggest.',
  personActed: 'Someone took or changed the task while AI was reviewing it, so a person decides.',
}

/** The suggested action in words. */
export function recommendationText(review: AiReview): string | null {
  if (review.recommendation === 'approve') return 'Approve'
  if (review.recommendation === 'reject') return 'Reject'
  return review.recommendation ?? null
}

/** What the approver can use as their comment: the rationale, else the reasons. */
export function suggestionText(review: AiReview): string {
  if (review.rationale) return review.rationale
  return review.reasons.join('; ')
}

/**
 * The AI reviewer's suggestion on a task (docs/build/S152): "AI reviewer suggests: Approve (0.91)" with the rationale and
 * reasons, why the AI did not decide on its own, and a button that prefills the approver's comment with the rationale.
 * The person still chooses Approve or Reject; the choice is recorded as accepted or overridden on the AI decision.
 */
export function AiReviewPanel({ review, canUse, onUse }: { review: AiReview; canUse: boolean; onUse: (text: string, action: 'approve' | 'reject' | null) => void }) {
  const suggestion = recommendationText(review)
  const action = review.recommendation === 'approve' || review.recommendation === 'reject' ? review.recommendation : null
  const text = suggestionText(review)
  return (
    <section aria-labelledby="task-ai-review-heading" className="space-y-2 rounded-card border border-border bg-surface-muted p-4 text-sm" data-testid="task-ai-review">
      <h3 id="task-ai-review-heading" className="flex flex-wrap items-center gap-2 font-semibold">
        <Bot className="size-4" aria-hidden="true" />
        {review.status === 'pending' ? 'AI reviewer is reviewing this request…'
          : review.status === 'unavailable' ? 'AI reviewer could not review this request'
            : review.status === 'cancelled' ? 'AI reviewer stopped: a person decided first'
              : review.decidedByAi ? `Decided by AI reviewer: ${suggestion ?? ''}`
                : suggestion ? `AI reviewer suggests: ${suggestion}${review.confidence !== null && review.confidence !== undefined ? ` (${review.confidence})` : ''}`
                  : 'AI reviewer suggestion'}
        {review.decidedByAi && review.confidence !== null && review.confidence !== undefined ? <Badge>confidence {review.confidence}</Badge> : null}
      </h3>
      {review.status === 'unavailable' ? <p className="text-muted-foreground">{review.unavailableReason ?? 'AI was unavailable.'} A person decides.</p> : null}
      {review.rationale ? <p className="wrap-anywhere whitespace-pre-wrap">{review.rationale}</p> : null}
      {review.reasons.length > 0 ? <ul className="list-disc pl-5" aria-label="Reasons given by the AI reviewer">
        {review.reasons.map((r, i) => <li key={i} className="wrap-anywhere">{r}</li>)}
      </ul> : null}
      {review.heldFor && HELD_FOR[review.heldFor] ? <p className="text-muted-foreground">{HELD_FOR[review.heldFor]}</p> : null}
      {review.threshold !== null && review.threshold !== undefined && review.status === 'suggested'
        ? <p className="text-xs text-muted-foreground">Skill {review.skillKey} v{review.skillVersion}; it decides on its own only at confidence {review.threshold} or higher.</p>
        : <p className="text-xs text-muted-foreground">Skill {review.skillKey} v{review.skillVersion}</p>}
      {canUse && review.status === 'suggested' && suggestion ? (
        <Button size="sm" variant="secondary" onClick={() => onUse(text, action)}>
          Use the suggestion as my comment
        </Button>
      ) : null}
      {review.humanOutcome ? <p className="text-xs text-muted-foreground">The approver {review.humanOutcome === 'accepted' ? 'followed' : 'did not follow'} the suggestion.</p> : null}
    </section>
  )
}
