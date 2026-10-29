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
