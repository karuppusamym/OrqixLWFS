import { beforeEach, describe, expect, it } from 'vitest'
import { seedDb } from './seed'
import { MockSourceError, analyzeBpmn, type ProjectSource, approveReview, convertMigration, createMigration, migrationPublishDenial, nextInProject, planProject, projectView, reviewView } from './migration'
import { bpmn } from './migration.fixtures'
import { WS_PURCHASING as WS, clock, db, userByName } from './state'

const user = userByName
const source = (fileName: string, content: string): ProjectSource => ({ fileName, content })
const dmn = (id = 'risk') => `<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="rules"><decision id="${id}"><decisionTable hitPolicy="FIRST"><input id="i"><inputExpression typeRef="number"><text>amount</text></inputExpression></input><output id="o" name="route" typeRef="string"/><rule id="r"><inputEntry><text>&gt; 10</text></inputEntry><outputEntry><text>"review"</text></outputEntry></rule></decisionTable></decision></definitions>`
const withDecision = (decision = 'risk') => bpmn('root').replace('<endEvent', `<businessRuleTask id="rule" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" camunda:decisionRef="${decision}"/><endEvent`)

function convert(fileName: string, xml: string, key: string, mapper = 'dana.designer', extra: Record<string, unknown> = {}) {
  const { migration } = createMigration(WS, fileName, xml)
  const outcome = convertMigration(migration, user(mapper), { workflowKey: key, name: key, ...extra })
  if (!outcome.ok) throw new Error(outcome.detail)
  return outcome.workflow
}
const require = (required = true) => db.migrationPolicies.set(WS, { required, version: 1, reason: 'Four eyes', updatedAt: clock.iso() })

beforeEach(() => seedDb())

describe('mock BPMN analysis', () => {
  it('reports called processes, group assignments and unsupported elements without trusting the source', () => {
    const xml = `<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn"><process id="p" name="Process"><startEvent id="s"/>
      <userTask id="u" camunda:candidateGroups="managers, finance"/><callActivity id="c" calledElement="child"/><scriptTask id="x"/><endEvent id="e"/></process></definitions>`
    const { report, processId, dependencies } = analyzeBpmn(xml, 'a'.repeat(64))
    expect(processId).toBe('p')
    expect(dependencies).toEqual(['child'])
    expect(report.requiredMappings).toMatchObject({ groups: ['managers', 'finance'], childWorkflows: ['c'] })
    expect(report.issues.map((i) => i.reasonCode).sort()).toEqual(['CHILD_WORKFLOW_MAPPING_REQUIRED', 'ELEMENT_NOT_SUPPORTED', 'GROUP_MAPPING_REQUIRED'])
    expect(report.publishable).toBe(false)
  })
  it('rejects DOCTYPE declarations, malformed XML and sources without a process', () => {
    expect(() => analyzeBpmn('<!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><x>&e;</x>', 'h')).toThrow(MockSourceError)
    expect(() => analyzeBpmn('not xml', 'h')).toThrow(MockSourceError)
    expect(() => analyzeBpmn('<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"/>', 'h')).toThrow(MockSourceError)
  })
})

describe('mock projects', () => {
  it('orders children before parents whatever the upload order', () => {
    const project = planProject(WS, 'P', [source('root.bpmn', bpmn('root', 'mid')), source('mid.bpmn', bpmn('mid', 'leaf')), source('leaf.bpmn', bpmn('leaf'))])
    expect(project.issues).toEqual([])
    const names = project.conversionOrder.map((id) => db.migrations.get(id)!.fileName)
    expect(names).toEqual(['leaf.bpmn', 'mid.bpmn', 'root.bpmn'])
    expect(projectView(project).status).toBe('analyzed')
    expect(nextInProject(project)).toBe(project.conversionOrder[0])
  })
  it('blocks missing, ambiguous, cyclic, dynamic and unsupported-format dependencies with no order', () => {
    const codes = (sources: ProjectSource[]) => planProject(WS, 'P', sources).issues.map((i) => i.code)
    expect(codes([source('a.bpmn', bpmn('a', 'gone'))])).toEqual(['PROJECT_MISSING_DEPENDENCY'])
    expect(codes([source('a.bpmn', bpmn('same')), source('b.bpmn', bpmn('same'))])).toEqual(['PROJECT_DUPLICATE_PROCESS'])
    expect(codes([source('a.bpmn', bpmn('a', 'b')), source('b.bpmn', bpmn('b', 'a'))])).toContain('PROJECT_DEPENDENCY_CYCLE')
    expect(codes([source('a.bpmn', bpmn('a', 'a'))])).toContain('PROJECT_DEPENDENCY_CYCLE')
    expect(codes([source('a.bpmn', bpmn('a', '${child}'))])).toContain('PROJECT_DYNAMIC_CHILD_REFERENCE')
    expect(codes([{ fileName: 'rules.form', content: '{}', sourceFormat: 'form' }])).toEqual(['PROJECT_FORMAT_NOT_SUPPORTED'])
    const blocked = planProject(WS, 'P', [source('a.bpmn', bpmn('a', 'gone'))])
    expect(blocked.conversionOrder).toEqual([])
    expect(projectView(blocked).status).toBe('blocked')
  })
  it('detects XML formats and orders referenced DMN before BPMN, preserving reviewed mapping checks', () => {
    const project = planProject(WS, 'Rules', [source('process.xml', withDecision()), source('rules.xml', dmn())])
    expect(project.issues).toEqual([])
    expect(project.conversionOrder.map(id => db.migrations.get(id)!.fileName)).toEqual(['rules.xml', 'process.xml'])
    expect(project.files[0]).toMatchObject({ sourceFormat: 'bpmn', decisionDependencies: { rule: 'risk' } })
    expect(project.files[1]).toMatchObject({ sourceFormat: 'dmn', decisionIds: ['risk'] })
    expect(db.migrations.get(project.files[1]!.migrationId!)!.sourceHash).toMatch(/^[0-9a-f]{64}$/)
    const parent = db.migrations.get(project.files[0]!.migrationId!)!
    expect(convertMigration(parent, user('dana.designer'), { workflowKey: 'rules-parent', name: 'Rules' })).toMatchObject({ ok: false, code: 'MIGRATION_BLOCKED' })
    expect(convertMigration(parent, user('dana.designer'), { workflowKey: 'rules-parent', name: 'Rules', decisionMappings: { rule: { migrationId: project.files[1]!.migrationId! } } })).toMatchObject({ ok: true })
  })
  it('blocks missing and duplicate decisions and multiple decisions in one file without a conversion order', () => {
    const missing = planProject(WS, 'Missing', [source('parent.bpmn', withDecision())])
    expect(missing.issues.map(i => i.code)).toContain('PROJECT_MISSING_DECISION')
    expect(missing.conversionOrder).toEqual([])
    const duplicate = planProject(WS, 'Duplicates', [source('a.dmn', dmn()), source('b.dmn', dmn())])
    expect(duplicate.issues.map(i => i.code)).toContain('PROJECT_DUPLICATE_DECISION')
    const multiple = dmn().replace('</definitions>', '<decision id="other"/></definitions>')
    expect(planProject(WS, 'Multiple', [source('a.dmn', multiple)]).issues.map(i => i.code)).toContain('PROJECT_DMN_SELECTION_REQUIRED')
  })
  it('stores nothing when one file is rejected', () => {
    const before = db.migrations.size
    expect(() => planProject(WS, 'P', [source('ok.bpmn', bpmn('ok')), source('bad.bpmn', 'not xml')])).toThrow(MockSourceError)
    expect(() => planProject(WS, 'P', [source('a.bpmn', bpmn('a')), source('a.bpmn', bpmn('b'))])).toThrow(MockSourceError)
    expect(db.migrations.size).toBe(before)
  })
  it('converts idempotently for identical mappings and rejects changed ones', () => {
    const { migration } = createMigration(WS, 'a.bpmn', bpmn('a'))
    const request = { workflowKey: 'a-flow', name: 'A' }
    const first = convertMigration(migration, user('dana.designer'), request)
    const again = convertMigration(migration, user('dana.designer'), request)
    expect(first.ok && again.ok && first.workflow.id === again.workflow.id && again.replay).toBe(true)
    expect(convertMigration(migration, user('dana.designer'), { ...request, name: 'B' })).toMatchObject({ ok: false, status: 409 })
    expect(convertMigration(createMigration(WS, 'b.bpmn', bpmn('b')).migration, user('dana.designer'), { workflowKey: 'Bad Key', name: 'B' })).toMatchObject({ ok: false, status: 400 })
  })
  it('refuses to convert a parent before its child is published', () => {
    convert('leaf.bpmn', bpmn('leaf'), 'leaf')
    const { migration } = createMigration(WS, 'root.bpmn', bpmn('root', 'leaf'))
    const mappings = { workflowKey: 'root', name: 'Root', childWorkflowMappings: { call1: { workflowRef: 'leaf', inputMapping: {}, resultMapping: {} } } }
    expect(convertMigration(migration, user('dana.designer'), mappings)).toMatchObject({ ok: false, status: 422, code: 'MIGRATION_BLOCKED' })
    ;[...db.workflows.values()].find((w) => w.key === 'leaf')!.activeReleaseId = 'release'
    expect(convertMigration(migration, user('dana.designer'), mappings)).toMatchObject({ ok: true })
  })
})

describe('mock independent review', () => {
  it('lets only a third person publish: mapper, reviewer and draft author are excluded', () => {
    const wf = convert('a.bpmn', bpmn('a'), 'gov')
    require()
    expect(approveReview(wf, user('dana.designer'), 1, 0, 'mine')).toMatchObject({ status: 403 })
    expect(migrationPublishDenial(wf, user('pia.publisher'), 1)).toMatchObject({ status: 422, code: 'MIGRATION_BLOCKED' })
    expect(approveReview(wf, user('paul.publisher'), 1, 5, 'x')).toMatchObject({ status: 409 })
    expect(approveReview(wf, user('paul.publisher'), 2, 0, 'x')).toMatchObject({ status: 409 })
    expect(approveReview(wf, user('paul.publisher'), 1, 0, 'checked')).toBeNull()
    expect(reviewView(wf, user('pia.publisher'))).toMatchObject({ approved: true, reviewVersion: 1, canApprove: true })
    expect(approveReview(wf, user('paul.publisher'), 1, 0, 'again')).toMatchObject({ status: 409 })
    expect(migrationPublishDenial(wf, user('paul.publisher'), 1)).toMatchObject({ status: 403 })
    expect(migrationPublishDenial(wf, user('dana.designer'), 1)).toMatchObject({ status: 403 })
    expect(migrationPublishDenial(wf, user('pia.publisher'), 1)).toBeNull()
    expect(migrationPublishDenial(wf, user('pia.publisher'), 7)).toMatchObject({ status: 422 })
  })
  it('does not govern workflows that were not migrated or workspaces without the policy', () => {
    const wf = convert('a.bpmn', bpmn('a'), 'gov')
    expect(migrationPublishDenial(wf, user('pia.publisher'), 1)).toBeNull()
    require()
    expect(migrationPublishDenial([...db.workflows.values()].find((w) => w.key === 'purchase-approval')!, user('pia.publisher'), 1)).toBeNull()
  })
  it('invalidates an approval when the draft, the mappings, the source or the reviewer’s access change', () => {
    const wf = convert('a.bpmn', bpmn('a'), 'gov')
    require()
    expect(approveReview(wf, user('paul.publisher'), 1, 0, 'checked')).toBeNull()
    const approved = () => reviewView(wf, user('pia.publisher')).approved
    expect(approved()).toBe(true)
    const migration = db.migrations.get(wf.migration!.migrationId)!
    const mappingsHash = migration.mappingsHash
    migration.mappingsHash = 'f'.repeat(64)
    expect(approved()).toBe(false)
    migration.mappingsHash = mappingsHash
    expect(approved()).toBe(true)
    const sourceHash = migration.sourceHash
    migration.sourceHash = 'e'.repeat(64)
    expect(approved()).toBe(false)
    migration.sourceHash = sourceHash
    // A reviewer who lost publish permission no longer counts.
    db.migrationReviews[0]!.reviewerId = user('olga.operator').id
    expect(approved()).toBe(false)
    expect(migrationPublishDenial(wf, user('pia.publisher'), 1)).toMatchObject({ status: 422, detail: expect.stringContaining('no longer has publishing access') })
    db.migrationReviews[0]!.reviewerId = user('paul.publisher').id
    // A new draft revision starts without a review, and its author cannot approve it.
    wf.drafts.push({ ...structuredClone(wf.drafts[0]!), revision: 2, savedById: user('paul.publisher').id, savedAt: clock.iso() })
    wf.drafts[1]!.definition.name = 'Changed'
    expect(approved()).toBe(false)
    expect(approveReview(wf, user('paul.publisher'), 2, 0, 'mine')).toMatchObject({ status: 403 })
    expect(approveReview(wf, user('pia.publisher'), 2, 0, 'ok')).toBeNull()
    expect(migrationPublishDenial(wf, user('pia.publisher'), 2)).toMatchObject({ status: 403 }) // she reviewed it
    expect(migrationPublishDenial(wf, user('wade.admin'), 2)).toBeNull()
  })
  it('cannot approve a historical conversion with an unknown mapper', () => {
    const wf = convert('a.bpmn', bpmn('a'), 'gov')
    require()
    wf.migration!.mapperId = null
    expect(approveReview(wf, user('paul.publisher'), 1, 0, 'x')).toMatchObject({ status: 422, code: 'MIGRATION_BLOCKED' })
    expect(reviewView(wf, user('paul.publisher'))).toMatchObject({ mapper: null, canApprove: false, approved: false })
    expect(migrationPublishDenial(wf, user('pia.publisher'), 1)).toMatchObject({ status: 422 })
  })
})
