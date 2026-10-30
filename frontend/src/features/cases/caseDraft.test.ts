import { describe, expect, it } from 'vitest'
import manualFixture from '../../../../backend/app/src/test/resources/cases/manual-required.cmmn?raw'
import workflowFixture from '../../../../backend/app/src/test/resources/cases/workflow-task.cmmn?raw'
import { draftFromDefinition, openDraft, type CaseDraft } from './caseDraft'
import { caseItems } from './caseModel'

const draft = (patch: Partial<CaseDraft>): CaseDraft => ({ id: 'd1', key: 'workflowCase', name: 'Purchase case', version: 1, sourceXml: null, designerModel: null, dataFields: [], viewRef: null,
  workflowTasks: [], caseTasks: [], decisionTasks: [], updatedAt: '2026-10-30T12:00:00Z', publishedVersion: null, publishedDraftVersion: null, ...patch })

describe('case type drafts (S126)', () => {
  it('reopens a saved designer model as it was', () => {
    const items = [{ id: 'task1', kind: 'humanTask' as const, label: 'Review', group: 'managers' }]
    expect(openDraft(draft({ designerModel: { items, options: { diagram: true }, diagram: true } }))).toEqual({ mode: 'designer', model: { items, options: { diagram: true }, diagram: true } })
    expect(openDraft(draft({}))).toEqual({ mode: 'designer' })
  })

  it('imports a published source into the designer and puts the pinned workflow mappings back on their task', () => {
    const opened = openDraft(draft({ sourceXml: workflowFixture.replace('WORKFLOWKEY', 'purchase-flow'),
      workflowTasks: [{ taskId: 'purchaseTask', inputMapping: { title: 'summary' }, resultMapping: { result: 'outcome' } }] }))
    expect(opened.mode).toBe('designer')
    const task = caseItems(opened.mode === 'designer' ? opened.model!.items : []).find(item => item.kind === 'processTask')
    expect(task?.workflow).toEqual({ key: 'purchase-flow', input: { title: 'summary' }, result: { result: 'outcome' } })
  })

  it('keeps a source the designer cannot represent as an uploaded file, with what would be left out', () => {
    const source = manualFixture.replace('</casePlanModel>', '<planItem id="extra" definitionRef="other"/><blockingThing id="other"/></casePlanModel>')
    const opened = openDraft(draft({ key: 'lifecycleCase', sourceXml: source }))
    expect(opened.mode).toBe('file')
    expect(opened.mode === 'file' && opened.diagnostics.some(diagnostic => diagnostic.severity === 'error')).toBe(true)
  })

  it('turns a published version into the draft that edits it', () => {
    expect(draftFromDefinition({ id: 'v1', key: 'claim', name: 'Claim', version: 3, createdAt: '2026-10-30T12:00:00Z', sourceXml: '<definitions/>',
      viewBinding: { id: 'view', version: 2, definition: {} as never }, workflowTasks: [{ taskId: 't', workflowId: 'w', workflowKey: 'k', workflowName: 'K', releaseId: 'r', releaseNumber: 1, inputMapping: { a: 'b' }, resultMapping: {} }] }))
      .toEqual({ key: 'claim', name: 'Claim', sourceXml: '<definitions/>', designerModel: null, dataFields: [], viewRef: { id: 'view', version: 2 },
        workflowTasks: [{ taskId: 't', inputMapping: { a: 'b' }, resultMapping: {} }], caseTasks: [], decisionTasks: [] })
  })
})
