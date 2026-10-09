import { ChevronDown, ChevronUp, Sparkles } from 'lucide-react'
import { useId, useState } from 'react'
import { useWorkspace } from '@/app/workspace'
import { Button } from '@/components/ui/button'
import { useStudio, useStudioContext } from '@/features/studio/StudioContext'
import { formatDuration, formatUsd } from './api'
import { clearReviewNotes, readReviewNotes } from './reviewNotes'

/**
 * "AI-drafted — review every step before publishing" (S10 section 6): shown in the studio after Describe it, with the
 * model's assumptions and the configuration a person must add (each with Go to step when it maps to a step).
 */
export function AiReviewPanel({ workflowId }: { workflowId: string }) {
  const { workspace } = useWorkspace()
  const { store } = useStudioContext()
  const nodes = useStudio((s) => s.definition.nodes)
  const [notes, setNotes] = useState(() => readReviewNotes(workspace.id, workflowId))
  const [open, setOpen] = useState(true)
  const headingId = useId()
  const bodyId = useId()
  if (!notes) return null

  const label = (nodeId: string | null | undefined) => (nodeId ? nodes.find((n) => n.id === nodeId)?.label : undefined)
  const goTo = (nodeId: string) => {
    store.getState().select({ type: 'node', id: nodeId })
    store.getState().requestFocus(nodeId, null)
  }
  const dismiss = () => {
    clearReviewNotes(workspace.id, workflowId)
    setNotes(null)
  }
  const { generation } = notes

  return (
    <section aria-labelledby={headingId} className="m-3 rounded-card border border-warning-border bg-warning-soft text-sm text-warning-foreground" data-testid="ai-review">
      <div className="flex flex-wrap items-center gap-3 px-4 py-3">
        <Sparkles className="size-5 shrink-0" aria-hidden="true" />
        <h2 id={headingId} className="min-w-0 flex-1 text-base">
          AI-drafted — review every step before publishing
        </h2>
        <Button size="sm" variant="ghost" onClick={() => setOpen((v) => !v)} aria-expanded={open} aria-controls={bodyId}>
          {open ? <ChevronUp aria-hidden="true" /> : <ChevronDown aria-hidden="true" />}
          {open ? 'Hide details' : 'Show details'}
        </Button>
        <Button size="sm" variant="secondary" onClick={dismiss}>
          Dismiss
        </Button>
      </div>
      {open ? (
        <div id={bodyId} className="max-h-[38vh] space-y-4 overflow-y-auto border-t border-warning-border px-4 py-3">
          <div>
            <h3 className="mb-1.5 text-sm">Missing configuration</h3>
            {notes.missingConfiguration.length === 0 ? (
              <p>Nothing to set up: the groups and connections it uses exist in this workspace.</p>
            ) : (
              <ul className="space-y-2" aria-label="Missing configuration">
                {notes.missingConfiguration.map((item, index) => {
                  const step = label(item.nodeId)
                  return (
                    <li key={`${item.nodeId ?? 'workflow'}-${index}`} className="flex flex-wrap items-start gap-3 rounded-control border border-warning-border bg-surface p-3 text-foreground">
                      <div className="min-w-0 flex-1">
                        {step ? <p className="text-xs font-semibold text-muted-foreground">{step}</p> : null}
                        <p>{item.message}</p>
                      </div>
                      {step && item.nodeId ? (
                        <Button size="sm" variant="secondary" onClick={() => goTo(item.nodeId!)} aria-label={`Go to step ${step}: ${item.message}`}>
                          Go to step
                        </Button>
                      ) : null}
                    </li>
                  )
                })}
              </ul>
            )}
          </div>
          <div>
            <h3 className="mb-1.5 text-sm">Assumptions the AI made</h3>
            {notes.assumptions.length === 0 ? (
              <p>None were reported. Still check every step against what you need.</p>
            ) : (
              <ul className="list-disc space-y-1 pl-5" aria-label="Assumptions">
                {notes.assumptions.map((assumption, index) => (
                  <li key={index}>{assumption}</li>
                ))}
              </ul>
            )}
          </div>
          <p className="text-xs" data-testid="ai-generation">
            Drafted with the {generation.profile} profile ({generation.model}) in {generation.attempts} {generation.attempts === 1 ? 'attempt' : 'attempts'}
            {generation.escalated ? ', after escalating to a stronger model' : ''}, {formatDuration(generation.durationMs)}, cost {formatUsd(generation.costUsd)}. Nothing is published until a
            person with publish permission publishes it.
          </p>
        </div>
      ) : null}
    </section>
  )
}
