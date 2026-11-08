import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { MOCK_VERIFICATION_ID, resetMockVerification } from '@/mocks/documentExtraction'
import { renderAppReady, signInAs, WS } from '@/test/utils'
import { parseValue } from './VerificationPage'

const RUN = '00000000-0000-7000-8000-0000000000c1'

describe('document verification task (CG-A06)', () => {
  beforeEach(() => {
    signInAs('olga.operator')
    resetMockVerification(RUN)
  })

  it('parses typed values into the field type', () => {
    expect(parseValue('number', '1,234.50')).toEqual({ value: 1234.5 })
    expect(parseValue('number', 'a lot')).toHaveProperty('error')
    expect(parseValue('date', '2026-10-01')).toEqual({ value: '2026-10-01' })
    expect(parseValue('date', '01.10.2026')).toHaveProperty('error')
    expect(parseValue('integer', '2.5')).toHaveProperty('error')
    expect(parseValue('string', '  ACME ')).toEqual({ value: 'ACME' })
    expect(parseValue('string', '')).toEqual({ value: null })
  })

  it('shows the document beside the fields and submits a correction', async () => {
    await renderAppReady(`/w/${WS}/runs/${RUN}/verifications/${MOCK_VERIFICATION_ID}`)
    expect(await screen.findByRole('heading', { name: 'Verify the invoice' })).toBeInTheDocument()
    const fields = screen.getByRole('list', { name: 'Extracted fields' })
    const total = within(fields).getByTestId('field-totalAmount')
    expect(within(total).getByText('Needs you')).toBeInTheDocument()
    expect(within(total).getByText(/does not equal subtotalAmount/)).toBeInTheDocument()
    expect(within(total).getByText(/Total: 119.00/)).toBeInTheDocument()
    // The document text with the selected field's source line highlighted.
    expect((await screen.findByTestId('source-line')).textContent).toBe('Total: 119.00')
    const submit = screen.getByRole('button', { name: '1 field still need you' })
    expect(submit).toBeDisabled()

    const user = userEvent.setup()
    const input = within(total).getByLabelText('Value')
    await user.clear(input)
    await user.type(input, '119.50')
    expect(within(total).getByText('Corrected')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Submit verified values' }))
    expect(await screen.findByText('Corrected: Total amount.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Submit verified values' })).not.toBeInTheDocument()
  })

  it('moves between fields and confirms with the keyboard', async () => {
    await renderAppReady(`/w/${WS}/runs/${RUN}/verifications/${MOCK_VERIFICATION_ID}`)
    await screen.findByRole('heading', { name: 'Verify the invoice' })
    const first = document.getElementById('verify-field-supplierName')!
    first.focus()
    fireEvent.keyDown(first, { key: 'ArrowDown' })
    const second = document.getElementById('verify-field-invoiceNumber')!
    await waitFor(() => expect(second).toHaveAttribute('aria-current', 'true'))
    fireEvent.keyDown(second, { key: 'Enter' })
    expect(within(screen.getByTestId('field-invoiceNumber')).getByText('Confirmed')).toBeInTheDocument()
  })
})
