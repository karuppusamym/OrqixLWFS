import { describe, expect, it } from 'vitest'
import discretionaryFixture from '../../../../backend/app/src/test/resources/cases/discretionary.cmmn?raw'
import workflowFixture from '../../../../backend/app/src/test/resources/cases/workflow-task.cmmn?raw'
import repetitionFixture from '../../../../backend/app/src/test/resources/cases/general-repetition.cmmn?raw'
import nestedFixture from '../../../../backend/app/src/test/resources/cases/nested-stages.cmmn?raw'
import { caseModelWorkflowTasks, caseModelXml, type CaseModelItem } from './caseModel'
import { connect, connectionProblem, disconnect, reparent, stageAt } from './caseCanvasOps'
import { importCaseModel } from './cmmnImport'

const parse = (xml: string) => { const doc = new DOMParser().parseFromString(xml, 'application/xml'); expect(doc.querySelector('parsererror')).toBeNull(); return doc }
const ids = (doc: Document, tag: string) => [...doc.getElementsByTagNameNS('*', tag)].map(element => element.getAttribute('id')).sort()

describe('WM04 case model generation', () => {
  const workflow: CaseModelItem = { id: 'purchase', kind: 'processTask', label: 'Run purchase', required: true, workflow: { key: 'purchase-flow', input: { title: 'title', amount: 'amount' }, result: { decision: 'outcome' } } }
  it('generates a standard process task that names the workflow by a namespaced QName and returns its mappings', () => {
    const xml = caseModelXml('wf-case', 'Workflow case', [workflow, { id: 'follow', kind: 'humanTask', label: 'Follow up', group: 'managers', after: 'purchase' }])
    const doc = parse(xml)
    expect(xml).toContain('xmlns:orqixwf="urn:orqix:workflow"')
    expect(xml).toContain('<processTask id="node_purchase" name="Run purchase" processRef="node_purchase_process" isBlocking="true"/>')
    const process = doc.getElementsByTagNameNS('http://www.omg.org/spec/CMMN/20151109/MODEL', 'process')[0]!
    expect(process.getAttribute('externalRef')).toBe('orqixwf:purchase-flow')
    expect(process.lookupNamespaceURI('orqixwf')).toBe('urn:orqix:workflow')
    expect(caseModelWorkflowTasks([workflow])).toEqual([{ taskId: 'node_purchase', inputMapping: { title: 'title', amount: 'amount' }, resultMapping: { decision: 'outcome' } }])
    expect(() => caseModelXml('wf-case', 'Workflow case', [{ ...workflow, workflow: { key: '', input: {}, result: {} } }])).toThrow('Choose the workflow')
    expect(() => caseModelXml('wf-case', 'Workflow case', [{ ...workflow, manual: true }])).toThrow('start automatically')
  })
  it('puts discretionary items in the stage planning table with authorized planner roles', () => {
    const xml = caseModelXml('plan-case', 'Plan case', [
      { id: 'intake', kind: 'humanTask', label: 'Intake', group: 'managers' },
      { id: 'expert', kind: 'humanTask', label: 'Expert opinion', group: 'finance-approvers', required: true, discretionary: true, planners: ['managers'] },
    ])
    expect(xml).toContain('<planningTable id="casePlan_planning"><discretionaryItem id="node_expert_item" name="Expert opinion" definitionRef="node_expert" authorizedRoleRefs="case_role_managers"><itemControl><requiredRule>')
    expect(xml).toContain('<caseRoles><role id="case_role_managers" name="managers"/></caseRoles>')
    expect(xml.indexOf('<planningTable')).toBeLessThan(xml.indexOf('<humanTask'))
    for (const invalid of [
      { id: 'expert', kind: 'humanTask' as const, label: 'Expert', group: 'managers', discretionary: true, planners: [] },
      { id: 'expert', kind: 'milestone' as const, label: 'Expert', discretionary: true, planners: ['managers'] },
      { id: 'expert', kind: 'humanTask' as const, label: 'Expert', group: 'managers', discretionary: true, planners: ['managers'], repetitions: 2 },
    ]) expect(() => caseModelXml('plan-case', 'Plan case', [{ id: 'intake', kind: 'humanTask', label: 'Intake', group: 'managers' }, invalid])).toThrow()
    expect(() => caseModelXml('plan-case', 'Plan case', [{ id: 'expert', kind: 'humanTask', label: 'Expert', group: 'managers', discretionary: true, planners: ['managers'] }])).toThrow('available when the case starts')
  })
  it('generates general repetition, joins of several sources and exit rules, refusing cycles', () => {
    const items: CaseModelItem[] = [
      { id: 'a', kind: 'humanTask', label: 'A', group: 'managers' },
      { id: 'b', kind: 'humanTask', label: 'B', group: 'managers' },
      { id: 'join', kind: 'humanTask', label: 'Join', group: 'managers', after: 'a', afterAll: ['b'], repetitions: 3 },
      { id: 'seen', kind: 'milestone', label: 'Seen', after: 'join', repetitions: 3 },
      { id: 'round', kind: 'stage', label: 'Round', repetitions: 2, children: [{ id: 'inner', kind: 'humanTask', label: 'Inner', group: 'managers' }] },
      { id: 'optional', kind: 'humanTask', label: 'Optional', group: 'managers', exitAfter: 'a' },
    ]
    const xml = caseModelXml('loop-case', 'Loop case', items)
    const doc = parse(xml)
    const joinSentry = [...doc.getElementsByTagNameNS('*', 'sentry')].find(sentry => sentry.getAttribute('id') === 'node_join_sentry')!
    expect([...joinSentry.children].map(child => child.getAttribute('sourceRef'))).toEqual(['node_a_item', 'node_b_item'])
    expect(xml).toContain('<exitCriterion id="node_optional_exit" sentryRef="node_optional_exit_sentry"/>')
    expect(xml).toContain('<planItem id="node_round_item" name="Round" definitionRef="node_round"><itemControl><repetitionRule><condition>${repetitionCounter &lt; 2}</condition></repetitionRule></itemControl></planItem>')
    expect(() => caseModelXml('loop-case', 'Loop case', [{ ...items[0]!, after: 'b' }, { ...items[1]!, after: 'a' }, { id: 'c', kind: 'humanTask', label: 'C', group: 'managers' }])).toThrow('cycle')
    expect(() => caseModelXml('loop-case', 'Loop case', [items[0]!, { id: 'm', kind: 'milestone', label: 'M', repetitions: 2 }])).toThrow('repeated milestone')
    expect(() => caseModelXml('loop-case', 'Loop case', [items[0]!, { ...items[5]!, repetitions: 2 }])).toThrow('exit rule')
  })
  it('emits CMMN 1.1 diagram interchange for every shape and connector', () => {
    const xml = caseModelXml('di-case', 'Diagram case', [
      { id: 'a', kind: 'humanTask', label: 'A', group: 'managers', layout: { x: 100, y: 100, w: 180, h: 84 } },
      { id: 'b', kind: 'milestone', label: 'B', after: 'a' },
    ], { diagram: true })
    const doc = parse(xml)
    expect(ids(doc, 'CMMNShape')).toEqual(['casePlan_di', 'node_a_item_di', 'node_b_entry_di', 'node_b_item_di'])
    const edge = doc.getElementsByTagNameNS('*', 'CMMNEdge')[0]!
    expect(edge.getAttribute('sourceCMMNElementRef')).toBe('node_a_item')
    expect(edge.getAttribute('targetCMMNElementRef')).toBe('node_b_entry')
    expect(edge.getAttribute('cmmnElementRef')).toBe('node_b_sentry_on1')
    expect(doc.querySelector('[id="node_a_item_di"] > *')!.getAttribute('x')).toBe('100')
    expect(doc.getElementsByTagNameNS('*', 'CMMNLabel').length).toBe(5)
  })
})

describe('CMMN import', () => {
  it('round-trips the planning fixture keeping model IDs, roles and diagram positions', () => {
    const imported = importCaseModel(discretionaryFixture)
    expect(imported.key).toBe('planCase')
    expect(imported.diagnostics.filter(d => d.severity === 'error')).toEqual([])
    const expert = imported.items.find(item => item.id === 'expert')!
    expect(expert).toMatchObject({ discretionary: true, planners: ['managers'], required: true, group: 'finance-approvers', layout: { x: 60, y: 220, w: 160, h: 80 } })
    const xml = caseModelXml('plan-case', imported.name, imported.items, imported.options)
    const original = parse(discretionaryFixture), again = parse(xml)
    for (const tag of ['planItem', 'humanTask', 'milestone', 'discretionaryItem', 'entryCriterion', 'sentry', 'role', 'casePlanModel', 'CMMNShape'].filter(tag => tag !== 'CMMNShape'))
      expect(ids(again, tag)).toEqual(ids(original, tag))
  })
  it('imports workflow tasks, nested stages and repetition and asks only for the workflow mappings', () => {
    const workflow = importCaseModel(workflowFixture.replace('WORKFLOWKEY', 'purchase-flow'))
    const task = workflow.items.find(item => item.kind === 'processTask')!
    expect(task.workflow).toEqual({ key: 'purchase-flow', input: {}, result: {} })
    expect(workflow.diagnostics.map(d => d.message)).toContain('Map case data to the workflow request and its result before publishing.')
    const nested = importCaseModel(nestedFixture)
    expect(nested.diagnostics.filter(d => d.severity === 'error')).toEqual([])
    const regenerated = parse(caseModelXml('nested-case', nested.name, nested.items, nested.options))
    for (const tag of ['planItem', 'stage', 'humanTask', 'milestone', 'entryCriterion', 'exitCriterion']) expect(ids(regenerated, tag)).toEqual(ids(parse(nestedFixture), tag))
    expect(nested.diagnostics.map(d => d.message).join(' ')).toContain('shared by several criteria')
    const loop = importCaseModel(repetitionFixture)
    expect(loop.items.find(item => item.id === 'round')?.repetitions).toBe(2)
    expect(loop.items.find(item => item.id === 'checked')).toMatchObject({ kind: 'milestone', repetitions: 3, after: 'check' })
  })
  it('reports unsupported content with the path of each element and leaves it out', () => {
    const source = `<?xml version="1.0"?><definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL" xmlns:flowable="http://flowable.org/cmmn">
      <case id="foreign" name="Foreign"><caseFileModel id="files"/><casePlanModel id="plan" name="Foreign">
        <planItem id="t1" definitionRef="task1"/><planItem id="t2" definitionRef="task2"><entryCriterion id="e1" sentryRef="s1"/><entryCriterion id="e2" sentryRef="s2"/></planItem>
        <planItem id="timer" definitionRef="timerDef"/>
        <sentry id="s1"><planItemOnPart sourceRef="t1"><standardEvent>complete</standardEvent></planItemOnPart><ifPart><condition>\${x}</condition></ifPart></sentry>
        <sentry id="s2"><planItemOnPart sourceRef="timer"><standardEvent>occur</standardEvent></planItemOnPart></sentry>
        <humanTask id="task1" flowable:candidateGroups="managers,finance"/><humanTask id="task2" flowable:candidateGroups="managers"/><timerEventListener id="timerDef"/>
      </casePlanModel></case></definitions>`
    const imported = importCaseModel(source)
    expect(imported.items.map(item => item.id)).toEqual(['t1', 't2'])
    const messages = imported.diagnostics.map(d => `${d.severity} ${d.path}: ${d.message}`)
    expect(messages.some(m => m.startsWith('error case[foreign]/caseFileModel[files]'))).toBe(true)
    expect(messages.some(m => m.includes('timerEventListener[timerDef]') && m.includes('not supported'))).toBe(true)
    expect(messages.some(m => m.includes('planItem[t2]/entryCriterion[e2]'))).toBe(true)
    expect(messages.some(m => m.includes('ifPart'))).toBe(true)
    expect(messages.some(m => m.startsWith('warning') && m.includes('Several candidate groups'))).toBe(true)
    expect(() => importCaseModel('<definitions xmlns="urn:other"/>')).toThrow('not a CMMN 1.1 file')
  })
})

describe('case canvas operations', () => {
  const base: CaseModelItem[] = [
    { id: 'a', kind: 'humanTask', label: 'A', group: 'managers', layout: { x: 40, y: 60, w: 180, h: 84 } },
    { id: 'b', kind: 'humanTask', label: 'B', group: 'managers', layout: { x: 300, y: 60, w: 180, h: 84 } },
    { id: 'event', kind: 'userEventListener', label: 'Go', group: 'managers', layout: { x: 600, y: 60, w: 150, h: 96 } },
    { id: 'phase', kind: 'stage', label: 'Phase', layout: { x: 40, y: 300, w: 420, h: 240 }, children: [{ id: 'inner', kind: 'humanTask', label: 'Inner', group: 'managers', layout: { x: 80, y: 360, w: 180, h: 84 } }] },
  ]
  it('connects entry and exit rules, joins sources and removes them again', () => {
    let items = connect(base, 'a', 'b', 'entry')
    expect(items[1]).toMatchObject({ after: 'a' })
    items = connect(items, 'event', 'b', 'entry')
    expect(items[1]).toMatchObject({ after: 'a', afterAll: ['event'] })
    expect(connectionProblem(items, 'b', 'a', 'entry')).toBe('That rule would form a cycle.')
    expect(connectionProblem(items, 'a', 'inner', 'entry')).toContain('same stage')
    expect(connectionProblem(items, 'a', 'event', 'entry')).toContain('user event')
    items = connect(items, 'event', 'a', 'exit')
    expect(items[0]).toMatchObject({ exitAfter: 'event' })
    items = disconnect(disconnect(items, 'a', 'b', 'entry'), 'event', 'a', 'exit')
    expect(items[1]).toMatchObject({ after: 'event' })
    expect(items[0]!.exitAfter).toBeUndefined()
  })
  it('nests by dropping onto a stage and drops rules that would cross stages', () => {
    expect(stageAt(base, 100, 400, 'a')).toBe('phase')
    expect(stageAt(base, 100, 100, 'a')).toBeNull()
    const linked = connect(base, 'a', 'b', 'entry')
    const moved = reparent(linked, 'b', 'phase')
    expect(moved.map(item => item.id)).toEqual(['a', 'event', 'phase'])
    expect(moved[2]!.children!.map(item => item.id)).toEqual(['inner', 'b'])
    expect(moved[2]!.children![1]!.after).toBeUndefined()
    expect(() => reparent(base, 'phase', 'phase')).toThrow('inside itself')
  })
})
