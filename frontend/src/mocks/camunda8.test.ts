import { beforeEach, describe, expect, it } from 'vitest'
import connector from '../../../examples/migration/camunda8-connector-http.bpmn?raw'
import feel from '../../../examples/migration/camunda8-feel-gateways.bpmn?raw'
import formBpmn from '../../../examples/migration/camunda8-user-task-form.bpmn?raw'
import formFile from '../../../examples/migration/camunda8-user-task-form.form?raw'
import timers from '../../../examples/migration/camunda8-timer-callactivity.bpmn?raw'
import unsupported from '../../../examples/migration/camunda8-unsupported.bpmn?raw'
import { analyzeFormText } from './camunda8'
import { MockSourceError, convertMigration, createMigration } from './migration'
import { seedDb } from './seed'
import { WS_PURCHASING as WS, db, userByName } from './state'

beforeEach(() => seedDb())
const codes = (report: { issues: { sourceElementId: string; reasonCode: string }[] }, id: string) => report.issues.filter(i => i.sourceElementId === id).map(i => i.reasonCode)

describe('mock Camunda 8 inventory', () => {
  it('reads the modeler version and the REST connector provenance without copying secrets', () => {
    const { migration } = createMigration(WS, 'connector.bpmn', connector)
    const report = migration.report
    expect(report.sourceSystem).toBe('camunda8')
    expect(report.sourceVersion).toBe('8.5.0')
    expect(report.assumptions[0]).toContain('not a claim of general Camunda 8 compatibility')
    const hint = report.mappingHints?.services?.procure
    expect(hint).toMatchObject({ kind: 'httpJson', method: 'POST', urlPath: '/orders', suggestedOperation: 'createPurchaseOrder' })
    expect(hint?.inputFields).toMatchObject({ title: 'title', amount: 'amount' })
    expect(hint?.resultFields).toEqual({ procurementId: '/id' })
    expect(codes(report, 'procure')).toContain('CONNECTOR_MAPPING_REQUIRED')
    expect(report.requiredMappings?.serviceTasks).toEqual(['procure'])
  })

  it('classifies FEEL, timers, call activities and unsupported constructs with the importer reason codes', () => {
    const f = createMigration(WS, 'feel.bpmn', feel).migration.report
    expect(codes(f, 'gAmount')).toEqual(['GATEWAY_MAPPED_TO_BRANCH'])
    const u = createMigration(WS, 'unsupported.bpmn', unsupported).migration.report
    expect(codes(u, 'gFunction')).toEqual(['EXPRESSION_NOT_SUPPORTED'])
    expect(codes(u, 'gNull')).toEqual(['EXPRESSION_NOT_SUPPORTED'])
    expect(codes(u, 'messageCatch')).toEqual(['MESSAGE_EVENT_NOT_SUPPORTED'])
    expect(codes(u, 'slack')).toEqual(['CONNECTOR_NOT_SUPPORTED'])
    expect(u.mappingHints?.forms?.formTask?.state).toBe('embedded')
    expect(u.issues.map(i => i.reasonCode)).toContain('FORM_COMPONENT_NOT_SUPPORTED')
    const t = createMigration(WS, 'timers.bpmn', timers).migration.report
    expect(codes(t, 'leadTime')).toEqual(['DURATION_TIMER_MAPPED'])
    expect(codes(t, 'reviewLate')).toEqual(['BOUNDARY_TIMER_MAPPED'])
    expect(t.mappingHints?.calls?.child).toMatchObject({ processId: 'reviewed-child', propagateAllChildVariables: false })
  })

  it('imports a form file, matches its id and turns the start form into request fields', () => {
    const form = createMigration(WS, 'camunda8-user-task-form.form', formFile, 'camundaForm').migration
    expect(form.report.sourceSystem).toBe('camundaForm')
    expect(form.report.processId).toBe('expense-request')
    expect(form.report.mappingHints?.formPreviews?.['expense-request']?.fields).toHaveLength(7)
    const { migration } = createMigration(WS, 'expense.bpmn', formBpmn)
    expect(migration.report.mappingHints?.forms?.start).toMatchObject({ state: 'upload', formId: 'expense-request' })
    expect(migration.report.mappingHints?.forms?.managerReview?.state).toBe('embedded')
    const request = { workflowKey: 'expense-import', name: 'Expense', groupMappings: { 'source-managers': 'managers' }, formMappings: { start: { migrationId: form.id } } }
    const outcome = convertMigration(migration, userByName('dana.designer'), request)
    if (!outcome.ok) throw new Error(outcome.detail)
    const definition = outcome.workflow.drafts[0]!.definition
    expect(Object.keys(definition.inputSchema.properties)).toContain('employeeName')
    expect(definition.inputSchema.required).toContain('amount')
  })

  it('refuses a form whose id does not match, or a mapping for a step without a linked form', () => {
    const wrong = createMigration(WS, 'other.form', '{"id":"other","type":"default","components":[{"type":"textfield","id":"a","key":"x","label":"X"}]}', 'camundaForm').migration
    const { migration } = createMigration(WS, 'expense.bpmn', formBpmn)
    const base = { workflowKey: 'expense-import', name: 'Expense', groupMappings: { 'source-managers': 'managers' } }
    const mismatch = convertMigration(migration, userByName('dana.designer'), { ...base, formMappings: { start: { migrationId: wrong.id } } })
    expect(mismatch.ok).toBe(false)
    const unknown = convertMigration(migration, userByName('dana.designer'), { ...base, formMappings: { managerReview: { migrationId: wrong.id } } })
    expect(unknown.ok).toBe(false)
    expect(db.migrations.get(migration.id)?.status).toBe('analyzed')
  })

  it('maps a connector task through serviceTaskMappings and blocks an unmapped one', () => {
    const { migration } = createMigration(WS, 'connector.bpmn', connector)
    const base = { workflowKey: 'c8-connector', name: 'Connector', groupMappings: { 'source-managers': 'managers', 'source-finance': 'finance-approvers' } }
    const blocked = convertMigration(migration, userByName('dana.designer'), base)
    expect(blocked.ok).toBe(false)
    const mapped = convertMigration(migration, userByName('dana.designer'), { ...base, serviceTaskMappings: { procure: { connectionRef: 'procurement', operation: 'createOrder' } } })
    expect(mapped.ok).toBe(true)
  })

  it('rejects unusable form text with a plain message', () => {
    expect(() => analyzeFormText('nope')).toThrow(/not valid JSON/)
    expect(() => analyzeFormText('{"id":"x"}')).toThrow(/components/)
    expect(() => createMigration(WS, 'x.form', '{"id":"x"}', 'camundaForm')).toThrow(MockSourceError)
  })
})
