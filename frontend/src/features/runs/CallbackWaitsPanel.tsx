import { useQuery, useQueryClient } from '@tanstack/react-query'
import { KeyRound } from 'lucide-react'
import { useState } from 'react'
import { api, call, callVoid } from '@/api/client'
import type { components } from '@/api/schema'
import type { ProblemError } from '@/api/problem'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { formatDateTime } from '@/lib/dates'

type CallbackWait = components['schemas']['CallbackWait']
type Issued = components['schemas']['CallbackTokenIssued']

export const CALLBACK_STATE: Record<CallbackWait['state'], string> = {
  waiting: 'Waiting for the external system',
  resumed: 'Answered by the external system',
  expired: 'Token expired; the step still waits',
  cancelled: 'No longer waiting',
}
const REASON: Record<string, string> = {
  timedOut: 'the time limit passed',
  requestEnded: 'the request ended',
  answeredElsewhere: 'a message answered it first',
}

/**
 * Waits answered by an external system (EX10, docs/build/S98), for operators (run.operate). Issuing shows the token once:
 * Orqix keeps only its hash, so it cannot be shown again; issuing again replaces it. Revoking stops it at once and the
 * step keeps waiting. This is separate from suspending and resuming the whole request.
 */
export function CallbackWaitsPanel({ ws, runId }: { ws: string; runId: string }) {
  const client = useQueryClient()
  const key = ['ws', ws, 'callback-waits', runId]
  const query = useQuery({ queryKey: key, retry: false,
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/callback-waits', { params: { path: { workspaceId: ws }, query: { runId } } })) })
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
  const issue = (w: CallbackWait) => act(async () => {
    const result = await call(api.POST('/workspaces/{workspaceId}/callback-waits/{waitId}/token', { params: { path: { workspaceId: ws, waitId: w.id } } }))
    setIssued(result)
    setCopied('')
  })
  const copy = async (token: string) => {
    try {
      if (!navigator.clipboard) throw new Error('no clipboard')
      await navigator.clipboard.writeText(token)
      setCopied('Copied.')
    } catch {
      setCopied('Copying failed. Select the token and copy it yourself.')
    }
  }
  const revoke = (w: CallbackWait) => act(async () => {
    await callVoid(api.DELETE('/workspaces/{workspaceId}/callback-waits/{waitId}/token', { params: { path: { workspaceId: ws, waitId: w.id } } }))
    if (issued?.waitId === w.id) setIssued(null)
    setNotice(`The token for "${w.stepLabel}" no longer works. The step still waits; you can issue a new token.`)
  })

  return (
    <section aria-labelledby="callback-heading" className="rounded-card border border-border bg-surface p-4">
      <h2 id="callback-heading" className="mb-1 flex items-center gap-2 text-base">
        <KeyRound className="size-4" aria-hidden="true" /> External system waits
      </h2>
      <p className="mb-3 text-sm text-muted-foreground">
        An external system can answer these steps with a callback token. Give the token to that system only; it is shown once.
      </p>
      {error ? <ProblemAlert error={error} /> : null}
      {notice ? <Alert tone="info">{notice}</Alert> : null}
      <ul className="divide-y divide-border" aria-label="External system waits">
        {items.map((w) => {
          const open = w.state === 'waiting' || w.state === 'expired'
          return (
            <li key={w.id} className="space-y-2 py-2 text-sm" data-testid={`callback-wait-${w.nodeId}`}>
              <p><span className="font-semibold">{w.stepLabel}</span> · {CALLBACK_STATE[w.state]}{w.reason ? ` (${REASON[w.reason] ?? w.reason})` : ''}{w.suspended ? ' · request suspended: answers are refused until it is resumed' : ''}</p>
              <p className="text-muted-foreground">
                {w.tokenIssued && w.tokenExpiresAt ? `Token valid until ${formatDateTime(w.tokenExpiresAt)}` : 'No token issued'}
                {w.tokensIssued > 1 ? ` · ${w.tokensIssued} tokens issued so far` : ''}
                {w.resumedAt ? ` · answered ${formatDateTime(w.resumedAt)}` : ''}
                {w.fields.length ? ` · expects ${w.fields.map((f) => f.name + (f.required ? '' : ' (optional)')).join(', ')}` : ''}
              </p>
              {open ? (
                <div className="flex flex-wrap gap-2">
                  <Button variant="secondary" disabled={pending} onClick={() => void issue(w)} aria-label={`${w.tokenIssued ? 'Replace the token' : 'Issue a token'} for ${w.stepLabel}`}>
                    {w.tokenIssued ? 'Replace token' : 'Issue token'}
                  </Button>
                  {w.tokenIssued ? <Button variant="danger" disabled={pending} onClick={() => void revoke(w)} aria-label={`Revoke the token for ${w.stepLabel}`}>Revoke token</Button> : null}
                </div>
              ) : null}
              {issued?.waitId === w.id ? (
                <div className="space-y-1 rounded-control border border-border p-3" data-testid="callback-token-once">
                  {/* Only this sentence is announced; the secret itself is never read out by a live region. */}
                  <p role="status" className="font-medium">Token issued. Copy it now: it will not be shown again.</p>
                  <code className="block break-all" aria-label="Callback token">{issued.token}</code>
                  <p className="text-muted-foreground">Send it in the <code>{issued.header}</code> header. Poll <code>{issued.pollPath}</code>, resume with <code>{issued.resumePath}</code>. Valid until {formatDateTime(issued.expiresAt)}.</p>
                  <Button variant="secondary" onClick={() => { void copy(issued.token) }}>Copy token</Button>
                  <p role="status" aria-live="polite" data-testid="callback-token-copied" className="text-sm text-muted-foreground">{copied}</p>
                </div>
              ) : null}
            </li>
          )
        })}
      </ul>
    </section>
  )
}
