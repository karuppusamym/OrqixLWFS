import { describe, expect, it } from 'vitest'
import decisionFixture from '../../../../backend/app/src/test/resources/cases-extra/decision-file.cmmn?raw'
import { caseModelDecisionTasks, caseModelXml, type CaseModelItem } from './caseModel'
import { importCaseModel } from './cmmnImport'

const parse = (xml: string) => { const doc = new DOMParser().parseFromString(xml, 'application/xml'); expect(doc.querySelector('parsererror')).toBeNull(); return doc }
const review: CaseModelItem = { id: 'review', kind: 'humanTask', label: 'Review', group: 'managers' }

describe('decision tasks, case file events, timer dates and any-of rules (S118)', () => {
  it('generates a decision task naming a shared decision by a namespaced QName and returns its mappings', () => {
    const decide: CaseModelItem = { id: 'route', kind: 'decisionTask', label: 'Route', decision: { key: 'amount-policy', input: { '/input/amount': 'amount' }, result: { route: 'route' } } }
    const doc = parse(caseModelXml('routed', 'Routed', [decide, { ...review, after: 'route' }]))
    const decision = doc.getElementsByTagNameNS('*', 'decision')[0]!
    expect(decision.getAttribute('externalRef')).toBe('orqixdec:amount-policy')
    expect(decision.lookupNamespaceURI('orqixdec')).toBe('urn:orqix:decision')
    expect(doc.getElementsByTagNameNS('*', 'decisionTask')[0]!.getAttribute('decisionRef')).toBe(decision.getAttribute('id'))
    expect(caseModelDecisionTasks([decide])).toEqual([{ taskId: 'node_route', inputMapping: { '/input/amount': 'amount' }, resultMapping: { route: 'route' } }])
    expect(() => caseModelXml('routed', 'Routed', [{ ...decide, decision: { key: '', input: {}, result: {} } }])).toThrow('Choose the decision')
  })

  it('generates case file items for data events and one entry criterion per alternative for any-of rules', () => {
    const xml = caseModelXml('filed', 'Filed', [review,
      { id: 'answer', kind: 'humanTask', label: 'Answer', group: 'managers', onData: [{ field: 'comment', event: 'create' }] },
      { id: 'either', kind: 'milestone', label: 'Either', after: 'review', onData: [{ field: 'comment', event: 'update' }], anyOf: true }])
    const doc = parse(xml)
    expect([...doc.getElementsByTagNameNS('*', 'caseFileItem')].map(item => item.getAttribute('name'))).toEqual(['comment'])
    expect(xml.indexOf('<caseFileItemDefinition')).toBeLessThan(xml.indexOf('<case '))
    const either = doc.querySelector('[id="node_either_item"]')!
    expect([...either.children].filter(child => child.localName === 'entryCriterion')).toHaveLength(2)
    expect([...doc.getElementsByTagNameNS('*', 'caseFileItemOnPart')].map(part => part.textContent)).toEqual(['create', 'update'])
  })

  it('accepts timer dates a minute to five years ahead and refuses the past', () => {
    const at = new Date(Date.now() + 3 * 86_400_000).toISOString().replace(/\.\d{3}Z$/, 'Z')
    expect(caseModelXml('timed', 'Timed', [review, { id: 'deadline', kind: 'timerEventListener', label: 'Deadline', timer: at }])).toContain(`<timerExpression>${at}</timerExpression>`)
    expect(() => caseModelXml('timed', 'Timed', [review, { id: 'deadline', kind: 'timerEventListener', label: 'Deadline', timer: '2020-01-01T00:00:00Z' }])).toThrow('timer')
  })

  it('imports decision tasks, case file events and any-of criteria from CMMN files', () => {
    const imported = importCaseModel(decisionFixture.replace('DECISIONKEY', 'amount-policy'))
    expect(imported.diagnostics.filter(d => d.severity === 'error')).toEqual([])
    expect(imported.items.find(item => item.id === 'route')).toMatchObject({ kind: 'decisionTask', decision: { key: 'amount-policy' } })
    expect(imported.items.find(item => item.id === 'answer')?.onData).toEqual([{ field: 'comment', event: 'create' }])
    expect(imported.items.find(item => item.id === 'finance')).toMatchObject({ after: 'route', when: { clauses: [{ field: 'route', op: '==', value: 'financeReview' }] } })
    const again = parse(caseModelXml('decision-case', imported.name, imported.items, imported.options))
    expect(again.getElementsByTagNameNS('*', 'caseFileItemOnPart')).toHaveLength(1)
    const anyOf = importCaseModel(`<?xml version="1.0"?><definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL" xmlns:flowable="http://flowable.org/cmmn"><case id="any" name="Any"><casePlanModel id="plan">
      <planItem id="a" definitionRef="ta"/><planItem id="b" definitionRef="tb"/><planItem id="c" definitionRef="tc"><entryCriterion id="e1" sentryRef="s1"/><entryCriterion id="e2" sentryRef="s2"/></planItem>
      <sentry id="s1"><planItemOnPart sourceRef="a"><standardEvent>complete</standardEvent></planItemOnPart></sentry><sentry id="s2"><planItemOnPart sourceRef="b"><standardEvent>complete</standardEvent></planItemOnPart></sentry>
      <humanTask id="ta" flowable:candidateGroups="managers"/><humanTask id="tb" flowable:candidateGroups="managers"/><humanTask id="tc" flowable:candidateGroups="managers"/></casePlanModel></case></definitions>`)
    expect(anyOf.items.find(item => item.id === 'c')).toMatchObject({ after: 'a', afterAll: ['b'], anyOf: true })
    expect(anyOf.diagnostics.filter(d => d.severity === 'error')).toEqual([])
  })
})
