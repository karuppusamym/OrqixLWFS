import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ScanText, ShieldAlert, ShieldCheck } from 'lucide-react'
import { useState } from 'react'
import { getAttachmentOcr, quarantineAttachment, releaseAttachment, requestAttachmentOcr } from '@/api/attachments'
import { isProblemError } from '@/api/problem'
import type { Attachment } from '@/api/types'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/input'

/**
 * Operator actions on one request file (DOC08, docs/build/S52): quarantine with a reason, release, and text
 * extraction with the OCR provider contract. The server decides every permission; these controls are only offered to
 * people holding run.operate.
 */

export function QuarantineDialog({ ws, runId, file, onClose, onDone }: { ws: string; runId: string; file: Attachment | null; onClose: () => void; onDone: () => void }) {
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState<string>()
  const mutation = useMutation({
    mutationFn: () => quarantineAttachment(ws, runId, (file as Attachment).id, reason.trim()),
    onSuccess: () => {
      setReason('')
      onDone()
    },
  })
  const close = () => {
    setReason('')
    setReasonError(undefined)
    mutation.reset()
    onClose()
  }
  return (
    <Dialog
      open={file !== null}
      onClose={close}
      title="Quarantine this file"
      dismissible={!mutation.isPending}
      footer={
        <>
          <Button variant="secondary" onClick={close} disabled={mutation.isPending}>
            Cancel
          </Button>
          <Button
            variant="danger-solid"
            loading={mutation.isPending}
            onClick={() => {
              if (reason.trim().length < 3) return setReasonError('Add a reason of at least 3 characters. It is recorded for reviewers.')
              setReasonError(undefined)
              mutation.mutate()
            }}
          >
            Quarantine file
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <Alert tone="warning" title="Nobody can download or extract text from a quarantined file">
          <strong className="wrap-anywhere">{file?.name}</strong> is kept for review and is not removed by the retention period. Release it to make it available again.
        </Alert>
        <div className="space-y-1.5">
          <Label htmlFor="quarantine-reason">Reason</Label>
          <Textarea id="quarantine-reason" value={reason} maxLength={300} rows={3} onChange={(e) => setReason(e.target.value)} aria-invalid={reasonError ? true : undefined} />
          {reasonError ? <p className="text-sm text-danger" role="alert">{reasonError}</p> : <p className="text-xs text-muted-foreground">People who can see this request&apos;s files will see the reason.</p>}
        </div>
        {mutation.isError ? <ProblemAlert error={toProblem(mutation.error)} /> : null}
      </div>
    </Dialog>
  )
}

export function QuarantineBadgeDetail({ file }: { file: Attachment }) {
  if (!file.quarantinedAt) return null
  return (
    <p className="text-xs text-muted-foreground" data-testid="quarantine-reason">
      Quarantined{file.quarantineReason ? `: ${file.quarantineReason}` : ''}
    </p>
  )
}

export function ReleaseButton({ ws, runId, file, onDone, disabled }: { ws: string; runId: string; file: Attachment; onDone: () => void; disabled?: boolean }) {
  const mutation = useMutation({ mutationFn: () => releaseAttachment(ws, runId, file.id), onSuccess: onDone })
  return (
    <>
      <Button type="button" variant="secondary" size="sm" disabled={disabled || mutation.isPending} onClick={() => mutation.mutate()} aria-label={`Release ${file.name} from quarantine`}>
        <ShieldCheck className="size-4" aria-hidden="true" /> Release
      </Button>
      {mutation.isError ? <span role="alert" className="text-xs text-danger">{toProblem(mutation.error).title}</span> : null}
    </>
  )
}

export function QuarantineButton({ file, onClick, disabled }: { file: Attachment; onClick: () => void; disabled?: boolean }) {
  return (
    <Button type="button" variant="ghost" size="sm" disabled={disabled} onClick={onClick} aria-label={`Quarantine ${file.name}`}>
      <ShieldAlert className="size-4" aria-hidden="true" /> Quarantine
    </Button>
  )
}

const statusText = { extracted: 'Text extracted', unsupported: 'This file type is not supported by the text extraction provider' } as const

/** Extract text on request, then show the stored result. Offered to operators; the result is visible to anyone who may see the file. */
export function OcrPanel({ ws, runId, file, canExtract }: { ws: string; runId: string; file: Attachment; canExtract: boolean }) {
  const queryClient = useQueryClient()
  const key = ['ws', ws, 'run-file-ocr', runId, file.id] as const
  const [open, setOpen] = useState(false)
  const stored = useQuery({
    queryKey: key,
    queryFn: () => getAttachmentOcr(ws, runId, file.id),
    enabled: open,
    retry: false,
  })
  const extract = useMutation({
    mutationFn: () => requestAttachmentOcr(ws, runId, file.id),
    onSuccess: (result) => {
      queryClient.setQueryData(key, result)
      setOpen(true)
    },
  })
  const missing = stored.isError && isProblemError(stored.error) && stored.error.status === 404
  const result = extract.data ?? stored.data
  return (
    <div className="w-full space-y-2" data-testid="file-ocr">
      <div className="flex flex-wrap items-center gap-2">
        {canExtract ? (
          <Button type="button" variant="ghost" size="sm" loading={extract.isPending} onClick={() => extract.mutate()} aria-label={`Extract text from ${file.name}`}>
            <ScanText className="size-4" aria-hidden="true" /> Extract text
          </Button>
        ) : null}
        <Button type="button" variant="ghost" size="sm" aria-expanded={open} onClick={() => setOpen((v) => !v)} aria-label={`${open ? 'Hide' : 'Show'} extracted text for ${file.name}`}>
          {open ? 'Hide text' : 'Show text'}
        </Button>
      </div>
      {extract.isError ? <ProblemAlert error={toProblem(extract.error)} /> : null}
      {open && stored.isPending && !result ? <p className="text-sm text-muted-foreground">Loading text...</p> : null}
      {open && missing && !result ? <p className="text-sm text-muted-foreground">No text has been extracted from this file yet.</p> : null}
      {open && stored.isError && !missing && !result ? <ProblemAlert error={toProblem(stored.error)} /> : null}
      {open && result ? (
        <div className="space-y-1 rounded-control border border-border p-3">
          <p className="text-xs text-muted-foreground">
            {statusText[result.status]} &middot; provider {result.provider}
          </p>
          {result.status === 'extracted' ? <pre className="max-h-60 overflow-auto whitespace-pre-wrap break-words text-sm" data-testid="ocr-text">{result.text}</pre> : null}
        </div>
      ) : null}
    </div>
  )
}
