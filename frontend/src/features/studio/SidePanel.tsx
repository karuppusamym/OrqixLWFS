import { ChevronDown, ChevronRight, Settings2, Sparkles } from 'lucide-react'
import { useId, type ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import { type FocusStep } from '@/features/ai/AuthoringPanel'
import { DiscussionPanel } from './DiscussionPanel'
import { Inspector } from './inspector/Inspector'
import { useStudio, useStudioContext } from './StudioContext'

export type SideCard = 'details' | 'discussion'

function Card({ title, open, onToggle, children }: { title: string; open: boolean; onToggle: () => void; children: ReactNode }) {
  const bodyId = useId()
  const Chevron = open ? ChevronDown : ChevronRight
  return (
    <section className="rounded-card border border-border bg-surface" aria-label={title}>
      <h2 className="m-0 text-sm">
        <button type="button" className="flex min-h-10 w-full items-center gap-2 rounded-card px-3 py-2 text-left font-semibold hover:bg-surface-muted" aria-expanded={open} aria-controls={bodyId} onClick={onToggle}>
          <Chevron className="size-4" aria-hidden="true" /> {title}
        </button>
      </h2>
      {/* Stays mounted when closed so a half-written comment is kept. */}
      <div id={bodyId} hidden={!open} className="border-t border-border">{children}</div>
    </section>
  )
}

/**
 * Details and Discussion as two collapsible cards on the right of the canvas (the Ask AI conversation is docked under
 * the canvas instead). Details shows the selected step or the workflow itself.
 */
export function SidePanel({ open, onOpenChange, showAi, onAskAbout, onDeleteNode }: {
  open: Record<SideCard, boolean>
  onOpenChange: (card: SideCard, next: boolean) => void
  showAi: boolean
  onAskAbout: (focus: FocusStep) => void
  onDeleteNode: (nodeId: string) => void
}) {
  const selectedId = useStudio((s) => (s.selection?.type === 'node' ? s.selection.id : null))
  const selectedLabel = useStudio((s) => s.definition.nodes.find((n) => n.id === selectedId)?.label ?? null)
  const hasSelection = useStudio((s) => !!s.selection)
  const { store } = useStudioContext()
  return (
    <aside className="w-80 shrink-0 space-y-3 overflow-y-auto border-l border-border bg-canvas p-3" aria-label="Details and discussion">
      <Card title="Details" open={open.details} onToggle={() => onOpenChange('details', !open.details)}>
        {hasSelection ? (
          <div className="flex flex-wrap gap-2 border-b border-border px-4 pt-3 pb-3">
            {showAi && selectedId && selectedLabel ? (
              <Button variant="secondary" size="sm" onClick={() => onAskAbout({ id: selectedId, label: selectedLabel })}>
                <Sparkles aria-hidden="true" /> Ask AI about this step
              </Button>
            ) : null}
            {/* Workflow settings (name, progress stages, sections) show when nothing is selected; this is the way back from any tab. */}
            <Button variant="ghost" size="sm" onClick={() => store.getState().select(null)}>
              <Settings2 aria-hidden="true" /> Workflow settings
            </Button>
          </div>
        ) : null}
        <Inspector onDeleteNode={onDeleteNode} />
      </Card>
      <Card title="Discussion" open={open.discussion} onToggle={() => onOpenChange('discussion', !open.discussion)}>
        <DiscussionPanel />
      </Card>
    </aside>
  )
}
