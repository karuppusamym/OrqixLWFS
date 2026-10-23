import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { listViews } from '@/api/views'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input, Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

export function CaseViewPicker({ ws, value, onChange, disabled }: { ws: string; value?: { id: string; version: number }; onChange: (value?: { id: string; version: number }) => void; disabled: boolean }) {
  const [choosing, setChoosing] = useState(false)
  const query = useQuery({ queryKey: ['views', ws], queryFn: () => listViews(ws), enabled: choosing })
  return <fieldset disabled={disabled} className="space-y-2">
    <legend className="font-semibold">Reusable case view</legend>
    <p className="text-sm text-muted-foreground">Optional. Pin an exact published version matching all case data fields. Later library edits keep this definition unchanged.</p>
    {!choosing ? <Button type="button" variant="secondary" onClick={() => setChoosing(true)}>Choose case view</Button> : <>
      <Label htmlFor="case-view-id">Published case view</Label>
      <Select id="case-view-id" value={value?.id ?? ''} disabled={query.isPending || query.isError} onChange={event => onChange(event.target.value ? { id: event.target.value, version: 1 } : undefined)}>
        <option value="">Standard case layout</option>{query.data?.items.filter(view => view.kind === 'case' && view.publishedVersion).map(view => <option key={view.id} value={view.id}>{view.name} (versions 1–{view.publishedVersion})</option>)}
      </Select>
      {value ? <><Label htmlFor="case-view-version">Case view version</Label><Input id="case-view-version" type="number" min={1} max={query.data?.items.find(view => view.id === value.id)?.publishedVersion ?? 1} value={value.version} onChange={event => onChange({ ...value, version: Number(event.target.value) })} /></> : null}
      {query.isError ? <Alert tone="danger">Case views could not be loaded. <Button type="button" variant="ghost" onClick={() => void query.refetch()}>Retry loading case views</Button></Alert> : null}
    </>}
  </fieldset>
}
