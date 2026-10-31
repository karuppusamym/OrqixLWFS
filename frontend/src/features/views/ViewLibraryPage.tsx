import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Copy, LayoutTemplate, Plus } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { copyView, createView, getView, listViews, type ViewSummary } from '@/api/views'
import { useWorkspace } from '@/app/workspace'
import { EmptyState } from '@/components/common/EmptyState'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { RelativeTime } from '@/components/common/RelativeTime'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { blankView, type ViewDefinition, type ViewKind } from './model'
import { ViewPreview } from './ViewPreview'
import { listViewPins } from '@/api/viewPins'
import { blankPage } from '@/features/pages/model'

export function ViewLibraryPage() {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const [params, setParams] = useSearchParams()
  const selectedKind = params.get('kind') ?? ''
  const search = params.get('q') ?? ''
  const setFilter = (key: string, value: string) => { const next = new URLSearchParams(params); if (value) next.set(key,value); else next.delete(key); setParams(next, { replace: true }) }
  const [preview, setPreview] = useState<ViewSummary | null>(null)
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const query = useQuery({ queryKey: ['views', ws], queryFn: () => listViews(ws), enabled: can('workflow.read') })
  const [dialog, setDialog] = useState<{ mode: 'create' | 'copy'; source?: ViewSummary } | null>(null)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [kind, setKind] = useState<ViewKind | 'page'>('request')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const open = (mode: 'create' | 'copy', source?: ViewSummary) => {
    setName(source ? `${source.name.slice(0, 75)} copy` : '')
    setDescription('')
    setKind(source?.kind ?? 'request')
    setError(null)
    setDialog({ mode, source })
  }
  const submit = async () => {
    if (!dialog || !name.trim()) return
    setBusy(true)
    setError(null)
    try {
      const detail = dialog.mode === 'copy' && dialog.source
        ? await copyView(ws, dialog.source.id, name.trim())
        : await createView(ws, { name: name.trim(), description: description.trim(), definition: kind === 'page' ? blankPage() as never : blankView(kind) })
      await queryClient.invalidateQueries({ queryKey: ['views', ws] })
      setDialog(null)
      await navigate(`/w/${ws}/views/${detail.id}`)
    } catch (cause) {
      setError(cause)
    } finally {
      setBusy(false)
    }
  }

  const items = query.data?.items.filter(view => (!selectedKind || view.kind === selectedKind) && `${view.name} ${view.description ?? ''}`.toLowerCase().includes(search.trim().toLowerCase())) ?? []
  if (!can('workflow.read')) return <p role="alert">You do not have permission to read views.</p>
  return <>
    <PageHeader title="Views" description="Reusable request, approval, case and worklist screens and the application pages that compose them. Published versions stay fixed when a draft changes." actions={<div className="flex flex-wrap gap-2"><Button variant="secondary" asChild><Link to={`/w/${ws}/extensions`}>Extensions</Link></Button>{can('workflow.edit') ? <Button onClick={() => open('create')}><Plus aria-hidden="true" /> New view</Button> : null}</div>} />
    <div className="mb-4 grid gap-3 rounded-card border border-border bg-surface p-4 sm:grid-cols-2">
      <div><Label htmlFor="view-search">Search views</Label><Input id="view-search" type="search" value={search} onChange={event => setFilter('q',event.target.value)} /></div>
      <div><Label htmlFor="view-filter-kind">View kind</Label><Select id="view-filter-kind" value={selectedKind} onChange={event => setFilter('kind',event.target.value)}><option value="">All kinds</option>{['request','approval','case','worklist','page'].map(value => <option key={value} value={value}>{value === 'page' ? 'Application page' : value.charAt(0).toUpperCase()+value.slice(1)}</option>)}</Select></div>
    </div>
    {preview ? <LibraryPreview ws={ws} view={preview} onClose={() => setPreview(null)} /> : null}
    {query.isPending ? <LoadingBlock label="Loading views" /> : query.isError ? <QueryError error={query.error} onRetry={() => void query.refetch()} what="views" /> : items.length === 0 ? <EmptyState icon={LayoutTemplate} title={query.data.items.length ? "No views match these filters" : "No views yet"}>{query.data.items.length ? 'Change the search or choose another kind.' : can('workflow.edit') ? 'Create a view to design a reusable screen.' : 'Ask a designer to create a view.'}</EmptyState> : <div className="overflow-x-auto rounded-card border border-border bg-surface"><Table>
      <caption className="sr-only">Workspace views</caption>
      <THead><TR><TH>Name</TH><TH>Kind</TH><TH>Version</TH><TH>Updated</TH><TH><span className="sr-only">Actions</span></TH></TR></THead>
      <TBody>{items.map(view => <TR key={view.id}>
        <TD><Link className="font-semibold" to={`/w/${ws}/views/${view.id}`}>{view.name}</Link>{view.description ? <span className="block text-xs text-muted-foreground">{view.description}</span> : null}</TD>
        <TD>{view.kind === 'page' ? 'Application page' : <span className="capitalize">{view.kind}</span>}</TD>
        <TD>{view.publishedVersion ? <Badge tone="success">Published v{view.publishedVersion}</Badge> : <Badge>Draft only</Badge>} <span className="ml-2 text-xs text-muted-foreground">Draft r{view.draftRevision}</span></TD>
        <TD><RelativeTime value={view.updatedAt} /></TD>
        <TD><Button variant="secondary" size="sm" aria-label={`Preview ${view.name}`} onClick={() => setPreview(view)}>Preview and usage</Button> {can('workflow.edit') ? <Button variant="secondary" size="sm" aria-label={`Copy ${view.name}`} onClick={() => open('copy', view)}><Copy aria-hidden="true" /> Copy</Button> : null}</TD>
      </TR>)}</TBody>
    </Table></div>}
    <Dialog open={!!dialog} onClose={() => setDialog(null)} title={dialog?.mode === 'copy' ? 'Copy view' : 'New view'} footer={<><Button variant="secondary" onClick={() => setDialog(null)} disabled={busy}>Cancel</Button><Button onClick={() => void submit()} disabled={!name.trim() || busy} loading={busy}>{dialog?.mode === 'copy' ? 'Copy view' : 'Create view'}</Button></>}>
      <div className="space-y-4">
        <div><Label htmlFor="view-name">Name</Label><Input id="view-name" value={name} onChange={event => setName(event.target.value)} maxLength={80} /></div>
        {dialog?.mode === 'create' ? <><div><Label htmlFor="view-kind">View type</Label><Select id="view-kind" value={kind} onChange={event => setKind(event.target.value as ViewKind | 'page')}><option value="request">Request</option><option value="approval">Approval</option><option value="case">Case</option><option value="worklist">Worklist</option><option value="page">Application page</option></Select></div><div><Label htmlFor="view-description">Description</Label><Textarea id="view-description" value={description} onChange={event => setDescription(event.target.value)} maxLength={300} /></div></> : <p className="text-sm text-muted-foreground">The copy starts as a new draft. Published versions stay with the original.</p>}
        {error ? <ProblemAlert error={error} /> : null}
      </div>
    </Dialog>
  </>
}

function LibraryPreview({ ws, view, onClose }: { ws: string; view: ViewSummary; onClose: () => void }) {
  const detail = useQuery({ queryKey: ['view-library-preview',ws,view.id], queryFn: () => getView(ws,view.id), staleTime: 0 })
  const pins = useQuery({ queryKey: ['view-library-usage',ws,view.id], queryFn: () => listViewPins(ws,view.id), enabled: !!view.publishedVersion, staleTime: 0 })
  return <Dialog open onClose={onClose} title={`Preview: ${view.name}`} footer={<Button asChild><Link to={`/w/${ws}/views/${view.id}`}>Open editor and version review</Link></Button>}>
    <div className="space-y-4">
      <p className="text-sm text-muted-foreground">Current draft preview. Published uses stay pinned to their recorded versions.</p>
      {detail.isPending ? <LoadingBlock label="Loading preview" /> : detail.isError ? <QueryError error={detail.error} onRetry={() => void detail.refetch()} what="view preview" /> : detail.data.definition.kind === 'page' ? <p>Application page: open the editor to preview its composed layout and review its pinned screens and actions.</p> : <ViewPreview definition={detail.data.definition as ViewDefinition} />}
      <section aria-label="Used by"><h2 className="mb-2 text-base font-semibold">Used by</h2>
        {!view.publishedVersion ? <p className="text-sm">Not published yet.</p> : pins.isPending ? <LoadingBlock label="Loading usage" /> : pins.isError ? <QueryError error={pins.error} onRetry={() => void pins.refetch()} what="view usage" /> : pins.data?.items.length ? <ul className="space-y-2">{pins.data.items.map(item => <li key={item.key} className="rounded-control border border-border p-3 text-sm"><strong>{item.holderName}</strong><span className="block">{item.location} - pinned v{item.pinnedVersions.join(', v')}</span></li>)}</ul> : <p className="text-sm">No published uses were returned.</p>}
      </section>
    </div>
  </Dialog>
}
