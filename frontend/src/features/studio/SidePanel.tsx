import { Sparkles } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { AuthoringPanel, type FocusStep } from '@/features/ai/AuthoringPanel'
import { DiscussionPanel } from './DiscussionPanel'
import { Inspector } from './inspector/Inspector'
import { useStudio } from './StudioContext'

export type SideTab = 'details' | 'ai' | 'discussion'

/**
 * One panel on the right of the canvas instead of three: Details (the selected step, or the workflow itself),
 * Ask AI and Discussion. The AI and Discussion tabs stay mounted so a half-written instruction or comment survives
 * a tab switch.
 */
export function SidePanel({ tab, onTabChange, showAi, focus, onFocusChange, onDeleteNode }: {
  tab: SideTab
  onTabChange: (tab: SideTab) => void
  showAi: boolean
  focus: FocusStep | null
  onFocusChange: (next: FocusStep | null) => void
  onDeleteNode: (nodeId: string) => void
}) {
  const selectedId = useStudio((s) => (s.selection?.type === 'node' ? s.selection.id : null))
  const selectedLabel = useStudio((s) => s.definition.nodes.find((n) => n.id === selectedId)?.label ?? null)
  const current: SideTab = tab === 'ai' && !showAi ? 'details' : tab
  return (
    <aside className="flex w-80 shrink-0 flex-col border-l border-border bg-surface" aria-label="Side panel">
      <Tabs value={current} onValueChange={(v) => onTabChange(v as SideTab)} className="flex min-h-0 flex-1 flex-col">
        <TabsList aria-label="Side panel sections" className="border-b border-border px-3 py-2">
          <TabsTrigger value="details" className="min-h-9 px-3">Details</TabsTrigger>
          {showAi ? <TabsTrigger value="ai" className="min-h-9 px-3" data-testid="ai-tab">Ask AI</TabsTrigger> : null}
          <TabsTrigger value="discussion" className="min-h-9 px-3">Discussion</TabsTrigger>
        </TabsList>
        <TabsContent value="details" className="m-0 min-h-0 flex-1 overflow-y-auto">
          {showAi && selectedId && selectedLabel ? (
            <div className="border-b border-border px-4 pt-3 pb-3">
              <Button variant="secondary" size="sm" onClick={() => { onFocusChange({ id: selectedId, label: selectedLabel }); onTabChange('ai') }}>
                <Sparkles aria-hidden="true" /> Ask AI about this step
              </Button>
            </div>
          ) : null}
          <Inspector onDeleteNode={onDeleteNode} />
        </TabsContent>
        {showAi ? (
          <TabsContent value="ai" forceMount className="m-0 min-h-0 flex-1 flex-col data-[state=active]:flex data-[state=inactive]:hidden">
            <AuthoringPanel focus={focus} onFocusChange={onFocusChange} />
          </TabsContent>
        ) : null}
        <TabsContent value="discussion" forceMount className="m-0 min-h-0 flex-1 overflow-y-auto data-[state=inactive]:hidden">
          <DiscussionPanel />
        </TabsContent>
      </Tabs>
    </aside>
  )
}
