import { Sparkles, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { AuthoringPanel, type FocusStep } from '@/features/ai/AuthoringPanel'

/** The conversation with the AI, docked under the canvas like a chat composer. Hide it from here or from View. */
export function AskDock({ focus, onFocusChange, onHide }: { focus: FocusStep | null; onFocusChange: (next: FocusStep | null) => void; onHide: () => void }) {
  return (
    <section aria-label="Ask AI" className="flex max-h-[50%] min-h-0 shrink-0 flex-col border-t border-border bg-surface shadow-[0_-2px_8px_rgb(0_0_0/0.04)]" data-testid="ask-dock">
      <div className="flex items-center justify-between px-3 pt-2">
        <h2 className="flex items-center gap-1.5 text-sm"><Sparkles className="size-4 text-primary-text" aria-hidden="true" /> Ask AI</h2>
        <Button variant="ghost" size="icon" className="size-7" onClick={onHide} aria-label="Hide Ask AI" title="Hide Ask AI (bring it back from View)"><X aria-hidden="true" /></Button>
      </div>
      <AuthoringPanel focus={focus} onFocusChange={onFocusChange} />
    </section>
  )
}
