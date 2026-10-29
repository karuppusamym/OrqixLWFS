import { useRef, useState } from 'react'
import { downloadBlob } from '@/lib/download'
import { useGroups, useStartForm, useWorkflows } from '@/api/queries'
import { Button } from '@/components/ui/button'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Alert } from '@/components/ui/alert'
import { KIND_LABEL, WORKFLOW_RESULT_SOURCES, caseItems, caseModelWorkflowTasks, caseModelXml, entrySources, occurs, type CaseItemKind, type CaseModelItem, type CaseModelOptions } from './caseModel'
import { findItem, fitStages, parentOf, placement } from './caseCanvasOps'
import { importCaseModel, type ImportDiagnostic } from './cmmnImport'
import { CaseCanvas } from './CaseCanvas'
import { CmmnPreview } from './CmmnPreview'
import type { DataField } from './CaseDataFields'

export type WorkflowTaskMapping = ReturnType<typeof caseModelWorkflowTasks>[number]
type Group = { key: string; name: string }
type Change = (items: CaseModelItem[]) => CaseModelItem[]

const ADDABLE: CaseItemKind[] = ['humanTask', 'processTask', 'stage', 'milestone', 'userEventListener']
const ADD_LABEL: Record<CaseItemKind, string> = { humanTask: 'human task', processTask: 'workflow task', stage: 'stage', milestone: 'milestone', userEventListener: 'user event' }

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
  const canDiscretionary = item.kind === 'humanTask' || item.kind === 'processTask'
  return <div className="space-y-3">
    <div><Label htmlFor={`case-model-${item.id}-label`}>Name for {item.id}</Label><Input id={`case-model-${item.id}-label`} value={item.label} maxLength={120} onChange={event => onPatch({ label: event.target.value })} /></div>
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
    {canDiscretionary ? <label className="flex items-center gap-2"><input type="checkbox" checked={!!item.discretionary} onChange={event => onPatch(event.target.checked ? { discretionary: true, planners: item.planners ?? [], after: undefined, afterAll: undefined, exitAfter: undefined, repetitions: undefined } : { discretionary: undefined, planners: undefined })} />Discretionary {item.id}: people decide during the case whether it is needed</label> : null}
    {item.discretionary ? <fieldset className="space-y-1"><legend className="text-sm font-semibold">Who may plan {item.label}</legend>
      {groups.map(group => <label key={group.key} className="flex items-center gap-2"><input type="checkbox" checked={item.planners?.includes(group.key) ?? false} onChange={event => onPatch({ planners: event.target.checked ? [...(item.planners ?? []), group.key] : (item.planners ?? []).filter(value => value !== group.key) })} />{group.name}</label>)}
    </fieldset> : null}
    {item.kind !== 'userEventListener' && !item.discretionary ? <div><Label htmlFor={`case-model-${item.id}-after`}>Start after for {item.id}</Label><Select id={`case-model-${item.id}-after`} value={item.after ?? ''} onChange={event => onPatch({ after: event.target.value || undefined, afterAll: event.target.value ? item.afterAll?.filter(value => value !== event.target.value) : undefined, ...(event.target.value || item.kind !== 'milestone' ? {} : { repetitions: undefined }) })}><option value="">Available when its stage starts</option>{others.map(source => <option key={source.id} value={source.id}>{source.label} {occurs(source) ? 'occurs' : 'completes'}</option>)}</Select>
      {sources.length > 1 ? <p className="text-xs text-muted-foreground">Also waits for: {sources.slice(1).map(id => siblings.find(other => other.id === id)?.label ?? id).join(', ')}. <button type="button" className="underline" onClick={() => onPatch({ afterAll: undefined })}>Wait only for the first</button></p> : null}</div> : null}
    {item.kind !== 'userEventListener' && item.kind !== 'milestone' && !item.discretionary ? <div><Label htmlFor={`case-model-${item.id}-exit`}>Ends when for {item.id}</Label><Select id={`case-model-${item.id}-exit`} value={item.exitAfter ?? ''} disabled={!!item.repetitions} onChange={event => onPatch({ exitAfter: event.target.value || undefined })}><option value="">Not ended by another item</option>{others.map(source => <option key={source.id} value={source.id}>{source.label} {occurs(source) ? 'occurs' : 'completes'}</option>)}</Select></div> : null}
    {item.kind !== 'userEventListener' ? <label className="flex items-center gap-2"><input type="checkbox" checked={!!item.required} onChange={event => onPatch({ required: event.target.checked })} />Required work {item.id}</label> : null}
    {item.kind !== 'milestone' && item.kind !== 'userEventListener' && item.kind !== 'processTask' ? <label className="flex items-center gap-2"><input type="checkbox" checked={!!item.manual} onChange={event => onPatch({ manual: event.target.checked })} />Manual activation {item.id}</label> : null}
    {item.kind !== 'userEventListener' && !item.discretionary ? <div><Label htmlFor={`case-model-${item.id}-repeat`}>Executions for {item.id}</Label><Select id={`case-model-${item.id}-repeat`} value={item.repetitions ?? 1} disabled={!!item.exitAfter || item.kind === 'milestone' && !item.after} onChange={event => onPatch({ repetitions: Number(event.target.value) === 1 ? undefined : Number(event.target.value) })}>{Array.from({ length: 30 }, (_, i) => <option key={i + 1} value={i + 1}>{i + 1}</option>)}</Select><p className="text-xs text-muted-foreground">{item.after ? 'Repeats each time its start-after rule is met again, up to this many times.' : 'Repeats one after another, up to this many times. Each occurrence fires its own completion event.'}</p></div> : null}
  </div>
}

export interface CaseModelDesignerProps {
  ws: string
  caseKey: string
  name: string
  disabled: boolean
  dataFields?: DataField[]
  /** The generated CMMN and the workflow mappings it needs, or null after an edit invalidated them. */
  onApply: (xml: string | null, workflowTasks?: WorkflowTaskMapping[]) => void
  /** Called after a file import with the case key and name it contained. */
  onImported?: (key: string, name: string) => void
}

export function CaseModelDesigner({ ws, caseKey, name, onApply, disabled, dataFields = [], onImported }: CaseModelDesignerProps) {
  const groups = useGroups(ws)
  const groupList: Group[] = groups.data?.items ?? []
  const [items, setItems] = useState<CaseModelItem[]>([{ id: 'task1', kind: 'humanTask', label: 'Review evidence', required: true, manual: true, group: '' }])
  const [history, setHistory] = useState<CaseModelItem[][]>([]), [error, setError] = useState<string>(), [xml, setXml] = useState('')
  const [view, setView] = useState<'canvas' | 'form'>('canvas')
  const [selected, setSelected] = useState<string | null>(null)
  const [options, setOptions] = useState<CaseModelOptions>({})
  const [diagnostics, setDiagnostics] = useState<ImportDiagnostic[] | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const edit = (change: Change) => { setHistory(previous => [...previous.slice(-49), items]); setItems(change(structuredClone(items))); setXml(''); setError(undefined); onApply(null) }
  const nextId = () => { let index = 1; const used = new Set(caseItems(items).map(item => item.id)); while (used.has(`item${index}`)) index++; return `item${index}` }
  const replace = (id: string, patch: Partial<CaseModelItem>) => edit(nodes => patchItem(nodes, id, patch))
  const add = (kind: CaseItemKind, parent?: string) => edit(nodes => {
    const item: CaseModelItem = { id: nextId(), kind, label: `New ${ADD_LABEL[kind]}`, ...(kind === 'humanTask' ? { group: '', manual: true } : kind === 'userEventListener' ? { group: '' } : kind === 'processTask' ? { workflow: { key: '', input: {}, result: {} } } : {}), ...(kind === 'stage' ? { children: [] } : {}) }
    if (view === 'canvas' || caseItems(nodes).some(node => node.layout)) item.layout = placement(nodes, parent ?? null, kind)
    if (!parent) return fitStages([...nodes, item])
    const stage = caseItems(nodes).find(node => node.id === parent)!; stage.children = [...(stage.children ?? []), item]; return fitStages(nodes)
  })
  const remove = (id: string) => edit(nodes => {
    const removed = new Set(caseItems(nodes).filter(item => item.id === id).flatMap(item => caseItems([item])).map(item => item.id))
    const filter = (list: CaseModelItem[]): CaseModelItem[] => list.filter(item => !removed.has(item.id)).map(item => {
      const sources = entrySources(item).filter(source => !removed.has(source))
      const next: CaseModelItem = { ...item, after: sources[0], afterAll: sources.length > 1 ? sources.slice(1) : undefined, exitAfter: item.exitAfter && removed.has(item.exitAfter) ? undefined : item.exitAfter, children: item.children ? filter(item.children) : undefined }
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
      const tasks = caseModelWorkflowTasks(items)
      setXml(generated); onApply(generated, tasks); setError(undefined)
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
    <p className="text-sm text-muted-foreground">Draw the case plan on the canvas, or use the form. Plan human tasks, workflow tasks that run a published workflow, nested stages, milestones and authorized user events; mark work discretionary so people add it when needed; repeat items; and connect items with start-after and ends-when rules. The server validates the generated CMMN before creating a definition.</p>
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
