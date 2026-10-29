import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowDown, ArrowLeft, ArrowLeftRight, ArrowUp, Eye, Plus, RotateCcw, Save, Trash2 } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link } from 'react-router'
import { listExtensions } from '@/api/extensions'
import { assignAppPage, listAppPages } from '@/api/pages'
import type { components } from '@/api/schema'
import { getPublishedView, listViews, publishView, saveView, type ViewDetail } from '@/api/views'
import { useWorkspace } from '@/app/workspace'
import { PageHeader } from '@/components/common/PageHeader'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { moveItem, type ViewDefinition } from '@/features/views/model'
import { ChainEditor } from './ChainEditor'
import { ComponentInspector } from './ComponentInspector'
import { allComponents, COMPONENT_LABELS, LAYOUT_REGIONS, newComponent, pinKey, STEP_LABELS, validatePage, withLayout, type ComponentType, type ExtensionInfo, type PageComponent, type PageDefinition, type PageLookups } from './model'
import { PageCanvas } from './PageCanvas'

interface Draft { name: string; description: string; definition: PageDefinition }
type ExtensionUse = components['schemas']['AppPageExtensionUse']
const PALETTE: ComponentType[] = ['heading', 'text', 'view', 'list', 'details', 'actions', 'extension']
const DEVICES = { desktop: { label: 'Desktop', width: 1200 }, tablet: { label: 'Tablet', width: 768 }, mobile: { label: 'Mobile', width: 375 } } as const
const BREAKPOINT_PX = { sm: 640, md: 768, lg: 1024 } as const

/** UI02 / S111 visual page designer inside the view library. Server validation and publication remain authoritative. */
export function PageDesigner({ detail }: { detail: ViewDetail }) {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const queryClient = useQueryClient()
  const [draft, setDraft] = useState<Draft>(() => ({ name: detail.name, description: detail.description ?? '', definition: detail.definition as unknown as PageDefinition }))
  const [history, setHistory] = useState<Draft[]>([])
  const [dirty, setDirty] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [review, setReview] = useState(false)
  const [device, setDevice] = useState<keyof typeof DEVICES>('desktop')
  const [selected, setSelected] = useState<string | null>(() => allComponents(detail.definition as unknown as PageDefinition)[0]?.id ?? null)
  const [region, setRegion] = useState(0)
  const editable = can('workflow.edit')
  const page = draft.definition

  const library = useQuery({ queryKey: ['views', ws], queryFn: () => listViews(ws) })
  const extensionList = useQuery({ queryKey: ['uiExtensions', ws], queryFn: () => listExtensions(ws) })
  const pins = useMemo(() => [...new Map(allComponents(page).flatMap(component => component.view?.id && component.view.version >= 1 ? [[pinKey(component.view), component.view]] as const : [])).values()], [page])
  const pinned = useQueries({ queries: pins.map(pin => ({ queryKey: ['viewVersion', ws, pin.id, pin.version], queryFn: () => getPublishedView(ws, pin.id, pin.version), retry: false })) })
  const lookups: PageLookups = useMemo(() => {
    const views = new Map<string, ViewDefinition | PageDefinition>()
    pinned.forEach((query, index) => { if (query.data) views.set(pinKey(pins[index]!), query.data.definition as unknown as ViewDefinition | PageDefinition) })
    const extensions = new Map<string, ExtensionInfo>()
    for (const item of extensionList.data?.items ?? []) for (const version of item.versions) extensions.set(`${item.id}:${version.version}`, { id: item.id, version: version.version, name: item.name, status: version.status, capabilities: version.capabilities as ExtensionInfo['capabilities'], height: version.height })
    return { views, extensions }
  }, [pinned, pins, extensionList.data])
  const missingPins = pinned.flatMap((query, index) => query.isError ? [pins[index]!] : [])

  const edit = (transform: (current: Draft) => Draft) => {
    if (!editable) return
    setHistory(previous => [...previous.slice(-49), draft])
    setDraft(transform(draft))
    setDirty(true)
  }
  const changePage = (transform: (current: PageDefinition) => PageDefinition) => edit(current => ({ ...current, definition: transform(current.definition) }))
  const changeComponent = (id: string, transform: (component: PageComponent) => PageComponent) => changePage(current => ({ ...current, regions: current.regions.map(item => ({ ...item, components: item.components.map(component => component.id === id ? transform(component) : component) })) }))
  const locate = (id: string) => { for (const [r, item] of page.regions.entries()) { const c = item.components.findIndex(component => component.id === id); if (c >= 0) return { r, c } } return null }
  const move = (id: string, delta: -1 | 1) => { const at = locate(id); if (at) changePage(current => ({ ...current, regions: current.regions.map((item, r) => r === at.r ? { ...item, components: moveItem(item.components, at.c, delta) } : item) })) }
  const moveRegion = (id: string) => {
    const at = locate(id)
    if (!at || page.regions.length < 2) return
    const target = (at.r + 1) % page.regions.length
    changePage(current => { const component = current.regions[at.r]!.components[at.c]!; return { ...current, regions: current.regions.map((item, r) => r === at.r ? { ...item, components: item.components.filter((_, c) => c !== at.c) } : r === target ? { ...item, components: [...item.components, component] } : item) } })
  }
  const remove = (id: string) => {
    changePage(current => ({ ...current, regions: current.regions.map(item => ({ ...item, components: item.components.filter(component => component.id !== id) })), chains: current.chains.map(chain => ({ ...chain, steps: chain.steps.filter(step => step.component !== id) })).filter(chain => chain.steps.length > 0) }))
    if (selected === id) setSelected(null)
  }
  const add = (type: ComponentType) => {
    const component = newComponent(page, type)
    const target = Math.min(region, page.regions.length - 1)
    changePage(current => ({ ...current, regions: current.regions.map((item, r) => r === target ? { ...item, components: [...item.components, component] } : item) }))
    setSelected(component.id)
  }

  const save = async () => {
    setBusy(true); setError(null)
    try {
      const updated = await saveView(ws, detail.id, { name: draft.name, description: draft.description, definition: draft.definition as never, expectedRevision: detail.draftRevision })
      queryClient.setQueryData(['view', ws, detail.id], updated)
      setHistory([]); setDirty(false)
    } catch (cause) { setError(cause) } finally { setBusy(false) }
  }
  const publish = async () => {
    setBusy(true); setError(null)
    try {
      const updated = await publishView(ws, detail.id, detail.draftRevision)
      queryClient.setQueryData(['view', ws, detail.id], updated)
      await queryClient.invalidateQueries({ queryKey: ['views', ws] })
      setReview(false)
    } catch (cause) { setReview(false); setError(cause) } finally { setBusy(false) }
  }

  const issues = [...validatePage(page, lookups), ...missingPins.map(pin => `A pinned view version (${pin.id.slice(0, 8)} v${pin.version}) is not available in this workspace.`), ...(!draft.name.trim() || draft.name !== draft.name.trim() || draft.name.length > 80 ? ['Give this page a name of at most 80 characters.'] : [])]
  const publishIssues = [...validatePage(page, lookups, true), ...issues].filter((issue, index, all) => all.indexOf(issue) === index)
  const selectedComponent = selected ? allComponents(page).find(component => component.id === selected) : undefined
  const viewsForCanvas = new Map([...lookups.views].filter((entry): entry is [string, ViewDefinition] => entry[1].kind !== 'page'))
  const extensionsForCanvas = new Map<string, ExtensionUse>([...lookups.extensions].map(([key, info]) => [key, { ...info, sha256: '0'.repeat(64) }]))
  const columns = DEVICES[device].width >= BREAKPOINT_PX[page.breakpoint] && page.layout !== 'single' ? 2 : 1
  const libraryName = (id: string) => library.data?.items.find(view => view.id === id)?.name ?? id.slice(0, 8)

  return <>
    <Link to={`/w/${ws}/views`} className="mb-4 inline-flex items-center gap-1 text-sm"><ArrowLeft className="size-4" aria-hidden="true" /> All views</Link>
    <PageHeader title={draft.name || 'Untitled page'} description={`Application page. Draft revision ${detail.draftRevision}. ${detail.publishedVersion ? `Published version ${detail.publishedVersion}.` : 'Not published yet.'}`} actions={<div role="group" aria-label="Designer actions" className="flex flex-wrap gap-2">
      <Button variant="secondary" disabled={!editable || history.length === 0} onClick={() => { const previous = history.at(-1); if (!previous) return; setDraft(previous); setHistory(history.slice(0, -1)); setDirty(true) }}><RotateCcw aria-hidden="true" /> Undo</Button>
      {editable ? <Button variant="secondary" onClick={() => void save()} disabled={!dirty || busy || issues.length > 0} loading={busy}><Save aria-hidden="true" /> Save draft</Button> : null}
      {can('workflow.publish') ? <Button onClick={() => setReview(true)} disabled={dirty || busy}>Review and publish</Button> : null}
    </div>} />
    {error ? <div className="mb-4"><ProblemAlert error={error} /></div> : null}
    {dirty ? <p role="status" className="mb-4 text-sm text-warning-foreground">Unsaved changes. Save before publishing.</p> : null}
    <div className="grid items-start gap-6 xl:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)]">
      <div className="space-y-6">
        <section className="space-y-3 rounded-card border border-border bg-surface p-4" aria-label="Page properties">
          <h2 className="text-lg font-semibold">Page properties</h2>
          <div><Label htmlFor="page-name">Library name</Label><Input id="page-name" value={draft.name} maxLength={80} disabled={!editable} onChange={event => edit(current => ({ ...current, name: event.target.value }))} /></div>
          <div><Label htmlFor="page-description">Description</Label><Textarea id="page-description" value={draft.description} maxLength={300} disabled={!editable} onChange={event => edit(current => ({ ...current, description: event.target.value }))} /></div>
          <div><Label htmlFor="page-title">Page title</Label><Input id="page-title" value={page.title} maxLength={120} disabled={!editable} onChange={event => changePage(current => ({ ...current, title: event.target.value }))} /></div>
          <div className="grid gap-3 sm:grid-cols-3">
            <div><Label htmlFor="page-context">Opens for</Label><Select id="page-context" value={page.context} disabled={!editable} onChange={event => changePage(current => ({ ...current, context: event.target.value as PageDefinition['context'] }))}><option value="none">No record</option><option value="task">A task</option><option value="case">A case</option></Select></div>
            <div><Label htmlFor="page-layout">Layout</Label><Select id="page-layout" value={page.layout} disabled={!editable} onChange={event => { changePage(current => withLayout(current, event.target.value as PageDefinition['layout'])); setRegion(0) }}><option value="single">One column</option><option value="sidebar">Main with side panel</option><option value="split">Two equal columns</option></Select></div>
            <div><Label htmlFor="page-breakpoint">Columns from</Label><Select id="page-breakpoint" value={page.breakpoint} disabled={!editable} onChange={event => changePage(current => ({ ...current, breakpoint: event.target.value as PageDefinition['breakpoint'] }))}><option value="sm">640 px</option><option value="md">768 px</option><option value="lg">1024 px</option></Select></div>
          </div>
        </section>

        <section className="space-y-3 rounded-card border border-border bg-surface p-4" aria-label="Component palette">
          <h2 className="text-lg font-semibold">Palette</h2>
          {page.regions.length > 1 ? <div><Label htmlFor="palette-region">Add to region</Label><Select id="palette-region" value={region} onChange={event => setRegion(Number(event.target.value))}>{page.regions.map((item, index) => <option key={item.id} value={index}>{item.label}</option>)}</Select></div> : null}
          <div className="flex flex-wrap gap-2">{PALETTE.map(type => <Button key={type} size="sm" variant="secondary" disabled={!editable || allComponents(page).length >= 24} onClick={() => add(type)}><Plus aria-hidden="true" /> {COMPONENT_LABELS[type]}</Button>)}</div>
        </section>

        <section className="space-y-3 rounded-card border border-border bg-surface p-4" aria-label="Page outline">
          <h2 className="text-lg font-semibold">Outline</h2>
          {page.regions.map((item, r) => <fieldset key={item.id} className="space-y-2 rounded border border-border p-3"><legend className="px-1 font-semibold">{LAYOUT_REGIONS[page.layout][r]?.label} region</legend>
            <div><Label htmlFor={`region-${item.id}-label`}>Region label</Label><Input id={`region-${item.id}-label`} value={item.label} maxLength={120} disabled={!editable} onChange={event => changePage(current => ({ ...current, regions: current.regions.map((other, at) => at === r ? { ...other, label: event.target.value } : other) }))} /></div>
            {item.components.length === 0 ? <p className="text-sm text-muted-foreground">Empty. Add components from the palette.</p> : null}
            <ol className="space-y-1">{item.components.map((component, c) => {
              const name = component.label ?? component.text ?? component.id
              return <li key={component.id} className={`flex flex-wrap items-center gap-1 rounded p-1 ${selected === component.id ? 'bg-primary-soft' : ''}`}>
                <Button variant="ghost" size="sm" className="mr-auto min-w-0 justify-start" aria-pressed={selected === component.id} onClick={() => setSelected(component.id)}><span className="truncate">{COMPONENT_LABELS[component.type]}: {name}</span></Button>
                <Button size="icon" variant="ghost" aria-label={`Move ${component.id} up`} disabled={!editable || c === 0} onClick={() => move(component.id, -1)}><ArrowUp aria-hidden="true" /></Button>
                <Button size="icon" variant="ghost" aria-label={`Move ${component.id} down`} disabled={!editable || c === item.components.length - 1} onClick={() => move(component.id, 1)}><ArrowDown aria-hidden="true" /></Button>
                {page.regions.length > 1 ? <Button size="icon" variant="ghost" aria-label={`Move ${component.id} to the other region`} disabled={!editable} onClick={() => moveRegion(component.id)}><ArrowLeftRight aria-hidden="true" /></Button> : null}
                <Button size="icon" variant="ghost" aria-label={`Remove ${component.id}`} disabled={!editable} onClick={() => remove(component.id)}><Trash2 aria-hidden="true" /></Button>
              </li>
            })}</ol>
          </fieldset>)}
        </section>

        {selectedComponent ? <ComponentInspector key={selectedComponent.id} ws={ws} page={page} component={selectedComponent} library={library.data?.items ?? []} extensions={extensionList.data?.items ?? []} disabled={!editable} onChange={next => changeComponent(selectedComponent.id, () => next)} /> : null}
        <ChainEditor page={page} lookups={lookups} disabled={!editable} onChange={chains => changePage(current => ({ ...current, chains }))} />
      </div>

      <div className="space-y-4 xl:sticky xl:top-20">
        <section className="space-y-3 rounded-card border border-border bg-surface-muted p-4" aria-label="Bindings and problems">
          <h2 className="text-lg font-semibold">Bindings and problems</h2>
          {issues.length ? <ul className="list-disc space-y-1 pl-5 text-sm text-danger">{issues.map(issue => <li key={issue}>{issue}</li>)}</ul> : <Badge tone="success">Local checks passed</Badge>}
          <ul className="space-y-1 text-xs">
            {allComponents(page).flatMap(component => component.view?.id ? [<li key={component.id}><strong>{component.id}</strong> → view {libraryName(component.view.id)} v{component.view.version}{lookups.views.get(pinKey(component.view)) ? ` (${lookups.views.get(pinKey(component.view))!.kind})` : ''}{component.workflowId ? ' · submits to a workflow' : ''}</li>] : [])}
            {allComponents(page).flatMap(component => component.extension?.id ? [<li key={component.id}><strong>{component.id}</strong> → extension {lookups.extensions.get(pinKey(component.extension))?.name ?? 'unknown'} v{component.extension.version} ({lookups.extensions.get(pinKey(component.extension))?.status ?? 'not found'}); grants {component.bindings?.join(', ') || 'no data'}</li>] : [])}
          </ul>
          <p className="text-xs text-muted-foreground">Server validation is authoritative: pins, workflow inputs, view actions, extension approval and every runtime step are checked again.</p>
        </section>
        <section className="space-y-3" aria-label="Preview"><div className="flex flex-wrap items-center gap-2"><h2 className="mr-auto text-lg font-semibold"><Eye className="mr-1 inline size-4" aria-hidden="true" /> Preview</h2>
          <Label htmlFor="preview-device" className="sr-only">Preview width</Label><Select id="preview-device" className="w-auto" value={device} onChange={event => setDevice(event.target.value as keyof typeof DEVICES)}>{Object.entries(DEVICES).map(([key, item]) => <option key={key} value={key}>{item.label} ({item.width} px)</option>)}</Select></div>
          <div className="mx-auto w-full overflow-hidden rounded-card border border-border bg-background p-4 shadow-sm" style={{ maxWidth: DEVICES[device].width }} data-testid="page-preview" data-columns={columns}>
            <h3 className="mb-3 text-xl font-semibold">{page.title}</h3>
            <PageCanvas key={JSON.stringify(page)} page={page} views={viewsForCanvas} extensions={extensionsForCanvas} preview={{ columns }} />
          </div>
        </section>
        {can('workflow.publish') && detail.publishedVersion ? <RouteAssignment key={detail.publishedVersion} pageId={detail.id} versions={detail.versions.map(version => version.version).sort((a, b) => b - a)} /> : null}
      </div>
    </div>
    <Dialog open={review} onClose={() => setReview(false)} title="Review publication" description="Publishing creates a fixed page version. Routes keep the version they pin until a publisher changes them." footer={<><Button variant="secondary" onClick={() => setReview(false)} disabled={busy}>Cancel</Button><Button onClick={() => void publish()} disabled={dirty || publishIssues.length > 0 || busy} loading={busy}>Publish version {(detail.publishedVersion ?? 0) + 1}</Button></>}>
      <p className="mb-2 font-medium">{page.title} · opens for {page.context === 'none' ? 'no record' : `a ${page.context}`} · {page.layout} layout</p>
      <ul className="list-disc space-y-1 pl-5 text-sm">
        {page.regions.map(item => <li key={item.id}>{item.label}: {item.components.map(component => COMPONENT_LABELS[component.type]).join(', ') || 'empty'}</li>)}
        {page.chains.map(chain => <li key={chain.id}>Chain “{chain.label}”: {chain.steps.map(step => STEP_LABELS[step.action]).join(' → ')}, stops at the first failure</li>)}
      </ul>
      {publishIssues.length ? <ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-danger">{publishIssues.map(issue => <li key={issue}>{issue}</li>)}</ul> : <p className="mt-2 text-sm text-muted-foreground">Local checks passed; the server validates the page, its pins, extension approval and your permission.</p>}
    </Dialog>
  </>
}

/** Pins an exact published version to a route. Revision checks stop two publishers overwriting each other. */
function RouteAssignment({ pageId, versions }: { pageId: string; versions: number[] }) {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const queryClient = useQueryClient()
  const routes = useQuery({ queryKey: ['appPages', ws], queryFn: () => listAppPages(ws) })
  const [slug, setSlug] = useState('')
  const [version, setVersion] = useState(versions[0] ?? 1)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const mine = (routes.data?.items ?? []).filter(route => route.page?.id === pageId)
  const assign = async () => {
    const current = routes.data?.items.find(route => route.slug === slug)
    setBusy(true); setError(null)
    try {
      await assignAppPage(ws, slug, current?.revision ?? 0, { id: pageId, version })
      await queryClient.invalidateQueries({ queryKey: ['appPages', ws] })
    } catch (cause) { setError(cause) } finally { setBusy(false) }
  }
  return <section className="space-y-3 rounded-card border border-border bg-surface p-4" aria-label="Routes">
    <h2 className="text-lg font-semibold">Routes</h2>
    {mine.length ? <ul className="space-y-1 text-sm">{mine.map(route => <li key={route.slug}><Link to={`/w/${ws}/apps/${route.slug}`}>/{route.slug}</Link> pins version {route.page!.version}</li>)}</ul> : <p className="text-sm text-muted-foreground">No route shows this page yet.</p>}
    <div className="grid gap-2 sm:grid-cols-[minmax(0,1fr)_auto_auto]">
      <div><Label htmlFor="route-slug">Route</Label><Input id="route-slug" value={slug} placeholder="purchase-desk" maxLength={40} onChange={event => setSlug(event.target.value.toLowerCase())} /></div>
      <div><Label htmlFor="route-version">Version</Label><Select id="route-version" value={version} onChange={event => setVersion(Number(event.target.value))}>{versions.map(item => <option key={item} value={item}>Version {item}</option>)}</Select></div>
      <div className="flex items-end"><Button onClick={() => void assign()} disabled={busy || !/^[a-z][a-z0-9-]{0,38}[a-z0-9]$/.test(slug)} loading={busy}>Pin to route</Button></div>
    </div>
    {error ? <ProblemAlert error={error} /> : null}
  </section>
}
