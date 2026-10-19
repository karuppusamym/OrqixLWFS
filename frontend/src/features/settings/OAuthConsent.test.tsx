import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import { renderAppReady, signInAs } from '@/test/utils'
import { WS_SANDBOX } from '@/mocks/state'
import { OAuthConsent } from './OAuthConsent'

describe('delegated connection authorization', () => {
  it('starts the approved provider flow and disconnects without exposing credentials', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/api/v1/workspaces/:ws/connections/:key/oauth', () => HttpResponse.json({ status: 'consentRequired', expiresAt: null, remoteRevocation: null })),
      http.post('*/api/v1/workspaces/:ws/connections/:key/oauth/authorize', () => HttpResponse.json({ authorizationUrl: 'https://identity.example/authorize?state=opaque', expiresAt: '2026-10-18T12:10:00Z' })),
      http.post('*/api/v1/workspaces/:ws/connections/:key/oauth/disconnect', () => HttpResponse.json({ status: 'disconnected', expiresAt: null, remoteRevocation: 'unconfirmed' })),
    )
    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><OAuthConsent ws={WS_SANDBOX} connectionKey="approved" /></QueryClientProvider>)
    expect(await screen.findByText('Authorization needed')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Authorize workspace connection' }))
    expect(await screen.findByRole('link', { name: 'Continue to authorization provider' })).toHaveAttribute('href', 'https://identity.example/authorize?state=opaque')
    await user.click(screen.getByRole('button', { name: 'Disconnect authorization' }))
    expect(await screen.findByText('Disconnected')).toBeInTheDocument()
    expect(screen.getByText(/Provider revocation was not confirmed/)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Continue to authorization provider' })).not.toBeInTheDocument()
  })

  it('consumes the response once and removes transient provider data from the route', async () => {
    signInAs('wade.admin')
    let calls = 0, sent: unknown
    server.use(http.post('*/api/v1/workspaces/:ws/connections/:key/oauth/complete', async ({ request }) => {
      calls++; sent = await request.json()
      return HttpResponse.json({ status: 'connected', expiresAt: '2026-10-18T13:00:00Z', remoteRevocation: null })
    }))
    const state = 'a'.repeat(43)
    const { router } = await renderAppReady(`/w/${WS_SANDBOX}/settings/connections/approved/oauth/callback?code=temporary-code&state=${state}`)
    expect(await screen.findByText('The workspace connection is authorized.')).toBeInTheDocument()
    expect(calls).toBe(1)
    expect(sent).toEqual({ state, code: 'temporary-code' })
    expect(router.state.location.search).toBe('')
    expect(screen.queryByText('temporary-code')).not.toBeInTheDocument()
  })

  it('handles provider denial without sending an exchange or displaying provider details', async () => {
    signInAs('wade.admin')
    const { router } = await renderAppReady(`/w/${WS_SANDBOX}/settings/connections/approved/oauth/callback?error=access_denied&error_description=private-provider-detail`)
    expect(await screen.findByText(/Authorization could not be completed/)).toBeInTheDocument()
    expect(router.state.location.search).toBe('')
    expect(screen.queryByText('private-provider-detail')).not.toBeInTheDocument()
  })
})
