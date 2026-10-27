import { PanelsLeftRight } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { DropdownMenu, DropdownMenuCheckboxItem, DropdownMenuContent, DropdownMenuLabel, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { usePersistedFlag } from '@/lib/usePersistedFlag'
import { STEPS_COLLAPSED_KEY, AI_DOCK_KEY, SIDE_PANEL_KEY } from './viewKeys'

/** Show or hide the three studio panes: the step list, the Ask AI dock and the details cards. Remembered per browser. */
export function ViewMenu({ canEdit }: { canEdit: boolean }) {
  const [stepsCollapsed, setStepsCollapsed] = usePersistedFlag(STEPS_COLLAPSED_KEY, true)
  const [aiOpen, setAiOpen] = usePersistedFlag(AI_DOCK_KEY, true)
  const [sideOpen, setSideOpen] = usePersistedFlag(SIDE_PANEL_KEY, true)
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="secondary" aria-label="View: show or hide panels">
          <PanelsLeftRight aria-hidden="true" /> View
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuLabel>Panels</DropdownMenuLabel>
        {canEdit ? <DropdownMenuCheckboxItem checked={!stepsCollapsed} onCheckedChange={(on) => setStepsCollapsed(!on)}>Step list</DropdownMenuCheckboxItem> : null}
        {canEdit ? <DropdownMenuCheckboxItem checked={aiOpen} onCheckedChange={setAiOpen}>Ask AI</DropdownMenuCheckboxItem> : null}
        <DropdownMenuCheckboxItem checked={sideOpen} onCheckedChange={setSideOpen}>Details and discussion</DropdownMenuCheckboxItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
