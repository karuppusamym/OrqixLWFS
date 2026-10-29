import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import parentFixture from '../../../../backend/app/src/test/resources/cases/parent-case.cmmn?raw'
import triggerFixture from '../../../../backend/app/src/test/resources/cases/timer-and-data.cmmn?raw'
import { renderInWorkspace, signInAs, WS } from '@/test/utils'
import { caseModelCaseTasks, caseModelXml, conditionExpression, type CaseModelItem } from './caseModel'
import { importCaseModel, parseCondition } from './cmmnImport'
import { CaseModelDesigner } from './CaseModelDesigner'

const parse = (xml: string) => { const doc = new DOMParser().parseFromString(xml, 'application/xml'); expect(doc.querySelector('parsererror')).toBeNull(); return doc }
const review: CaseModelItem = { id: 'review', kind: 'humanTask', label: 'Review', group: 'managers' }

describe('case tasks, timers and data conditions (S113)', () => {
  it('generates a case task that names the child case by a namespaced QName and returns its mappings', () => {
    const child: CaseModelItem = { id: 'supplier', kind: 'caseTask', label: 'Supplier review', required: true, childCase: { key: 'supplier-review', input: { amount: 'total' }, result: { verdict: 'outcome' } } }
    const xml = caseModelXml('parent-case', 'Parent', [child, { ...review, after: 'supplier' }])
    const task = parse(xml).getElementsByTagNameNS('*', 'caseTask')[0]!
    expect(task.getAttribute('caseRef')).toBe('orqixcase:supplier-review')
    expect(task.lookupNamespaceURI('orqixcase')).toBe('urn:orqix:case')
    expect(caseModelCaseTasks([child])).toEqual([{ taskId: 'node_supplier', inputMapping: { amount: 'total' }, resultMapping: { verdict: 'outcome' } }])
    expect(() => caseModelXml('parent-case', 'Parent', [{ ...child, childCase: { key: '', input: {}, result: {} } }])).toThrow('Choose the case')
  })

  it('generates timers and if-parts in the admitted grammar, alone or combined with an event', () => {
    const items: CaseModelItem[] = [
      review,
      { id: 'reminder', kind: 'timerEventListener', label: 'Reminder', timer: 'PT4H' },
      { id: 'chase', kind: 'humanTask', label: 'Chase', group: 'managers', after: 'reminder' },
      { id: 'escalate', kind: 'humanTask', label: 'Escalate', group: 'managers', when: { join: 'all', clauses: [{ field: 'amount', op: '>', value: 5000 }] } },
      { id: 'eu', kind: 'milestone', label: 'EU approval', after: 'review', when: { join: 'all', clauses: [{ field: 'region', op: '==', value: 'EU' }, { field: 'urgent', op: '==', value: true }] } },
    ]
    const xml = caseModelXml('trigger-case', 'Triggers', items)
    const doc = parse(xml)
    expect(doc.getElementsByTagNameNS('*', 'timerExpression')[0]!.textContent).toBe('PT4H')
    const conditions = [...doc.getElementsByTagNameNS('*', 'ifPart')].map(part => part.textContent)
    expect(conditions).toEqual(['${cd_amount > 5000}', "${cd_region == 'EU' && cd_urgent == true}"])
    expect(xml).toContain('sourceRef="node_reminder_item"><standardEvent>occur</standardEvent>')
    const euSentry = [...doc.getElementsByTagNameNS('*', 'sentry')].find(sentry => sentry.getAttribute('id') === 'node_eu_sentry')!
    expect([...euSentry.children].map(child => child.localName)).toEqual(['planItemOnPart', 'ifPart'])
    for (const invalid of [
      { id: 'reminder', kind: 'timerEventListener' as const, label: 'Reminder', timer: 'P1M' },
      { id: 'reminder', kind: 'timerEventListener' as const, label: 'Reminder', timer: 'PT10S' },
      { id: 'reminder', kind: 'timerEventListener' as const, label: 'Reminder', timer: 'PT4H', after: 'review' },
      { ...review, id: 'odd', when: { join: 'all' as const, clauses: [{ field: 'region', op: '==' as const, value: "EU' || true" }] } },
      { ...review, id: 'odd', when: { join: 'all' as const, clauses: [{ field: 'bad field', op: '==' as const, value: 1 }] } },
    ]) expect(() => caseModelXml('trigger-case', 'Triggers', [review, invalid])).toThrow()
  })

  it('reads conditions, timers and case tasks back from the backend fixtures', () => {
    const imported = importCaseModel(triggerFixture)
    expect(imported.diagnostics.filter(d => d.severity === 'error')).toEqual([])
    expect(imported.items.find(item => item.id === 'reminder')).toMatchObject({ kind: 'timerEventListener', timer: 'PT1H' })
    expect(imported.items.find(item => item.id === 'escalate')?.when).toEqual({ join: 'all', clauses: [{ field: 'amount', op: '>', value: 5000 }] })
    expect(imported.items.find(item => item.id === 'approved')).toMatchObject({ after: 'review', when: { join: 'all', clauses: [{ field: 'region', op: '==', value: 'EU' }, { field: 'amount', op: '<=', value: 5000 }] } })
    const again = parse(caseModelXml('trigger-case', imported.name, imported.items, imported.options))
    expect([...again.getElementsByTagNameNS('*', 'ifPart')].map(part => parseCondition(part.textContent ?? ''))).toEqual(
      [...parse(triggerFixture).getElementsByTagNameNS('*', 'ifPart')].map(part => parseCondition(part.textContent ?? '')))
    const parent = importCaseModel(parentFixture.replace('CHILDKEY', 'supplier-review'))
    expect(parent.items.find(item => item.kind === 'caseTask')?.childCase).toEqual({ key: 'supplier-review', input: {}, result: {} })
    expect(conditionExpression({ join: 'any', clauses: [{ field: 'a', op: '<', value: 1 }, { field: 'b', op: '!=', value: 'x' }] })).toBe("${cd_a < 1 || cd_b != 'x'}")
    for (const refused of ['${amount > 1}', '${cd_a > 1 && cd_b < 2 || cd_c == 3}', '${cd_a.class}', "${cd_a == 'x' + bean.run()}", '${execution.foo}', 'cd_a > 1'])
      expect(parseCondition(refused)).toBeNull()
  })

  it('builds a timer, a case task and a data condition in the form view', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    renderInWorkspace(<CaseModelDesigner ws={WS} caseKey="form-case" name="Form case" onApply={vi.fn()} disabled={false}
      dataFields={[{ key: 'amount', label: 'Amount', type: 'number', required: false }]} />)
    await user.click(await screen.findByRole('button', { name: 'Form view' }))
    await user.click(screen.getByRole('button', { name: 'Add timer' }))
    expect(await screen.findByLabelText('Wait for item1')).toHaveValue(1)
    expect(screen.getByLabelText('Unit for item1')).toHaveValue('hours')
    await user.selectOptions(screen.getByLabelText('Unit for item1'), 'days')
    await user.click(screen.getByRole('button', { name: 'Add case task' }))
    expect(await screen.findByLabelText('Case for item2')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Add data comparison to task1' }))
    const condition = screen.getByRole('group', { name: 'Only when case data matches (task1)' })
    await user.selectOptions(within(condition).getByLabelText('Comparison 1 for task1'), '>')
    await user.clear(within(condition).getByLabelText('Value 1 for task1'))
    await user.type(within(condition).getByLabelText('Value 1 for task1'), '5000')
    expect(screen.getByTestId('cmmn-task1')).toHaveTextContent('Starts when case data amount > 5000')
    expect(screen.getByTestId('cmmn-item1')).toHaveTextContent('Fires after P1D')
  })
})
