import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { getPublishedView, listViews } from '@/api/views'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input, Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import type { ViewDefinition } from '@/features/views/model'
import { bindApprovalView, type ApprovalViewBinding } from '@/features/views/approvalBinding'
import { useStudioContext } from '../StudioContext'
import type { WorkflowNode } from '../model/types'
import { InspectorSection } from './controls'

export function ApprovalViewPicker({ node }: { node: WorkflowNode }) {
  const { wsId, store, readOnly } = useStudioContext()
  const pin = node.config.viewBinding as ApprovalViewBinding | undefined
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
      store.getState().apply(current => bindApprovalView(current, node.id, published.viewId, published.version, published.definition as unknown as ViewDefinition))
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'The approval view could not be applied.') }
    finally { setBusy(false) }
  }
  return <InspectorSection title="Reusable approval view">
    <p className="text-xs text-muted-foreground">Show request details using an exact published approval view. It must bind every request field and include Approve and Reject. Task permissions and correction controls remain in force.</p>
    {pin ? <div className="space-y-2"><p className="text-sm">Pinned approval view {pin.id}, version {pin.version}</p><Button variant="secondary" disabled={readOnly || busy} onClick={() => store.getState().updateNodeConfig(node.id, { viewBinding: undefined })}>Remove approval view</Button></div> : null}
    {!readOnly && !choosing ? <Button variant="secondary" onClick={() => setChoosing(true)}>Choose approval view</Button> : null}
    {!readOnly && choosing ? <>
      <Label htmlFor={`approval-view-${node.id}`}>Published approval view</Label>
      <Select id={`approval-view-${node.id}`} value={id} disabled={busy || views.isPending || views.isError} onChange={event => { setId(event.target.value); setVersion(1); setError(null) }}>
        <option value="">Choose a view</option>
        {views.data?.items.filter(view => view.kind === 'approval' && view.publishedVersion).map(view => <option key={view.id} value={view.id}>{view.name} (versions 1–{view.publishedVersion})</option>)}
      </Select>
      <Label htmlFor={`approval-version-${node.id}`}>Approval view version</Label>
      <Input id={`approval-version-${node.id}`} type="number" min={1} max={views.data?.items.find(view => view.id === id)?.publishedVersion ?? 1} value={version} disabled={busy} onChange={event => setVersion(Number(event.target.value))} />
      <Button disabled={!id || !Number.isInteger(version) || version < 1 || busy} loading={busy} onClick={() => void apply()}>Apply approval view</Button>
      {views.isError ? <Alert tone="danger">Approval views could not be loaded. <Button variant="ghost" onClick={() => void views.refetch()}>Retry loading views</Button></Alert> : null}
    </> : null}
    {error ? <Alert tone="danger">{error}</Alert> : null}
  </InspectorSection>
}
