import { useEffect, useRef, useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

export type DocumentOperation = {
  path?: string
  requestSchema?: { required?: string[]; properties?: Record<string, { enum?: unknown[] }> }
}

type HistoricalVersion = { versionId: string; size: number; lastModifiedDateTime: string; newestListed: boolean }
const validId = (value: unknown): value is string => typeof value === 'string' && /^[A-Za-z0-9!._~-]{1,200}$/.test(value) && value !== '.' && value !== '..'

/** Only complete, scoped, bounded server listings can become selectable pins. */
export function parseDocumentVersions(output: unknown, driveId: string, itemId: string): HistoricalVersion[] {
  if (!output || typeof output !== 'object') throw new Error('The version listing is unavailable.')
  const result = output as Record<string, unknown>
  if (result.driveId !== driveId || result.itemId !== itemId || result.hasMore !== false ||
      typeof result.versionsJson !== 'string' || result.versionsJson.length > 100_000) {
    throw new Error('The version listing is incomplete or belongs to another document.')
  }
  let entries: unknown
  try { entries = JSON.parse(result.versionsJson) } catch { throw new Error('The version listing contains invalid JSON.') }
  if (!Array.isArray(entries) || entries.length > 100 || result.versionCount !== entries.length) {
    throw new Error('The version listing exceeds its approved bounds.')
  }
  const seen = new Set<string>()
  return entries.map((entry: unknown) => {
    if (!entry || typeof entry !== 'object') throw new Error('The version listing contains an invalid entry.')
    const value = entry as Record<string, unknown>
    if (!validId(value.versionId) || seen.has(value.versionId) || typeof value.size !== 'number' ||
        !Number.isSafeInteger(value.size) || value.size < 0 || typeof value.lastModifiedDateTime !== 'string' ||
        value.lastModifiedDateTime.length > 100 || (value.lastModifiedDateTime !== '' && !Number.isFinite(Date.parse(value.lastModifiedDateTime))) ||
        typeof value.newestListed !== 'boolean') throw new Error('The version listing contains an invalid entry.')
    seen.add(value.versionId)
    // Retain only safe provenance, never arbitrary response fields or signed URLs.
    return { versionId: value.versionId, size: value.size, lastModifiedDateTime: value.lastModifiedDateTime, newestListed: value.newestListed }
  })
}

export function DocumentVersionPicker({ workspaceId, connectionKey, connectionVersion, operation, driveId, itemId, approvedDrives, enabled, selectedVersion, onSelect }: {
  workspaceId: string; connectionKey: string; connectionVersion: number; operation: string
  driveId: string; itemId: string; approvedDrives: unknown[]; enabled: boolean
  selectedVersion: string; onSelect: (versionId: string) => void
}) {
  const [versions, setVersions] = useState<HistoricalVersion[] | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const active = useRef(false)
  useEffect(() => { active.current = true; return () => { active.current = false } }, [])
  const approved = approvedDrives.includes(driveId) && validId(driveId) && validId(itemId)
  const load = async () => {
    setPending(true)
    setError(null)
    setVersions(null)
    try {
      const result = await endpoints.testConnection(workspaceId, connectionKey, connectionVersion, operation, { driveId, itemId })
      if (!active.current) return
      if (result.outcome !== 'succeeded') throw new Error(result.errorCode ?? 'The version listing failed.')
      setVersions(parseDocumentVersions(result.output, driveId, itemId))
    } catch (cause) {
      if (active.current) setError(cause instanceof Error ? cause.message : 'The version listing failed.')
    } finally {
      if (active.current) setPending(false)
    }
  }
  const selected = versions?.find((version) => version.versionId === selectedVersion)
  const id = `historical-version-${connectionKey}`
  return <div className="space-y-2 rounded border p-3">
    <p className="text-sm">Choose a historical version from the approved document. Listing never chooses a version automatically.</p>
    {!approved ? <p className="text-sm text-muted-foreground">Enter an approved drive ID and document item ID to load versions.</p> : null}
    <Button variant="secondary" onClick={() => void load()} disabled={!enabled || !approved || pending} loading={pending}>Load document versions</Button>
    {error ? <p role="alert">{error}</p> : null}
    {versions ? <>
      <p className="break-all text-xs">Drive: {driveId} · Document: {itemId}</p>
      {versions.length === 0 ? <p>No historical versions were returned.</p> : <>
        <Label htmlFor={id}>Choose historical version</Label>
        <Select id={id} disabled={!enabled || pending} value={selected ? selectedVersion : ''} onChange={(event) => onSelect(event.target.value)}>
          <option value="">Select a version explicitly</option>
          {versions.map((version) => <option key={version.versionId} value={version.versionId}>{version.versionId} · {version.lastModifiedDateTime || 'date unavailable'} · {version.size} bytes{version.newestListed ? ' · newest listed' : ''}</option>)}
        </Select>
      </>}
      {selected ? <p className="text-sm">Selected pin: {selected.versionId}. Extraction reads this exact version. If unavailable, it fails visibly.</p> : null}
      <p className="text-xs text-muted-foreground">Microsoft may list the current version without allowing a historical download. To capture current content, choose the capture-latest operation separately.</p>
    </> : null}
  </div>
}
