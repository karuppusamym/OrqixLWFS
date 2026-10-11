import { useEffect, useRef, useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { Button } from '@/components/ui/button'
import { Input, Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { DocumentVersionPicker, type DocumentOperation } from '@/features/settings/DocumentVersionPicker'
import { validDocumentId, validDocumentSnapshotId, type DocumentReference } from '../model/documentReference'

type Props = {
  workspaceId: string; connectionKey: string; connectionVersion: number; path: string
  operations: Record<string, DocumentOperation>; testableOperations: string[]
  approvedDrives: unknown[]; canManage: boolean; enabled: boolean; readOnly: boolean
  configuredReference?: Partial<DocumentReference>
  onApply: (reference: DocumentReference) => string | undefined
}

/** Known references only: connector reads retain the administrator-only test authorization. */
export function DocumentReferenceAuthoring(props: Props) {
  const [open, setOpen] = useState(false)
  return <div className="space-y-2 rounded border p-3">
    <p className="text-sm font-semibold">Document reference authoring</p>
    <p className="text-xs">Configure an approved exact reference as request-form choices. This does not browse tenant files or grant Microsoft access to requesters.</p>
    {props.configuredReference?.driveId && props.configuredReference.itemId ? <p className="break-all text-xs">Configured drive: {props.configuredReference.driveId} · Document: {props.configuredReference.itemId}{props.configuredReference.versionId ? ` · Historical version: ${props.configuredReference.versionId}` : ''}{props.configuredReference.snapshotId ? ` · Retained snapshot: ${props.configuredReference.snapshotId}` : ''}</p> : null}
    <Button variant="secondary" disabled={props.readOnly} onClick={() => setOpen(!open)}>{open ? 'Cancel document reference' : 'Configure document reference'}</Button>
    {open ? <ReferenceEditor {...props} onClose={() => setOpen(false)} /> : null}
  </div>
}

function ReferenceEditor({ workspaceId, connectionKey, connectionVersion, path, operations, testableOperations, approvedDrives, canManage, enabled, readOnly, onApply, onClose }: Props & { onClose: () => void }) {
  const [driveId, setDriveId] = useState('')
  const [itemId, setItemId] = useState('')
  const [pin, setPin] = useState('')
  const [provenance, setProvenance] = useState<{ contentSha256: string; eTag: string; expiresAt: string } | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const active = useRef(false)
  useEffect(() => { active.current = true; return () => { active.current = false } }, [])
  const snapshot = path === '/extractSnapshot'
  const drives = approvedDrives.filter((value): value is string => typeof value === 'string' && validDocumentId(value))
  const versions = testableOperations.find((name) => operations[name]?.path === '/versions')
  const capture = testableOperations.find((name) => operations[name]?.path === '/pinLatest')
  const captureAllowed = capture && operations[capture]?.requestSchema?.properties?.driveId?.enum?.includes(driveId)
  const valid = drives.includes(driveId) && validDocumentId(itemId)
  const resetPin = () => { setPin(''); setProvenance(null); setError(null) }
  const captureLatest = async () => {
    if (!capture || !captureAllowed || !canManage || !valid || !enabled) return
    setPending(true)
    resetPin()
    try {
      const result = await endpoints.testConnection(workspaceId, connectionKey, connectionVersion, capture, { driveId, itemId })
      if (!active.current) return
      if (result.outcome !== 'succeeded') throw new Error(result.errorCode ?? 'Document capture failed.')
      const output = result.output as Record<string, unknown> | undefined
      if (!output || output.driveId !== driveId || output.itemId !== itemId || typeof output.snapshotId !== 'string' ||
          !validDocumentSnapshotId(output.snapshotId) || output.pinMode !== 'snapshot' ||
          typeof output.contentSha256 !== 'string' || !/^[a-f0-9]{64}$/i.test(output.contentSha256) ||
          typeof output.eTag !== 'string' || !output.eTag || output.eTag.length > 512 ||
          typeof output.snapshotExpiresAt !== 'string' || output.snapshotExpiresAt.length > 100 || !Number.isFinite(Date.parse(output.snapshotExpiresAt))) {
        throw new Error('Capture returned incomplete or mismatched snapshot provenance.')
      }
      setPin(output.snapshotId)
      setProvenance({ contentSha256: output.contentSha256, eTag: output.eTag, expiresAt: output.snapshotExpiresAt })
    } catch (cause) {
      if (active.current) setError(cause instanceof Error ? cause.message : 'Document capture failed.')
    } finally {
      if (active.current) setPending(false)
    }
  }
  return <div className="space-y-3">
    <Label htmlFor={`author-drive-${connectionKey}`}>Approved document drive</Label>
    <Select id={`author-drive-${connectionKey}`} value={driveId} disabled={pending || readOnly} onChange={(event) => { setDriveId(event.target.value); resetPin() }}>
      <option value="">Choose an approved drive</option>
      {drives.map((drive) => <option key={drive} value={drive}>{drive}</option>)}
    </Select>
    {!drives.length ? <p role="alert">This operation needs an explicit approved drive enum before reference authoring is available.</p> : null}
    <Label htmlFor={`author-item-${connectionKey}`}>Known document item ID</Label>
    <Input id={`author-item-${connectionKey}`} value={itemId} disabled={pending || readOnly} autoComplete="off" onChange={(event) => { setItemId(event.target.value); resetPin() }} />
    <Label htmlFor={`author-pin-${connectionKey}`}>{snapshot ? 'Retained snapshot ID' : 'Exact historical version ID'}</Label>
    <Input id={`author-pin-${connectionKey}`} value={pin} disabled={pending || readOnly} autoComplete="off" onChange={(event) => { setPin(event.target.value); setProvenance(null); setError(null) }} />
    {!canManage ? <p className="text-xs">Use a known exact reference supplied by a connection administrator. Loading versions and capturing content require connection management permission.</p> : null}
    {!snapshot && canManage && versions ? <DocumentVersionPicker
      key={`${driveId}:${itemId}`} workspaceId={workspaceId} connectionKey={connectionKey} connectionVersion={connectionVersion}
      operation={versions} driveId={driveId} itemId={itemId} approvedDrives={drives.filter((drive) => operations[versions]?.requestSchema?.properties?.driveId?.enum?.includes(drive))}
      enabled={enabled && !readOnly && !pending} selectedVersion={pin} onSelect={(version) => { setPin(version); setError(null) }}
    /> : null}
    {snapshot && canManage && capture ? <Button variant="secondary" loading={pending} disabled={!enabled || readOnly || !valid || !captureAllowed || pending} onClick={() => void captureLatest()}>Capture current document as snapshot</Button> : null}
    {snapshot ? <p className="text-xs">Snapshot availability is limited by retention. Canceling this editor leaves any captured snapshot to expire normally. An expired snapshot fails; no new capture occurs automatically.</p> : <p className="text-xs">This exact historical version is used by every request. An unavailable version fails; no latest fallback occurs.</p>}
    {provenance ? <dl className="break-all text-xs"><dt>SHA-256</dt><dd>{provenance.contentSha256}</dd><dt>Source eTag</dt><dd>{provenance.eTag}</dd><dt>Retained until</dt><dd>{provenance.expiresAt}</dd></dl> : null}
    {error ? <p role="alert">{error}</p> : null}
    <Button disabled={readOnly || pending || !valid || !(snapshot ? validDocumentSnapshotId(pin) : validDocumentId(pin))} onClick={() => {
      const failure = onApply({ driveId, itemId, ...(snapshot ? { snapshotId: pin } : { versionId: pin }) })
      if (failure) setError(failure)
      else onClose()
    }}>Apply approved reference to request form</Button>
  </div>
}
