import { describe, expect, it } from 'vitest'
import type { ViewDefinition } from '@/features/views/model'
import { blankPage, isContextBinding, pinKey, validatePage, visible, withLayout, type ExtensionInfo, type PageDefinition, type PageLookups } from './model'

const request: ViewDefinition = { schemaVersion: 1, kind: 'request', sections: [{ id: 's', label: 'S', columns: 1, fields: [{ id: 'title', label: 'Title', type: 'text', binding: 'request.title', required: true }] }], actions: [{ id: 'submit', label: 'Submit', type: 'submit' }] }
const approval: ViewDefinition = { schemaVersion: 1, kind: 'approval', sections: [{ id: 'a', label: 'A', columns: 1, fields: [{ id: 'title', label: 'Title', type: 'text', binding: 'request.title' }] }], actions: [{ id: 'approve', label: 'Approve', type: 'approve' }] }
const extension: ExtensionInfo = { id: 'ext', version: 1, name: 'Badge', status: 'approved', capabilities: { bindings: ['task.requestTitle'], actions: ['claim', 'approve', 'navigate'] }, height: 120 }

function lookups(status: ExtensionInfo['status'] = 'approved'): PageLookups {
  return { views: new Map([[pinKey({ id: 'req', version: 1 }), request], [pinKey({ id: 'app', version: 2 }), approval]]), extensions: new Map([['ext:1', { ...extension, status }]]) }
}

function taskPage(): PageDefinition {
  return { ...blankPage(), title: 'Decide', context: 'task', regions: [{ id: 'main', label: 'Main', components: [
    { id: 'review', type: 'view', view: { id: 'app', version: 2 } },
    { id: 'badge', type: 'extension', label: 'Badge', extension: { id: 'ext', version: 1 }, bindings: ['task.requestTitle'], chains: ['go'] },
    { id: 'bar', type: 'actions', chains: ['go'] },
  ] }], chains: [{ id: 'go', label: 'Claim and approve', stopOnFailure: true, steps: [{ action: 'claim', component: 'review' }, { action: 'approve', component: 'review' }, { action: 'navigate', target: '/w/x/tasks' }] }] }
}

const caseView: ViewDefinition = { schemaVersion: 1, kind: 'case', sections: [{ id: 'c', label: 'C', columns: 1, fields: [{ id: 'summary', label: 'Summary', type: 'text', binding: 'case.summary' }] }], actions: [{ id: 'save', label: 'Save', type: 'saveData' }] }
const correcting: ViewDefinition = { ...approval, actions: [...approval.actions, { id: 'fix', label: 'Fix', type: 'requestCorrection' }] }

describe('S116 chain rules', () => {
  const taskChain = (steps: PageDefinition['chains'][number]['steps']): PageDefinition => ({ ...taskPage(), regions: [{ id: 'main', label: 'Main', components: [{ id: 'review', type: 'view', view: { id: 'fix', version: 1 } }, { id: 'bar', type: 'actions', chains: ['go'] }] }], chains: [{ id: 'go', label: 'Go', stopOnFailure: true, steps }] })
  const fixLookups: PageLookups = { views: new Map([['fix:1', correcting]]), extensions: new Map() }

  it('accepts a send-back with its own undo, and refuses it next to a decision', () => {
    expect(validatePage(taskChain([{ action: 'claim', component: 'review', compensate: 'releaseTask' }, { action: 'sendBack', component: 'review', compensate: 'withdrawSendBack' }]), fixLookups)).toEqual([])
    expect(validatePage(taskChain([{ action: 'sendBack', component: 'review' }, { action: 'approve', component: 'review' }]), fixLookups).join(' ')).toContain('never with a decision')
  })

  it('allows only a step\'s own inverse as its undo', () => {
    expect(validatePage(taskChain([{ action: 'approve', component: 'review', compensate: 'releaseTask' }]), fixLookups).join(' ')).toContain('cannot be undone')
    expect(validatePage(taskChain([{ action: 'claim', component: 'review', compensate: 'withdrawSendBack' }]), fixLookups).join(' ')).toContain('can only be undone by return the task to its group')
  })

  it('lets only plan-item steps choose an occurrence', () => {
    expect(validatePage(taskChain([{ action: 'claim', component: 'review', occurrence: 'newest' }]), fixLookups).join(' ')).toContain('only plan-item steps')
  })
})

describe('S114 chain steps', () => {
  const casePage = (steps: PageDefinition['chains'][number]['steps']): PageDefinition => ({ ...blankPage(), title: 'Case', context: 'case', regions: [{ id: 'main', label: 'Main', components: [{ id: 'data', type: 'view', view: { id: 'case', version: 1 } }, { id: 'bar', type: 'actions', chains: ['go'] }] }], chains: [{ id: 'go', label: 'Go', stopOnFailure: true, steps }] })
  const caseLookups: PageLookups = { views: new Map([['case:1', caseView], ['fix:1', correcting], ['app:2', approval]]), extensions: new Map() }

  it('accepts case lifecycle and plan-item steps that name a CMMN element', () => {
    expect(validatePage(casePage([{ action: 'startPlanItem', component: 'data', planItem: 'requiredReview' }, { action: 'completePlanItem', component: 'data', planItem: 'requiredReview' }, { action: 'suspendCase', component: 'data' }, { action: 'resumeCase', component: 'data' }, { action: 'closeCase', component: 'data' }]), caseLookups)).toEqual([])
  })

  it('needs a plan item for plan-item steps only, and nothing on the case after closing it', () => {
    expect(validatePage(casePage([{ action: 'planDiscretionaryItem', component: 'data' }]), caseLookups).join(' ')).toContain('CMMN element id')
    expect(validatePage(casePage([{ action: 'suspendCase', component: 'data', planItem: 'x' }]), caseLookups).join(' ')).toContain('does not use a plan item')
    expect(validatePage(casePage([{ action: 'closeCase', component: 'data' }, { action: 'saveCaseData', component: 'data' }]), caseLookups).join(' ')).toContain('no case step can follow closing the case')
  })

  it('places a correction once, before the decision, on a view that declares it', () => {
    const page = (view: string, steps: PageDefinition['chains'][number]['steps']): PageDefinition => ({ ...taskPage(), regions: [{ id: 'main', label: 'Main', components: [{ id: 'review', type: 'view', view: { id: view, version: view === 'fix' ? 1 : 2 } }, { id: 'bar', type: 'actions', chains: ['go'] }] }], chains: [{ id: 'go', label: 'Go', stopOnFailure: true, steps }] })
    expect(validatePage(page('fix', [{ action: 'claim', component: 'review' }, { action: 'requestCorrection', component: 'review' }, { action: 'approve', component: 'review' }]), caseLookups)).toEqual([])
    expect(validatePage(page('fix', [{ action: 'approve', component: 'review' }, { action: 'requestCorrection', component: 'review' }]), caseLookups).join(' ')).toContain('before a decision')
    expect(validatePage(page('app', [{ action: 'requestCorrection', component: 'review' }]), caseLookups).join(' ')).toContain('has no requestCorrection action')
  })

  it('lets request and approval views use file and row controls, but not case views', () => {
    const withFile: ViewDefinition = { ...request, sections: [{ ...request.sections[0]!, fields: [...request.sections[0]!.fields, { id: 'quote', label: 'Quote', type: 'file', binding: 'request.quote' }] }] }
    const page: PageDefinition = { ...blankPage(), regions: [{ id: 'main', label: 'Main', components: [{ id: 'form', type: 'view', view: { id: 'req', version: 1 }, workflowId: 'wf' }] }] }
    expect(validatePage(page, { views: new Map([['req:1', withFile]]), extensions: new Map() })).toEqual([])
  })
})

describe('application page model (S111)', () => {
  it('accepts a compatible task page with a chain, an extension grant and an action bar', () => {
    expect(validatePage(taskPage(), lookups(), true)).toEqual([])
  })

  it('rejects chains that do not stop on failure, navigate early or target an incompatible view', () => {
    const page = taskPage()
    page.chains = [{ id: 'go', label: 'Go', stopOnFailure: false, steps: [{ action: 'navigate', target: '/w/x' }, { action: 'submit', component: 'review' }] }]
    const problems = validatePage(page, lookups())
    expect(problems.some(problem => problem.includes('must stop at the first failed step'))).toBe(true)
    expect(problems.some(problem => problem.includes('navigation must be the last step'))).toBe(true)
    expect(problems.some(problem => problem.includes('needs a request view'))).toBe(true)
  })

  it('limits extension grants to declared capabilities and requires approval to publish', () => {
    const page = taskPage()
    page.regions[0]!.components[1]!.bindings = ['task.requestTitle', 'request.amount']
    page.chains[0]!.steps = [{ action: 'reject', component: 'review' }]
    const problems = validatePage(page, lookups())
    expect(problems.some(problem => problem.includes('request.amount, which the extension does not declare'))).toBe(true)
    expect(problems.some(problem => problem.includes('with reject, which the extension does not declare'))).toBe(true)
    expect(problems.some(problem => problem.includes('has no reject action'))).toBe(true)
    expect(validatePage(taskPage(), lookups('pending'))).toEqual([])
    expect(validatePage(taskPage(), lookups('pending'), true)[0]).toContain('still waiting for approval')
    expect(validatePage(taskPage(), lookups('revoked'))[0]).toContain('revoked')
  })

  it('keeps view kinds in their context and request views bound to a workflow', () => {
    const page: PageDefinition = { ...blankPage(), regions: [{ id: 'main', label: 'Main', components: [{ id: 'review', type: 'view', view: { id: 'app', version: 2 } }, { id: 'form', type: 'view', view: { id: 'req', version: 1 } }] }] }
    const problems = validatePage(page, lookups())
    expect(problems).toContain('Component review: an approval view needs a task page.')
    expect(problems).toContain('Component form needs a workflow to submit the request view to.')
  })

  it('moves components between layouts without losing them and evaluates presentation-only visibility', () => {
    const page = withLayout({ ...blankPage(), layout: 'split', regions: [{ id: 'start', label: 'L', components: [{ id: 'a', type: 'heading', text: 'A' }] }, { id: 'end', label: 'R', components: [{ id: 'b', type: 'heading', text: 'B' }] }] }, 'single')
    expect(page.regions.map(region => region.components.map(component => component.id))).toEqual([['a', 'b']])
    expect(withLayout(page, 'sidebar').regions.map(region => region.id)).toEqual(['main', 'side'])
    expect(visible({ id: 'x', type: 'text', text: 't', visibleWhen: { binding: 'task.status', equals: 'claimed' } }, { 'task.status': 'open' })).toBe(false)
    expect(isContextBinding('task.secret', 'task')).toBe(false)
    expect(isContextBinding('request.amount', 'task')).toBe(true)
    expect(isContextBinding('case.amount', 'task')).toBe(false)
  })
})
