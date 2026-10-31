import { ChevronDown, ChevronUp, Sparkles, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { AuthoringPanel, type FocusStep } from '@/features/ai/AuthoringPanel'
import { usePersistedFlag } from '@/lib/usePersistedFlag'
import { AI_EXPANDED_KEY } from './viewKeys'

/** Start compact so the workflow keeps its height; an explicit saved preference takes precedence. */
const defaultExpanded = () => false

/**
 * The conversation with the AI, docked under the canvas like a chat box. It opens and closes from its own bar (the
 * conversation is kept while closed), and can be hidden from here or from View.
 */
export function AskDock({ focus, onFocusChange, onHide }: { focus: FocusStep | null; onFocusChange: (next: FocusStep | null) => void; onHide: () => void }) {
  const [expanded, setExpanded] = usePersistedFlag(AI_EXPANDED_KEY, defaultExpanded())
  const Chevron = expanded ? ChevronDown : ChevronUp
  return (
    <section aria-label="Ask AI" className="flex max-h-[45%] min-h-0 shrink-0 flex-col overflow-hidden border-t border-border bg-surface shadow-[0_-2px_8px_rgb(0_0_0/0.04)]" data-testid="ask-dock">
      <div className="flex items-center justify-between gap-2 px-3 py-1.5">
        <h2 className="m-0 text-sm">
          <button type="button" className="flex items-center gap-1.5 rounded-control px-1 py-1 font-semibold hover:bg-surface-muted" aria-expanded={expanded} onClick={() => setExpanded(!expanded)}>
            <Sparkles className="size-4 text-primary-text" aria-hidden="true" /> Ask AI <Chevron className="size-4" aria-hidden="true" />
            <span className="sr-only">{expanded ? '(close)' : '(open)'}</span>
          </button>
        </h2>
        {!expanded ? <span className="min-w-0 truncate text-xs text-muted-foreground">Describe a change, or press Talk, and review it before it is applied.</span> : null}
        <Button variant="ghost" size="icon" className="size-7" onClick={onHide} aria-label="Hide Ask AI" title="Hide Ask AI (bring it back from View)"><X aria-hidden="true" /></Button>
      </div>
      <div hidden={!expanded} className="flex min-h-0 flex-1 flex-col overflow-hidden">
        <AuthoringPanel focus={focus} onFocusChange={onFocusChange} />
      </div>
    </section>
  )
}
