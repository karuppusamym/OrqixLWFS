import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { downloadBlob } from '@/lib/download'
import { useGroups, useStartForm, useWorkflows } from '@/api/queries'
import { Button } from '@/components/ui/button'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Alert } from '@/components/ui/alert'
import { CONDITION_OPERATORS, KIND_LABEL, WORKFLOW_RESULT_SOURCES, caseItems, caseModelCaseTasks, caseModelDecisionTasks, caseModelWorkflowTasks, caseModelXml, durationMinutes, entrySources, isListener, occurs, type CaseConditionClause, type CaseFileEvent, type CaseItemKind, type CaseModelItem, type CaseModelOptions, type ConditionOperator } from './caseModel'
import { getSharedAsset, listSharedAssets } from '@/api/endpoints'
import { listCaseDefinitions } from './api'
import { findItem, fitStages, parentOf, placement } from './caseCanvasOps'
import { importCaseModel, type ImportDiagnostic } from './cmmnImport'
import { CaseCanvas } from './CaseCanvas'
import { CmmnPreview } from './CmmnPreview'
import type { DataField } from './CaseDataFields'

export type WorkflowTaskMapping = ReturnType<typeof caseModelWorkflowTasks>[number]
export type CaseTaskMapping = ReturnType<typeof caseModelCaseTasks>[number]
export type DecisionTaskMapping = ReturnType<typeof caseModelDecisionTasks>[number]
type Group = { key: string; name: string }
type Change = (items: CaseModelItem[]) => CaseModelItem[]

const ADDABLE: CaseItemKind[] = ['humanTask', 'processTask', 'caseTask', 'decisionTask', 'stage', 'milestone', 'userEventListener', 'timerEventListener']
const ADD_LABEL: Record<CaseItemKind, string> = { humanTask: 'human task', processTask: 'workflow task', caseTask: 'case task', decisionTask: 'decision task', stage: 'stage', milestone: 'milestone', userEventListener: 'user event', timerEventListener: 'timer' }

function collectFieldRefs(node: unknown, into: Set<string>): void {
  if (Array.isArray(node)) { for (const child of node) collectFieldRefs(child, into); return }
  if (!node || typeof node !== 'object') return
  for (const [key, value] of Object.entries(node)) {
    if (key === 'fieldRef' && typeof value === 'string') into.add(value)
    else if (key !== 'then') collectFieldRefs(value, into)
  }
}

/** The decision task's shared decision table and its mappings: table field from case field, case field from a table output. */
function DecisionMapping({ ws, item, dataFields, onPatch }: { ws: string; item: CaseModelItem; dataFields: DataField[]; onPatch: (patch: Partial<CaseModelItem>) => void }) {
  const assets = useQuery({ queryKey: ['ws', ws, 'shared-decisions'], queryFn: () => listSharedAssets(ws, { kind: 'decision' }) })
  const ref = item.decision ?? { key: '', input: {}, result: {} }
  const detail = useQuery({ queryKey: ['ws', ws, 'shared-decision', ref.key], queryFn: () => getSharedAsset(ws, 'decision', ref.key), enabled: !!ref.key })
  const latest = detail.data?.versions.find(version => version.version === detail.data?.asset.latestVersion)?.content
  const fieldRefs = new Set<string>(); collectFieldRefs(latest?.rows ?? [], fieldRefs)
  const outputs = new Set<string>([...(latest?.rows ?? []).flatMap(row => Object.keys((row as { then?: Record<string, unknown> }).then ?? {})), ...Object.keys(latest?.default ?? {})])
  const set = (part: 'input' | 'result', key: string, value: string) => {
    const next = { ...ref[part] }
    if (value) next[key] = value; else delete next[key]
    onPatch({ decision: { ...ref, [part]: next } })
  }
  return <div className="space-y-3">
    <div><Label htmlFor={`case-model-${item.id}-decision`}>Decision for {item.id}</Label>
      <Select id={`case-model-${item.id}-decision`} value={ref.key} onChange={event => onPatch({ decision: { key: event.target.value, input: {}, result: {} } })}>
        <option value="">Choose a shared decision</option>
        {(assets.data?.items ?? []).filter(asset => !asset.archived).map(asset => <option key={asset.key} value={asset.key}>{asset.name} (version {asset.latestVersion})</option>)}
      </Select>
      <p className="text-xs text-muted-foreground">The latest version is pinned when this definition is created. The table is evaluated as soon as the task starts and its result is written into case data.</p></div>
    {latest ? <div className="space-y-3 rounded border border-border p-3">
      <p className="text-sm font-semibold">Values the table reads</p>
      {[...fieldRefs].map(fieldRef => <div key={fieldRef}><Label htmlFor={`case-model-${item.id}-din-${fieldRef}`}>{fieldRef.replace('/input/', '')} for {item.id}</Label>
        <Select id={`case-model-${item.id}-din-${fieldRef}`} value={ref.input[fieldRef] ?? ''} onChange={event => set('input', fieldRef, event.target.value)}>
          <option value="">Choose a case data field</option>{dataFields.map(data => <option key={data.key} value={data.key}>Case data: {data.label}</option>)}
        </Select></div>)}
      <p className="text-sm font-semibold">Write the table's result into case data</p>
      {dataFields.map(data => <div key={data.key}><Label htmlFor={`case-model-${item.id}-dout-${data.key}`}>Case data {data.label} from {item.id}</Label>
        <Select id={`case-model-${item.id}-dout-${data.key}`} value={ref.result[data.key] ?? ''} onChange={event => set('result', data.key, event.target.value)}>
          <option value="">Not written</option>{[...outputs].map(output => <option key={output} value={output}>Result: {output}</option>)}
        </Select></div>)}
    </div> : null}
  </div>
}

/** Case file item events on the entry rule: a case data field gets its first value, or changes. */
function DataEventFields({ item, dataFields, onPatch }: { item: CaseModelItem; dataFields: DataField[]; onPatch: (patch: Partial<CaseModelItem>) => void }) {
  const events = item.onData ?? []
  const set = (next: CaseFileEvent[]) => onPatch({ onData: next.length ? next : undefined, ...(next.length ? {} : entrySources(item).length > 1 ? {} : { anyOf: undefined }) })
  if (!dataFields.length) return null
  return <fieldset className="space-y-2 rounded border border-border p-2"><legend className="px-1 text-sm font-semibold">Start when case data changes ({item.id})</legend>
    {events.map((event, index) => <div key={index} className="grid gap-2 sm:grid-cols-[1fr_1fr_auto]">
      <Select aria-label={`Data event field ${index + 1} for ${item.id}`} value={event.field} onChange={e => set(events.map((x, i) => i === index ? { ...x, field: e.target.value } : x))}>{dataFields.map(data => <option key={data.key} value={data.key}>{data.label}</option>)}</Select>
      <Select aria-label={`Data event ${index + 1} for ${item.id}`} value={event.event} onChange={e => set(events.map((x, i) => i === index ? { ...x, event: e.target.value as CaseFileEvent['event'] } : x))}><option value="create">gets its first value</option><option value="update">changes</option></Select>
      <Button type="button" variant="ghost" onClick={() => set(events.filter((_, i) => i !== index))}>Remove data event {index + 1}</Button>
    </div>)}
    <Button type="button" variant="secondary" disabled={events.length >= 10} onClick={() => set([...events, { field: dataFields[0]!.key, event: 'update' }])}>Add data event to {item.id}</Button>
  </fieldset>
}
const OPERATOR_LABEL: Record<ConditionOperator, string> = { '==': 'is', '!=': 'is not', '<': 'is less than', '<=': 'is at most', '>': 'is more than', '>=': 'is at least' }

/** The case task's child case and its mappings: child field from case field, case field from child field or outcome. */
function ChildCaseMapping({ ws, item, dataFields, onPatch }: { ws: string; item: CaseModelItem; dataFields: DataField[]; onPatch: (patch: Partial<CaseModelItem>) => void }) {
  const definitions = useQuery({ queryKey: ['ws', ws, 'case-definitions'], queryFn: () => listCaseDefinitions(ws) })
  const latest = new Map<string, NonNullable<typeof definitions.data>[number]>()
  for (const definition of definitions.data ?? []) if (!latest.has(definition.key) || latest.get(definition.key)!.version < definition.version) latest.set(definition.key, definition)
  const ref = item.childCase ?? { key: '', input: {}, result: {} }
  const chosen = latest.get(ref.key)
  const set = (part: 'input' | 'result', key: string, value: string) => {
    const next = { ...ref[part] }
    if (value) next[key] = value; else delete next[key]
    onPatch({ childCase: { ...ref, [part]: next } })
  }
  return <div className="space-y-3">
    <div><Label htmlFor={`case-model-${item.id}-child`}>Case for {item.id}</Label>
      <Select id={`case-model-${item.id}-child`} value={ref.key} onChange={event => onPatch({ childCase: { key: event.target.value, input: {}, result: {} } })}>
        <option value="">Choose a case definition</option>
        {[...latest.values()].map(definition => <option key={definition.key} value={definition.key}>{definition.name} (version {definition.version})</option>)}
        {ref.key && !chosen ? <option value={ref.key}>{ref.key} (not found)</option> : null}
      </Select>
      <p className="text-xs text-muted-foreground">The version that is current when this definition is created is pinned. The child case starts for whoever started this case; when it completes, this task completes.</p></div>
    {chosen ? <div className="space-y-3 rounded border border-border p-3">
      <p className="text-sm font-semibold">Data of the child case</p>
      {(chosen.dataFields ?? []).length ? (chosen.dataFields ?? []).map(field => <div key={field.key}>
        <Label htmlFor={`case-model-${item.id}-cin-${field.key}`}>{field.label}{field.required ? ' (required)' : ''} for {item.id}</Label>
        <Select id={`case-model-${item.id}-cin-${field.key}`} value={ref.input[field.key] ?? ''} onChange={event => set('input', field.key, event.target.value)}>
          <option value="">Not filled</option>{dataFields.map(data => <option key={data.key} value={data.key}>Case data: {data.label}</option>)}
        </Select></div>) : <p className="text-sm text-muted-foreground">The child case has no data fields.</p>}
      {dataFields.length ? <>
        <p className="text-sm font-semibold">Write the child case's result into case data</p>
        {dataFields.map(data => <div key={data.key}><Label htmlFor={`case-model-${item.id}-cout-${data.key}`}>Case data {data.label} from {item.id}</Label>
          <Select id={`case-model-${item.id}-cout-${data.key}`} value={ref.result[data.key] ?? ''} onChange={event => set('result', data.key, event.target.value)}>
            <option value="">Not written</option><option value="outcome">Outcome</option>{(chosen.dataFields ?? []).map(field => <option key={field.key} value={field.key}>Child data: {field.label}</option>)}
          </Select></div>)}
      </> : null}
    </div> : null}
  </div>
}

/** Timer duration as a number and a unit, stored as an ISO-8601 duration. */
function TimerField({ item, onPatch }: { item: CaseModelItem; onPatch: (patch: Partial<CaseModelItem>) => void }) {
  const atDate = !!item.timer && item.timer.includes('T') && !item.timer.startsWith('P')
  if (atDate) return <div className="space-y-2">
    <div><Label htmlFor={`case-model-${item.id}-at`}>Fires at for {item.id}</Label><Input id={`case-model-${item.id}-at`} value={item.timer} onChange={event => onPatch({ timer: event.target.value.trim() })} /></div>
    <p className="text-xs text-muted-foreground">A date and time with an offset, for example 2026-12-01T09:00:00Z; a minute to five years ahead.</p>
    <Button type="button" variant="ghost" onClick={() => onPatch({ timer: 'PT1H' })}>Wait a time instead ({item.id})</Button>
  </div>
  const minutes = durationMinutes(item.timer ?? '') ?? 60
  const unit = minutes % 1440 === 0 ? 'days' : minutes % 60 === 0 ? 'hours' : 'minutes'
  const amount = unit === 'days' ? minutes / 1440 : unit === 'hours' ? minutes / 60 : minutes
  const set = (value: number, next: string) => onPatch({ timer: next === 'days' ? `P${value}D` : next === 'hours' ? `PT${value}H` : `PT${value}M` })
  return <div className="grid gap-2 sm:grid-cols-2">
    <div><Label htmlFor={`case-model-${item.id}-wait`}>Wait for {item.id}</Label><Input id={`case-model-${item.id}-wait`} type="number" min={1} max={unit === 'days' ? 366 : 9999} value={amount} onChange={event => set(Math.max(1, Math.round(Number(event.target.value) || 1)), unit)} /></div>
    <div><Label htmlFor={`case-model-${item.id}-unit`}>Unit for {item.id}</Label><Select id={`case-model-${item.id}-unit`} value={unit} onChange={event => set(amount, event.target.value)}><option value="minutes">Minutes</option><option value="hours">Hours</option><option value="days">Days</option></Select></div>
    <p className="text-xs text-muted-foreground sm:col-span-2">Counted from when its stage starts. When it fires, items that start after it become available.</p>
    <Button type="button" variant="ghost" className="sm:col-span-2 justify-self-start" onClick={() => onPatch({ timer: new Date(Date.now() + 7 * 86_400_000).toISOString().replace(/\.\d{3}Z$/, 'Z') })}>Fire at a date instead ({item.id})</Button>
  </div>
}

/** "Only when case data …": the CMMN if-part of the entry rule, built from field comparisons. */
function ConditionFields({ item, dataFields, onPatch }: { item: CaseModelItem; dataFields: DataField[]; onPatch: (patch: Partial<CaseModelItem>) => void }) {
  const condition = item.when
  const set = (clauses: CaseConditionClause[], join = condition?.join ?? 'all') => onPatch({ when: clauses.length ? { join, clauses } : undefined })
  const typed = (field: string, raw: string): CaseConditionClause['value'] => {
    const type = dataFields.find(data => data.key === field)?.type
    if (type === 'number' || type === 'integer') return Number(raw) || 0
    if (type === 'boolean') return raw === 'true'
    return raw
  }
  if (!dataFields.length) return <p className="text-xs text-muted-foreground">Add case data fields to make this item depend on case data.</p>
  return <fieldset className="space-y-2 rounded border border-border p-2"><legend className="px-1 text-sm font-semibold">Only when case data matches ({item.id})</legend>
    {condition?.clauses.length && condition.clauses.length > 1 ? <div><Label htmlFor={`case-model-${item.id}-join`}>Combine comparisons for {item.id}</Label><Select id={`case-model-${item.id}-join`} value={condition.join} onChange={event => set(condition.clauses, event.target.value as 'all' | 'any')}><option value="all">All must hold</option><option value="any">Any may hold</option></Select></div> : null}
    {(condition?.clauses ?? []).map((clause, index) => <div key={index} className="grid gap-2 sm:grid-cols-[1fr_auto_1fr_auto]">
      <Select aria-label={`Field ${index + 1} for ${item.id}`} value={clause.field} onChange={event => set(condition!.clauses.map((c, i) => i === index ? { ...c, field: event.target.value, value: typed(event.target.value, String(c.value)) } : c))}>{dataFields.map(data => <option key={data.key} value={data.key}>{data.label}</option>)}</Select>
      <Select aria-label={`Comparison ${index + 1} for ${item.id}`} value={clause.op} onChange={event => set(condition!.clauses.map((c, i) => i === index ? { ...c, op: event.target.value as ConditionOperator } : c))}>{CONDITION_OPERATORS.map(op => <option key={op} value={op}>{OPERATOR_LABEL[op]}</option>)}</Select>
      <Input aria-label={`Value ${index + 1} for ${item.id}`} value={String(clause.value)} maxLength={120} onChange={event => set(condition!.clauses.map((c, i) => i === index ? { ...c, value: typed(c.field, event.target.value) } : c))} />
      <Button type="button" variant="ghost" onClick={() => set(condition!.clauses.filter((_, i) => i !== index))}>Remove comparison {index + 1}</Button>
    </div>)}
    <Button type="button" variant="secondary" disabled={(condition?.clauses.length ?? 0) >= 10} onClick={() => set([...(condition?.clauses ?? []), { field: dataFields[0]!.key, op: '==', value: typed(dataFields[0]!.key, '') }])}>Add data comparison to {item.id}</Button>
    <p className="text-xs text-muted-foreground">{item.after ? 'Checked when the start-after event happens.' : 'The item starts as soon as case data makes this true.'} Only fields and literal values are compared.</p>
  </fieldset>
}

function patchItem(items: CaseModelItem[], id: string, patch: Partial<CaseModelItem>): CaseModelItem[] {
  return items.map(item => item.id === id ? { ...item, ...patch } : item.children ? { ...item, children: patchItem(item.children, id, patch) } : item)
}

/** Workflow request fields of the chosen workflow, mapped from case data fields; results mapped back. */
function WorkflowMapping({ ws, item, dataFields, workflowId, onPatch }: { ws: string; item: CaseModelItem; dataFields: DataField[]; workflowId: string; onPatch: (patch: Partial<CaseModelItem>) => void }) {
  const form = useStartForm(ws, workflowId)
  const schema = form.data?.inputSchema as { properties?: Record<string, { title?: string; type?: string }>; required?: string[] } | undefined
  const fields = Object.entries(schema?.properties ?? {})
  const workflow = item.workflow!
  const set = (part: 'input' | 'result', key: string, value: string) => {
    const next = { ...workflow[part] }
    if (value) next[key] = value; else delete next[key]
    onPatch({ workflow: { ...workflow, [part]: next } })
  }
  return <div className="space-y-3 rounded border border-border p-3">
    <p className="text-sm font-semibold">Request fields of the workflow</p>
    {form.isPending ? <p className="text-sm text-muted-foreground">Loading the request form…</p> : null}
    {form.isError ? <p className="text-sm text-muted-foreground">The request form could not be loaded; the server checks the mapping when you create the definition.</p> : null}
    {!dataFields.length ? <p className="text-sm text-muted-foreground">Add case data fields below to fill the workflow's request from case data.</p> : null}
    {fields.map(([field, spec]) => <div key={field}>
      <Label htmlFor={`case-model-${item.id}-in-${field}`}>{spec.title ?? field}{schema?.required?.includes(field) ? ' (required)' : ''} for {item.id}</Label>
      <Select id={`case-model-${item.id}-in-${field}`} value={workflow.input[field] ?? ''} onChange={event => set('input', field, event.target.value)}>
        <option value="">Not filled</option>{dataFields.map(data => <option key={data.key} value={data.key}>Case data: {data.label}</option>)}
      </Select>
    </div>)}
    {dataFields.length ? <>
      <p className="text-sm font-semibold">Write the workflow's result into case data</p>
      {dataFields.map(data => <div key={data.key} className="grid gap-2 sm:grid-cols-2">
        <div><Label htmlFor={`case-model-${item.id}-out-${data.key}`}>Case data {data.label} from {item.id}</Label>
          <Select id={`case-model-${item.id}-out-${data.key}`} value={WORKFLOW_RESULT_SOURCES.includes(workflow.result[data.key] as never) ? workflow.result[data.key] : workflow.result[data.key] ? 'field' : ''} onChange={event => set('result', data.key, event.target.value === 'field' ? workflow.result[data.key] && !WORKFLOW_RESULT_SOURCES.includes(workflow.result[data.key] as never) ? workflow.result[data.key]! : 'result' : event.target.value)}>
            <option value="">Not written</option><option value="outcome">Outcome</option><option value="outcomeKind">Outcome kind</option><option value="reference">Request reference</option><option value="field">A result field…</option>
          </Select></div>
        {workflow.result[data.key] && !WORKFLOW_RESULT_SOURCES.includes(workflow.result[data.key] as never) ? <div><Label htmlFor={`case-model-${item.id}-outf-${data.key}`}>Result field name for {data.label}</Label>
          <Input id={`case-model-${item.id}-outf-${data.key}`} value={workflow.result[data.key]} maxLength={64} onChange={event => set('result', data.key, event.target.value.trim() || 'result')} /></div> : null}
      </div>)}
    </> : null}
  </div>
}

/** Every editable property of one plan item; shared by the form view and the canvas inspector. */
function ItemFields({ ws, item, siblings, groups, dataFields, onPatch }: { ws: string; item: CaseModelItem; siblings: CaseModelItem[]; groups: Group[]; dataFields: DataField[]; onPatch: (patch: Partial<CaseModelItem>) => void }) {
  const workflows = useWorkflows(ws, {}, item.kind === 'processTask')
  const published = (workflows.data?.pages ?? []).flatMap(page => page.items).filter(workflow => workflow.activeRelease)
  const chosen = published.find(workflow => workflow.key === item.workflow?.key)
  const others = siblings.filter(other => other.id !== item.id && !other.discretionary)
  const sources = entrySources(item)
  const canDiscretionary = item.kind === 'humanTask' || item.kind === 'processTask' || item.kind === 'caseTask' || item.kind === 'decisionTask'
  return <div className="space-y-3">
    <div><Label htmlFor={`case-model-${item.id}-label`}>Name for {item.id}</Label><Input id={`case-model-${item.id}-label`} value={item.label} maxLength={120} onChange={event => onPatch({ label: event.target.value })} /></div>
    {item.kind === 'caseTask' ? <ChildCaseMapping ws={ws} item={item} dataFields={dataFields} onPatch={onPatch} /> : null}
    {item.kind === 'decisionTask' ? <DecisionMapping ws={ws} item={item} dataFields={dataFields} onPatch={onPatch} /> : null}
    {item.kind === 'timerEventListener' ? <TimerField item={item} onPatch={onPatch} /> : null}
    {item.kind === 'humanTask' || item.kind === 'userEventListener' ? <div><Label htmlFor={`case-model-${item.id}-group`}>Eligible group for {item.id}</Label><Select id={`case-model-${item.id}-group`} value={item.group ?? ''} onChange={event => onPatch({ group: event.target.value })}><option value="">Choose a group</option>{groups.map(group => <option key={group.key} value={group.key}>{group.name}</option>)}</Select></div> : null}
    {item.kind === 'processTask' ? <>
      <div><Label htmlFor={`case-model-${item.id}-workflow`}>Workflow for {item.id}</Label>
        <Select id={`case-model-${item.id}-workflow`} value={item.workflow?.key ?? ''} onChange={event => onPatch({ workflow: { key: event.target.value, input: {}, result: {} } })}>
          <option value="">Choose a published workflow</option>
          {published.map(workflow => <option key={workflow.id} value={workflow.key}>{workflow.name} (release {workflow.activeRelease!.releaseNumber})</option>)}
          {item.workflow?.key && !chosen ? <option value={item.workflow.key}>{item.workflow.key} (not found among published workflows)</option> : null}
        </Select>
        <p className="text-xs text-muted-foreground">The release that is active when the definition is created is pinned. The workflow runs on behalf of whoever starts the case and finishes the task when it ends.</p></div>
      {chosen && item.workflow ? <WorkflowMapping ws={ws} item={item} dataFields={dataFields} workflowId={chosen.id} onPatch={onPatch} /> : null}
    </> : null}
    {canDiscretionary ? <label className="flex items-center gap-2"><input type="checkbox" checked={!!item.discretionary} onChange={event => onPatch(event.target.checked ? { discretionary: true, planners: item.planners ?? [], after: undefined, afterAll: undefined, exitAfter: undefined, repetitions: undefined, when: undefined } : { discretionary: undefined, planners: undefined })} />Discretionary {item.id}: people decide during the case whether it is needed</label> : null}
    {item.discretionary ? <fieldset className="space-y-1"><legend className="text-sm font-semibold">Who may plan {item.label}</legend>
      {groups.map(group => <label key={group.key} className="flex items-center gap-2"><input type="checkbox" checked={item.planners?.includes(group.key) ?? false} onChange={event => onPatch({ planners: event.target.checked ? [...(item.planners ?? []), group.key] : (item.planners ?? []).filter(value => value !== group.key) })} />{group.name}</label>)}
    </fieldset> : null}
    {!isListener(item) && !item.discretionary ? <ConditionFields item={item} dataFields={dataFields} onPatch={onPatch} /> : null}
    {!isListener(item) && !item.discretionary ? <DataEventFields item={item} dataFields={dataFields} onPatch={onPatch} /> : null}
    {!isListener(item) && !item.discretionary && entrySources(item).length + (item.onData?.length ?? 0) > 1 ? <label className="flex items-center gap-2"><input type="checkbox" checked={!!item.anyOf} onChange={event => onPatch({ anyOf: event.target.checked || undefined })} />Start after any one of these {item.id} (instead of all of them)</label> : null}
    {!isListener(item) && !item.discretionary ? <div><Label htmlFor={`case-model-${item.id}-after`}>Start after for {item.id}</Label><Select id={`case-model-${item.id}-after`} value={item.after ?? ''} onChange={event => onPatch({ after: event.target.value || undefined, afterAll: event.target.value ? item.afterAll?.filter(value => value !== event.target.value) : undefined, ...(event.target.value || item.kind !== 'milestone' ? {} : { repetitions: undefined }) })}><option value="">Available when its stage starts</option>{others.map(source => <option key={source.id} value={source.id}>{source.label} {occurs(source) ? 'occurs' : 'completes'}</option>)}</Select>
      {sources.length > 1 ? <p className="text-xs text-muted-foreground">Also waits for: {sources.slice(1).map(id => siblings.find(other => other.id === id)?.label ?? id).join(', ')}. <button type="button" className="underline" onClick={() => onPatch({ afterAll: undefined })}>Wait only for the first</button></p> : null}</div> : null}
    {!isListener(item) && item.kind !== 'milestone' && !item.discretionary ? <div><Label htmlFor={`case-model-${item.id}-exit`}>Ends when for {item.id}</Label><Select id={`case-model-${item.id}-exit`} value={item.exitAfter ?? ''} disabled={!!item.repetitions} onChange={event => onPatch({ exitAfter: event.target.value || undefined })}><option value="">Not ended by another item</option>{others.map(source => <option key={source.id} value={source.id}>{source.label} {occurs(source) ? 'occurs' : 'completes'}</option>)}</Select></div> : null}
    {!isListener(item) ? <label className="flex items-center gap-2"><input type="checkbox" checked={!!item.required} onChange={event => onPatch({ required: event.target.checked })} />Required work {item.id}</label> : null}
    {item.kind !== 'milestone' && !isListener(item) && item.kind !== 'processTask' && item.kind !== 'caseTask' && item.kind !== 'decisionTask' ? <label className="flex items-center gap-2"><input type="checkbox" checked={!!item.manual} onChange={event => onPatch({ manual: event.target.checked })} />Manual activation {item.id}</label> : null}
    {!isListener(item) && !item.discretionary ? <div><Label htmlFor={`case-model-${item.id}-repeat`}>Executions for {item.id}</Label><Select id={`case-model-${item.id}-repeat`} value={item.repetitions ?? 1} disabled={!!item.exitAfter || item.kind === 'milestone' && !item.after && !item.when} onChange={event => onPatch({ repetitions: Number(event.target.value) === 1 ? undefined : Number(event.target.value) })}>{Array.from({ length: 30 }, (_, i) => <option key={i + 1} value={i + 1}>{i + 1}</option>)}</Select><p className="text-xs text-muted-foreground">{item.after ? 'Repeats each time its start-after rule is met again, up to this many times.' : 'Repeats one after another, up to this many times. Each occurrence fires its own completion event.'}</p></div> : null}
  </div>
}

export interface CaseModelDesignerProps {
  ws: string
  caseKey: string
  name: string
  disabled: boolean
  dataFields?: DataField[]
  /** The generated CMMN and the workflow mappings it needs, or null after an edit invalidated them. */
  onApply: (xml: string | null, workflowTasks?: WorkflowTaskMapping[], caseTasks?: CaseTaskMapping[], decisionTasks?: DecisionTaskMapping[]) => void
  /** Called after a file import with the case key and name it contained. */
  onImported?: (key: string, name: string) => void
  /** S126: the model to open with (a saved draft), instead of the single starter task. Read once, on mount. */
  initialModel?: { items: CaseModelItem[]; options?: CaseModelOptions }
  /** S126: the current model after every change (and once on mount), so a page can save it as a draft. */
  onModelChange?: (model: CaseDesignerModel) => void
}

/** The designer's state as a draft keeps it; `diagram` is whether generated CMMN carries diagram interchange. */
export interface CaseDesignerModel { items: CaseModelItem[]; options: CaseModelOptions; diagram: boolean }

const STARTER_ITEMS: CaseModelItem[] = [{ id: 'task1', kind: 'humanTask', label: 'Review evidence', required: true, manual: true, group: '' }]

export function CaseModelDesigner({ ws, caseKey, name, onApply, disabled, dataFields = [], onImported, initialModel, onModelChange }: CaseModelDesignerProps) {
  const groups = useGroups(ws)
  const groupList: Group[] = groups.data?.items ?? []
  const [items, setItems] = useState<CaseModelItem[]>(() => structuredClone(initialModel?.items.length ? initialModel.items : STARTER_ITEMS))
  const [history, setHistory] = useState<CaseModelItem[][]>([]), [error, setError] = useState<string>(), [xml, setXml] = useState('')
  const [view, setView] = useState<'canvas' | 'form'>('canvas')
  const [selected, setSelected] = useState<string | null>(null)
  const [options, setOptions] = useState<CaseModelOptions>(() => structuredClone(initialModel?.options ?? {}))
  const [diagnostics, setDiagnostics] = useState<ImportDiagnostic[] | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const reportModel = useRef(onModelChange)
  useEffect(() => { reportModel.current = onModelChange })
  useEffect(() => {
    reportModel.current?.({ items, options, diagram: view === 'canvas' || !!options.diagram || caseItems(items).some(item => item.layout) })
  }, [items, options, view])
  const edit = (change: Change) => { setHistory(previous => [...previous.slice(-49), items]); setItems(change(structuredClone(items))); setXml(''); setError(undefined); onApply(null) }
  const nextId = () => { let index = 1; const used = new Set(caseItems(items).map(item => item.id)); while (used.has(`item${index}`)) index++; return `item${index}` }
  const replace = (id: string, patch: Partial<CaseModelItem>) => edit(nodes => patchItem(nodes, id, patch))
  const add = (kind: CaseItemKind, parent?: string) => edit(nodes => {
    const item: CaseModelItem = { id: nextId(), kind, label: `New ${ADD_LABEL[kind]}`, ...(kind === 'humanTask' ? { group: '', manual: true } : kind === 'userEventListener' ? { group: '' } : kind === 'processTask' ? { workflow: { key: '', input: {}, result: {} } } : kind === 'caseTask' ? { childCase: { key: '', input: {}, result: {} } } : kind === 'decisionTask' ? { decision: { key: '', input: {}, result: {} } } : kind === 'timerEventListener' ? { timer: 'PT1H' } : {}), ...(kind === 'stage' ? { children: [] } : {}) }
    if (view === 'canvas' || caseItems(nodes).some(node => node.layout)) item.layout = placement(nodes, parent ?? null, kind)
    if (!parent) return fitStages([...nodes, item])
    const stage = caseItems(nodes).find(node => node.id === parent)!; stage.children = [...(stage.children ?? []), item]; return fitStages(nodes)
  })
  const remove = (id: string) => edit(nodes => {
    const removed = new Set(caseItems(nodes).filter(item => item.id === id).flatMap(item => caseItems([item])).map(item => item.id))
    const filter = (list: CaseModelItem[]): CaseModelItem[] => list.filter(item => !removed.has(item.id)).map(item => {
      const sources = entrySources(item).filter(source => !removed.has(source))
      const next: CaseModelItem = { ...item, after: sources[0], afterAll: sources.length > 1 ? sources.slice(1) : undefined, exitAfter: item.exitAfter && removed.has(item.exitAfter) ? undefined : item.exitAfter, children: item.children ? filter(item.children) : undefined }
      if (!next.after) delete next.after
      return next
    })
    return filter(nodes)
  })
  const move = (id: string, delta: -1 | 1) => edit(nodes => {
    const reorder = (list: CaseModelItem[]): CaseModelItem[] => {
      const index = list.findIndex(item => item.id === id), target = index + delta
      if (index >= 0 && target >= 0 && target < list.length) { [list[index], list[target]] = [list[target]!, list[index]!]; return list }
      return list.map(item => ({ ...item, children: item.children ? reorder(item.children) : undefined }))
    }
    return reorder(nodes)
  })
  const importFile = async (file: File) => {
    try {
      if (file.size > 65_536) throw new Error('Choose a CMMN file of at most 64 KiB.')
      const imported = importCaseModel(await file.text())
      setHistory(previous => [...previous.slice(-49), items])
      setItems(imported.items.length ? imported.items : items); setOptions(imported.options); setDiagnostics(imported.diagnostics)
      setXml(''); setError(undefined); onApply(null); setSelected(null)
      onImported?.(imported.key, imported.name)
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'The file could not be imported.') }
  }
  const generate = () => {
    try {
      const generated = caseModelXml(caseKey, name, items, { ...options, diagram: view === 'canvas' || options.diagram || caseItems(items).some(item => item.layout) })
      setXml(generated); onApply(generated, caseModelWorkflowTasks(items), caseModelCaseTasks(items), caseModelDecisionTasks(items)); setError(undefined)
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'The case model is invalid.') }
  }
  const selectedItem = selected ? findItem(items, selected) : undefined
  const selectedParent = selected ? parentOf(items, selected) : null
  const addTarget = selectedItem?.kind === 'stage' ? selectedItem.id : selectedParent?.id ?? undefined

  const render = (nodes: CaseModelItem[], depth = 0): React.ReactNode => <ol className="space-y-3">{nodes.map((item, index) => <li key={item.id} className="space-y-3 rounded border border-border bg-surface p-3">
    <p className="text-xs font-semibold uppercase text-muted-foreground">{KIND_LABEL[item.kind]}{item.discretionary ? ' (discretionary)' : ''} · {item.id}</p>
    <ItemFields ws={ws} item={item} siblings={nodes} groups={groupList} dataFields={dataFields} onPatch={patch => replace(item.id, patch)} />
    <div className="flex flex-wrap gap-2"><Button type="button" variant="secondary" disabled={index === 0} onClick={() => move(item.id, -1)}>Move {item.id} up</Button><Button type="button" variant="secondary" disabled={index === nodes.length - 1} onClick={() => move(item.id, 1)}>Move {item.id} down</Button><Button type="button" variant="secondary" onClick={() => remove(item.id)}>Remove {item.id}</Button></div>
    {item.kind === 'stage' ? <div className="space-y-3 border-l-2 pl-3">{render(item.children ?? [], depth + 1)}<div className="flex flex-wrap gap-2">{ADDABLE.filter(kind => kind !== 'stage' || depth < 3).map(kind => <Button key={kind} type="button" variant="secondary" disabled={caseItems(items).length >= 30} onClick={() => add(kind, item.id)}>Add {kind === 'humanTask' ? 'task' : ADD_LABEL[kind]} to {item.id}</Button>)}</div></div> : null}
  </li>)}</ol>

  return <fieldset disabled={disabled} className="space-y-4 rounded border p-4"><legend className="font-semibold">Case model designer</legend>
    <p className="text-sm text-muted-foreground">Draw the case plan on the canvas, or use the form. Plan human tasks, workflow tasks that run a published workflow, case tasks that start another case, nested stages, milestones, timers and authorized user events; mark work discretionary so people add it when needed; repeat items; connect items with start-after and ends-when rules; and make work depend on case data. The server validates the generated CMMN before creating a definition.</p>
    {groups.isError ? <Alert tone="danger">Workspace groups could not be loaded. <Button type="button" variant="ghost" onClick={() => void groups.refetch()}>Retry groups</Button></Alert> : null}
    <div className="flex flex-wrap items-center gap-2" role="group" aria-label="Designer view">
      <Button type="button" variant={view === 'canvas' ? 'primary' : 'secondary'} aria-pressed={view === 'canvas'} onClick={() => setView('canvas')}>Canvas view</Button>
      <Button type="button" variant={view === 'form' ? 'primary' : 'secondary'} aria-pressed={view === 'form'} onClick={() => setView('form')}>Form view</Button>
      <Button type="button" variant="secondary" onClick={() => fileInput.current?.click()}>Import CMMN file</Button>
      <input ref={fileInput} type="file" accept=".cmmn,.xml" className="sr-only" aria-label="CMMN file to import into the designer" tabIndex={-1} onChange={event => { const file = event.target.files?.[0]; event.target.value = ''; if (file) void importFile(file) }} />
    </div>
    {diagnostics ? <div className="space-y-2 rounded border border-border p-3" role="region" aria-label="Import report">
      <p className="font-semibold">Import report: {diagnostics.filter(d => d.severity === 'error').length} left out, {diagnostics.filter(d => d.severity === 'warning').length} to review</p>
      {diagnostics.length ? <ul className="space-y-1 text-sm">{diagnostics.map((d, index) => <li key={index}><span className="font-semibold">{d.severity === 'error' ? 'Left out' : 'Review'}</span> <code className="break-all">{d.path}</code>: {d.message}</li>)}</ul> : <p className="text-sm">Everything in the file was imported, with its IDs and diagram positions.</p>}
      <Button type="button" variant="ghost" onClick={() => setDiagnostics(null)}>Dismiss import report</Button>
    </div> : null}
    {view === 'canvas' ? <div className="space-y-3">
      <div className="flex flex-wrap gap-2" role="toolbar" aria-label="Add to the case plan">{ADDABLE.map(kind => <Button type="button" key={kind} variant="secondary" disabled={caseItems(items).length >= 30 || kind === 'stage' && !!addTarget && (parentOf(items, addTarget) !== null)} onClick={() => add(kind, addTarget)}>Add {ADD_LABEL[kind]}{addTarget ? ` to ${findItem(items, addTarget)?.label}` : ''}</Button>)}</div>
      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_22rem]">
        <CaseCanvas name={name} items={items} selected={selected} onSelect={setSelected} onChange={edit} readOnly={disabled} />
        <aside className="space-y-3 rounded border border-border bg-surface p-3" aria-label="Selected plan item">
          {selectedItem ? <>
            <p className="text-xs font-semibold uppercase text-muted-foreground">{KIND_LABEL[selectedItem.kind]} · {selectedItem.id}{selectedParent ? ` · in ${selectedParent.label}` : ''}</p>
            <ItemFields ws={ws} item={selectedItem} siblings={selectedParent?.children ?? items} groups={groupList} dataFields={dataFields} onPatch={patch => replace(selectedItem.id, patch)} />
            <Button type="button" variant="secondary" onClick={() => { remove(selectedItem.id); setSelected(null) }}>Remove {selectedItem.id}</Button>
          </> : <p className="text-sm text-muted-foreground">Select a shape to edit it. With a stage selected, new items are added inside it.</p>}
        </aside>
      </div>
    </div> : <>
      {render(items)}
      <div className="flex flex-wrap gap-2">{ADDABLE.map(kind => <Button type="button" key={kind} variant="secondary" disabled={caseItems(items).length >= 30} onClick={() => add(kind)}>Add {ADD_LABEL[kind]}</Button>)}</div>
    </>}
    <div className="flex flex-wrap gap-2">
      <Button type="button" variant="secondary" disabled={!history.length} onClick={() => { setItems(history.at(-1)!); setHistory(history.slice(0, -1)); setXml(''); onApply(null) }}>Undo model change</Button>
      <Button type="button" onClick={generate}>Use generated CMMN</Button>
    </div>
    {error ? <Alert tone="danger">{error}</Alert> : null}{xml ? <div><Label htmlFor="case-generated-xml">Generated CMMN preview</Label><Textarea id="case-generated-xml" rows={8} readOnly value={xml} /><Button type="button" variant="secondary" onClick={() => downloadBlob(new Blob([xml], { type: 'application/xml' }), `${caseKey}.cmmn`)}>Download generated CMMN</Button></div> : null}
    <CmmnPreview name={name} items={items} />
  </fieldset>
}
