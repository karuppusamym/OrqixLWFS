import { BookOpenText, X } from 'lucide-react'
import { useCallback, useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { useWorkspace } from '@/app/workspace'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { Button } from '@/components/ui/button'
import { useStudio, useStudioContext } from '@/features/studio/StudioContext'
import { explainWorkflow, formatUsd, useAiEnabled, type WorkflowExplanation } from './api'

/**
 * Side sheet on the native <dialog> element (modal: focus trap, Escape, inert page, focus returns to the opener).
 * No script-injected styles, so it works under the platform CSP like the shared Dialog.
 */
export function SidePanel({ title, onClose, children, footer, testId }: { title: string; onClose: () => void; children: ReactNode; footer?: ReactNode; testId?: string }) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()

  useEffect(() => {
    const el = ref.current
    if (el && !el.open) el.showModal()
    const onClick = (event: MouseEvent) => {
      if (event.target === el) onClose()
    }
    el?.addEventListener('click', onClick)
    return () => {
      el?.removeEventListener('click', onClick)
      if (el?.open) el.close()
    }
  }, [onClose])

  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      data-testid={testId}
      className="m-0 ml-auto h-dvh max-h-dvh w-full max-w-[min(100vw,30rem)] border-0 border-l border-border bg-surface p-0 text-foreground shadow-card backdrop:bg-overlay"
      onCancel={(event) => {
        event.preventDefault()
        onClose()
      }}
    >
      <div className="flex h-full flex-col">
        <div className="flex items-start justify-between gap-4 border-b border-border px-5 py-4">
          <h2 id={titleId} className="text-lg">
            {title}
          </h2>
          <Button variant="ghost" size="icon" onClick={onClose} aria-label="Close the explanation" className="-mr-2 shrink-0">
            <X aria-hidden="true" />
          </Button>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">{children}</div>
        {footer ? <div className="border-t border-border bg-surface-muted px-5 py-3 text-xs text-muted-foreground">{footer}</div> : null}
      </div>
    </dialog>
  )
}

/** Studio header action "Explain" (S10 sections 4 and 6); hidden unless AI assistance is on. */
export function ExplainWorkflowButton() {
  const enabled = useAiEnabled()
  const { can } = useWorkspace()
  const [open, setOpen] = useState(false)
  const close = useCallback(() => setOpen(false), [])
  if (!enabled || !can('workflow.read')) return null
  return (
    <>
      <Button variant="secondary" onClick={() => setOpen(true)} aria-haspopup="dialog">
        <BookOpenText aria-hidden="true" /> Explain
      </Button>
      {open ? <ExplainPanel onClose={close} /> : null}
    </>
  )
}

type State = { phase: 'loading' } | { phase: 'done'; explanation: WorkflowExplanation; revision: number } | { phase: 'error'; error: unknown }

/** Plain-language explanation of the current draft revision (saved first, so the explanation matches it). */
export function ExplainPanel({ onClose }: { onClose: () => void }) {
  const { store, saver, wsId, workflowId, readOnly } = useStudioContext()
  const nodes = useStudio((s) => s.definition.nodes)
  const [state, setState] = useState<State>({ phase: 'loading' })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false
    const run = async () => {
      try {
        if (!readOnly) await saver.flush()
        const revision = store.getState().revision
        const explanation = await explainWorkflow(wsId, workflowId, { revision })
        if (!cancelled) setState({ phase: 'done', explanation, revision })
      } catch (cause) {
        if (!cancelled) setState({ phase: 'error', error: toProblem(cause) })
      }
    }
    void run()
    return () => {
      cancelled = true
    }
  }, [store, saver, wsId, workflowId, readOnly, attempt])

  const label = (nodeId: string) => nodes.find((n) => n.id === nodeId)?.label ?? nodeId
  const goTo = (nodeId: string) => {
    onClose()
    store.getState().select({ type: 'node', id: nodeId })
    store.getState().requestFocus(nodeId, null)
  }

  return (
    <SidePanel
      title="What this workflow does"
      onClose={onClose}
      testId="explain-panel"
      footer={
        state.phase === 'done' ? (
          <>
            <p className="text-sm font-semibold text-foreground">Written by AI from draft revision {state.revision}. AI can be wrong: check each step before you rely on it.</p>
            <p className="mt-1">
              {state.explanation.generation.model} · cost {formatUsd(state.explanation.generation.costUsd)}
            </p>
          </>
        ) : (
          <p>Only the workflow definition is sent to the AI service, never request data.</p>
        )
      }
    >
      <div role="status" aria-live="polite" className="sr-only">
        {state.phase === 'loading' ? 'Explaining the workflow…' : state.phase === 'done' ? 'Explanation ready.' : ''}
      </div>
      {state.phase === 'loading' ? (
        <div className="space-y-3" aria-hidden="true">
          <p className="text-sm text-muted-foreground">Explaining the workflow. This usually takes a few seconds.</p>
          <div className="h-4 w-11/12 animate-pulse rounded bg-neutral-soft" />
          <div className="h-4 w-10/12 animate-pulse rounded bg-neutral-soft" />
          <div className="h-4 w-8/12 animate-pulse rounded bg-neutral-soft" />
        </div>
      ) : state.phase === 'error' ? (
        <ProblemAlert
          error={state.error}
          onRetry={() => {
            setState({ phase: 'loading' })
            setAttempt((n) => n + 1)
          }}
        />
      ) : (
        <div className="space-y-5">
          <p className="text-sm leading-6" data-testid="explain-summary">
            {state.explanation.summary}
          </p>
          {state.explanation.steps.length > 0 ? (
            <section aria-labelledby="explain-steps">
              <h3 id="explain-steps" className="mb-2 text-sm">
                Step by step
              </h3>
              <ol className="space-y-3">
                {state.explanation.steps.map((step, index) => (
                  <li key={step.nodeId} className="rounded-control border border-border p-3 text-sm">
                    <div className="flex items-start justify-between gap-3">
                      <p className="font-semibold">
                        <span className="mr-1.5 text-muted-foreground">{index + 1}.</span>
                        {label(step.nodeId)}
                      </p>
                      <Button size="sm" variant="ghost" onClick={() => goTo(step.nodeId)} aria-label={`Go to step ${label(step.nodeId)}`}>
                        Go to step
                      </Button>
                    </div>
                    <p className="mt-1 text-muted-foreground">{step.text}</p>
                  </li>
                ))}
              </ol>
            </section>
          ) : null}
        </div>
      )}
    </SidePanel>
  )
}
