import { useQuery } from '@tanstack/react-query'
import { listWorkflows } from '@/api/endpoints'
import type { UiExtension } from '@/api/extensions'
import { getView, type ViewSummary } from '@/api/views'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { COMPONENT_LABELS, isContextBinding, LIST_COLUMNS, LIST_LABELS, TASK_FIELDS, type ListSource, type PageComponent, type PageDefinition, type PageField } from './model'

interface InspectorProps {
  ws: string
  page: PageDefinition
  component: PageComponent
  library: ViewSummary[]
  extensions: UiExtension[]
  disabled: boolean
  onChange: (component: PageComponent) => void
}

const FIELD_LABELS: Record<string, string> = { requestTitle: 'Request', reference: 'Reference', label: 'Step', workflowName: 'Workflow', requester: 'Requester', createdAt: 'Created', dueAt: 'Due', status: 'Status', priority: 'Priority', lifecycle: 'Status', updatedAt: 'Updated', definitionVersion: 'Definition version', version: 'Version' }

function fieldsText(fields: PageField[] | undefined) { return (fields ?? []).map(field => `${field.field} | ${field.label}`).join('\n') }
function parseFields(text: string): PageField[] { return text.split('\n').filter(line => line.trim()).map(line => { const [field, ...label] = line.split('|'); return { field: field?.trim() ?? '', label: label.join('|').trim() } }) }

/** Properties of the selected component. Choices are limited to what the server accepts for this page context. */
export function ComponentInspector({ ws, page, component, library, extensions, disabled, onChange }: InspectorProps) {
  const set = (patch: Partial<PageComponent>) => onChange({ ...component, ...patch })
  const id = (name: string) => `component-${component.id}-${name}`
  const pinned = component.view?.id ? library.find(view => view.id === component.view!.id) : undefined
  const versions = useQuery({ queryKey: ['view', ws, component.view?.id], queryFn: () => getView(ws, component.view!.id), enabled: component.type === 'view' && !!component.view?.id })
  const workflows = useQuery({ queryKey: ['workflows', ws, 'pageInspector'], queryFn: () => listWorkflows(ws, { limit: 100 }), enabled: component.type === 'view' && pinned?.kind === 'request' })
  const usable = library.filter(view => view.kind !== 'page' && view.publishedVersion)
  const newest = versions.data?.versions[0]?.version
  const extension = component.extension?.id ? extensions.find(item => item.id === component.extension!.id) : undefined
  const extensionVersion = extension?.versions.find(version => version.version === component.extension?.version)

  return <section className="space-y-3 rounded-card border border-border bg-surface p-4" aria-label="Component properties">
    <h2 className="text-lg font-semibold">{COMPONENT_LABELS[component.type]} <span className="text-sm font-normal text-muted-foreground">({component.id})</span></h2>
    {component.type === 'heading' || component.type === 'text' ? <div><Label htmlFor={id('text')}>Text</Label>{component.type === 'heading'
      ? <Input id={id('text')} value={component.text ?? ''} maxLength={200} disabled={disabled} onChange={event => set({ text: event.target.value })} />
      : <Textarea id={id('text')} value={component.text ?? ''} maxLength={1000} disabled={disabled} onChange={event => set({ text: event.target.value })} />}</div> : null}

    {component.type === 'view' ? <>
      <div><Label htmlFor={id('view')}>Published view</Label><Select id={id('view')} value={component.view?.id ?? ''} disabled={disabled} onChange={event => { const view = usable.find(item => item.id === event.target.value); set({ view: view ? { id: view.id, version: view.publishedVersion! } : { id: '', version: 0 }, workflowId: undefined }) }}>
        <option value="">Choose a view</option>{usable.map(view => <option key={view.id} value={view.id}>{view.name} ({view.kind})</option>)}</Select></div>
      {component.view?.id ? <div><Label htmlFor={id('version')}>Pinned version</Label><Select id={id('version')} value={component.view.version} disabled={disabled} onChange={event => set({ view: { id: component.view!.id, version: Number(event.target.value) } })}>{(versions.data?.versions ?? [{ version: component.view.version }]).map(version => <option key={version.version} value={version.version}>Version {version.version}</option>)}</Select></div> : null}
      {newest && component.view && newest > component.view.version ? <div className="flex flex-wrap items-center gap-2 rounded bg-warning-soft p-2 text-sm" role="status">Version {newest} is available. The page keeps version {component.view.version} until you change it. <Button size="sm" variant="secondary" disabled={disabled} onClick={() => set({ view: { id: component.view!.id, version: newest } })}>Use version {newest}</Button></div> : null}
      {pinned?.kind === 'request' ? <div><Label htmlFor={id('workflow')}>Submit to workflow</Label><Select id={id('workflow')} value={component.workflowId ?? ''} disabled={disabled} onChange={event => set({ workflowId: event.target.value || undefined })}><option value="">Choose a workflow</option>{(workflows.data?.items ?? []).filter(workflow => workflow.activeRelease).map(workflow => <option key={workflow.id} value={workflow.id}>{workflow.name}</option>)}{component.workflowId && !workflows.data?.items.some(workflow => workflow.id === component.workflowId) ? <option value={component.workflowId}>{component.workflowId}</option> : null}</Select>
        <p className="mt-1 text-xs text-muted-foreground">The server checks that the view's fields match the workflow's input when you save and publish.</p></div> : null}
      <div><Label htmlFor={id('label')}>Accessible name (optional)</Label><Input id={id('label')} value={component.label ?? ''} maxLength={120} disabled={disabled} onChange={event => set({ label: event.target.value || undefined })} /></div>
    </> : null}

    {component.type === 'list' ? <>
      <div><Label htmlFor={id('label')}>List title</Label><Input id={id('label')} value={component.label ?? ''} maxLength={120} disabled={disabled} onChange={event => set({ label: event.target.value })} /></div>
      <div><Label htmlFor={id('source')}>Data</Label><Select id={id('source')} value={component.source} disabled={disabled} onChange={event => { const source = event.target.value as ListSource; set({ source, columns: [{ field: LIST_COLUMNS[source][0]!, label: FIELD_LABELS[LIST_COLUMNS[source][0]!] ?? 'Item' }] }) }}>{(Object.keys(LIST_COLUMNS) as ListSource[]).map(source => <option key={source} value={source}>{LIST_LABELS[source]}</option>)}</Select></div>
      <fieldset className="space-y-1"><legend className="text-sm font-semibold">Columns</legend>{LIST_COLUMNS[component.source as ListSource]?.map(field => {
        const checked = component.columns?.some(column => column.field === field) ?? false
        return <div key={field} className="flex items-center gap-2"><Checkbox id={id(`col-${field}`)} checked={checked} disabled={disabled} onCheckedChange={value => set({ columns: value === true ? [...(component.columns ?? []), { field, label: FIELD_LABELS[field] ?? field }] : (component.columns ?? []).filter(column => column.field !== field) })} /><Label htmlFor={id(`col-${field}`)}>{FIELD_LABELS[field] ?? field}</Label></div>
      })}</fieldset>
      <div><Label htmlFor={id('limit')}>Rows</Label><Input id={id('limit')} type="number" min={1} max={50} value={component.limit ?? 10} disabled={disabled} onChange={event => set({ limit: Number(event.target.value) })} /></div>
    </> : null}

    {component.type === 'details' ? <>
      <div><Label htmlFor={id('label')}>Title</Label><Input id={id('label')} value={component.label ?? ''} maxLength={120} disabled={disabled} onChange={event => set({ label: event.target.value })} /></div>
      <div><Label htmlFor={id('fields')}>Fields (binding | label per line)</Label><Textarea id={id('fields')} value={fieldsText(component.fields)} disabled={disabled} onChange={event => set({ fields: parseFields(event.target.value) })} />
        <p className="mt-1 text-xs text-muted-foreground">{page.context === 'task' ? `Use ${TASK_FIELDS.map(field => `task.${field}`).join(', ')} or request.fieldName.` : page.context === 'case' ? 'Use case.fieldName.' : 'Set the page context to task or case first.'}</p></div>
    </> : null}

    {component.type === 'actions' || component.type === 'extension' ? <fieldset className="space-y-1"><legend className="text-sm font-semibold">{component.type === 'actions' ? 'Chains on this bar' : 'Chains the extension may request'}</legend>
      {page.chains.length === 0 ? <p className="text-sm text-muted-foreground">Add a chain first.</p> : page.chains.map(chain => {
        const checked = component.chains?.includes(chain.id) ?? false
        return <div key={chain.id} className="flex items-center gap-2"><Checkbox id={id(`chain-${chain.id}`)} checked={checked} disabled={disabled} onCheckedChange={value => set({ chains: value === true ? [...(component.chains ?? []), chain.id] : (component.chains ?? []).filter(item => item !== chain.id) })} /><Label htmlFor={id(`chain-${chain.id}`)}>{chain.label}</Label></div>
      })}</fieldset> : null}
    {component.type === 'actions' ? <div><Label htmlFor={id('label')}>Bar title (optional)</Label><Input id={id('label')} value={component.label ?? ''} maxLength={120} disabled={disabled} onChange={event => set({ label: event.target.value || undefined })} /></div> : null}

    {component.type === 'extension' ? <>
      <div><Label htmlFor={id('label')}>Title</Label><Input id={id('label')} value={component.label ?? ''} maxLength={120} disabled={disabled} onChange={event => set({ label: event.target.value })} /></div>
      <div><Label htmlFor={id('extension')}>Extension version</Label><Select id={id('extension')} value={component.extension?.id ? `${component.extension.id}:${component.extension.version}` : ''} disabled={disabled} onChange={event => { const [extensionId, version] = event.target.value.split(':'); set({ extension: extensionId ? { id: extensionId, version: Number(version) } : { id: '', version: 0 }, bindings: [] }) }}>
        <option value="">Choose an extension</option>{extensions.flatMap(item => item.versions.filter(version => version.status === 'approved' || version.status === 'pending').map(version => <option key={`${item.id}:${version.version}`} value={`${item.id}:${version.version}`}>{item.name} v{version.version} ({version.status})</option>))}</Select>
        {extensionVersion?.status === 'pending' ? <p className="mt-1 text-xs text-warning-foreground" role="status">This version still needs administrator approval before the page can be published.</p> : null}</div>
      <fieldset className="space-y-1"><legend className="text-sm font-semibold">Granted data</legend>
        {(extensionVersion?.capabilities.bindings ?? []).filter(binding => isContextBinding(binding, page.context)).map(binding => {
          const checked = component.bindings?.includes(binding) ?? false
          return <div key={binding} className="flex items-center gap-2"><Checkbox id={id(`bind-${binding}`)} checked={checked} disabled={disabled} onCheckedChange={value => set({ bindings: value === true ? [...(component.bindings ?? []), binding] : (component.bindings ?? []).filter(item => item !== binding) })} /><Label htmlFor={id(`bind-${binding}`)}>{binding}</Label></div>
        })}
        {extensionVersion && !extensionVersion.capabilities.bindings.some(binding => isContextBinding(binding, page.context)) ? <p className="text-sm text-muted-foreground">The extension declares no bindings this page context provides.</p> : null}
      </fieldset>
      <div><Label htmlFor={id('height')}>Height in pixels</Label><Input id={id('height')} type="number" min={80} max={800} value={component.height ?? 240} disabled={disabled} onChange={event => set({ height: Number(event.target.value) })} /></div>
    </> : null}

    {page.context !== 'none' ? <fieldset className="space-y-2 border-t border-border pt-3"><legend className="text-sm font-semibold">Show only when (optional)</legend>
      <div className="grid gap-2 sm:grid-cols-2">
        <div><Label htmlFor={id('when-binding')}>Binding</Label><Input id={id('when-binding')} value={component.visibleWhen?.binding ?? ''} placeholder={page.context === 'task' ? 'task.status' : 'case.status'} disabled={disabled} onChange={event => set({ visibleWhen: event.target.value ? { binding: event.target.value, equals: component.visibleWhen?.equals ?? '' } : undefined })} /></div>
        <div><Label htmlFor={id('when-equals')}>Equals</Label><Input id={id('when-equals')} value={String(component.visibleWhen?.equals ?? '')} disabled={disabled || !component.visibleWhen} onChange={event => set({ visibleWhen: { binding: component.visibleWhen!.binding, equals: event.target.value } })} /></div>
      </div>
      <p className="text-xs text-muted-foreground">Visibility is presentation only. Hiding a component never grants or removes access.</p>
    </fieldset> : null}
  </section>
}
