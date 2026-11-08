import { useQuery, useQueryClient } from '@tanstack/react-query'
import { UserRoundPen } from 'lucide-react'
import { useState } from 'react'
import { issueGuestLink, listGuestLinks, revokeGuestLink, sendGuestLink, type GuestLink } from '@/api/guest'
import type { components } from '@/api/schema'
import type { ProblemError } from '@/api/problem'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { formatDateTime } from '@/lib/dates'

type Issued = components['schemas']['GuestLinkIssued']

export const GUEST_STATE: Record<GuestLink['state'], string> = {
  waiting: 'Waiting for the guest',
  submitted: 'Answered by the guest',
  expired: 'Link expired; the step still waits',
  revoked: 'Link withdrawn; the step still waits',
  cancelled: 'No longer waiting',
}
const SEND_STATE: Record<GuestLink['sendState'], string> = {
  manual: 'Shared by an operator',
  pending: 'Being emailed',
  sent: 'Emailed',
  refused: 'Not emailed: the address is missing or its domain is not approved',
  failed: 'Not emailed: mail is not available',
}
const REASON: Record<string, string> = {
  timedOut: 'the time limit passed',
  requestEnded: 'the request ended',
  answeredElsewhere: 'a message answered it first',
}

/**
 * Guest response links (CG-F05, docs/build/S140) for operators (run.operate). A link is shown once: Orqix keeps only its
 * hash. Issuing again replaces it; withdrawing stops it at once and the step keeps waiting. Emailing goes only to an
 * approved domain, checked when the email is sent.
 */
export function GuestLinksPanel({ ws, runId }: { ws: string; runId: string }) {
  const client = useQueryClient()
  const key = ['ws', ws, 'guest-links', runId]
  const query = useQuery({ queryKey: key, retry: false, queryFn: () => listGuestLinks(ws, runId) })
  const [issued, setIssued] = useState<Issued | null>(null)
  const [error, setError] = useState<ProblemError | null>(null)
  const [notice, setNotice] = useState('')
  const [pending, setPending] = useState(false)
  const [copied, setCopied] = useState('')
  const items = query.data?.items ?? []
  if (items.length === 0) return null

  const act = async (action: () => Promise<void>) => {
    setPending(true); setError(null); setNotice('')
    try { await action(); await client.invalidateQueries({ queryKey: key }) } catch (cause) { setError(toProblem(cause)) } finally { setPending(false) }
  }
  const copy = async (url: string) => {
    try {
      if (!navigator.clipboard) throw new Error('no clipboard')
      await navigator.clipboard.writeText(url)
      setCopied('Copied.')
    } catch {
      setCopied('Copying failed. Select the link and copy it yourself.')
    }
  }

  return (
    <section aria-labelledby="guest-links-heading" className="rounded-card border border-border bg-surface p-4">
      <h2 id="guest-links-heading" className="mb-1 flex items-center gap-2 text-base">
        <UserRoundPen className="size-4" aria-hidden="true" /> Guest responses
      </h2>
      <p className="mb-3 text-sm text-muted-foreground">
        A person outside the organization answers these steps through a one-time link. Share it with that person only.
      </p>
      {error ? <ProblemAlert error={error} /> : null}
      {notice ? <Alert tone="info">{notice}</Alert> : null}
      <ul className="divide-y divide-border" aria-label="Guest responses">
        {items.map((g) => {
          const open = g.state === 'waiting' || g.state === 'expired' || g.state === 'revoked'
          return (
            <li key={g.id} className="space-y-2 py-2 text-sm" data-testid={`guest-link-${g.nodeId}`}>
              <p><span className="font-semibold">{g.stepLabel}</span>{g.title ? ` · ${g.title}` : ''} · {GUEST_STATE[g.state]}{g.reason ? ` (${REASON[g.reason] ?? g.reason})` : ''}</p>
              <p className="text-muted-foreground">
                {SEND_STATE[g.sendState]}{g.sentDomain ? ` (${g.sentDomain})` : ''}
                {g.tokenIssued && g.tokenExpiresAt ? ` · link valid until ${formatDateTime(g.tokenExpiresAt)}` : ' · no link issued'}
                {g.firstViewedAt ? ` · opened ${formatDateTime(g.firstViewedAt)}` : ''}
                {g.submittedAt ? ` · answered ${formatDateTime(g.submittedAt)}` : ''}
              </p>
              {open ? (
                <div className="flex flex-wrap gap-2">
                  <Button variant="secondary" disabled={pending} aria-label={`${g.tokenIssued ? 'Replace the link' : 'Create a link'} for ${g.stepLabel}`}
                    onClick={() => void act(async () => { setIssued(await issueGuestLink(ws, g.id)); setCopied('') })}>
                    {g.tokenIssued ? 'Replace link' : 'Create link'}
                  </Button>
                  {g.sendState === 'refused' || g.sendState === 'failed' || g.sendState === 'sent' ? (
                    <Button variant="secondary" disabled={pending} aria-label={`Email a new link for ${g.stepLabel}`}
                      onClick={() => void act(async () => { await sendGuestLink(ws, g.id); setNotice('A new link will be emailed if the address is in an approved domain.') })}>
                      Email again
                    </Button>
                  ) : null}
                  {g.tokenIssued ? (
                    <Button variant="danger" disabled={pending} aria-label={`Withdraw the link for ${g.stepLabel}`}
                      onClick={() => void act(async () => { await revokeGuestLink(ws, g.id); if (issued?.linkId === g.id) setIssued(null); setNotice(`The link for "${g.stepLabel}" no longer works. The step still waits.`) })}>
                      Withdraw link
                    </Button>
                  ) : null}
                </div>
              ) : null}
              {issued?.linkId === g.id ? (
                <div className="space-y-1 rounded-control border border-border p-3" data-testid="guest-link-once">
                  <p role="status" className="font-medium">Link created. Copy it now: it will not be shown again.</p>
                  <code className="block break-all" aria-label="Guest link">{issued.url}</code>
                  <p className="text-muted-foreground">It works once, until {formatDateTime(issued.expiresAt)}.</p>
                  <Button variant="secondary" onClick={() => { void copy(issued.url) }}>Copy link</Button>
                  <p role="status" aria-live="polite" className="text-sm text-muted-foreground">{copied}</p>
                </div>
              ) : null}
            </li>
          )
        })}
      </ul>
    </section>
  )
}
