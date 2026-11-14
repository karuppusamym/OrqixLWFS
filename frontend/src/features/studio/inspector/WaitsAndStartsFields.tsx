import { useQuery } from '@tanstack/react-query'
import { Plus, Trash2 } from 'lucide-react'
import { api, call } from '@/api/client'
import { useWorkspace } from '@/app/workspace'
import { FormField } from '@/components/common/FormField'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useStudio, useStudioContext } from '../StudioContext'
import { availableFields, chipLabel } from '../model/fieldRefs'
import { cfg, type SubprocessDef, type WorkflowNode } from '../model/types'
import {
  BRANCH_ID, BRANCH_KIND_LABEL, branchesOf, branchOfKind, branchTransition, newBranchId, pinnedCalendar, removeBranch,
  renameBranch, setBranchRoute, syncCalendarPins, type BranchKind, type FirstBranch,
} from '../model/waitsAndStarts'
import { CommitInput, DurationPreview, InspectorSection } from './controls'
import { MESSAGE_NAME } from './MessageInspector'

/**
 * Studio fields of docs/build/S139: working-time waits and deadlines in a shared calendar (CG-W05), reminders while
 * waiting (CG-W06), the branches of "Wait for the first" (CG-W07) and message handlers (CG-E08). The server checks
 * everything again at validation and publish.
 */

/** Shared working calendars of the workspace, with their latest versions. */
export function useCalendars() {
  const { workspace, can } = useWorkspace()
  const query = useQuery({
    queryKey: ['ws', workspace.id, 'calendars'],
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/calendars', { params: { path: { workspaceId: workspace.id } } })),
    enabled: can('workflow.read'),
  })
  const items = query.data?.items ?? []
  const latest: Record<string, number> = {}
  items.forEach((c) => { latest[c.key] = c.latestVersion })
  return { items, latest }
}

/** Applies a config change and keeps the calendar pins in step with the steps that use calendars. */
function useCalendarPatch(nodeId: string) {
  const { store } = useStudioContext()
  const { latest } = useCalendars()
  return (change: (config: Record<string, unknown>) => Record<string, unknown>) => store.getState().apply((d) => syncCalendarPins({
    ...d,
    nodes: d.nodes.map((n) => n.id === nodeId ? { ...n, config: change({ ...n.config }) } : n),
  }, latest))
}

function CalendarSelect({ id, value, onChange, disabled, allowNone }: { id: string; value: string; onChange: (key: string) => void; disabled?: boolean; allowNone?: string }) {
  const { items } = useCalendars()
  const def = useStudio((s) => s.definition)
  const pinned = value ? pinnedCalendar(def, value) : undefined
  const current = items.find((c) => c.key === value)
  return <>
    <Select id={id} value={value} disabled={disabled} onChange={(e) => onChange(e.target.value)}>
      <option value="">{allowNone ?? 'Choose a calendar'}</option>
      {value && !current ? <option value={value}>{value} (not in this workspace)</option> : null}
      {items.filter((c) => !c.archived || c.key === value).map((c) => <option key={c.key} value={c.key}>{c.name}{c.archived ? ' (archived)' : ''}</option>)}
    </Select>
    {value && pinned ? <p className="text-sm text-muted-foreground" data-testid="calendar-pin">Uses version {pinned}{current && current.latestVersion > pinned ? `; version ${current.latestVersion} is available. Choose the calendar again to use it.` : '.'}</p> : null}
    {items.length === 0 ? <p className="text-sm text-muted-foreground">There are no working calendars yet. Add one in Settings, Working calendars.</p> : null}
  </>
}

// ------------------------------------------------------------------ CG-W05 working time

/** "Wait": count working time of a shared calendar instead of a length of time or a date. */
export function WorkingTimeFields({ node }: { node: WorkflowNode }) {
  const { readOnly } = useStudioContext()
  const change = useCalendarPatch(node.id)
  const working = cfg<string>(node, 'workingDuration')
  const calendar = cfg<string>(node, 'calendarRef') ?? ''
  const on = working !== undefined
  return (
    <InspectorSection title="Working time">
      <div className="flex items-center gap-2">
        <Checkbox id={`working-${node.id}`} checked={on} disabled={readOnly} onCheckedChange={(checked) => change((c) => {
          if (checked === true) {
            c.workingDuration = typeof c.duration === 'string' ? c.duration : 'P1D'
            delete c.duration; delete c.untilField; delete c.atTime
          } else {
            c.duration = typeof c.workingDuration === 'string' ? c.workingDuration : 'PT1H'
            delete c.workingDuration; delete c.calendarRef
          }
          return c
        })} />
        <Label htmlFor={`working-${node.id}`}>Count working time of a shared calendar</Label>
      </div>
      {on ? <>
        <FormField label="Wait for" required id={`working-duration-${node.id}`} description="Working time, for example P3D for three working days (one working day is the calendar's working hours of one day) or PT4H for four working hours.">
          {(ids) => <CommitInput id={ids.input} value={working ?? ''} disabled={readOnly} aria-describedby={ids.describedBy} onCommit={(v) => change((c) => ({ ...c, workingDuration: v.trim() }))} />}
        </FormField>
        {working ? <DurationPreview text={working} verb="waits" /> : null}
        <FormField label="Working calendar" required id={`working-calendar-${node.id}`}>
          {(ids) => <CalendarSelect id={ids.input} value={calendar} disabled={readOnly} onChange={(key) => change((c) => { if (key) c.calendarRef = key; else delete c.calendarRef; return c })} />}
        </FormField>
      </> : <p className="text-sm text-muted-foreground">Turn this on for waits such as "3 working days": weekends, holidays and closed hours of the calendar do not count.</p>}
    </InspectorSection>
  )
}

/** Wait for a message and check-until calls: count the time limit in working time. */
export function TimeoutCalendarField({ node }: { node: WorkflowNode }) {
  const { readOnly } = useStudioContext()
  const change = useCalendarPatch(node.id)
  const poll = node.type === 'http' ? (cfg<Record<string, unknown>>(node, 'poll') ?? undefined) : undefined
  if (node.type === 'http' && !poll) return null
  if (node.type === 'waitForMessage' && !cfg<string>(node, 'timeoutAfter')) return null
  const value = (node.type === 'http' ? (poll?.timeoutCalendarRef as string | undefined) : cfg<string>(node, 'timeoutCalendarRef')) ?? ''
  const set = (key: string) => change((c) => {
    if (node.type === 'http') {
      const p = { ...(c.poll as Record<string, unknown>) }
      if (key) p.timeoutCalendarRef = key
      else delete p.timeoutCalendarRef
      c.poll = p
    } else if (key) c.timeoutCalendarRef = key
    else delete c.timeoutCalendarRef
    return c
  })
  return (
    <InspectorSection title="Time limit in working time">
      <FormField label="Count the time limit in" id={`timeout-calendar-${node.id}`} description="With a calendar, the time limit counts only its working hours.">
        {(ids) => <CalendarSelect id={ids.input} value={value} disabled={readOnly} onChange={set} allowNone="Calendar time (every hour counts)" />}
      </FormField>
    </InspectorSection>
  )
}

// ------------------------------------------------------------------ CG-W06 reminders

interface Remind {
  every: string
  maxTimes?: number
  adapter: string
  to?: string[]
  toField?: string
  template: { subject?: string; body?: string; text?: string }
  inputMapping?: Record<string, string>
}

const PLACEHOLDERS = '{{reference}}, {{workflow}}, {{step}}, {{since}}, {{count}}, {{maxTimes}}'

/** Reminders while the step waits for someone outside: a message wait, a callback, a check-until call or a worker job. */
export function ReminderFields({ node }: { node: WorkflowNode }) {
  const { store, readOnly } = useStudioContext()
  const { workspace, can } = useWorkspace()
  const def = useStudio((s) => s.definition)
  const adapters = useQuery({
    queryKey: ['ws', workspace.id, 'adapters'],
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/adapters', { params: { path: { workspaceId: workspace.id } } })),
    enabled: can('connection.read'),
  }).data?.items ?? []
  if (node.type === 'http' && !cfg(node, 'poll')) return null
  const remind = (cfg<{ remind?: Remind }>(node, 'whileWaiting') ?? {}).remind
  const set = (next: Remind | undefined) => store.getState().updateNodeConfig(node.id, { whileWaiting: next ? { remind: next } : undefined })
  const change = (p: Partial<Remind>) => remind && set({ ...remind, ...p })
  const usable = adapters.filter((a) => a.kind === 'email' || a.kind === 'teams')
  const adapter = usable.find((a) => a.key === remind?.adapter)
  const teams = adapter?.kind === 'teams'
  const textFields = availableFields(def, node.id).filter((f) => f.ref.startsWith('/input/') && f.type === 'string')
  const domains = ((adapter?.config as { allowedRecipientDomains?: string[] } | undefined)?.allowedRecipientDomains ?? [])
  return (
    <InspectorSection title="Reminders while waiting">
      <div className="flex items-center gap-2">
        <Checkbox id={`remind-${node.id}`} checked={remind !== undefined} disabled={readOnly}
          onCheckedChange={(checked) => set(checked === true ? { every: 'P2D', maxTimes: 3, adapter: usable[0]?.key ?? '', to: [], template: { subject: 'Reminder: {{step}} ({{reference}})', body: 'We are still waiting for {{step}} since {{since}}. This is reminder {{count}} of {{maxTimes}}.' } } : undefined)} />
        <Label htmlFor={`remind-${node.id}`}>Send reminders while waiting</Label>
      </div>
      {remind ? <>
        <div className="grid grid-cols-2 gap-3">
          <FormField label="Every" required id={`remind-every-${node.id}`} description="PT1H to P30D, from the start of the wait.">
            {(ids) => <CommitInput id={ids.input} value={remind.every} disabled={readOnly} aria-describedby={ids.describedBy} onCommit={(v) => change({ every: v.trim() })} />}
          </FormField>
          <FormField label="At most" id={`remind-max-${node.id}`} description="1 to 10 reminders.">
            {(ids) => <Input id={ids.input} type="number" min={1} max={10} value={remind.maxTimes ?? 3} disabled={readOnly} aria-describedby={ids.describedBy} onChange={(e) => change({ maxTimes: Math.max(1, Math.min(10, Number(e.target.value) || 1)) })} />}
          </FormField>
        </div>
        <FormField label="Send through" required id={`remind-adapter-${node.id}`} description="An email or Teams adapter set up by an administrator.">
          {(ids) => <Select id={ids.input} value={remind.adapter} disabled={readOnly} aria-describedby={ids.describedBy} onChange={(e) => {
            const next = usable.find((a) => a.key === e.target.value)
            change(next?.kind === 'teams'
              ? { adapter: e.target.value, to: undefined, toField: undefined, template: { text: remind.template.body ?? remind.template.text ?? '' } }
              : { adapter: e.target.value, template: { subject: remind.template.subject ?? 'Reminder: {{step}}', body: remind.template.body ?? remind.template.text ?? '' } })
          }}>
            <option value="">Choose an adapter</option>
            {usable.map((a) => <option key={a.key} value={a.key}>{a.name} ({a.kind})</option>)}
          </Select>}
        </FormField>
        {!teams ? <>
          <FormField label="Send to" id={`remind-to-mode-${node.id}`}>
            {(ids) => <Select id={ids.input} value={remind.toField !== undefined ? 'field' : 'fixed'} disabled={readOnly} onChange={(e) => change(e.target.value === 'field' ? { to: undefined, toField: textFields[0]?.ref ?? '' } : { toField: undefined, to: [] })}>
              <option value="fixed">Fixed addresses</option>
              <option value="field">The address in a request field</option>
            </Select>}
          </FormField>
          {remind.toField !== undefined ? <FormField label="Request field with the address" required id={`remind-field-${node.id}`} description={domains.length ? `Checked when each reminder is sent: only addresses at ${domains.join(', ')} receive it.` : 'The adapter approves no domains yet, so no address from the request can be used.'}>
            {(ids) => <Select id={ids.input} value={remind.toField} disabled={readOnly} aria-describedby={ids.describedBy} onChange={(e) => change({ toField: e.target.value })}>
              <option value="">Choose a field</option>
              {textFields.map((f) => <option key={f.ref} value={f.ref}>{chipLabel(f)}</option>)}
            </Select>}
          </FormField> : <FormField label="Addresses" required id={`remind-to-${node.id}`} description={domains.length ? `Separate with commas. Approved domains: ${domains.join(', ')}.` : 'Separate with commas.'}>
            {(ids) => <CommitInput id={ids.input} value={(remind.to ?? []).join(', ')} disabled={readOnly} aria-describedby={ids.describedBy} onCommit={(v) => change({ to: v.split(',').map((x) => x.trim()).filter(Boolean) })} />}
          </FormField>}
          <FormField label="Subject" required id={`remind-subject-${node.id}`}>
            {(ids) => <CommitInput id={ids.input} value={remind.template.subject ?? ''} maxLength={200} disabled={readOnly} onCommit={(v) => change({ template: { ...remind.template, subject: v } })} />}
          </FormField>
        </> : null}
        <FormField label={teams ? 'Message' : 'Body'} required id={`remind-body-${node.id}`} description={`You can use ${PLACEHOLDERS}.`}>
          {(ids) => <Textarea id={ids.input} rows={4} value={(teams ? remind.template.text : remind.template.body) ?? ''} disabled={readOnly} aria-describedby={ids.describedBy}
            onChange={(e) => change({ template: teams ? { text: e.target.value } : { ...remind.template, body: e.target.value } })} />}
        </FormField>
        <p className="text-sm text-muted-foreground" data-testid="remind-summary">{`Up to ${remind.maxTimes ?? 3} reminders, ${describeEvery(remind.every)}, while the step waits. Each reminder is sent once, even when a send is retried.`}</p>
      </> : <p className="text-sm text-muted-foreground">For example, chase the vendor every 2 days, 3 times. Reminders stop as soon as the wait ends.</p>}
    </InspectorSection>
  )
}

function describeEvery(text: string): string {
  const m = /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?)?$/.exec(text)
  if (!m) return `every ${text}`
  const parts = [m[1] && `${m[1]} day${m[1] === '1' ? '' : 's'}`, m[2] && `${m[2]} hour${m[2] === '1' ? '' : 's'}`, m[3] && `${m[3]} minute${m[3] === '1' ? '' : 's'}`].filter(Boolean)
  return `every ${parts.join(' ')}`
}

// ------------------------------------------------------------------ CG-W07 wait for the first

/** "Wait for the first": 2 to 5 branches, each with its own path. */
export function WaitForFirstInspector({ node }: { node: WorkflowNode }) {
  const { store, readOnly, connections } = useStudioContext()
  const { latest } = useCalendars()
  const def = useStudio((s) => s.definition)
  const branches = branchesOf(node)
  const targets = def.nodes.filter((n) => n.id !== node.id && n.type !== 'start')
  const keyFields = availableFields(def, node.id, connections).filter((f) => f.type === 'string' || f.type === 'integer')
  const dateFields = availableFields(def, node.id, connections).filter((f) => f.type === 'date')
  const setBranches = (next: FirstBranch[]) => store.getState().apply((d) => syncCalendarPins({
    ...d, nodes: d.nodes.map((n) => n.id === node.id ? { ...n, config: { ...n.config, branches: next } } : n),
  }, latest))
  const change = (index: number, p: Partial<FirstBranch>) => setBranches(branches.map((b, i) => (i === index ? { ...b, ...p } : b)))
  return <>
    <InspectorSection title="Wait for whichever happens first">
      <p className="text-sm text-muted-foreground">The first branch to happen wins; the request takes its path and stops waiting for the others. Two to five branches.</p>
      <ol className="space-y-3" aria-label="Branches">
        {branches.map((b, i) => {
          const idOk = BRANCH_ID.test(b.id) && !branches.some((o, j) => j !== i && o.id === b.id)
          const route = branchTransition(def, node.id, b.id)
          return (
            <li key={i} className="space-y-2 rounded-control border border-border p-3" data-testid={`first-branch-${i}`}>
              <FormField label="What happens" required id={`first-label-${node.id}-${i}`}>
                {(ids) => <CommitInput id={ids.input} value={b.label} maxLength={80} disabled={readOnly} onCommit={(v) => change(i, { label: v.trim() })} />}
              </FormField>
              <FormField label="Kind" id={`first-kind-${node.id}-${i}`}>
                {(ids) => <Select id={ids.input} value={b.kind} disabled={readOnly} onChange={(e) => change(i, branchOfKind(b, e.target.value as BranchKind))}>
                  {(Object.keys(BRANCH_KIND_LABEL) as BranchKind[]).map((k) => <option key={k} value={k}>{BRANCH_KIND_LABEL[k]}</option>)}
                </Select>}
              </FormField>
              {b.kind === 'message' ? <>
                <FormField label="Message name" required id={`first-message-${node.id}-${i}`} error={b.message && !MESSAGE_NAME.test(b.message) ? 'Use lowercase letters and digits joined with dashes.' : undefined}>
                  {(ids) => <CommitInput id={ids.input} value={b.message ?? ''} maxLength={64} disabled={readOnly} onCommit={(v) => change(i, { message: v.trim() })} />}
                </FormField>
                <FormField label="Which field identifies the request?" required id={`first-correlation-${node.id}-${i}`}>
                  {(ids) => <Select id={ids.input} value={b.correlationField ?? ''} disabled={readOnly} onChange={(e) => change(i, { correlationField: e.target.value })}>
                    <option value="">Choose a field</option>
                    {keyFields.map((f) => <option key={f.ref} value={f.ref}>{chipLabel(f)}</option>)}
                  </Select>}
                </FormField>
              </> : null}
              {b.kind === 'signal' ? <FormField label="Signal" required id={`first-signal-${node.id}-${i}`}>
                {(ids) => <CommitInput id={ids.input} value={b.signal ?? ''} disabled={readOnly} onCommit={(v) => change(i, { signal: v.trim() })} />}
              </FormField> : null}
              {b.kind === 'callback' ? <FormField label="Token is valid for" required id={`first-token-${node.id}-${i}`} description="PT1M to P30D. An operator issues the token on the request page.">
                {(ids) => <CommitInput id={ids.input} value={b.tokenValidFor ?? ''} disabled={readOnly} aria-describedby={ids.describedBy} onCommit={(v) => change(i, { tokenValidFor: v.trim() })} />}
              </FormField> : null}
              {b.kind === 'timer' ? <TimerBranch node={node} b={b} index={i} dateFields={dateFields.map((f) => ({ ref: f.ref, label: chipLabel(f) }))} change={(p) => change(i, p)} /> : null}
              {b.kind === 'check' ? <p className="text-sm text-muted-foreground">Checks a read-only operation now and then, like a safety-net check. Edit the check in the Source tab (connection, operation, mapping and done condition); its mapped results fill the values below.</p> : null}
              {b.kind === 'message' || b.kind === 'callback' || b.kind === 'check' ? <BranchValues b={b} index={i} nodeId={node.id} change={(p) => change(i, p)} /> : null}
              <FormField label="Then go to" required id={`first-route-${node.id}-${i}`}>
                {(ids) => <Select id={ids.input} value={route?.to ?? ''} disabled={readOnly} onChange={(e) => store.getState().apply((d) => setBranchRoute(d, node.id, b.id, e.target.value))}>
                  <option value="">Choose a step</option>
                  {targets.map((n) => <option key={n.id} value={n.id}>{n.label || n.id}</option>)}
                </Select>}
              </FormField>
              <div className="flex items-end justify-between gap-3">
                <FormField label="Branch ID" id={`first-id-${node.id}-${i}`} error={idOk ? undefined : 'A short name: a lowercase letter, then letters and digits, unique in this step.'}>
                  {(ids) => <CommitInput id={ids.input} value={b.id} maxLength={20} disabled={readOnly} aria-invalid={!idOk || undefined} onCommit={(v) => store.getState().apply((d) => renameBranch(d, node.id, b.id, v.trim()))} />}
                </FormField>
                {readOnly || branches.length <= 2 ? null : <Button variant="secondary" aria-label={`Remove the branch ${b.label || b.id}`} onClick={() => store.getState().apply((d) => syncCalendarPins(removeBranch(d, node.id, b.id), latest))}><Trash2 className="size-4" aria-hidden="true" /> Remove</Button>}
              </div>
            </li>
          )
        })}
      </ol>
      {readOnly || branches.length >= 5 ? null : <Button variant="secondary" onClick={() => {
        const id = newBranchId(node)
        setBranches([...branches, branchOfKind({ id, label: `Branch ${branches.length + 1}` }, 'signal')])
      }}><Plus className="size-4" aria-hidden="true" /> Add a branch</Button>}
    </InspectorSection>
  </>
}

function TimerBranch({ node, b, index, dateFields, change }: { node: WorkflowNode; b: FirstBranch; index: number; dateFields: Array<{ ref: string; label: string }>; change: (p: Partial<FirstBranch>) => void }) {
  const { readOnly } = useStudioContext()
  const mode = b.untilField !== undefined ? 'date' : b.workingDuration !== undefined ? 'working' : 'after'
  const clear = { after: undefined, untilField: undefined, atTime: undefined, workingDuration: undefined, calendarRef: undefined }
  return <>
    <FormField label="When" id={`first-timer-mode-${node.id}-${index}`}>
      {(ids) => <Select id={ids.input} value={mode} disabled={readOnly} onChange={(e) => change(e.target.value === 'date' ? { ...clear, untilField: dateFields[0]?.ref ?? '' }
        : e.target.value === 'working' ? { ...clear, workingDuration: 'P3D', calendarRef: '' } : { ...clear, after: 'P1D' })}>
        <option value="after">After a length of time</option>
        <option value="working">After working time of a calendar</option>
        <option value="date">On a date from the request</option>
      </Select>}
    </FormField>
    {mode === 'after' ? <FormField label="After" required id={`first-after-${node.id}-${index}`}>
      {(ids) => <CommitInput id={ids.input} value={b.after ?? ''} disabled={readOnly} onCommit={(v) => change({ after: v.trim() })} />}
    </FormField> : null}
    {mode === 'after' && b.after ? <DurationPreview text={b.after} verb="waits" /> : null}
    {mode === 'working' ? <>
      <FormField label="Working time" required id={`first-working-${node.id}-${index}`}>
        {(ids) => <CommitInput id={ids.input} value={b.workingDuration ?? ''} disabled={readOnly} onCommit={(v) => change({ workingDuration: v.trim() })} />}
      </FormField>
      <FormField label="Working calendar" required id={`first-calendar-${node.id}-${index}`}>
        {(ids) => <CalendarSelect id={ids.input} value={b.calendarRef ?? ''} disabled={readOnly} onChange={(key) => change({ calendarRef: key || undefined })} />}
      </FormField>
    </> : null}
    {mode === 'date' ? <FormField label="Date field" required id={`first-date-${node.id}-${index}`}>
      {(ids) => <Select id={ids.input} value={b.untilField ?? ''} disabled={readOnly} onChange={(e) => change({ untilField: e.target.value })}>
        <option value="">Choose a date field</option>
        {dateFields.map((f) => <option key={f.ref} value={f.ref}>{f.label}</option>)}
      </Select>}
    </FormField> : null}
  </>
}

function BranchValues({ b, index, nodeId, change }: { b: FirstBranch; index: number; nodeId: string; change: (p: Partial<FirstBranch>) => void }) {
  const { readOnly } = useStudioContext()
  const payload = b.payload ?? []
  return (
    <div className="space-y-2">
      <p className="text-sm font-medium">Values it brings</p>
      {payload.length === 0 ? <p className="text-sm text-muted-foreground">No values.</p> : null}
      {payload.map((f, j) => (
        <div key={j} className="flex items-end gap-2">
          <FormField label="Name" id={`first-value-${nodeId}-${index}-${j}`}>
            {(ids) => <CommitInput id={ids.input} value={f.name} maxLength={64} disabled={readOnly} onCommit={(v) => change({ payload: payload.map((x, k) => k === j ? { ...x, name: v.trim() } : x) })} />}
          </FormField>
          <div className="flex items-center gap-2 pb-2">
            <Checkbox id={`first-value-req-${nodeId}-${index}-${j}`} checked={f.required === true} disabled={readOnly} onCheckedChange={(c) => change({ payload: payload.map((x, k) => k === j ? { ...x, required: c === true ? true : undefined } : x) })} />
            <Label htmlFor={`first-value-req-${nodeId}-${index}-${j}`}>Required</Label>
          </div>
          {readOnly ? null : <Button variant="secondary" aria-label={`Remove the value ${f.name}`} onClick={() => change({ payload: payload.filter((_, k) => k !== j) })}><Trash2 className="size-4" aria-hidden="true" /></Button>}
        </div>
      ))}
      {readOnly || payload.length >= 10 ? null : <Button variant="secondary" onClick={() => change({ payload: [...payload, { name: `value${payload.length + 1}`, type: 'string' }] })}><Plus className="size-4" aria-hidden="true" /> Add a value</Button>}
    </div>
  )
}

// ------------------------------------------------------------------ CG-E08 message handlers

/** The message that starts a handler, the request field that identifies the request and the values it may carry. */
export function MessageTriggerFields({ scope, patch }: { scope: SubprocessDef; patch: (p: Partial<SubprocessDef>) => void }) {
  const { readOnly, connections } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const trigger = scope.trigger ?? { type: 'message' as const }
  const fields = availableFields(def, def.nodes.find((n) => n.type === 'start')?.id ?? '', connections)
  const inputs = fields.filter((f) => f.ref.startsWith('/input/') && (f.type === 'string' || f.type === 'integer'))
  const set = (p: Partial<NonNullable<SubprocessDef['trigger']>>) => patch({ trigger: { ...trigger, type: 'message', ...p } })
  const ok = !trigger.message || MESSAGE_NAME.test(trigger.message)
  return <>
    <FormField id={`scope-message-${scope.id}`} label="Message" required error={ok ? undefined : 'Use lowercase letters and digits joined with dashes, like customer-cancelled.'} description="For example customer-cancelled. It reaches this request when it carries the identifying value.">
      {(ids) => <CommitInput id={ids.input} value={trigger.message ?? ''} maxLength={64} disabled={readOnly} aria-describedby={ids.describedBy} onCommit={(v) => set({ message: v.trim() })} />}
    </FormField>
    <FormField id={`scope-correlation-${scope.id}`} label="Which request field identifies the request?" required>
      {(ids) => <Select id={ids.input} value={trigger.correlationField ?? ''} disabled={readOnly} onChange={(e) => set({ correlationField: e.target.value })}>
        <option value="">Choose a field</option>
        {inputs.map((f) => <option key={f.ref} value={f.ref}>{chipLabel(f)}</option>)}
      </Select>}
    </FormField>
  </>
}
