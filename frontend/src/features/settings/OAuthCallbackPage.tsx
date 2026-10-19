import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router'
import { useWorkspace } from '@/app/workspace'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { PageHeader } from '@/components/common/PageHeader'
import { finishOAuth } from './oauthApi'

export function OAuthCallbackPage() {
  const { workspace, can } = useWorkspace()
  const { connectionKey = '' } = useParams()
  const location = useLocation(), navigate = useNavigate()
  const [response] = useState(() => new URLSearchParams(location.search))
  const [status, setStatus] = useState<'pending' | 'connected' | 'failed'>(() => response.has('error') || !response.get('code') || !response.get('state') ? 'failed' : 'pending')
  const attempt = useRef<ReturnType<typeof finishOAuth> | null>(null)
  // Remove transient provider data before starting API calls or displaying the rest of the page.
  useLayoutEffect(() => { if (location.search) void navigate(location.pathname, { replace: true }) }, [location.pathname, location.search, navigate])
  const permitted = can('connection.manage')
  useEffect(() => {
    if (!permitted) return
    const code = response.get('code'), state = response.get('state')
    if (response.has('error') || !code || !state) return
    let active = true
    attempt.current ??= finishOAuth(workspace.id, connectionKey, state, code)
    void attempt.current.then(() => { if (active) setStatus('connected') }, () => { if (active) setStatus('failed') })
    return () => { active = false }
  }, [permitted, workspace.id, connectionKey, response])
  return <div className="space-y-4">
    <PageHeader title="Connection authorization" />
    {!permitted ? <Alert tone="warning">You need connection management permission to authorize this workspace connection.</Alert>
      : status === 'pending' ? <p role="status">Completing authorization…</p>
        : status === 'connected' ? <Alert tone="success">The workspace connection is authorized.</Alert>
          : <Alert tone="warning">Authorization could not be completed. Return to settings and start authorization again.</Alert>}
    <Button asChild><Link to={`/w/${workspace.id}/settings`}>Return to settings</Link></Button>
  </div>
}
