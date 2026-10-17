import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/input'
import { FormField } from '@/components/common/FormField'
import { useStudio, useStudioContext } from '../StudioContext'
import { cfg, type WorkflowNode } from '../model/types'
import { guaranteedBefore, reachableFrom, startNode } from '../model/graph'
import { CommitInput, CommitNumber, CommitTextarea, InspectorSection } from './controls'

type Calendar = { zone: string; weekdays: number[]; holidays?: string[]; start: string; end: string }
type Escalation = { intervalHours: number; maxNotices: number }

export function WorkManagementFields({ node, groups }: { node: WorkflowNode; groups: { key: string; name: string }[] }) {
  const { store, readOnly } = useStudioContext()
  const skills = cfg<string[]>(node, 'requiredSkills') ?? []
  const calendar = cfg<Calendar>(node, 'calendar')
  const escalation = cfg<Escalation>(node, 'escalation')
  const dispatch = cfg<{ policy: string }>(node, 'dispatch')
  const separateFrom = cfg<string[]>(node, 'separateFrom') ?? []
  const definition = useStudio(state => state.definition)
  // Only approvals that every path passes through before this step can be kept apart; the server checks the same rule.
  const before = guaranteedBefore(definition, node.id)
  const start = startNode(definition)
  const reachable = start ? reachableFrom(definition, start.id) : new Set<string>()
  const otherApprovals = definition.nodes.filter(other => other.type === 'approval' && other.id !== node.id
    && reachable.has(node.id) && reachable.has(other.id) && before.has(other.id))
  const correctable = cfg<string[]>(node, 'correctable') ?? []
  const setCorrectable = (keys: string[]) => set({ correctable: keys.length ? keys : undefined })
  // Single-value request fields only; lists, rows and files cannot be corrected.
  const correctableCandidates = Object.entries(definition.inputSchema.properties)
    .filter(([, field]) => ['string', 'number', 'integer', 'boolean'].includes(field.type) && field.format !== 'attachment')
    .map(([key, field]) => ({ key, title: field.title ?? key }))
  const setSeparate = (ids: string[]) => set({ separateFrom: ids.length ? ids : undefined })
  const set = (changes: Record<string, unknown>) => store.getState().updateNodeConfig(node.id, changes)
  const calendarSet = (changes: Partial<Calendar>) => set({ calendar: { ...calendar, ...changes } })
  return <InspectorSection title="Work queues and deadlines">
    <p className="text-xs text-muted-foreground">The approver group is the work queue. Every selected skill group is also required; permissions and self-approval policy still apply.</p>
    <fieldset disabled={readOnly} className="space-y-2">
      <legend className="text-sm font-medium">Required skill groups</legend>
      {groups.map(group => <label key={group.key} className="flex items-center gap-2 text-sm">
        <Checkbox checked={skills.includes(group.key)} disabled={readOnly || !skills.includes(group.key) && skills.length >= 10}
          onCheckedChange={checked => set({ requiredSkills: checked ? [...skills, group.key] : skills.filter(key => key !== group.key) })} />{group.name}
      </label>)}
      {skills.filter(key => !groups.some(group => group.key === key)).map(key => <label key={key} className="flex items-center gap-2 text-sm">
        <Checkbox checked onCheckedChange={() => set({ requiredSkills: skills.filter(skill => skill !== key) })} />{key} (not found in this workspace)
      </label>)}
    </fieldset>
    {otherApprovals.length > 0 || separateFrom.length > 0 ? <fieldset disabled={readOnly} className="space-y-2">
      <legend className="text-sm font-medium">Keep apart from earlier approvals</legend>
      <p className="text-xs text-muted-foreground">Whoever decided a selected step in the same request cannot claim, decide or be assigned this one. Publishing checks that the step is always decided first.</p>
      {otherApprovals.map(other => <label key={other.id} className="flex items-center gap-2 text-sm">
        <Checkbox checked={separateFrom.includes(other.id)} disabled={readOnly || !separateFrom.includes(other.id) && separateFrom.length >= 5}
          onCheckedChange={checked => setSeparate(checked ? [...separateFrom, other.id] : separateFrom.filter(id => id !== other.id))} />{other.label}
      </label>)}
      {separateFrom.filter(id => !otherApprovals.some(other => other.id === id)).map(id => <label key={id} className="flex items-center gap-2 text-sm">
        <Checkbox checked onCheckedChange={() => setSeparate(separateFrom.filter(ref => ref !== id))} />{definition.nodes.find(other => other.id === id)?.label ?? id} (not an earlier approval on every path)
      </label>)}
    </fieldset> : null}
    {correctableCandidates.length > 0 || correctable.length > 0 ? <fieldset disabled={readOnly} className="space-y-2">
      <legend className="text-sm font-medium">Fields the approver may correct</legend>
      <p className="text-xs text-muted-foreground">The person holding this task can fix these request details before deciding. Publishing is blocked if an earlier step already uses a selected field.</p>
      {correctableCandidates.map(field => <label key={field.key} className="flex items-center gap-2 text-sm">
        <Checkbox checked={correctable.includes(field.key)} disabled={readOnly || !correctable.includes(field.key) && correctable.length >= 10}
          onCheckedChange={checked => setCorrectable(checked ? [...correctable, field.key] : correctable.filter(key => key !== field.key))} />{field.title}
      </label>)}
      {correctable.filter(key => !correctableCandidates.some(field => field.key === key)).map(key => <label key={key} className="flex items-center gap-2 text-sm">
        <Checkbox checked onCheckedChange={() => setCorrectable(correctable.filter(ref => ref !== key))} />{key} (not a single-value request field)
      </label>)}
    </fieldset> : null}
    <FormField label="Assign new tasks" id={`dispatch-${node.id}`} description="Otherwise tasks wait in the group queue. The person can still return a task to the group, and the timeline explains each assignment.">
      {ids => <Select id={ids.input} value={dispatch?.policy ?? ''} disabled={readOnly}
        onChange={e => set({ dispatch: e.target.value ? { policy: e.target.value } : undefined })}>
        <option value="">Leave in the group queue</option>
        <option value="leastLoaded">To the person with the fewest open tasks</option>
        <option value="roundRobin">To each eligible person in turn</option>
      </Select>}
    </FormField>
    <div className="flex items-center gap-2"><Checkbox id={`calendar-${node.id}`} checked={!!calendar} disabled={readOnly}
      onCheckedChange={checked => set({ calendar: checked ? { zone: 'UTC', weekdays: [1,2,3,4,5], start: '09:00', end: '17:00', holidays: [] } : undefined,
        ...(checked && !cfg<number>(node, 'dueAfterHours') ? { dueAfterHours: 8 } : {}) })} />
      <Label htmlFor={`calendar-${node.id}`}>Count working hours for the due date</Label></div>
    {calendar ? <>
      <p className="text-xs text-muted-foreground">Due after counts elapsed time inside these hours. The execution time limit still uses wall-clock time. Overnight schedules are unsupported.</p>
      <FormField label="Calendar time zone" id={`calendar-zone-${node.id}`}>{ids => <CommitInput id={ids.input} value={calendar.zone} disabled={readOnly} onCommit={zone => calendarSet({ zone: zone.trim() })} />}</FormField>
      <fieldset disabled={readOnly}><legend className="text-sm font-medium">Working days</legend>
        <div className="flex flex-wrap gap-3">{['Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday'].map((day,index) => <label key={day} className="flex items-center gap-1 text-sm">
          <Checkbox checked={calendar.weekdays.includes(index+1)} onCheckedChange={checked => calendarSet({ weekdays: checked ? [...calendar.weekdays,index+1].sort() : calendar.weekdays.filter(d => d !== index+1) })} />{day}
        </label>)}</div>
      </fieldset>
      <FormField label="Opening time" id={`calendar-start-${node.id}`}>{ids => <CommitInput id={ids.input} value={calendar.start} disabled={readOnly} onCommit={start => calendarSet({ start })} />}</FormField>
      <FormField label="Closing time" id={`calendar-end-${node.id}`}>{ids => <CommitInput id={ids.input} value={calendar.end} disabled={readOnly} onCommit={end => calendarSet({ end })} />}</FormField>
      <FormField label="Holidays" id={`calendar-holidays-${node.id}`} description="One YYYY-MM-DD date per line, up to 366 dates.">{ids => <CommitTextarea id={ids.input} value={(calendar.holidays ?? []).join('\n')} disabled={readOnly}
        onCommit={value => calendarSet({ holidays: value.split(/\r?\n/).map(day => day.trim()).filter(Boolean) })} />}</FormField>
    </> : null}
    <div className="flex items-center gap-2"><Checkbox id={`escalation-${node.id}`} checked={!!escalation} disabled={readOnly}
      onCheckedChange={checked => set({ escalation: checked ? { intervalHours: 24, maxNotices: 3 } : undefined,
        ...(checked && !cfg<number>(node,'dueAfterHours') ? { dueAfterHours: 24 } : {}) })} />
      <Label htmlFor={`escalation-${node.id}`}>Repeat overdue notices</Label></div>
    {escalation ? <>
      <p className="text-xs text-muted-foreground">Sends an in-app notice to the current eligible assignee at the due date, then at this interval. Stops at the cap or when the task ends. No automatic reassignment.</p>
      <FormField label="Notice interval (hours)" id={`escalation-interval-${node.id}`}>{ids => <CommitNumber id={ids.input} integer value={escalation.intervalHours} disabled={readOnly}
        onCommit={value => set({ escalation: { ...escalation, intervalHours: Math.max(1, Math.min(720,value ?? 24)) } })} />}</FormField>
      <FormField label="Maximum notices" id={`escalation-cap-${node.id}`}>{ids => <CommitNumber id={ids.input} integer value={escalation.maxNotices} disabled={readOnly}
        onCommit={value => set({ escalation: { ...escalation, maxNotices: Math.max(1,Math.min(20,value ?? 3)) } })} />}</FormField>
    </> : null}
  </InspectorSection>
}
