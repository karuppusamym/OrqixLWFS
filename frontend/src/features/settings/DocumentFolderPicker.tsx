import { useEffect, useRef, useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

type File = { itemId: string; name: string; size: number; contentType: string }
export function parseDocumentFiles(output: unknown, driveId: string, folderId: string): File[] {
  if (!output || typeof output !== 'object') throw new Error('The folder listing is unavailable.')
  const result = output as Record<string, unknown>
  if (result.driveId !== driveId || result.folderId !== folderId || result.hasMore !== false || typeof result.filesJson !== 'string' || result.filesJson.length > 100_000)
    throw new Error('The folder listing is incomplete or belongs to another folder.')
  let files: unknown
  try { files = JSON.parse(result.filesJson) } catch { throw new Error('The folder listing is invalid.') }
  if (!Array.isArray(files) || files.length > 100 || result.fileCount !== files.length) throw new Error('The folder listing exceeds its limit.')
  const seen = new Set<string>()
  return files.map((entry: unknown) => {
    if (!entry || typeof entry !== 'object') throw new Error('The file entry is invalid.')
    const file = entry as Record<string, unknown>
    if (typeof file.itemId !== 'string' || !/^[A-Za-z0-9!._~-]{1,200}$/.test(file.itemId) || ['.', '..'].includes(file.itemId) || seen.has(file.itemId) || typeof file.name !== 'string' || file.name.length > 256 || typeof file.size !== 'number' || !Number.isSafeInteger(file.size) || file.size < 0 || typeof file.contentType !== 'string') throw new Error('The file entry is invalid.')
    seen.add(file.itemId)
    return { itemId: file.itemId, name: file.name, size: file.size, contentType: file.contentType }
  })
}

/** Administrator-only test UI, using the existing connection.test permission. Never authorizes requesters. */
export function DocumentFolderPicker({ workspaceId, connectionKey, connectionVersion, operation, driveId, approvedDrives, approvedFolders, enabled, onSelect }: {
  workspaceId: string; connectionKey: string; connectionVersion: number; operation: string; driveId: string
  approvedDrives: unknown[]; approvedFolders: unknown[]; enabled: boolean; onSelect: (itemId: string) => void
}) {
  const [folderId, setFolderId] = useState('')
  const [files, setFiles] = useState<File[] | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const active = useRef(false)
  useEffect(() => { active.current = true; return () => { active.current = false } }, [])
  const folders = approvedFolders.filter((value): value is string => typeof value === 'string' && /^[A-Za-z0-9!._~-]{1,200}$/.test(value) && value !== '.' && value !== '..')
  const load = async () => {
    setPending(true); setError(null); setFiles(null)
    try {
      const result = await endpoints.testConnection(workspaceId, connectionKey, connectionVersion, operation, { driveId, folderId })
      if (!active.current) return
      if (result.outcome !== 'succeeded') throw new Error(result.errorCode ?? 'The folder listing failed.')
      setFiles(parseDocumentFiles(result.output, driveId, folderId))
    } catch (cause) { if (active.current) setError(cause instanceof Error ? cause.message : 'The folder listing failed.') }
    finally { if (active.current) setPending(false) }
  }
  return <div className="space-y-2 rounded border p-3">
    <Label htmlFor={`document-folder-${connectionKey}`}>Approved folder</Label>
    <Select id={`document-folder-${connectionKey}`} value={folderId} disabled={!enabled || pending} onChange={(e) => { setFolderId(e.target.value); setFiles(null); setError(null) }}>
      <option value="">Choose a folder</option>{folders.map((folder) => <option key={folder} value={folder}>{folder}</option>)}
    </Select>
    <Button variant="secondary" onClick={() => void load()} disabled={!enabled || pending || !approvedDrives.includes(driveId) || !folders.includes(folderId)} loading={pending}>Browse approved folder</Button>
    {error ? <p role="alert">{error}</p> : null}
    {files ? <><Label htmlFor={`document-file-${connectionKey}`}>Choose document</Label>
      <Select id={`document-file-${connectionKey}`} value="" disabled={!enabled || pending} onChange={(e) => { if (files.some((file) => file.itemId === e.target.value)) onSelect(e.target.value) }}>
        <option value="">Choose a file explicitly</option>{files.map((file) => <option key={file.itemId} value={file.itemId}>{file.name} · {file.size} bytes</option>)}
      </Select>{files.length === 0 ? <p>No files in this approved folder.</p> : null}</> : null}
    <p className="text-xs text-muted-foreground">Lists direct files only. Selecting a file clears any previous version pin; choose its historical version separately.</p>
  </div>
}
