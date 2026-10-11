import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Download, FileText, Trash2, Upload } from 'lucide-react'
import { useRef, useState } from 'react'
import { addAttachment, checkFile, describeTypes, downloadAttachment, formatBytes, listAttachments, removeAttachment } from '@/api/attachments'
import { isProblemError } from '@/api/problem'
import { keys } from '@/api/queries'
import type { Attachment } from '@/api/types'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { ConfirmDialog } from '@/components/common/ConfirmDialog'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { formatDateTime } from '@/lib/dates'
import { describeProblem } from '@/lib/problemMessages'
import { uuid } from '@/lib/text'

const scanText: Record<Attachment['scanStatus'], { label: string; tone: 'neutral' | 'success' | 'danger' | 'warning' }> = {
  notScanned: { label: 'Not scanned', tone: 'neutral' },
  clean: { label: 'Scanned: clean', tone: 'success' },
  infected: { label: 'Blocked: virus found', tone: 'danger' },
  error: { label: 'Blocked: scan failed', tone: 'warning' },
}

export const filesKey = (ws: string, runId: string) => ['ws', ws, 'run-files', runId] as const

/** Files of a request: shown to the people who can read its data; they never appear for anyone else. */
export function useRunFiles(ws: string, runId: string, enabled = true) {
  return useQuery({ queryKey: filesKey(ws, runId), queryFn: () => listAttachments(ws, runId), enabled })
}

function FileRow({ file, onDownload, onRemove, readOnly, busy }: { file: Attachment; onDownload: () => void; onRemove: () => void; readOnly: boolean; busy: boolean }) {
  const removed = file.removedAt != null
  const scan = scanText[file.scanStatus]
  return (
    <li className="flex flex-wrap items-start gap-3 rounded-control border border-border px-4 py-3" data-testid="file-row" data-removed={removed || undefined}>
      <FileText className="mt-0.5 size-5 shrink-0 text-muted-foreground" aria-hidden="true" />
      <div className="min-w-0 flex-1 space-y-0.5">
        <p className={removed ? 'wrap-anywhere text-muted-foreground line-through' : 'wrap-anywhere font-semibold'}>{file.name}</p>
        {file.description ? <p className="wrap-anywhere text-sm">{file.description}</p> : null}
        <p className="text-xs text-muted-foreground">
          {formatBytes(file.sizeBytes)} &middot; added by {file.uploadedBy.displayName} on <time dateTime={file.uploadedAt}>{formatDateTime(file.uploadedAt)}</time>
          {removed ? <> &middot; removed by {file.removedBy?.displayName ?? 'someone'} on {formatDateTime(file.removedAt)}</> : null}
        </p>
        <p className="flex flex-wrap items-center gap-2 pt-0.5">
          {removed ? <Badge tone="neutral">Removed</Badge> : <Badge tone={scan.tone} data-testid="scan-status">{scan.label}</Badge>}
        </p>
      </div>
      {!removed ? (
        <div className="flex flex-wrap gap-2">
          <Button type="button" variant="secondary" size="sm" disabled={!file.downloadable || busy} onClick={onDownload} aria-label={`Download ${file.name}`}>
            <Download className="size-4" aria-hidden="true" /> Download
          </Button>
          {!readOnly && file.canRemove ? (
            <Button type="button" variant="ghost" size="sm" disabled={busy} onClick={onRemove} aria-label={`Remove ${file.name}`}>
              <Trash2 className="size-4" aria-hidden="true" /> Remove
            </Button>
          ) : null}
        </div>
      ) : null}
    </li>
  )
}

/**
 * The "Files" section of a request (docs/build/S17 section 1): the list with name, size, who and when, scan status and
 * download; adding a file with a short description; removing one after a confirmation. With `readOnly` (the task panel)
 * deciders see and download the files but cannot change them.
 */
export function RunFiles({ ws, runId, readOnly = false }: { ws: string; runId: string; readOnly?: boolean }) {
  const queryClient = useQueryClient()
  const files = useRunFiles(ws, runId)
  const [description, setDescription] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [localError, setLocalError] = useState<string | null>(null)
  const [progress, setProgress] = useState<number | null>(null)
  const [confirm, setConfirm] = useState<Attachment | null>(null)
  const [downloadError, setDownloadError] = useState<string | null>(null)
  const key = useRef({ signature: '', key: uuid() })
  const input = useRef<HTMLInputElement>(null)

  const add = useMutation({
    mutationFn: () => {
      const signature = `${file?.name}|${file?.size}|${description}`
      if (key.current.signature !== signature) key.current = { signature, key: uuid() }
      return addAttachment(ws, runId, file as File, description, { key: key.current.key, onProgress: setProgress })
    },
    onSuccess: async () => {
      setFile(null)
      setDescription('')
      setLocalError(null)
      if (input.current) input.current.value = ''
      await queryClient.invalidateQueries({ queryKey: filesKey(ws, runId) })
      await queryClient.invalidateQueries({ queryKey: keys.run(ws, runId) })
    },
    onSettled: () => setProgress(null),
  })
  const remove = useMutation({
    mutationFn: (attachment: Attachment) => removeAttachment(ws, runId, attachment.id),
    onSuccess: async () => {
      setConfirm(null)
      await queryClient.invalidateQueries({ queryKey: filesKey(ws, runId) })
      await queryClient.invalidateQueries({ queryKey: keys.run(ws, runId) })
    },
  })

  if (files.isError && isProblemError(files.error) && (files.error.status === 403 || files.error.status === 404)) return null
  const data = files.data
  const limits = data?.limits
  const active = data?.items.filter((f) => f.removedAt == null) ?? []

  function pick(chosen: File | undefined) {
    add.reset()
    if (!chosen) {
      setFile(null)
      return
    }
    const problem = limits ? checkFile(chosen, limits.allowedTypes, limits.maxFileBytes) : null
    setLocalError(problem)
    setFile(problem ? null : chosen)
  }

  async function download(attachment: Attachment) {
    setDownloadError(null)
    try {
      await downloadAttachment(ws, runId, attachment)
    } catch (cause) {
      setDownloadError(isProblemError(cause) ? describeProblem(cause).message : 'The file could not be downloaded. Try again.')
    }
  }

  const addMessage = add.error && isProblemError(add.error) ? (add.error.fieldErrors[0]?.message ?? describeProblem(add.error).message) : add.isError ? 'The file could not be added. Try again.' : null

  return (
    <Card data-testid="run-files">
      <CardHeader>
        <h2>Files</h2>
      </CardHeader>
      <CardContent className="space-y-4">
        {files.isPending ? <p className="text-sm text-muted-foreground">Loading files...</p> : null}
        {files.isError ? <ProblemAlert error={toProblem(files.error)} /> : null}
        {data && data.items.length === 0 ? <p className="text-sm text-muted-foreground">No files are attached to this request.</p> : null}
        {data && data.items.length > 0 ? (
          <ul className="space-y-2">
            {data.items.map((f) => (
              <FileRow key={f.id} file={f} readOnly={readOnly} busy={remove.isPending} onDownload={() => void download(f)} onRemove={() => setConfirm(f)} />
            ))}
          </ul>
        ) : null}
        {downloadError ? <Alert tone="danger">{downloadError}</Alert> : null}
        {limits ? (
          <p className="text-xs text-muted-foreground" data-testid="file-limits">
            {active.length} of {limits.maxFiles} files, {formatBytes(limits.usedBytes)} of {formatBytes(limits.maxTotalBytes)} used. Largest file {formatBytes(limits.maxFileBytes)}. Accepted: {describeTypes(limits.allowedTypes)}.
          </p>
        ) : null}

        {!readOnly && data?.canAdd ? (
          <form
            className="space-y-3 rounded-card border border-dashed border-border p-4"
            onSubmit={(event) => {
              event.preventDefault()
              if (file) add.mutate()
            }}
          >
            <h3 className="text-sm font-semibold">Add a file</h3>
            <div className="space-y-1.5">
              <Label htmlFor={`file-${runId}`}>File</Label>
              <Input id={`file-${runId}`} ref={input} type="file" disabled={add.isPending} onChange={(e) => pick(e.target.files?.[0])} aria-describedby={`file-help-${runId}`} />
              <p id={`file-help-${runId}`} className="text-xs text-muted-foreground">
                {limits ? `Accepted: ${describeTypes(limits.allowedTypes)}. Largest file: ${formatBytes(limits.maxFileBytes)}.` : null}
              </p>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor={`file-note-${runId}`}>Description (optional)</Label>
              <Input id={`file-note-${runId}`} value={description} maxLength={200} disabled={add.isPending} onChange={(e) => setDescription(e.target.value)} placeholder="For example: Supplier quote, signed" />
            </div>
            {localError ? <Alert tone="danger">{localError}</Alert> : null}
            {addMessage ? <Alert tone="danger">{addMessage}</Alert> : null}
            <div className="flex flex-wrap items-center gap-3">
              <Button type="submit" variant="primary" disabled={!file || add.isPending}>
                <Upload className="size-4" aria-hidden="true" /> {add.isPending ? 'Uploading...' : 'Add file'}
              </Button>
              {progress !== null ? (
                <span role="status" className="flex items-center gap-2 text-xs text-muted-foreground">
                  <progress className="h-2 w-32" value={Math.round(progress * 100)} max={100} aria-label="Upload progress" /> {Math.round(progress * 100)}%
                </span>
              ) : null}
            </div>
          </form>
        ) : null}
      </CardContent>
      <ConfirmDialog
        open={confirm !== null}
        title="Remove this file?"
        confirmLabel="Remove file"
        destructive
        pending={remove.isPending}
        onCancel={() => setConfirm(null)}
        onConfirm={() => confirm && remove.mutate(confirm)}
        error={remove.isError ? 'The file could not be removed. Try again.' : undefined}
      >
        <p>
          <strong className="wrap-anywhere">{confirm?.name}</strong> will no longer be available to download. The request keeps a record that it was added and removed.
        </p>
      </ConfirmDialog>
    </Card>
  )
}
