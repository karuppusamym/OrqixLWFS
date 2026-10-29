import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowDown, ArrowLeft, ArrowUp, Eye, Plus, RotateCcw, Save } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { getPublishedView, getView, publishView, saveView, type ViewDetail } from '@/api/views'
import { useWorkspace } from '@/app/workspace'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Dialog } from '@/components/ui/dialog'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ACTION_TYPES, FIELD_TYPES, pruneTranslations, moveItem, uniqueId, validateView, type ViewAction, type ViewActionType, type ViewDefinition, type ViewField, type ViewFieldType, type ViewSection } from './model'
import { PresentationEditor } from './PresentationEditor'
import { ViewPreview } from './ViewPreview'
import { WorklistAssignment } from './WorklistAssignment'
import { isPage } from '@/features/pages/model'
import { PageDesigner } from '@/features/pages/PageDesigner'

interface Draft { name: string; description: string; definition: ViewDefinition }

export function ViewEditorPage() {
  const { workspace, can } = useWorkspace()
  const { viewId = '' } = useParams()
  const ws = workspace.id
  const query = useQuery({ queryKey: ['view', ws, viewId], queryFn: () => getView(ws, viewId), enabled: !!viewId && can('workflow.read') })
  if (!can('workflow.read')) return <p role="alert">You do not have permission to read views.</p>
  if (query.isError) return <QueryError error={query.error} onRetry={() => void query.refetch()} what="view" />
  if (query.isPending) return <LoadingBlock label="Loading view" />
  if (isPage(query.data.definition)) return <PageDesigner key={`${ws}:${viewId}:${query.data.draftRevision}`} detail={query.data} />
  return <LoadedViewEditor key={`${ws}:${viewId}:${query.data.draftRevision}`} detail={query.data} />
}

function LoadedViewEditor({ detail }: { detail: ViewDetail }) {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const viewId = detail.id
  const queryClient = useQueryClient()
  const [draft, setDraft] = useState<Draft>(() => ({ name: detail.name, description: detail.description ?? '', definition: detail.definition as ViewDefinition }))
  const [history, setHistory] = useState<Draft[]>([])
  const [dirty, setDirty] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [review, setReview] = useState(false)
  const [narrow, setNarrow] = useState(false)
  const [version, setVersion] = useState<number | null>(null)
  const published = useQuery({ queryKey: ['viewVersion', ws, viewId, version], queryFn: () => getPublishedView(ws, viewId, version!), enabled: version !== null && can('workflow.read') })

  const edit = (transform: (current: Draft) => Draft) => {
    if (!can('workflow.edit')) return
    setHistory(previous => [...previous.slice(-49), draft])
    setDraft(transform(draft))
    setDirty(true)
  }
  const changeDefinition = (transform: (current: ViewDefinition) => ViewDefinition) => edit(current => ({ ...current, definition: pruneTranslations(transform(current.definition)) }))
  const changeSection = (index: number, transform: (section: ViewSection) => ViewSection) => changeDefinition(definition => ({ ...definition, sections: definition.sections.map((section, at) => at === index ? transform(section) : section) }))
  const changeField = (sectionIndex: number, fieldIndex: number, transform: (field: ViewField) => ViewField) => changeSection(sectionIndex, section => ({ ...section, fields: section.fields.map((field, at) => at === fieldIndex ? transform(field) : field) }))
  const changeAction = (index: number, transform: (action: ViewAction) => ViewAction) => changeDefinition(definition => ({ ...definition, actions: definition.actions.map((action, at) => at === index ? transform(action) : action) }))
  const save = async () => {
    setBusy(true)
    setError(null)
    try {
      const updated = await saveView(ws, viewId, { ...draft, expectedRevision: detail.draftRevision })
      queryClient.setQueryData(['view', ws, viewId], updated)
      setHistory([])
      setDirty(false)
    } catch (cause) {
      setError(cause)
    } finally {
      setBusy(false)
    }
  }
  const publish = async () => {
    if (dirty) return
    setBusy(true)
    setError(null)
    try {
      const updated = await publishView(ws, viewId, detail.draftRevision)
      queryClient.setQueryData(['view', ws, viewId], updated)
      await queryClient.invalidateQueries({ queryKey: ['views', ws] })
      setReview(false)
    } catch (cause) {
      setReview(false)
      setError(cause)
    } finally {
      setBusy(false)
    }
  }

  const issues = [...validateView(draft.definition), ...(!draft.name.trim() || draft.name !== draft.name.trim() || draft.name.length > 80 ? ['Give this view a name of at most 80 characters.'] : [])]
  const controls = FIELD_TYPES.filter(type => draft.definition.kind === 'worklist' ? type === 'text' || type === 'number' : draft.definition.kind === 'case' ? type !== 'file' && type !== 'row' : type !== 'boolean')
  const actions = ACTION_TYPES.filter(type => draft.definition.kind === 'worklist' ? type === 'openTask' : draft.definition.kind === 'case' ? type === 'saveData' || type === 'navigate' : type !== 'saveData' && type !== 'openTask')
  const editable = can('workflow.edit')
  const preview = version === null ? draft.definition : published.data?.definition as ViewDefinition | undefined

  return <>
    <Link to={`/w/${ws}/views`} className="mb-4 inline-flex items-center gap-1 text-sm"><ArrowLeft className="size-4" aria-hidden="true" /> All views</Link>
    <PageHeader title={draft.name || 'Untitled view'} description={`Draft revision ${detail.draftRevision}. ${detail.publishedVersion ? `Published version ${detail.publishedVersion}.` : 'Not published yet.'}`} actions={<div className="flex flex-wrap gap-2">
      <Button variant="secondary" onClick={() => {
        const previous = history.at(-1)
        if (!previous) return
        setDraft(previous)
        setHistory(history.slice(0, -1))
        setDirty(true)
      }} disabled={!editable || history.length === 0}><RotateCcw aria-hidden="true" /> Undo</Button>
      {editable ? <Button variant="secondary" onClick={() => void save()} disabled={!dirty || busy || issues.length > 0} loading={busy}><Save aria-hidden="true" /> Save draft</Button> : null}
      {can('workflow.publish') ? <Button onClick={() => setReview(true)} disabled={dirty || busy || issues.length > 0}>Review and publish</Button> : null}
    </div>} />
    {error ? <div className="mb-4"><ProblemAlert error={error} /></div> : null}
    {dirty ? <p role="status" className="mb-4 text-sm text-warning-foreground">Unsaved changes. Save before publishing or switching to a published version.</p> : null}
    <div className="grid items-start gap-6 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
      <div className="space-y-6">
        <section className="space-y-3 rounded-card border border-border bg-surface p-4" aria-label="View properties">
          <h2 className="text-lg font-semibold">View properties</h2>
          <div><Label htmlFor="view-title">Name</Label><Input id="view-title" value={draft.name} disabled={!editable} maxLength={80} onChange={event => edit(current => ({ ...current, name: event.target.value }))} /></div>
          <div><Label htmlFor="view-description">Description</Label><Textarea id="view-description" value={draft.description} disabled={!editable} maxLength={300} onChange={event => edit(current => ({ ...current, description: event.target.value }))} /></div>
          <div><Label htmlFor="view-kind">Kind</Label><Select id="view-kind" value={draft.definition.kind} disabled={!editable} onChange={event => changeDefinition(definition => ({ ...definition, kind: event.target.value as ViewDefinition['kind'] }))}><option value="request">Request</option><option value="approval">Approval</option><option value="case">Case</option><option value="worklist">Worklist</option></Select></div>
          {draft.definition.kind === 'worklist' ? <p className="text-sm text-muted-foreground">Use one section and up to ten read-only columns. Include task.requestTitle and one Open task action. Other text columns: task.reference, task.label, task.workflowName, task.requester, task.createdAt, task.dueAt, task.status and task.candidateGroup. Use a number control for task.priority.</p> : null}
          {draft.definition.kind === 'case' ? <p className="text-sm text-muted-foreground">Bind every case field once with case.fieldName, matching types and required flags. Include one Save data action. Select a published version when creating a case definition; current case permissions still control editing.</p> : null}
          {draft.definition.kind === 'approval' ? <p className="text-sm text-muted-foreground">To reuse this view on a workflow approval, bind each request field once with request.fieldName and matching types and required flags. Include one Approve and one Reject action. Request details are read-only; comments and authorized corrections use the task page controls.</p> : null}
        </section>
        <section className="space-y-3 rounded-card border border-border bg-surface p-4" aria-label="Sections and fields">
          <div className="flex items-center justify-between"><h2 className="text-lg font-semibold">Sections and fields</h2><Button size="sm" variant="secondary" disabled={!editable} onClick={() => changeDefinition(definition => ({ ...definition, sections: [...definition.sections, { id: uniqueId('section', definition.sections.map(section => section.id)), label: 'New section', columns: 1, fields: [] }] }))}><Plus aria-hidden="true" /> Add section</Button></div>
          {draft.definition.sections.map((section, sectionIndex) => <fieldset key={section.id} className="space-y-3 rounded border border-border p-3"><legend className="px-1 font-semibold">Section {sectionIndex + 1}</legend>
            <div className="flex gap-1"><Button size="icon" variant="ghost" aria-label={`Move section ${section.label} up`} disabled={!editable || sectionIndex === 0} onClick={() => changeDefinition(definition => ({ ...definition, sections: moveItem(definition.sections, sectionIndex, -1) }))}><ArrowUp aria-hidden="true" /></Button><Button size="icon" variant="ghost" aria-label={`Move section ${section.label} down`} disabled={!editable || sectionIndex === draft.definition.sections.length - 1} onClick={() => changeDefinition(definition => ({ ...definition, sections: moveItem(definition.sections, sectionIndex, 1) }))}><ArrowDown aria-hidden="true" /></Button><Button variant="ghost" size="sm" disabled={!editable || draft.definition.sections.length === 1} onClick={() => changeDefinition(definition => ({ ...definition, sections: definition.sections.filter((_, at) => at !== sectionIndex) }))}>Remove</Button></div>
            <div><Label htmlFor={`section-${section.id}-label`}>Section label</Label><Input id={`section-${section.id}-label`} value={section.label} disabled={!editable} onChange={event => changeSection(sectionIndex, old => ({ ...old, label: event.target.value }))} /></div>
            <div><Label htmlFor={`section-${section.id}-columns`}>Columns</Label><Select id={`section-${section.id}-columns`} value={section.columns} disabled={!editable} onChange={event => changeSection(sectionIndex, old => ({ ...old, columns: Number(event.target.value) as 1 | 2 }))}><option value="1">One</option><option value="2">Two on larger screens</option></Select></div>
            {section.fields.map((field, fieldIndex) => <fieldset key={field.id} className="space-y-2 rounded border border-border bg-surface-muted p-3"><legend className="px-1 text-sm font-semibold">{field.label || field.id}</legend>
              <div className="flex gap-1"><Button size="icon" variant="ghost" aria-label={`Move field ${field.id} up`} disabled={!editable || fieldIndex === 0} onClick={() => changeSection(sectionIndex, old => ({ ...old, fields: moveItem(old.fields, fieldIndex, -1) }))}><ArrowUp aria-hidden="true" /></Button><Button size="icon" variant="ghost" aria-label={`Move field ${field.id} down`} disabled={!editable || fieldIndex === section.fields.length - 1} onClick={() => changeSection(sectionIndex, old => ({ ...old, fields: moveItem(old.fields, fieldIndex, 1) }))}><ArrowDown aria-hidden="true" /></Button><Button variant="ghost" size="sm" disabled={!editable} onClick={() => changeSection(sectionIndex, old => ({ ...old, fields: old.fields.filter((_, at) => at !== fieldIndex) }))}>Remove</Button></div>
              <div><Label htmlFor={`field-${field.id}-label`}>Question label</Label><Input id={`field-${field.id}-label`} value={field.label} disabled={!editable} onChange={event => changeField(sectionIndex, fieldIndex, old => ({ ...old, label: event.target.value }))} /></div>
              <div className="grid gap-2 sm:grid-cols-2"><div><Label htmlFor={`field-${field.id}-type`}>Control</Label><Select id={`field-${field.id}-type`} value={field.type} disabled={!editable} onChange={event => changeField(sectionIndex, fieldIndex, old => ({ ...old, type: event.target.value as ViewFieldType, ...(event.target.value === 'choice' ? { options: old.options?.length ? old.options : [{ value: 'option1', label: 'Option 1' }] } : { options: undefined }) }))}>{controls.map(type => <option key={type} value={type}>{type}</option>)}</Select></div><div><Label htmlFor={`field-${field.id}-binding`}>Data binding</Label><Input id={`field-${field.id}-binding`} value={field.binding} disabled={!editable} placeholder={draft.definition.kind === 'case' ? 'case.fieldKey' : draft.definition.kind === 'worklist' ? 'task.requestTitle' : 'request.fieldKey'} onChange={event => changeField(sectionIndex, fieldIndex, old => ({ ...old, binding: event.target.value }))} /></div></div>
              <div className="flex items-center gap-2"><Checkbox id={`field-${field.id}-required`} checked={!!field.required} disabled={!editable} onCheckedChange={checked => changeField(sectionIndex, fieldIndex, old => ({ ...old, required: checked === true }))} /><Label htmlFor={`field-${field.id}-required`}>Required</Label></div>
              {field.type === 'choice' ? <div><Label htmlFor={`field-${field.id}-options`}>Choices (one value | label per line)</Label><Textarea id={`field-${field.id}-options`} disabled={!editable} value={field.options?.map(option => `${option.value} | ${option.label}`).join('\n') ?? ''} onChange={event => changeField(sectionIndex, fieldIndex, old => ({ ...old, options: event.target.value.split('\n').filter(Boolean).map(line => { const [value, ...label] = line.split('|'); return { value: value?.trim() ?? '', label: label.join('|').trim() } }) }))} /></div> : null}
            </fieldset>)}
            <div className="flex flex-wrap gap-2">{controls.map(type => <Button key={type} variant="secondary" size="sm" disabled={!editable} onClick={() => changeSection(sectionIndex, old => ({ ...old, fields: [...old.fields, { id: uniqueId('field', draft.definition.sections.flatMap(item => item.fields.map(field => field.id))), label: `New ${type} question`, type, binding: `${draft.definition.kind === 'case' ? 'case' : draft.definition.kind === 'worklist' ? 'task' : 'request'}.${uniqueId('field', draft.definition.sections.flatMap(item => item.fields.map(field => field.id)))}`, ...(type === 'choice' ? { options: [{ value: 'option1', label: 'Option 1' }] } : {}) }] }))}><Plus aria-hidden="true" /> {type}</Button>)}</div>
          </fieldset>)}
        </section>
        <section className="space-y-3 rounded-card border border-border bg-surface p-4" aria-label="Actions"><h2 className="text-lg font-semibold">Actions</h2>
          {draft.definition.actions.map((action, index) => <fieldset key={action.id} className="space-y-2 rounded border border-border p-3"><legend className="px-1 font-semibold">{action.label || action.id}</legend>
            <div className="flex gap-1"><Button size="icon" variant="ghost" aria-label={`Move action ${action.id} up`} disabled={!editable || index === 0} onClick={() => changeDefinition(definition => ({ ...definition, actions: moveItem(definition.actions, index, -1) }))}><ArrowUp aria-hidden="true" /></Button><Button size="icon" variant="ghost" aria-label={`Move action ${action.id} down`} disabled={!editable || index === draft.definition.actions.length - 1} onClick={() => changeDefinition(definition => ({ ...definition, actions: moveItem(definition.actions, index, 1) }))}><ArrowDown aria-hidden="true" /></Button><Button variant="ghost" size="sm" disabled={!editable} onClick={() => changeDefinition(definition => ({ ...definition, actions: definition.actions.filter((_, at) => at !== index) }))}>Remove</Button></div>
            <div><Label htmlFor={`action-${action.id}-label`}>Action label</Label><Input id={`action-${action.id}-label`} value={action.label} disabled={!editable} onChange={event => changeAction(index, old => ({ ...old, label: event.target.value }))} /></div>
            <div><Label htmlFor={`action-${action.id}-type`}>Action type</Label><Select id={`action-${action.id}-type`} value={action.type} disabled={!editable} onChange={event => changeAction(index, old => ({ ...old, type: event.target.value as ViewActionType, target: undefined }))}>{actions.map(type => <option key={type} value={type}>{type}</option>)}</Select></div>
            {action.type === 'navigate' ? <div><Label htmlFor={`action-${action.id}-target`}>Same-app path</Label><Input id={`action-${action.id}-target`} value={action.target ?? ''} disabled={!editable} placeholder="/w/..." onChange={event => changeAction(index, old => ({ ...old, target: event.target.value }))} /></div> : null}
          </fieldset>)}
          <Button variant="secondary" size="sm" disabled={!editable || draft.definition.actions.length >= 10} onClick={() => changeDefinition(definition => ({ ...definition, actions: [...definition.actions, { id: uniqueId('action', definition.actions.map(action => action.id)), label: definition.kind === 'request' ? 'Submit' : definition.kind === 'case' ? 'Save case data' : definition.kind === 'worklist' ? 'Open task' : 'Approve', type: definition.kind === 'request' ? 'submit' : definition.kind === 'case' ? 'saveData' : definition.kind === 'worklist' ? 'openTask' : 'approve' }] }))}><Plus aria-hidden="true" /> Add action</Button>
        </section>
      </div>
      <div className="space-y-4 xl:sticky xl:top-20">
        {draft.definition.kind==='worklist' ? <WorklistAssignment id={viewId} version={version} /> : null}
        <section className="space-y-3 rounded-card border border-border bg-surface-muted p-4" aria-label="Validation and bindings"><h2 className="text-lg font-semibold">Bindings and review</h2>
          {issues.length ? <ul className="list-disc space-y-1 pl-5 text-sm text-danger">{issues.map(issue => <li key={issue}>{issue}</li>)}</ul> : <Badge tone="success">Local checks passed</Badge>}
          <p className="text-xs text-muted-foreground">Server validation is authoritative. A published workflow must pin a compatible version.</p>
          <ul className="space-y-1 text-xs">{draft.definition.sections.flatMap(section => section.fields.map(field => <li key={field.id}><strong>{field.label}</strong> → {field.binding || 'Unbound'}</li>))}</ul>
        </section>
        <PresentationEditor definition={draft.definition} disabled={!editable||busy} onChange={presentation=>changeDefinition(view=>({...view,presentation}))}/>
        <section className="space-y-3" aria-label="Preview"><div className="flex flex-wrap items-center gap-2"><h2 className="mr-auto text-lg font-semibold"><Eye className="mr-1 inline size-4" aria-hidden="true" /> Preview</h2><Label htmlFor="preview-version" className="sr-only">Preview version</Label><Select id="preview-version" className="w-auto" value={version ?? 'draft'} onChange={event => setVersion(event.target.value === 'draft' ? null : Number(event.target.value))}><option value="draft">Current draft</option>{detail.versions.map(item => <option key={item.version} value={item.version}>Published v{item.version}</option>)}</Select><Button size="sm" variant="secondary" onClick={() => setNarrow(!narrow)}>{narrow ? 'Desktop' : 'Mobile'}</Button></div>
          {version !== null && published.isPending ? <LoadingBlock label="Loading published view" /> : version !== null && published.isError ? <QueryError error={published.error} onRetry={() => void published.refetch()} what="published view" /> : preview ? <ViewPreview definition={preview} narrow={narrow} /> : null}
        </section>
      </div>
    </div>
    <Dialog open={review} onClose={() => setReview(false)} title="Review publication" description="Publishing creates a fixed version that can be pinned by workflows or cases." footer={<><Button variant="secondary" onClick={() => setReview(false)} disabled={busy}>Cancel</Button><Button onClick={() => void publish()} disabled={dirty || issues.length > 0 || busy} loading={busy}>Publish version {(detail.publishedVersion ?? 0) + 1}</Button></>}>
      <p className="mb-2 font-medium">{draft.name} · {draft.definition.kind}</p>
      <p className="text-sm">{draft.definition.sections.length} sections, {draft.definition.sections.reduce((count, section) => count + section.fields.length, 0)} fields, {draft.definition.actions.length} actions.</p>
      {draft.definition.presentation?<p className="mt-2 text-sm">Presentation: {draft.definition.presentation.baseLocale}; {draft.definition.presentation.breakpoint} breakpoint; {draft.definition.presentation.density}; {draft.definition.presentation.tone}. Translated languages: {Object.keys(draft.definition.presentation.translations).join(', ')||'none'}.</p>:null}
      <p className="mt-2 text-sm text-muted-foreground">Draft revision {detail.draftRevision}. {dirty ? 'Save the draft first.' : issues.length ? 'Fix the listed issues first.' : 'Local checks passed; the server will validate the definition and your permission.'}</p>
    </Dialog>
  </>
}
