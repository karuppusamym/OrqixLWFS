import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import type { FieldDef } from '@/lib/form/schema'
import { parseDocumentFiles } from '@/features/settings/DocumentFolderPicker'
import { parseDocumentVersions } from '@/features/settings/DocumentVersionPicker'
import * as documents from './documentSourcesApi'

export function compatibleDocumentField(field: FieldDef, value: string): boolean {
  return field.schema.type === 'string' && !field.schema.format && (!field.schema.enum || field.schema.enum.includes(value)) &&
    value.length >= (field.schema.minLength ?? 0) && value.length <= (field.schema.maxLength ?? Number.MAX_SAFE_INTEGER)
}

/** Files are browsed through explicitly granted resource descriptors, never through the administrator test API. */
export function RequestDocumentPicker({ workspaceId, fields, onApply }: { workspaceId: string; fields: FieldDef[]; onApply: (values: Record<string, string>) => void }) {
  const [sourceId, setSourceId] = useState('')
  const [itemId, setItemId] = useState('')
  const [versionId, setVersionId] = useState('')
  const [targets, setTargets] = useState({ driveId: '', itemId: '', versionId: '' })
  const sources = useQuery({ queryKey: ['ws', workspaceId, 'document-sources'], queryFn: () => documents.listDocumentSources(workspaceId) })
  const source = sources.data?.find((entry) => entry.id === sourceId)
  const files = useQuery({ queryKey: ['ws', workspaceId, 'document-files', sourceId], enabled: !!source,
    queryFn: async () => parseDocumentFiles(await documents.listDocumentSourceFiles(workspaceId, sourceId), source!.driveId, source!.folderId) })
  const selectedFile = files.data?.find((entry) => entry.itemId === itemId)
  const versions = useQuery({ queryKey: ['ws', workspaceId, 'document-versions', sourceId, itemId], enabled: !!source && !!selectedFile,
    queryFn: async () => parseDocumentVersions(await documents.listDocumentSourceFileVersions(workspaceId, sourceId, itemId), source!.driveId, itemId) })
  const selectedVersion = versions.data?.find((entry) => entry.versionId === versionId)
  const values = { driveId: source?.driveId ?? '', itemId, versionId }
  const valid = source && selectedFile && selectedVersion && new Set(Object.values(targets)).size === 3 &&
    Object.entries(targets).every(([kind, key]) => fields.some((field) => field.key === key && compatibleDocumentField(field, values[kind as keyof typeof values])))
  return <details className="space-y-3 rounded border p-3">
    <summary className="cursor-pointer font-semibold">Choose an approved SharePoint or OneDrive document</summary>
    <p className="text-sm">Choose a file and its exact historical version, then choose the request fields that store its reference.</p>
    {sources.isError || files.isError || versions.isError ? <p role="alert">The approved document listing could not be loaded. Retry or contact your workspace administrator.</p> : null}
    <Label htmlFor="request-document-source">Approved document source</Label>
    <Select id="request-document-source" value={sourceId} disabled={sources.isPending} onChange={(e) => { setSourceId(e.target.value); setItemId(''); setVersionId(''); setTargets({ driveId: '', itemId: '', versionId: '' }) }}>
      <option value="">Choose a source</option>{sources.data?.map((entry) => <option key={entry.id} value={entry.id}>{entry.label}</option>)}
    </Select>
    {sources.data?.length === 0 ? <p>No document sources have been granted to you.</p> : null}
    {source ? <><Label htmlFor="request-document-file">Document</Label><Select id="request-document-file" value={itemId} disabled={files.isPending} onChange={(e) => { setItemId(e.target.value); setVersionId('') }}>
      <option value="">Choose a file</option>{files.data?.map((entry) => <option key={entry.itemId} value={entry.itemId}>{entry.name}</option>)}
    </Select></> : null}
    {selectedFile ? <><Label htmlFor="request-document-version">Historical version</Label><Select id="request-document-version" value={versionId} disabled={versions.isPending} onChange={(e) => setVersionId(e.target.value)}>
      <option value="">Choose an exact version</option>{versions.data?.map((entry) => <option key={entry.versionId} value={entry.versionId}>{entry.versionId} · {entry.lastModifiedDateTime || 'date unavailable'}</option>)}
    </Select></> : null}
    {selectedVersion ? <>{(['driveId', 'itemId', 'versionId'] as const).map((kind) => <div key={kind}>
      <Label htmlFor={`request-document-target-${kind}`}>Request field for {kind === 'driveId' ? 'drive' : kind === 'itemId' ? 'document' : 'version'}</Label>
      <Select id={`request-document-target-${kind}`} value={targets[kind]} onChange={(e) => setTargets({ ...targets, [kind]: e.target.value })}>
        <option value="">Choose a compatible text field</option>{fields.filter((field) => compatibleDocumentField(field, values[kind])).map((field) => <option key={field.key} value={field.key}>{field.schema.title}</option>)}
      </Select>
    </div>)}<Button type="button" disabled={!valid} onClick={() => { if (valid) onApply(Object.fromEntries(Object.entries(targets).map(([kind, key]) => [key, values[kind as keyof typeof values]]))) }}>Use document reference</Button>
      <p className="text-xs">This selects reference fields only. The workflow must contain an approved document read step. Historical reads fail visibly if the selected version is unavailable.</p></> : null}
  </details>
}
