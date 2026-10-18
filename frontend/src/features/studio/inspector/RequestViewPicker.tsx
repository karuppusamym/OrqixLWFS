import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { getPublishedView, listViews } from '@/api/views'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input, Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import type { ViewDefinition } from '@/features/views/model'
import { bindRequestView } from '@/features/views/requestBinding'
import { useStudio, useStudioContext } from '../StudioContext'
import { InspectorSection } from './controls'

export function RequestViewPicker() {
  const { wsId, store, readOnly } = useStudioContext()
  const pin = useStudio(state => state.definition.inputUi?.viewRef)
  const [id, setId] = useState('')
  const [version, setVersion] = useState(1)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [choosing, setChoosing] = useState(false)
  const views = useQuery({ queryKey: ['views', wsId], queryFn: () => listViews(wsId), enabled: !readOnly && choosing })
  const apply = async () => {
    setBusy(true); setError(null)
    const workflowKey = store.getState().definition.workflowKey
    try {
      const published = await getPublishedView(wsId, id, version)
      if (store.getState().definition.workflowKey !== workflowKey) return
      store.getState().apply(current => bindRequestView(current, published.viewId, published.version, published.definition as unknown as ViewDefinition))
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'The view could not be applied.') }
    finally { setBusy(false) }
  }
  return <InspectorSection title="Reusable request view">
    <p className="text-xs text-muted-foreground">Apply an exact published version to this form. Bindings and required flags must match every question. Later library changes keep the selected version pinned.</p>
    {pin ? <div className="space-y-2"><p className="text-sm">Pinned view {pin.id}, version {pin.version}</p><Button variant="secondary" disabled={readOnly || busy} onClick={() => store.getState().apply(current => {
      const next = structuredClone(current)
      if (next.inputUi) delete next.inputUi.viewRef
      return next
    })}>Detach view and keep form</Button></div> : null}
    {!readOnly && !choosing ? <Button variant="secondary" onClick={() => setChoosing(true)}>Choose reusable view</Button> : null}
    {!readOnly && choosing ? <>
      <Label htmlFor="request-view-id">Published request view</Label>
      <Select id="request-view-id" value={id} disabled={busy || views.isPending || views.isError} onChange={event => { setId(event.target.value); setVersion(1); setError(null) }}>
        <option value="">Choose a view</option>
        {views.data?.items.filter(view => view.kind === 'request' && view.publishedVersion).map(view => <option key={view.id} value={view.id}>{view.name} (versions 1–{view.publishedVersion})</option>)}
      </Select>
      <Label htmlFor="request-view-version">Version to apply</Label>
      <Input id="request-view-version" type="number" min={1} max={views.data?.items.find(view => view.id === id)?.publishedVersion ?? 1} value={version} disabled={busy} onChange={event => setVersion(Number(event.target.value))} />
      <Button disabled={!id || !Number.isInteger(version) || version < 1 || busy} loading={busy} onClick={() => void apply()}>Apply selected version</Button>
      {views.isError ? <Alert tone="danger">Views could not be loaded. <Button variant="ghost" onClick={() => void views.refetch()}>Retry loading views</Button></Alert> : null}
    </> : null}
    {error ? <Alert tone="danger">{error}</Alert> : null}
  </InspectorSection>
}
