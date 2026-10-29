import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Puzzle } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { listMyRequests, listTasks } from '@/api/endpoints'
import { getComponentData, runChain, type AppPageRuntime, type ChainRunResult } from '@/api/pages'
import { useStartForm } from '@/api/queries'
import type { components } from '@/api/schema'
import type { TaskDetail, TaskSummary } from '@/api/types'
import { isProblemError } from '@/api/problem'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { FormRenderer } from '@/components/form/FormRenderer'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { listCaseInstances } from '@/features/cases/api'
import { ExtensionHost } from '@/features/extensions/ExtensionHost'
import { frameSource } from '@/features/extensions/frame'
import { ConfiguredWorklist } from '@/features/tasks/ConfiguredWorklist'
import type { ViewDefinition, ViewField } from '@/features/views/model'
import { formatDateTime } from '@/lib/dates'
import { buildFields, parseFormSchema, parseFormUi, presentFields } from '@/lib/form/schema'
import { buildPayload, validateForm, valuesFromJson, type FieldErrors, type RawValues } from '@/lib/form/values'
import { requestReference, taskTitle } from '@/lib/runText'
import { uuid } from '@/lib/text'
import { LAYOUT_REGIONS, needsContext, pinKey, STEP_LABELS, visible, type ActionChain, type ListSource, type PageComponent, type PageDefinition } from './model'

type S = components['schemas']
type ExtensionUse = S['AppPageExtensionUse']
type RunSummary = Awaited<ReturnType<typeof listMyRequests>>['items'][number]
type CaseData = S['CaseData']

export interface RuntimeData {
  ws: string
  slug: string
  revision: number
  context?: string
  contextData: Record<string, unknown>
  task?: TaskDetail
  caseData?: CaseData
  drafts: Record<string, Record<string, unknown>>
  grants: AppPageRuntime['grants']
}

interface PageCanvasProps {
  page: PageDefinition
  views: Map<string, ViewDefinition>
  extensions: Map<string, ExtensionUse>
  /** Designer preview: fixed column count for the chosen device width; nothing runs. */
  preview?: { columns: 1 | 2 }
  runtime?: RuntimeData
}

const GRID: Record<PageDefinition['layout'], Record<PageDefinition['breakpoint'], string>> = {
  single: { sm: '', md: '', lg: '' },
  sidebar: { sm: 'sm:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]', md: 'md:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]', lg: 'lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]' },
  split: { sm: 'sm:grid-cols-2', md: 'md:grid-cols-2', lg: 'lg:grid-cols-2' },
}
const FIXED: Record<PageDefinition['layout'], string> = { single: '', sidebar: 'grid-cols-[minmax(0,2fr)_minmax(0,1fr)]', split: 'grid-cols-2' }

type Values = Record<string, Record<string, string>>
const fieldKey = (field: ViewField) => field.binding.slice(field.binding.indexOf('.') + 1)
const viewFields = (view: ViewDefinition) => view.sections.flatMap(section => section.fields)
/** File and row controls use the request form's own renderer, uploads and validation (S114). */
const usesRichControls = (view: ViewDefinition) => viewFields(view).some(field => field.type === 'file' || field.type === 'row')
/** Collects a rich form's values; `validate` for a submit, lenient for a saved draft. Null means the form shows errors. */
type Collect = (validate: boolean) => Record<string, unknown> | null
const declares = (view: ViewDefinition, type: string) => view.actions.some(action => action.type === type)

function initialValues(page: PageDefinition, views: Map<string, ViewDefinition>, runtime?: RuntimeData): Values {
  const values: Values = {}
  for (const region of page.regions) for (const component of region.components) {
    const view = component.view ? views.get(pinKey(component.view)) : undefined
    if (!view || (view.kind !== 'request' && view.kind !== 'case')) continue
    const source = view.kind === 'request' ? runtime?.drafts[component.id] : runtime?.caseData?.values as Record<string, unknown> | undefined
    values[component.id] = Object.fromEntries(viewFields(view).map(field => [fieldKey(field), source?.[fieldKey(field)] === undefined || source[fieldKey(field)] === null ? '' : String(source[fieldKey(field)])]))
  }
  return values
}

function typed(view: ViewDefinition, raw: Record<string, string> | undefined): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const field of viewFields(view)) {
    const value = raw?.[fieldKey(field)] ?? ''
    if (value === '') continue
    out[fieldKey(field)] = field.type === 'number' ? Number(value) : field.type === 'boolean' ? value === 'true' : value
  }
  return out
}

/** Renders a page from its pinned definition. Runtime actions go through the server chain operation only. */
export function PageCanvas({ page, views, extensions, preview, runtime }: PageCanvasProps) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [values, setValues] = useState<Values>(() => initialValues(page, views, runtime))
  const [notes, setNotes] = useState<Record<string, { comment?: string; reason?: string }>>({})
  const [results, setResults] = useState<Record<string, ChainRunResult>>({})
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<unknown>(null)
  const byId = useMemo(() => new Map(page.regions.flatMap(region => region.components).map(component => [component.id, component])), [page])
  const forms = useRef(new Map<string, Collect>())
  const register = useCallback((id: string, collect: Collect | null) => { if (collect) forms.current.set(id, collect); else forms.current.delete(id) }, [])

  const inputFor = (componentId: string, submitting: boolean) => {
    const component = byId.get(componentId)
    const view = component?.view ? views.get(pinKey(component.view)) : undefined
    if (!view) return {}
    const note = notes[componentId] ?? {}
    const rich = forms.current.get(componentId)
    if (view.kind === 'request' && rich) {
      const collected = rich(submitting)
      if (!collected) throw new Error('Correct the highlighted fields before running this action.')
      return { values: collected }
    }
    if (view.kind === 'request') return { values: typed(view, values[componentId]) }
    if (view.kind === 'approval') {
      const current = (runtime?.task?.request?.input ?? {}) as Record<string, unknown>
      const edited = values[componentId] ?? {}
      const changes = Object.fromEntries(viewFields(view).filter(field => edited[fieldKey(field)] !== undefined && edited[fieldKey(field)] !== String(current[fieldKey(field)] ?? ''))
        .map(field => [fieldKey(field), field.type === 'number' ? Number(edited[fieldKey(field)]) : field.type === 'boolean' ? edited[fieldKey(field)] === 'true' : edited[fieldKey(field)]]))
      return { ...(runtime?.task ? { expectedVersion: runtime.task.version } : {}), ...(declares(view, 'requestCorrection') ? { values: changes } : {}), ...(note.comment?.trim() ? { comment: note.comment.trim() } : {}), ...(note.reason?.trim() ? { reason: note.reason.trim() } : {}) }
    }
    if (view.kind === 'case') return { values: typed(view, values[componentId]), ...(runtime?.caseData ? { expectedVersion: runtime.caseData.version } : {}), ...(note.reason?.trim() ? { reason: note.reason.trim() } : {}) }
    return {}
  }

  const run = async (chain: ActionChain, source?: string): Promise<ChainRunResult> => {
    if (!runtime) throw new Error('Preview only')
    setBusy(chain.id)
    setError(null)
    try {
      const inputs = Object.fromEntries([...new Set(chain.steps.flatMap(step => step.component ? [step.component] : []))].map(id => [id, inputFor(id, chain.steps.some(step => step.component === id && step.action === 'submit'))]))
      const result = await runChain(runtime.ws, runtime.slug, chain.id, { expectedRevision: runtime.revision, ...(runtime.context ? { context: runtime.context } : {}), ...(source ? { source } : {}), inputs }, uuid())
      setResults(current => ({ ...current, [chain.id]: result }))
      await queryClient.invalidateQueries()
      if (result.completed && result.navigateTo) await navigate(result.navigateTo)
      return result
    } catch (cause) {
      setError(cause)
      throw cause
    } finally {
      setBusy(null)
    }
  }

  const grid = preview ? (preview.columns === 2 ? FIXED[page.layout] : '') : GRID[page.layout][page.breakpoint]
  const regionLabels = LAYOUT_REGIONS[page.layout]
  return <div className="space-y-4" data-testid="page-canvas">
    {/* Local form checks stop a chain before the server and say what to fix; server problems keep their own text. */}
    {error ? isProblemError(error) || !(error instanceof Error) ? <ProblemAlert error={error} /> : <Alert tone="danger">{error.message}</Alert> : null}
    <div className={`grid gap-6 ${grid}`}>
      {page.regions.map((region, index) => <section key={region.id} aria-label={region.label || regionLabels[index]?.label} className="min-w-0 space-y-4">
        {region.components.filter(component => preview || visible(component, runtime?.contextData ?? {})).map(component => <div key={component.id} className="min-w-0">
          <Component component={component} page={page} views={views} extensions={extensions} preview={!!preview} runtime={runtime}
            values={values[component.id] ?? {}} onValue={(key, value) => setValues(current => ({ ...current, [component.id]: { ...current[component.id], [key]: value } }))}
            note={notes[component.id] ?? {}} onNote={note => setNotes(current => ({ ...current, [component.id]: { ...current[component.id], ...note } }))}
            busy={busy} results={results} onRun={run} register={register} />
        </div>)}
      </section>)}
    </div>
  </div>
}

interface ComponentProps {
  component: PageComponent
  page: PageDefinition
  views: Map<string, ViewDefinition>
  extensions: Map<string, ExtensionUse>
  preview: boolean
  runtime?: RuntimeData
  values: Record<string, string>
  onValue: (key: string, value: string) => void
  note: { comment?: string; reason?: string }
  onNote: (note: { comment?: string; reason?: string }) => void
  busy: string | null
  results: Record<string, ChainRunResult>
  onRun: (chain: ActionChain, source?: string) => Promise<ChainRunResult>
  register: (id: string, collect: Collect | null) => void
}

function Component(props: ComponentProps) {
  const { component, page } = props
  switch (component.type) {
    case 'heading': return <h2 className="text-xl font-semibold">{component.text}</h2>
    case 'text': return <p className="whitespace-pre-wrap text-sm">{component.text}</p>
    case 'view': return <EmbeddedView {...props} />
    case 'list': return <DataList {...props} />
    case 'details': return <section aria-label={component.label} className="rounded-card border border-border bg-surface p-4">
      <h2 className="mb-2 font-semibold">{component.label}</h2>
      <dl className="grid gap-2 sm:grid-cols-2">{(component.fields ?? []).map(field => <div key={field.field} className="min-w-0"><dt className="text-sm text-muted-foreground">{field.label}</dt><dd className="wrap-anywhere">{props.preview ? <span className="text-muted-foreground">{field.field}</span> : display(props.runtime?.contextData[field.field])}</dd></div>)}</dl>
    </section>
    case 'actions': return <ActionBar {...props} />
    case 'extension': return <ExtensionSlot {...props} />
    default: return <p>Unsupported component in {page.title}.</p>
  }
}

function display(value: unknown): string {
  if (value === undefined || value === null || value === '') return 'Not available'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function EmbeddedView({ component, views, preview, runtime, values, onValue, note, onNote, page, register }: ComponentProps) {
  const navigate = useNavigate()
  const view = component.view ? views.get(pinKey(component.view)) : undefined
  const tasks = useQuery({ queryKey: ['tasks', runtime?.ws, 'assigned', 'page'], queryFn: () => listTasks(runtime!.ws, 'assigned', { limit: 25 }), enabled: !!runtime && view?.kind === 'worklist' })
  if (!view) return <p className="rounded-card border border-dashed border-border p-4 text-sm text-muted-foreground">Choose a published view for {component.id}.</p>
  const title = component.label ?? view.sections[0]?.label
  if (view.kind === 'worklist') {
    if (preview || !runtime) return <section className="rounded-card border border-border p-4" aria-label={title}><h2 className="font-semibold">{title}</h2><p className="text-sm text-muted-foreground">Authorized task list with {viewFields(view).length} columns.</p></section>
    if (tasks.isError) return <ProblemAlert error={tasks.error} />
    return <ConfiguredWorklist tasks={(tasks.data?.items ?? []) as TaskSummary[]} definition={view} ws={runtime.ws} query="" desktop onOpen={id => void navigate(page.context === 'task' ? `/w/${runtime.ws}/apps/${runtime.slug}/${id}` : `/w/${runtime.ws}/tasks/${id}`)} />
  }
  if (view.kind === 'approval') {
    if (!preview && !runtime?.task) return <p className="rounded-card border border-dashed border-border p-4 text-sm text-muted-foreground">Choose a task to see its request.</p>
    const input = (runtime?.task?.request?.input ?? {}) as Record<string, unknown>
    const correcting = declares(view, 'requestCorrection')
    // Only the fields the server says the caller may correct on this task now; the server checks again.
    const correctable = correcting && !preview ? viewFields(view).filter(field => field.type !== 'file' && field.type !== 'row' && (runtime?.task?.form.correctableFields ?? []).includes(fieldKey(field))) : []
    return <section aria-label={title} className="space-y-3 rounded-card border border-border bg-surface p-4">
      {view.sections.map(section => <div key={section.id}><h2 className="mb-2 font-semibold">{section.label}</h2><dl className={`grid gap-3 ${section.columns === 2 ? 'sm:grid-cols-2' : ''}`}>{section.fields.map(field => <div key={field.id} className="min-w-0"><dt className="text-sm text-muted-foreground">{field.label}</dt><dd className="wrap-anywhere">{preview ? field.binding : <ReadValue field={field} value={input[fieldKey(field)]} />}</dd></div>)}</dl></div>)}
      {correcting ? <fieldset className="space-y-3"><legend className="font-semibold">Corrections</legend>
        {preview ? <p className="text-sm text-muted-foreground">Fields the workflow lets the task holder correct appear here.</p>
          : correctable.length === 0 ? <p className="text-sm text-muted-foreground">No fields can be corrected on this task now.</p>
            : <div className="grid gap-3 sm:grid-cols-2">{correctable.map(field => <FieldInput key={field.id} id={`${component.id}-correct-${field.id}`} field={field} value={values[fieldKey(field)] ?? (input[fieldKey(field)] === undefined || input[fieldKey(field)] === null ? '' : String(input[fieldKey(field)]))} disabled={false} onChange={value => onValue(fieldKey(field), value)} />)}</div>}
      </fieldset> : null}
      <div className="grid gap-3 sm:grid-cols-2">
        <div><Label htmlFor={`${component.id}-comment`}>Comment (optional)</Label><Textarea id={`${component.id}-comment`} value={note.comment ?? ''} maxLength={2000} disabled={preview} onChange={event => onNote({ comment: event.target.value })} /></div>
        <div><Label htmlFor={`${component.id}-reason`}>{correcting ? 'Reason (needed to reject or correct)' : 'Reason (needed to reject)'}</Label><Textarea id={`${component.id}-reason`} value={note.reason ?? ''} maxLength={1000} disabled={preview} onChange={event => onNote({ reason: event.target.value })} /></div>
      </div>
    </section>
  }
  const editable = !preview && (view.kind === 'request' ? !!runtime?.grants.startWorkflows.includes(component.workflowId ?? '') : !!runtime?.caseData?.canEdit)
  if (view.kind === 'case' && !preview && !runtime?.caseData) return <p className="rounded-card border border-dashed border-border p-4 text-sm text-muted-foreground">Choose a case to see its data.</p>
  if (view.kind === 'request' && runtime && !preview && usesRichControls(view)) return <section aria-label={title} className="space-y-4 rounded-card border border-border bg-surface p-4">
    <h2 className="font-semibold">{title}</h2>
    <RequestForm component={component} view={view} runtime={runtime} editable={editable} register={register} />
    {!editable ? <p className="text-sm text-muted-foreground">You cannot start this request.</p> : null}
  </section>
  return <section aria-label={title} className="space-y-4 rounded-card border border-border bg-surface p-4">
    {view.sections.map(section => <fieldset key={section.id} className="space-y-3"><legend className="font-semibold">{section.label}</legend>
      <div className={`grid gap-3 ${section.columns === 2 ? 'sm:grid-cols-2' : ''}`}>{section.fields.map(field => <FieldInput key={field.id} id={`${component.id}-${field.id}`} field={field} value={values[fieldKey(field)] ?? ''} disabled={!editable} onChange={value => onValue(fieldKey(field), value)} />)}</div>
    </fieldset>)}
    {view.kind === 'case' ? <div><Label htmlFor={`${component.id}-reason`}>Reason for the change or case action</Label><Input id={`${component.id}-reason`} value={note.reason ?? ''} maxLength={1000} disabled={preview} onChange={event => onNote({ reason: event.target.value })} /></div> : null}
    {!preview && !editable ? <p className="text-sm text-muted-foreground">{view.kind === 'request' ? 'You cannot start this request.' : 'You can read this case data but not change it.'}</p> : null}
  </section>
}

/** Read-only request value; rows show as a small table, a file only as attached (downloads stay on the request page). */
function ReadValue({ field, value }: { field: ViewField; value: unknown }) {
  if (field.type === 'file') return <>{value ? 'A file is attached' : 'Not available'}</>
  if (field.type === 'row') {
    const rows = Array.isArray(value) ? value.filter((row): row is Record<string, unknown> => !!row && typeof row === 'object') : []
    if (rows.length === 0) return <>No rows</>
    const columns = [...new Set(rows.flatMap(row => Object.keys(row)))]
    return <Table><caption className="sr-only">{field.label}</caption><THead><TR>{columns.map(column => <TH key={column}>{column}</TH>)}</TR></THead><TBody>{rows.map((row, index) => <TR key={index}>{columns.map(column => <TD key={column}>{display(row[column])}</TD>)}</TR>)}</TBody></Table>
  }
  return <>{field.options?.find(option => option.value === value)?.label ?? display(value)}</>
}

/** A request view with file or row controls, rendered by the request form renderer against the workflow's active form. */
function RequestForm({ component, view, runtime, editable, register }: { component: PageComponent; view: ViewDefinition; runtime: RuntimeData; editable: boolean; register: ComponentProps['register'] }) {
  const form = useStartForm(runtime.ws, component.workflowId ?? '')
  const schema = useMemo(() => parseFormSchema(form.data?.inputSchema), [form.data?.inputSchema])
  const ui = useMemo(() => parseFormUi(form.data?.inputUi), [form.data?.inputUi])
  const bound = useMemo(() => new Set(viewFields(view).map(fieldKey)), [view])
  const fields = useMemo(() => presentFields(buildFields(schema, ui), view).filter(field => bound.has(field.key)), [schema, ui, view, bound])
  const [edited, setEdited] = useState<RawValues | null>(null)
  const values = useMemo(() => edited ?? valuesFromJson(fields, runtime.drafts[component.id]), [edited, fields, runtime.drafts, component.id])
  const [errors, setErrors] = useState<FieldErrors>({})
  const [uploading, setUploading] = useState<Set<string>>(new Set())
  const latest = useRef({ schema, ui, fields, values, uploading })
  useEffect(() => { latest.current = { schema, ui, fields, values, uploading } })
  useEffect(() => {
    register(component.id, validate => {
      const current = latest.current
      if (current.uploading.size) throw new Error('Wait for the file upload to finish.')
      // Only the view's own fields go to the server, which repeats every check and attaches the staged uploads on submit.
      const pick = (payload: Record<string, unknown>) => Object.fromEntries(Object.entries(payload).filter(([key]) => bound.has(key)))
      if (!validate) return pick(buildPayload(current.fields, current.values).payload)
      const result = validateForm(current.schema, current.ui, current.values)
      setErrors(result.valid ? {} : result.errors)
      return result.valid ? pick(result.payload) : null
    })
    return () => register(component.id, null)
  }, [component.id, register, bound])
  if (form.isPending) return <p className="text-sm text-muted-foreground" role="status">Loading the request form</p>
  if (form.isError) return <ProblemAlert error={form.error} />
  return <FormRenderer view={view} fields={fields} values={values} errors={errors} idPrefix={component.id} disabled={!editable}
    onChange={(key, value) => { setEdited({ ...values, [key]: value }); if (errors[key]) setErrors(current => ({ ...current, [key]: '' })) }}
    onFileBusyChange={(key, busy) => setUploading(current => { const next = new Set(current); if (busy) next.add(key); else next.delete(key); return next })} />
}

function FieldInput({ id, field, value, disabled, onChange }: { id: string; field: ViewField; value: string; disabled: boolean; onChange: (value: string) => void }) {
  const label = <Label htmlFor={id}>{field.label}{field.required ? ' *' : ''}</Label>
  if (field.type === 'file' || field.type === 'row') return <div className="min-w-0 space-y-1.5"><p className="text-sm font-medium">{field.label}{field.required ? ' *' : ''}</p><p className="text-sm text-muted-foreground">{field.type === 'file' ? 'File upload' : 'Repeating rows'} from the request form.</p></div>
  if (field.type === 'choice' || field.type === 'boolean') {
    const options = field.type === 'boolean' ? [{ value: 'true', label: 'Yes' }, { value: 'false', label: 'No' }] : field.options ?? []
    return <div className="min-w-0 space-y-1.5">{label}<Select id={id} value={value} disabled={disabled} required={field.required} onChange={event => onChange(event.target.value)}><option value="">Choose an option</option>{options.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}</Select></div>
  }
  return <div className="min-w-0 space-y-1.5">{label}<Input id={id} type={field.type === 'number' ? 'number' : field.type === 'date' ? 'date' : 'text'} step={field.type === 'number' ? 'any' : undefined} value={value} disabled={disabled} required={field.required} onChange={event => onChange(event.target.value)} /></div>
}

const TASK_VALUE: Record<string, (task: TaskSummary) => string> = {
  requestTitle: task => taskTitle(task), reference: task => requestReference(task), label: task => task.label, workflowName: task => task.workflowName,
  requester: task => task.requester.displayName, createdAt: task => formatDateTime(task.createdAt), dueAt: task => task.dueAt ? formatDateTime(task.dueAt) : 'No due date',
  status: task => task.status, priority: task => String(task.priority ?? 0),
}
const REQUEST_VALUE: Record<string, (run: RunSummary) => string> = {
  requestTitle: run => run.requestTitle ?? run.reference ?? 'Request', reference: run => run.reference ?? '', workflowName: run => run.workflow.name, lifecycle: run => run.lifecycle,
  createdAt: run => formatDateTime(run.createdAt), updatedAt: run => formatDateTime(run.updatedAt),
}

function DataList({ component, page, preview, runtime }: ComponentProps) {
  const source = component.source as ListSource
  const ws = runtime?.ws ?? ''
  const limit = component.limit ?? 10
  const query = useQuery({
    queryKey: ['appPageList', ws, source, limit],
    enabled: !!runtime,
    queryFn: async (): Promise<Array<{ id: string; href: string; cells: string[] }>> => {
      const columns = component.columns ?? []
      if (source === 'assignedTasks' || source === 'claimableTasks') {
        const page_ = await listTasks(ws, source === 'assignedTasks' ? 'assigned' : 'claimable', { limit })
        return page_.items.map(task => ({ id: task.id, href: page.context === 'task' ? `/w/${ws}/apps/${runtime!.slug}/${task.id}` : `/w/${ws}/tasks/${task.id}`, cells: columns.map(column => TASK_VALUE[column.field]?.(task) ?? '') }))
      }
      if (source === 'myRequests') {
        const runs = await listMyRequests(ws, { limit })
        return runs.items.map(run => ({ id: run.id, href: `/w/${ws}/runs/${run.id}`, cells: columns.map(column => REQUEST_VALUE[column.field]?.(run) ?? '') }))
      }
      const cases = await listCaseInstances(ws)
      return cases.slice(0, limit).map(item => ({ id: item.id, href: page.context === 'case' ? `/w/${ws}/apps/${runtime!.slug}/${item.id}` : `/w/${ws}/cases/${item.id}`, cells: columns.map(column => column.field === 'createdAt' ? formatDateTime(item.createdAt) : String((item as Record<string, unknown>)[column.field] ?? '')) }))
    },
  })
  const columns = component.columns ?? []
  return <section aria-label={component.label} className="rounded-card border border-border bg-surface">
    <h2 className="px-4 pt-3 font-semibold">{component.label}</h2>
    {preview || !runtime ? <p className="px-4 pb-3 text-sm text-muted-foreground">Shows up to {limit} rows from the caller's own authorized data ({columns.map(column => column.label).join(', ')}).</p>
      : query.isPending ? <p className="px-4 pb-3 text-sm text-muted-foreground" role="status">Loading</p>
        : query.isError ? <div className="p-3"><ProblemAlert error={query.error} /></div>
          : query.data.length === 0 ? <p className="px-4 pb-3 text-sm text-muted-foreground">Nothing to show.</p>
            : <Table><caption className="sr-only">{component.label}</caption><THead><TR>{columns.map(column => <TH key={column.field}>{column.label}</TH>)}</TR></THead><TBody>{query.data.map(row => <TR key={row.id}>{row.cells.map((cell, index) => <TD key={columns[index]!.field}>{index === 0 ? <Link className="font-semibold" to={row.href}>{cell || 'Open'}</Link> : cell}</TD>)}</TR>)}</TBody></Table>}
  </section>
}

function ActionBar({ component, page, preview, runtime, busy, results, onRun }: ComponentProps) {
  const chains = (component.chains ?? []).flatMap(id => page.chains.filter(chain => chain.id === id))
  const blocked = !preview && runtime && page.context !== 'none' && !runtime.context && chains.some(chain => chain.steps.some(step => needsContext(step.action)))
  return <section aria-label={component.label ?? 'Actions'} className="space-y-3 rounded-card border border-border bg-surface p-4">
    {component.label ? <h2 className="font-semibold">{component.label}</h2> : null}
    <div className="flex flex-wrap gap-2">{chains.map((chain, index) => <Button key={chain.id} variant={index === 0 ? 'primary' : 'secondary'} disabled={preview || !!busy || !!blocked} loading={busy === chain.id} onClick={() => void onRun(chain).catch(() => undefined)}>{chain.label}</Button>)}</div>
    {blocked ? <p className="text-sm text-muted-foreground">Open a {page.context} from a list first.</p> : null}
    {preview ? <p className="text-xs text-muted-foreground">Preview only. Each step is authorized again by the server and the chain stops at the first failure.</p> : null}
    {chains.map(chain => results[chain.id] ? <ChainOutcome key={chain.id} chain={chain} result={results[chain.id]!} /> : null)}
  </section>
}

export function ChainOutcome({ chain, result }: { chain: ActionChain; result: ChainRunResult }) {
  return <div role="status" className="rounded border border-border p-3 text-sm">
    <p className="font-semibold">{chain.label}: {result.completed ? 'completed' : 'stopped'}</p>
    <ol className="mt-1 list-decimal space-y-1 pl-5">{result.steps.map(step => <li key={step.index}>{STEP_LABELS[step.action]} <Badge tone={step.status === 'succeeded' ? 'success' : step.status === 'failed' ? 'danger' : 'neutral'}>{step.status}</Badge>{step.detail ? <span className="ml-1 text-muted-foreground">{step.detail}</span> : null}</li>)}</ol>
  </div>
}

function ExtensionSlot({ component, page, extensions, preview, runtime, onRun }: ComponentProps) {
  const use = component.extension ? extensions.get(pinKey(component.extension)) : undefined
  const source = useQuery({ queryKey: ['extensionFrame', runtime?.ws, use?.id, use?.version], queryFn: () => frameSource(runtime!.ws, use!.id, use!.version), enabled: !!runtime && use?.status === 'approved', retry: false, staleTime: Infinity })
  const data = useQuery({ queryKey: ['appPageComponentData', runtime?.ws, runtime?.slug, component.id, runtime?.context], queryFn: () => getComponentData(runtime!.ws, runtime!.slug, component.id, runtime!.context), enabled: !!runtime && use?.status === 'approved' && (page.context === 'none' || !!runtime.context), retry: false })
  if (preview || !runtime) return <section aria-label={component.label} className="flex items-start gap-3 rounded-card border border-dashed border-border p-4 text-sm">
    <Puzzle className="mt-0.5 size-4" aria-hidden="true" /><div><p className="font-semibold">{component.label}</p><p className="text-muted-foreground">{use ? `${use.name} v${use.version} (${use.status})` : 'Extension'} runs in a sandboxed frame with {component.bindings?.length ?? 0} granted bindings and {component.chains?.length ?? 0} granted chains.</p></div>
  </section>
  const chains = (component.chains ?? []).flatMap(id => page.chains.filter(chain => chain.id === id)).map(chain => ({ id: chain.id, label: chain.label }))
  const blocked = !use ? 'its version is missing' : use.status !== 'approved' ? `its version is ${use.status}` : source.isError ? 'its sandboxed document could not be loaded' : data.isError ? 'its data could not be read' : null
  return <section aria-label={component.label} className="space-y-1">
    <h2 className="text-sm font-semibold">{component.label}</h2>
    <ExtensionHost label={component.label ?? 'Extension'} source={source.data ?? null} data={data.data?.values as Record<string, unknown> | undefined} chains={chains} height={component.height ?? use?.height ?? 240} blocked={blocked}
      onRun={async chainId => { const chain = page.chains.find(item => item.id === chainId)!; return onRun(chain, component.id) }} />
  </section>
}
