import { X } from 'lucide-react'
import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { readStorage, writeStorage } from '@/lib/storage'
import { useStudio } from './StudioContext'

const DISMISSED_KEY = 'orqix.studio.hintDismissed'

/** Four-step reminder for a nearly empty draft; per-viewer convenience, dismissed for good with the close button. */
export function StudioHint() {
  const small = useStudio((s) => s.definition.nodes.length <= 3)
  const [dismissed, setDismissed] = useState(() => readStorage(DISMISSED_KEY) === 'true')
  if (!small || dismissed) return null
  return (
    <section aria-label="How to build a workflow" className="flex items-start justify-between gap-3 border-b border-border bg-primary-soft px-4 py-2 text-sm" data-testid="studio-hint">
      <ol className="flex flex-wrap gap-x-6 gap-y-1">
        <li><strong>1.</strong> Add steps from the step list, or ask AI.</li>
        <li><strong>2.</strong> Connect them on the canvas.</li>
        <li><strong>3.</strong> Press Check workflow.</li>
        <li><strong>4.</strong> Publish.</li>
      </ol>
      <Button variant="ghost" size="icon" className="-my-1 size-7 shrink-0" aria-label="Hide these tips" onClick={() => { setDismissed(true); writeStorage(DISMISSED_KEY, 'true') }}>
        <X aria-hidden="true" />
      </Button>
    </section>
  )
}
