import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, expect, it } from 'vitest'
import { server } from '@/test/server'
import { GuestResponsePage } from './GuestResponsePage'

const TOKEN = `ogf_${'a'.repeat(43)}`

function open(hash: string) {
  window.history.replaceState(null, '', `/guest/ws-1${hash}`)
  render(<MemoryRouter initialEntries={['/guest/ws-1']}><Routes><Route path="/guest/:workspaceId" element={<GuestResponsePage />} /></Routes></MemoryRouter>)
}

afterEach(() => {
  sessionStorage.clear()
  window.history.replaceState(null, '', '/')
})

it('reads the token from the fragment, removes it from the address bar and sends the typed answers once', async () => {
  const seen: { token: string | null; body: unknown; key: string | null }[] = []
  server.use(
    http.get('/api/v1/workspaces/:ws/guest-response', ({ request }) => {
      seen.push({ token: request.headers.get('X-Orqix-Guest-Token'), body: null, key: null })
      return HttpResponse.json({ title: 'Confirm the payment', instructions: 'Enter the reference from your bank.', state: 'open', expiresAt: '2026-11-09T10:00:00Z',
        questions: [{ name: 'paymentReference', type: 'string', title: 'Payment reference', required: true }, { name: 'amountPaid', type: 'number', title: 'Amount paid', required: false }],
        files: null, uploaded: [] })
    }),
    http.post('/api/v1/workspaces/:ws/guest-response/submit', async ({ request }) => {
      seen.push({ token: request.headers.get('X-Orqix-Guest-Token'), body: await request.json(), key: request.headers.get('Idempotency-Key') })
      return HttpResponse.json({ state: 'submitted', submittedAt: '2026-11-06T10:00:00Z', fileCount: 0 })
    }),
  )
  open(`#${TOKEN}`)
  expect(await screen.findByRole('heading', { name: 'Confirm the payment' })).toBeInTheDocument()
  expect(window.location.hash).toBe('')
  expect(seen[0]?.token).toBe(TOKEN)
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: 'Send response' }))
  expect(await screen.findByText('This field is required.')).toBeInTheDocument()
  await user.type(screen.getByRole('textbox', { name: /Payment reference/ }), 'PAY-1')
  await user.type(screen.getByRole('textbox', { name: /Amount paid/ }), '99.5')
  await user.click(screen.getByRole('button', { name: 'Send response' }))
  expect(await screen.findByText(/Your response was sent/)).toBeInTheDocument()
  const submit = seen.find((s) => s.body)
  expect(submit?.body).toEqual({ result: { paymentReference: 'PAY-1', amountPaid: 99.5 }, files: [] })
  expect(submit?.key).toBeTruthy()
  expect(submit?.token).toBe(TOKEN)
})

it('explains an unknown or withdrawn link without showing any form', async () => {
  server.use(http.get('/api/v1/workspaces/:ws/guest-response', () => HttpResponse.json({ code: 'NOT_FOUND', status: 404, title: 'Not found' }, { status: 404 })))
  open(`#${TOKEN}`)
  expect(await screen.findByText(/This link does not work/)).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Send response' })).not.toBeInTheDocument()
})

it('asks for the link again when the fragment is missing', async () => {
  open('')
  expect(await screen.findByText(/This link is not complete/)).toBeInTheDocument()
})
