import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useWorkspace } from '@/app/workspace'
import { Button } from '@/components/ui/button'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { uuid } from '@/lib/text'
import type { components } from '@/api/schema'
import * as documents from '@/features/start/documentSourcesApi'

type Grant = components['schemas']['DocumentSourceGrant']
const empty = { label: '', connectionKey: '', definitionVersion: '4', browseOperation: 'browseFolder', versionsOperation: 'listVersions', driveId: '', folderId: '', audience: '', enabled: true }
export function DocumentSourcesPanel() {
  const { workspace } = useWorkspace(); const ws = workspace.id; const client = useQueryClient()
  const sources = useQuery({ queryKey: ['ws', ws, 'document-source-grants'], queryFn: () => documents.listDocumentSourceGrants(ws) })
  const [selected, setSelected] = useState<Grant | null>(null)
  const [form, setForm] = useState(empty)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const edit = (grant: Grant | null) => {
    setSelected(grant); setError(null)
    setForm(grant ? { label: grant.source.label, connectionKey: grant.source.connectionKey, definitionVersion: String(grant.source.definitionVersion), browseOperation: grant.browseOperation, versionsOperation: grant.versionsOperation, driveId: grant.source.driveId, folderId: grant.source.folderId, audience: grant.audience.map((p) => `${p.type}:${p.value}`).join('\n'), enabled: grant.enabled } : empty)
  }
  const save = async () => {
    setPending(true); setError(null)
    try {
      const audience = form.audience.split('\n').filter((line) => line.trim()).map((line) => {
        const separator = line.indexOf(':'); const type = line.slice(0, separator); const value = line.slice(separator + 1).trim()
        if (separator < 0 || !['subject', 'idpGroup'].includes(type) || !value) throw new Error('Use one subject:UUID or idpGroup:name per audience line.')
        return { type: type as 'subject' | 'idpGroup', value }
      })
      if (!Number.isSafeInteger(Number(form.definitionVersion)) || Number(form.definitionVersion) < 1) throw new Error('Enter a positive connection version.')
      const result = await documents.putDocumentSourceGrant(ws, selected?.source.id ?? uuid(), { ...form, definitionVersion: Number(form.definitionVersion), audience, expectedRevision: selected?.revision ?? 0 })
      edit(result); await client.invalidateQueries({ queryKey: ['ws', ws, 'document-source-grants'] }); await client.invalidateQueries({ queryKey: ['ws', ws, 'document-sources'] })
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'The source could not be saved. Reload and retry.') }
    finally { setPending(false) }
  }
  return <section className="max-w-2xl space-y-4">
    <h2>Approved document sources</h2>
    <p className="text-sm">A requester needs both the explicit document.browse workspace grant and this source's subject or IdP group audience. No role automatically receives browsing access. Connection versions and drive/folder grants must already be approved.</p>
    {sources.isError ? <p role="alert">Document source grants could not be loaded.</p> : null}
    <Label htmlFor="document-source-edit">Source to edit</Label>
    <Select id="document-source-edit" value={selected?.source.id ?? ''} disabled={pending} onChange={(e) => edit(sources.data?.find((entry) => entry.source.id === e.target.value) ?? null)}>
      <option value="">New document source</option>{sources.data?.map((entry) => <option key={entry.source.id} value={entry.source.id}>{entry.source.label}{entry.enabled ? '' : ' (disabled)'}</option>)}
    </Select>
    {(['label', 'connectionKey', 'definitionVersion', 'browseOperation', 'versionsOperation', 'driveId', 'folderId'] as const).map((key) => <div key={key}>
      <Label htmlFor={`document-source-${key}`}>{({ label: 'Source name', connectionKey: 'Approved connection key', definitionVersion: 'Pinned connection version', browseOperation: 'Folder browse operation', versionsOperation: 'Version listing operation', driveId: 'Approved drive ID', folderId: 'Approved folder ID' })[key]}</Label>
      <Input id={`document-source-${key}`} value={form[key]} disabled={pending} onChange={(e) => setForm({ ...form, [key]: e.target.value })} maxLength={key === 'label' ? 100 : 256} />
    </div>)}
    <Label htmlFor="document-source-audience">Source audience</Label>
    <Textarea id="document-source-audience" value={form.audience} disabled={pending} maxLength={30000} placeholder={'subject:00000000-0000-0000-0000-000000000001\nidpGroup:procurement'} onChange={(e) => setForm({ ...form, audience: e.target.value })} />
    <Label htmlFor="document-source-enabled">Source availability</Label>
    <Select id="document-source-enabled" value={String(form.enabled)} disabled={pending} onChange={(e) => setForm({ ...form, enabled: e.target.value === 'true' })}><option value="true">Enabled</option><option value="false">Disabled</option></Select>
    {error ? <p role="alert">{error}</p> : null}
    <Button onClick={() => void save()} disabled={pending} loading={pending}>Save document source</Button>
  </section>
}
