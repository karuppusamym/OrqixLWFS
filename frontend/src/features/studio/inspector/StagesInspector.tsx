import { ArrowDown, ArrowUp, Plus, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { FormField } from '@/components/common/FormField'
import { durationMinutes } from '@/lib/duration'
import { useStudio, useStudioContext } from '../StudioContext'
import type { StageDef } from '../model/types'
import { CommitInput, InspectorSection } from './controls'

/** Presentation/SLA groups, using the already supported stages contract. */
export function StagesInspector() {
  const definition = useStudio((state) => state.definition)
  const { store, readOnly } = useStudioContext()
  const stages = definition.stages ?? []
  const patch = (id: string, change: Partial<StageDef>) => store.getState().apply((current) => ({
    ...current, stages: (current.stages ?? []).map((stage) => stage.id === id ? { ...stage, ...change } : stage),
  }))
  const move = (index: number, delta: number) => store.getState().apply((current) => {
    const ordered = [...(current.stages ?? [])]
    const other = index + delta
    if (other < 0 || other >= ordered.length) return current
    ;[ordered[index], ordered[other]] = [ordered[other]!, ordered[index]!]
    return { ...current, stages: ordered }
  })
  return <InspectorSection title="Progress stages">
    <p className="text-xs">Group steps to show request progress. An optional time limit marks a stage overdue and sends notices; it does not stop or reroute the workflow.</p>
    {!stages.length ? <p className="text-sm">No progress stages yet.</p> : null}
    {stages.map((stage, index) => {
      const minutes = stage.sla ? durationMinutes(stage.sla) : null
      const invalidSla = stage.sla && (minutes === null || minutes < 1 || minutes > 90 * 24 * 60)
      return <fieldset key={stage.id} className="space-y-2 rounded border p-3" disabled={readOnly}>
        <legend className="text-sm font-semibold">Stage {index + 1}: {stage.label}</legend>
        <div className="flex gap-1">
          <Button variant="ghost" size="icon" aria-label={`Move stage ${stage.label} up`} disabled={readOnly || index === 0} onClick={() => move(index, -1)}><ArrowUp /></Button>
          <Button variant="ghost" size="icon" aria-label={`Move stage ${stage.label} down`} disabled={readOnly || index === stages.length - 1} onClick={() => move(index, 1)}><ArrowDown /></Button>
          <Button variant="ghost" size="icon" aria-label={`Remove stage ${stage.label}`} disabled={readOnly} onClick={() => store.getState().apply((current) => ({ ...current, stages: current.stages?.filter((entry) => entry.id !== stage.id) }))}><Trash2 /></Button>
        </div>
        <FormField id={`stage-label-${stage.id}`} label="Stage name" required>{ids => <CommitInput id={ids.input} value={stage.label} maxLength={200} disabled={readOnly} onCommit={(label) => patch(stage.id, { label })} />}</FormField>
        <FormField id={`stage-sla-${stage.id}`} label="Stage time limit" optionalText description="Examples: PT8H or P5D. Allowed range: 1 minute to 90 days." error={invalidSla ? 'Use a duration from PT1M to P90D.' : undefined}>{ids => <CommitInput id={ids.input} value={stage.sla ?? ''} disabled={readOnly} aria-describedby={ids.describedBy} onCommit={(sla) => patch(stage.id, { sla: sla.trim() || undefined })} />}</FormField>
        <p className="text-xs">Stage ID: {stage.id}. Choose steps; each step belongs to at most one stage.</p>
        {definition.nodes.map((node) => {
          const other = stages.find((entry) => entry.id !== stage.id && entry.nodes.includes(node.id))
          const id = `stage-${stage.id}-node-${node.id}`
          return <div key={node.id} className="flex items-center gap-2">
            <Checkbox id={id} checked={stage.nodes.includes(node.id)} disabled={readOnly || Boolean(other)} onCheckedChange={(checked) => patch(stage.id, { nodes: checked ? [...new Set([...stage.nodes, node.id])] : stage.nodes.filter((key) => key !== node.id) })} />
            <Label htmlFor={id}>{node.label}{other ? ` (in ${other.label})` : ''}</Label>
          </div>
        })}
        {!stage.nodes.length ? <p className="text-xs">Choose at least one step before publishing.</p> : null}
      </fieldset>
    })}
    <Button variant="secondary" disabled={readOnly || stages.length >= 20} onClick={() => store.getState().apply((current) => {
      const present = current.stages ?? []
      let suffix = 1
      while (present.some((stage) => stage.id === `stage${suffix}`)) suffix++
      return { ...current, stages: [...present, { id: `stage${suffix}`, label: `Stage ${present.length + 1}`, nodes: [] }] }
    })}><Plus /> Add a progress stage</Button>
  </InspectorSection>
}
