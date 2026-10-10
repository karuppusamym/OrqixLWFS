import { expect, it } from 'vitest'
import type { components } from '@/api/schema'
import { reportCsv } from './reportExport'
it('exports every finding with provenance and protects spreadsheet formulas and embedded CSV syntax', () => {
  const report: components['schemas']['CompatibilityReport'] = { sourceSystem: 'camunda7', adapterVersion: 'test', sourceHash: 'a'.repeat(64), elementCounts: { total: 2 }, assumptions: [], publishable: false,
    issues: [ { sourceElementId: 'one', sourceName: '=HYPERLINK("unsafe")', sourceKind: 'task', severity: 'blocker', reasonCode: 'BLOCKED', resolutionStatus: 'open', explanation: 'First, "quoted"\nsecond line' },
      { sourceElementId: 'two', sourceName: 'ordinary name', sourceKind: 'task', severity: 'info', reasonCode: 'SUPPORTED', resolutionStatus: 'resolved', explanation: 'Supported.' } ] }
  const csv = reportCsv(report)
  expect(csv).toContain('"\'=HYPERLINK(""unsafe"")"')
  expect(csv).toContain('"First, ""quoted""\nsecond line"')
  expect(csv).toContain('"two","ordinary name","task","info","SUPPORTED","resolved"')
  expect(csv.match(new RegExp(report.sourceHash, 'g'))).toHaveLength(2)
})
