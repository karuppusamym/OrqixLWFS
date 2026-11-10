import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { grantDataReveal, revealLog, seedProtectedRequest } from '@/mocks/security'
import { userByName } from '@/mocks/state'
import { server } from '@/test/server'
import { renderApp, signInAs, WS } from '@/test/utils'

const MASK = '•'.repeat(6)

function rowOf(label: string) {
  return screen.getByText(label, { selector: 'dt' }).parentElement as HTMLElement
}

describe('masked personal and sensitive answers (CG-I05)', () => {
  it('shows masked answers with a hint and reveals them inline for people with data.reveal', async () => {
    signInAs('olga.operator')
    const runId = seedProtectedRequest()
    grantDataReveal(WS, userByName('olga.operator').id)
    const user = userEvent.setup()
    renderApp(`/w/${WS}/runs/${runId}`)
    await screen.findByTestId('request-values')
    expect(within(rowOf('Bank account (IBAN)')).getByText(MASK)).toBeInTheDocument()
    expect(within(rowOf('Bank account (IBAN)')).getByText('Masked')).toBeInTheDocument()
    expect(within(rowOf('Home address')).getByText('Masked')).toBeInTheDocument()
    expect(within(rowOf('Business purpose')).queryByText('Masked')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Reveal' }))
    expect(await within(rowOf('Bank account (IBAN)')).findByText('DE89370400440532013000')).toBeInTheDocument()
    expect(within(rowOf('Home address')).getByText('12 Rose Street, Springfield')).toBeInTheDocument()
    expect(within(rowOf('Home address')).getByText('Revealed')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reveal' })).not.toBeInTheDocument()
    expect(revealLog).toEqual([expect.objectContaining({ runId, fields: ['homeAddress', 'bankAccount'] })])
    expect(JSON.stringify({ ...window.localStorage, ...window.sessionStorage })).not.toContain('DE89370400440532013000')
  })

  it('keeps answers masked and offers no reveal without the permission', async () => {
    signInAs('olga.operator')
    const runId = seedProtectedRequest()
    renderApp(`/w/${WS}/runs/${runId}`)
    await screen.findByTestId('request-values')
    expect(within(rowOf('Bank account (IBAN)')).getByText('Masked')).toBeInTheDocument()
    expect(screen.getByText(/Only people allowed to reveal them can see them/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reveal' })).not.toBeInTheDocument()
  })

  it('explains a refused reveal and keeps the answers masked', async () => {
    signInAs('olga.operator')
    const runId = seedProtectedRequest()
    grantDataReveal(WS, userByName('olga.operator').id)
    server.use(http.post('*/api/v1/workspaces/:ws/runs/:runId/reveal', () => HttpResponse.json(
      { type: 'https://orqix.example/problems/forbidden', title: 'You are not allowed to do this', status: 403, code: 'FORBIDDEN', correlationId: 'c-1' },
      { status: 403, headers: { 'Content-Type': 'application/problem+json' } },
    )))
    const user = userEvent.setup()
    renderApp(`/w/${WS}/runs/${runId}`)
    await screen.findByTestId('request-values')
    await user.click(screen.getByRole('button', { name: 'Reveal' }))
    expect(await screen.findByText(/reveal the masked answers/)).toBeInTheDocument()
    expect(within(rowOf('Bank account (IBAN)')).getByText('Masked')).toBeInTheDocument()
  })
})
