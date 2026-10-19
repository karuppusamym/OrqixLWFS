import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import type { ProblemError } from '@/api/problem'
import { beginOAuth, disconnectOAuth, getOAuth } from './oauthApi'

const labels = { consentRequired: 'Authorization needed', connected: 'Authorized', refreshing: 'Renewing access', disconnected: 'Disconnected' } as const

export function OAuthConsent({ ws, connectionKey }: { ws: string; connectionKey: string }) {
  const queryKey = ['ws', ws, 'connection-oauth', connectionKey]
  const client = useQueryClient()
  const status = useQuery({ queryKey, queryFn: () => getOAuth(ws, connectionKey) })
  const [busy, setBusy] = useState(false)
  const [url, setUrl] = useState<string | null>(null)
  const [error, setError] = useState<ProblemError | null>(null)
  const begin = async () => {
    setBusy(true); setError(null); setUrl(null)
    try { setUrl((await beginOAuth(ws, connectionKey)).authorizationUrl) }
    catch (cause) { setError(toProblem(cause)) }
    finally { setBusy(false) }
  }
  const disconnect = async () => {
    setBusy(true); setError(null); setUrl(null)
    try { client.setQueryData(queryKey, await disconnectOAuth(ws, connectionKey)) }
    catch (cause) { setError(toProblem(cause)) }
    finally { setBusy(false) }
  }
  if (status.isPending) return <LoadingBlock label="Loading OAuth authorization" rows={1} />
  if (status.isError) return <QueryError error={status.error} onRetry={() => void status.refetch()} what="OAuth authorization" />
  return <section aria-label={`Authorization of ${connectionKey}`} className="space-y-3 rounded border p-4">
    <h3>Workspace authorization</h3>
    <p role="status">{labels[status.data.status]}</p>
    <p className="text-sm text-muted-foreground">The account you authorize grants this workspace connection access to the configured scopes. Credentials stay on the server.</p>
    {error ? <ProblemAlert error={error} /> : null}
    {status.data.remoteRevocation === 'unconfirmed' ? <Alert tone="warning">Local access was removed. Provider revocation was not confirmed; review connected applications in the provider account.</Alert> : null}
    <div className="flex flex-wrap gap-2">
      <Button type="button" disabled={busy} onClick={() => void begin()}>Authorize workspace connection</Button>
      {status.data.status !== 'disconnected' ? <Button type="button" variant="secondary" disabled={busy} onClick={() => void disconnect()}>Disconnect authorization</Button> : null}
      {url ? <Button asChild><a href={url} referrerPolicy="no-referrer">Continue to authorization provider</a></Button> : null}
    </div>
  </section>
}
