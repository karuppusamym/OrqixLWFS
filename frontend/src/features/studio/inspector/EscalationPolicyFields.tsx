import { Plus, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/input'
import { FormField } from '@/components/common/FormField'
import { useStudioContext } from '../StudioContext'
import { cfg, type WorkflowNode } from '../model/types'
import { CommitNumber } from './controls'

export type EscalationAction = 'notify' | 'reassign' | 'alertOperators'
export interface EscalationLevel { afterHours: number; action: EscalationAction; groupRef?: string }

const ACTIONS: { value: EscalationAction; label: string }[] = [
  { value: 'notify', label: 'Tell a supervisor group' },
  { value: 'reassign', label: 'Move the task to a supervisor group' },
  { value: 'alertOperators', label: 'Alert the operators' },
]

/** The next level a designer adds: later than the last one, the next action in the usual order. */
export function nextLevel(levels: EscalationLevel[], fallbackGroup: string | undefined): EscalationLevel {
  const last = levels.at(-1)
  const action: EscalationAction = !last ? 'notify' : last.action === 'notify' ? 'reassign' : 'alertOperators'
  const afterHours = Math.min(2160, (last?.afterHours ?? 0) + 8)
  return action === 'alertOperators' ? { afterHours, action } : { afterHours, action, groupRef: last?.groupRef ?? fallbackGroup }
}

/**
 * CG-T04 (docs/build/S141): escalation levels of an approval step. Counted from task creation in working hours of the
 * step's calendar (or elapsed hours without one). Each level is applied once, after checking that the task is still
 * open and who may receive it; the due date does not change.
 */
export function EscalationPolicyFields({ node, groups }: { node: WorkflowNode; groups: { key: string; name: string }[] }) {
  const { store, readOnly } = useStudioContext()
  const policy = cfg<{ levels: EscalationLevel[] }>(node, 'escalationPolicy')
  const levels = policy?.levels ?? []
  const queue = cfg<string>(node, 'candidateGroupRef')
  const calendar = !!cfg<unknown>(node, 'calendar')
  const supervisors = groups.filter((g) => g.key !== queue)
  const set = (next: EscalationLevel[] | undefined) =>
    store.getState().updateNodeConfig(node.id, { escalationPolicy: next && next.length ? { levels: next } : undefined })
  const change = (index: number, patch: Partial<EscalationLevel>) => set(levels.map((level, i) => {
    if (i !== index) return level
    const merged = { ...level, ...patch }
    if (merged.action === 'alertOperators') {
      const { groupRef: _dropped, ...rest } = merged
      return rest
    }
    return { ...merged, groupRef: merged.groupRef ?? supervisors[0]?.key ?? queue }
  }))
  const orderProblem = levels.some((level, i) => i > 0 && level.afterHours <= levels[i - 1]!.afterHours)

  return <div className="space-y-3">
    <div className="flex items-center gap-2"><Checkbox id={`escalation-policy-${node.id}`} checked={levels.length > 0} disabled={readOnly}
      onCheckedChange={checked => set(checked ? [nextLevel([], supervisors[0]?.key ?? queue)] : undefined)} />
      <Label htmlFor={`escalation-policy-${node.id}`}>Escalate when nobody decides</Label></div>
    {levels.length > 0 ? <>
      <p className="text-xs text-muted-foreground">
        Counted from when the task was created, in {calendar ? 'working hours of the calendar above' : 'elapsed hours (add a calendar to count working hours)'}.
        Each level happens once and only while the task is still open; moving the task needs at least one person in the group who may decide it. The due date does not change.
      </p>
      <ol className="space-y-3" aria-label="Escalation levels">
        {levels.map((level, index) => <li key={index} className="space-y-2 rounded-card border border-border p-3">
          <div className="flex items-center justify-between">
            <span className="text-sm font-medium">Level {index + 1}</span>
            {!readOnly ? <Button variant="ghost" size="icon" aria-label={`Remove level ${index + 1}`} onClick={() => set(levels.filter((_, i) => i !== index))}>
              <Trash2 aria-hidden="true" />
            </Button> : null}
          </div>
          <FormField label={`Level ${index + 1}: after (hours)`} id={`escalation-${node.id}-${index}-hours`}>
            {ids => <CommitNumber id={ids.input} integer value={level.afterHours} disabled={readOnly}
              onCommit={value => change(index, { afterHours: Math.max(1, Math.min(2160, value ?? level.afterHours)) })} />}
          </FormField>
          <FormField label={`Level ${index + 1}: then`} id={`escalation-${node.id}-${index}-action`}>
            {ids => <Select id={ids.input} value={level.action} disabled={readOnly}
              onChange={e => change(index, { action: e.target.value as EscalationAction })}>
              {ACTIONS.map(a => <option key={a.value} value={a.value}>{a.label}</option>)}
            </Select>}
          </FormField>
          {level.action !== 'alertOperators' ? <FormField label={`Level ${index + 1}: group`} id={`escalation-${node.id}-${index}-group`}>
            {ids => <Select id={ids.input} value={level.groupRef ?? ''} disabled={readOnly}
              onChange={e => change(index, { groupRef: e.target.value })}>
              {level.groupRef && !groups.some(g => g.key === level.groupRef) ? <option value={level.groupRef}>{level.groupRef} (not found in this workspace)</option> : null}
              {groups.map(g => <option key={g.key} value={g.key}>{g.name}{g.key === queue ? ' (the current queue)' : ''}</option>)}
            </Select>}
          </FormField> : null}
        </li>)}
      </ol>
      {orderProblem ? <p className="text-sm text-danger" role="alert">Each level must come later than the one before it.</p> : null}
      {!readOnly && levels.length < 5 ? <Button variant="secondary" size="sm" onClick={() => set([...levels, nextLevel(levels, supervisors[0]?.key ?? queue)])}>
        <Plus aria-hidden="true" /> Add level
      </Button> : null}
    </> : null}
  </div>
}
