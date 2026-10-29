import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Copy, LayoutTemplate, Plus } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { copyView, createView, listViews, type ViewSummary } from '@/api/views'
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
import { blankView, type ViewKind } from './model'
import { blankPage } from '@/features/pages/model'

export function ViewLibraryPage() {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
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

  if (!can('workflow.read')) return <p role="alert">You do not have permission to read views.</p>
  return <>
    <PageHeader title="Views" description="Reusable request, approval, case and worklist screens and the application pages that compose them. Published versions stay fixed when a draft changes." actions={<div className="flex flex-wrap gap-2"><Button variant="secondary" asChild><Link to={`/w/${ws}/extensions`}>Extensions</Link></Button>{can('workflow.edit') ? <Button onClick={() => open('create')}><Plus aria-hidden="true" /> New view</Button> : null}</div>} />
    {query.isPending ? <LoadingBlock label="Loading views" /> : query.isError ? <QueryError error={query.error} onRetry={() => void query.refetch()} what="views" /> : query.data.items.length === 0 ? <EmptyState icon={LayoutTemplate} title="No views yet">{can('workflow.edit') ? 'Create a view to design a reusable screen.' : 'Ask a designer to create a view.'}</EmptyState> : <div className="overflow-hidden rounded-card border border-border bg-surface"><Table>
      <caption className="sr-only">Workspace views</caption>
      <THead><TR><TH>Name</TH><TH>Kind</TH><TH>Version</TH><TH>Updated</TH><TH><span className="sr-only">Actions</span></TH></TR></THead>
      <TBody>{query.data.items.map(view => <TR key={view.id}>
        <TD><Link className="font-semibold" to={`/w/${ws}/views/${view.id}`}>{view.name}</Link>{view.description ? <span className="block text-xs text-muted-foreground">{view.description}</span> : null}</TD>
        <TD>{view.kind === 'page' ? 'Application page' : <span className="capitalize">{view.kind}</span>}</TD>
        <TD>{view.publishedVersion ? <Badge tone="success">Published v{view.publishedVersion}</Badge> : <Badge>Draft only</Badge>} <span className="ml-2 text-xs text-muted-foreground">Draft r{view.draftRevision}</span></TD>
        <TD><RelativeTime value={view.updatedAt} /></TD>
        <TD>{can('workflow.edit') ? <Button variant="secondary" size="sm" aria-label={`Copy ${view.name}`} onClick={() => open('copy', view)}><Copy aria-hidden="true" /> Copy</Button> : null}</TD>
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
