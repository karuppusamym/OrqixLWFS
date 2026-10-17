import { expect, it } from 'vitest'
import { groupFindings, projectEvidence, projectFindings, projectFindingsCsv, type Job, type Project } from './projectFindings'

const project: Project = { id: 'p', name: 'Review', status: 'blocked', createdAt: '2026-01-01T00:00:00Z', conversionOrder: [], files: [{ fileName: '=unsafe.bpmn', migrationId: 'a', processId: 'a', dependencies: [], status: 'analyzed', convertedWorkflowId: null }, { fileName: 'empty.bpmn', migrationId: 'b', processId: 'b', dependencies: [], status: 'analyzed', convertedWorkflowId: null }], issues: [{ fileName: '=unsafe.bpmn', code: 'MISSING', detail: 'Missing dependency' }] }
const jobs: Job[] = ['a', 'b'].map(id => ({ id, fileName: id, status: 'analyzed', createdAt: project.createdAt, sourceMap: { secret: 'credential' }, report: { sourceSystem: 'bpmn', adapterVersion: 'v1', sourceHash: id.repeat(64), assumptions: ['Reviewed assumption'], elementCounts: { total: 1 }, publishable: false, issues: id === 'a' ? [{ sourceElementId: 'task', sourceKind: 'serviceTask', severity: 'action', reasonCode: 'MAP', resolutionStatus: 'open', sourceName: '+unsafe', explanation: 'First, "quoted"\nnext', targetNodeId: 'target' }] : [] } }))

it('aggregates project and file findings without losing identity, and groups by every review dimension', () => {
  const findings = projectFindings(project, jobs)
  expect(findings).toHaveLength(2)
  expect(findings[1]).toMatchObject({ fileName: '=unsafe.bpmn', sourceHash: 'a'.repeat(64), migrationId: 'a', targetNodeId: 'target' })
  expect(groupFindings(findings, 'reasonCode').map(([key]) => key)).toEqual(['MAP', 'MISSING'])
  expect(groupFindings(findings, 'sourceKind').map(([key]) => key)).toEqual(['projectDependency', 'serviceTask'])
  expect(groupFindings(findings, 'fileName')[0]![1]).toHaveLength(2)
})

it('exports complete reports and empty-file provenance while excluding source maps and preventing formula cells', () => {
  const evidence = projectEvidence(project, jobs)
  expect(evidence.project.files[1]!.report?.assumptions).toEqual(['Reviewed assumption'])
  expect(JSON.stringify(evidence)).not.toContain('credential')
  expect(evidence.sourceValidation).toBe('adapterAnalysisOnly')
  const csv = projectFindingsCsv(project, jobs)
  expect(csv).toContain('"\'=unsafe.bpmn"')
  expect(csv).toContain('"\'+unsafe"')
  expect(csv).toContain('"First, ""quoted""\nnext"')
  expect(csv).toContain('b'.repeat(64))
  expect(csv).toContain('FILE_PROVENANCE')
  expect(csv).toContain('"target"')
  expect(projectEvidence(project, [...jobs].reverse())).toEqual(evidence)
})

it('refuses to label partial reports as a complete export', () => {
  expect(() => projectEvidence(project, [jobs[0]!])).toThrow('Report missing for empty.bpmn')
  expect(() => projectFindingsCsv(project, [])).toThrow('Report missing')
})
