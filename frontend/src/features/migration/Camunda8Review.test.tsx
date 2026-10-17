import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import connector from '../../../../examples/migration/camunda8-connector-http.bpmn?raw'
import formBpmn from '../../../../examples/migration/camunda8-user-task-form.bpmn?raw'
import formFile from '../../../../examples/migration/camunda8-user-task-form.form?raw'
import unsupported from '../../../../examples/migration/camunda8-unsupported.bpmn?raw'
import { createMigration } from '@/mocks/migration'
import { renderApp, signInAs, WS } from '@/test/utils'
import { reasonLabel, sourceLabel } from './reasonLabels'

describe('Camunda 8 import review', () => {
  it('names the source version and the connector call as read-only provenance, in plain language', async () => {
    signInAs('dana.designer')
    const { migration } = createMigration(WS, 'connector.bpmn', connector)
    renderApp(`/w/${WS}/migrations/${migration.id}`)
    expect(await screen.findByText('Source: Camunda 8, version 8.5.0')).toBeInTheDocument()
    expect(screen.getByText('Choose the system call that replaces this connector')).toBeInTheDocument()
    const original = await screen.findByLabelText('Original call')
    expect(original).toHaveTextContent('Original call (read-only)')
    expect(original).toHaveTextContent('POST procurement.example.com/orders')
    expect(original).toHaveTextContent('Suggested operation name: createPurchaseOrder')
    expect(original).toHaveTextContent('title ← title')
    expect(original).not.toHaveTextContent('Content-Type')
    expect(screen.getByText(/not a claim of general Camunda 8 compatibility/)).toBeInTheDocument()
  })

  it('previews an embedded task form and an uploaded start form, and refuses a form with another id', async () => {
    signInAs('dana.designer')
    const { migration } = createMigration(WS, 'expense.bpmn', formBpmn)
    const user = userEvent.setup()
    renderApp(`/w/${WS}/migrations/${migration.id}`)
    const embedded = await screen.findByRole('region', { name: /Preview of Form reviewForm on managerReview/ })
    expect(within(embedded).getByText('Comment')).toBeInTheDocument()
    expect(screen.getAllByText('Task form kept as a note on the approval').length).toBeGreaterThan(0)
    const upload = await screen.findByLabelText(/Form file for start \(form id expense-request\)/)
    const wrong = new File(['{}'], 'wrong.form')
    Object.defineProperty(wrong, 'text', { value: async () => '{"id":"other","type":"default","components":[{"type":"textfield","id":"a","key":"x","label":"X"}]}' })
    await user.upload(upload, wrong)
    expect(await screen.findByText(/uses the form 'expense-request'/)).toBeInTheDocument()
    const right = new File(['{}'], 'expense.form')
    Object.defineProperty(right, 'text', { value: async () => formFile })
    await user.upload(upload, right)
    const preview = await screen.findByRole('region', { name: /Preview of Form expense-request as a request form/ })
    await waitFor(() => expect(within(preview).getByText('Employee name')).toBeInTheDocument())
    expect(within(preview).getByText('Not imported')).toBeInTheDocument()
    expect(within(preview).getByRole('combobox', { name: /Category/ })).toBeDisabled()
  })

  it('shows blocked Camunda 8 constructs with their reason codes and plain labels', async () => {
    signInAs('dana.designer')
    const { migration } = createMigration(WS, 'unsupported.bpmn', unsupported)
    renderApp(`/w/${WS}/migrations/${migration.id}`)
    await screen.findByText('Source: Camunda 8, version 8.5.0')
    for (const code of ['MESSAGE_EVENT_NOT_SUPPORTED', 'CONNECTOR_NOT_SUPPORTED', 'EXPRESSION_NOT_SUPPORTED', 'FORM_COMPONENT_NOT_SUPPORTED'])
      expect(screen.getAllByText(new RegExp(code)).length).toBeGreaterThan(0)
    expect(screen.getAllByText('This message event cannot be imported').length).toBeGreaterThan(0)
  })

  it('labels reason codes and sources', () => {
    expect(reasonLabel('BOUNDARY_TIMER_MAPPED')).toBe('Time limit on an approval')
    expect(reasonLabel('SOMETHING_ELSE')).toBeNull()
    expect(sourceLabel({ sourceSystem: 'camunda7', sourceVersion: null })).toBe('Camunda 7')
  })
})
