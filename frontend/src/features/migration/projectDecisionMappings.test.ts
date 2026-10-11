import { expect, it } from 'vitest'
import type { components } from '@/api/schema'
import { projectDecisionMappings } from './projectDecisionMappings'
type File = components['schemas']['MigrationProject']['files'][number]
const file = (extras: Partial<File>): File => ({ fileName: 'parent.bpmn', migrationId: 'parent', processId: 'parent', dependencies: [], status: 'analyzed', convertedWorkflowId: null, ...extras })
it('suggests explicit per-task mappings only for an unambiguous uploaded project decision', () => {
  const parent = file({ decisionDependencies: { task: 'risk', another: 'risk', unknown: 'missing' } })
  const dmn = file({ fileName: 'risk.dmn', sourceFormat: 'dmn', migrationId: 'dmn-job', decisionIds: ['risk'] })
  expect(projectDecisionMappings(parent, [parent, dmn])).toEqual({ task: { migrationId: 'dmn-job', decisionId: 'risk' }, another: { migrationId: 'dmn-job', decisionId: 'risk' } })
})
it('does not pick a duplicate, blocked or non-DMN file', () => {
  const parent = file({ decisionDependencies: { task: 'risk' } })
  const dmn = file({ sourceFormat: 'dmn', migrationId: 'dmn-job', decisionIds: ['risk'] })
  expect(projectDecisionMappings(parent, [dmn, { ...dmn, migrationId: 'twin' }])).toEqual({})
  expect(projectDecisionMappings(parent, [{ ...dmn, migrationId: null }, { ...dmn, sourceFormat: 'bpmn' }])).toEqual({})
})
it('reads legacy project records without optional decision metadata', () => {
  expect(projectDecisionMappings(file({}), [file({})])).toEqual({})
})
