import { useEffect, useState } from 'react'
import { attachmentUrl } from '@/api/attachments'
import type { components } from '@/api/schema'
import { Alert } from '@/components/ui/alert'
import { cn } from '@/lib/cn'

type Verification = components['schemas']['AiVerification']
type Field = components['schemas']['AiVerificationField']

const UNAVAILABLE: Record<string, string> = {
  removed: 'The document was removed from the request.',
  quarantined: 'The document is quarantined while it is reviewed, so it cannot be shown.',
  scanBlocked: 'The document is blocked by the virus scan, so it cannot be shown.',
}

/**
 * The document beside the fields (CG-A06): images with the active field's source box drawn on the page, text files
 * with the source line and value highlighted, PDFs in the browser's viewer at the source page (no box overlay). The
 * bytes are fetched like a download and shown from a local object URL; nothing is sent elsewhere.
 */
export function DocumentPreview({ ws, verification, active }: { ws: string; verification: Verification; active: Field | null }) {
  const { attachment } = verification
  const [content, setContent] = useState<{ url: string; text: string | null } | null>(null)
  const [failed, setFailed] = useState(false)
  const image = attachment.contentType.startsWith('image/')
  const pdf = attachment.contentType === 'application/pdf'

  useEffect(() => {
    if (!attachment.available) return undefined
    let url: string | null = null
    let cancelled = false
    void (async () => {
      try {
        const response = await fetch(attachmentUrl(ws, verification.runId, attachment.id), { credentials: 'same-origin' })
        if (!response.ok) throw new Error(String(response.status))
        const blob = await response.blob()
        const text = !image && !pdf ? await blob.text() : null
        url = URL.createObjectURL(new Blob([blob], { type: attachment.contentType }))
        if (!cancelled) setContent({ url, text })
      } catch {
        if (!cancelled) setFailed(true)
      }
    })()
    return () => {
      cancelled = true
      if (url) URL.revokeObjectURL(url)
    }
  }, [ws, verification.runId, attachment.id, attachment.available, attachment.contentType, image, pdf])

  if (!attachment.available) return <Alert tone="warning">{UNAVAILABLE[attachment.unavailableReason ?? 'removed'] ?? 'The document cannot be shown.'}</Alert>
  if (failed) return <Alert tone="warning">The document could not be loaded. The fields and their source text are still shown.</Alert>
  if (!content) return <p className="text-sm text-muted-foreground">Loading the document…</p>
  const box = active?.source.box ?? null
  if (image) {
    return (
      <div className="relative inline-block max-w-full" data-testid="document-image">
        <img src={content.url} alt={`Page 1 of ${attachment.name}`} className="block max-w-full border border-border" />
        {box ? <div aria-hidden="true" data-testid="source-box" className="pointer-events-none absolute rounded-sm border-2 border-primary bg-primary/10"
          style={{ left: `${box.x * 100}%`, top: `${box.y * 100}%`, width: `${box.w * 100}%`, height: `${box.h * 100}%` }} /> : null}
      </div>
    )
  }
  if (pdf) {
    const page = active?.source.page ?? 1
    return (
      <div className="space-y-2">
        <p className="text-sm text-muted-foreground">Showing page {page}. The source text of the selected field is shown with the field.</p>
        <iframe key={page} title={`Document ${attachment.name}, page ${page}`} src={`${content.url}#page=${page}`} className="h-[70vh] w-full border border-border" />
      </div>
    )
  }
  const lines = (content.text ?? '').split(/\r?\n/)
  const activeLine = active?.source.line ?? 0
  return (
    <ol className="max-h-[70vh] overflow-auto rounded-md border border-border bg-surface p-3 font-mono text-sm" aria-label={`Text of ${attachment.name}`}>
      {lines.map((line, i) => (
        <li key={i} data-testid={i + 1 === activeLine ? 'source-line' : undefined}
          className={cn('whitespace-pre-wrap px-1', i + 1 === activeLine && 'rounded-sm bg-primary-soft font-semibold')}>
          {line || ' '}
        </li>
      ))}
    </ol>
  )
}
