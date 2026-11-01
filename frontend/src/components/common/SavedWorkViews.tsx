import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useSearchParams } from 'react-router'
import { api, call } from '@/api/client'
import type { components } from '@/api/schema'
import { useWorkspace } from '@/app/workspace'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Input, Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { uuid } from '@/lib/text'
import { ProblemAlert } from './ProblemAlert'
import { QueryError } from './QueryError'

type Saved = components['schemas']['SavedWorkView']
const KEYS = { tasks: ['view','q','queue','due','sort','minPriority'], runs: ['lifecycle','workflowId','businessKey','overdue','outcomeKind'] }

export function SavedWorkViews({ kind, effectiveFilters }: { kind: 'tasks' | 'runs'; effectiveFilters?: Record<string,string> }) {
  const { workspace, me } = useWorkspace(), ws = workspace.id
  const client = useQueryClient()
  const [params, setParams] = useSearchParams()
  const queryKey = ['saved-work-views',ws,me.subject.id,kind]
  const query = useQuery({ queryKey, queryFn: () => call(api.GET('/workspaces/{workspaceId}/work-views', { params: { path: { workspaceId: ws }, query: { kind } } })), staleTime: 0 })
  const [editing, setEditing] = useState<{ id: string; revision: number } | null>(null)
  const [name, setName] = useState('')
  const [shared, setShared] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const selected = query.data?.items.find(item => item.id === params.get('savedView'))
  const filters = Object.fromEntries(KEYS[kind].flatMap(key => {
    const value = effectiveFilters?.[key] ?? params.get(key)
    return value ? [[key,value]] : []
  }))
  const open = (item?: Saved) => { setEditing({ id: item?.id ?? uuid(), revision: item?.revision ?? 0 }); setName(item?.name ?? ''); setShared(item?.shared ?? false); setError(null) }
  const apply = (id: string) => {
    const item = query.data?.items.find(view => view.id === id)
    const next = new URLSearchParams(item?.filters ?? {})
    if (item) next.set('savedView', item.id)
    else if (kind === 'runs') next.set('scope','all')
    setParams(next)
  }
  const save = async (remove = false) => {
    if (!editing) return
    setBusy(true); setError(null)
    try {
      if (remove) await call(api.DELETE('/workspaces/{workspaceId}/work-views/{viewId}', { params: { path: { workspaceId: ws, viewId: editing.id }, query: { expectedRevision: editing.revision } } }))
      else await call(api.PUT('/workspaces/{workspaceId}/work-views/{viewId}', { params: { path: { workspaceId: ws, viewId: editing.id } }, body: { kind,name:name.trim(),shared,filters,expectedRevision:editing.revision } }))
      await client.invalidateQueries({ queryKey })
      const next = new URLSearchParams(params)
      if (remove) next.delete('savedView'); else next.set('savedView',editing.id)
      setParams(next,{replace:true}); setEditing(null)
    } catch (cause) { setError(cause) } finally { setBusy(false) }
  }
  return <section aria-label="Saved work views" className="mb-4 flex flex-wrap items-end gap-2">
    <div className="min-w-48"><Label htmlFor={`saved-${kind}`}>Saved views</Label><Select id={`saved-${kind}`} value={selected?.id ?? ''} disabled={query.isPending || query.isError} onChange={event => apply(event.target.value)}>
      <option value="">Current filters</option>{query.data?.items.map(item => <option key={item.id} value={item.id}>{item.name}{item.shared ? ' (shared)' : ''}</option>)}
    </Select></div>
    <Button variant="secondary" onClick={() => open()}>Save current view</Button>
    {selected?.ownerId === me.subject.id ? <Button variant="ghost" onClick={() => open(selected)}>Update saved view</Button> : null}
    {selected ? <span className="text-xs text-muted-foreground">Saved v{selected.revision}; changing filters does not change the saved view.</span> : null}
    {query.isError ? <QueryError error={query.error} onRetry={() => void query.refetch()} what="saved views" /> : null}
    <Dialog open={!!editing} onClose={() => { if (!busy) setEditing(null) }} title={editing?.revision ? 'Update saved view' : 'Save current view'} footer={<>
      {editing?.revision ? <Button variant="ghost" disabled={busy} onClick={() => void save(true)}>Delete saved view</Button> : null}
      <Button variant="secondary" disabled={busy} onClick={() => setEditing(null)}>Cancel</Button><Button loading={busy} disabled={!name.trim()} onClick={() => void save()}>Save view</Button>
    </>}><div className="space-y-4">
      <div><Label htmlFor="saved-view-name">View name</Label><Input id="saved-view-name" value={name} maxLength={80} onChange={event => setName(event.target.value)} /></div>
      <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={shared} onChange={event => setShared(event.target.checked)} />Share with this workspace</label>
      <p className="text-sm text-muted-foreground">Saves the current filters. Sharing makes the name and search terms visible to workspace users who can use this list. Each person sees only their own authorized results.</p>
      {error ? <ProblemAlert error={error} /> : null}
    </div></Dialog>
  </section>
}
