import type { components } from '@/api/schema'
type Report = components['schemas']['CompatibilityReport']

// Source labels may be untrusted. Quoting handles CSV syntax; an apostrophe prevents spreadsheet formulas.
export function cell(value: unknown): string {
  let text = value == null ? '' : String(value)
  if (/^[\s]*[=+@-]/.test(text) || /^[\t\r\n]/.test(text)) text = `'${text}`
  return `"${text.replace(/"/g, '""')}"`
}
export function reportCsv(report: Report): string {
  const header = ['sourceSystem', 'sourceHash', 'elementId', 'name', 'kind', 'severity', 'reasonCode', 'resolutionStatus', 'sourcePath', 'explanation', 'suggestedAction', 'targetNodeId', 'adapterVersion', 'verifiedBy', 'verifiedAt', 'verificationReason']
  const verified = (i: Report['issues'][number]) => (report.verifications ?? []).filter(v => v.sourceElementId === i.sourceElementId && v.reasonCode === i.reasonCode && (v.sourcePath ?? null) === (i.sourcePath ?? null)).at(-1)
  const rows = report.issues.map(i => { const v = verified(i); return [report.sourceSystem, report.sourceHash, i.sourceElementId, i.sourceName, i.sourceKind, i.severity, i.reasonCode, i.resolutionStatus, i.sourcePath, i.explanation, i.suggestedAction,
    i.targetNodeId, report.adapterVersion, v ? v.acknowledgedByName ?? v.acknowledgedBy : null, v?.acknowledgedAt, v?.reason] })
  return [header, ...rows].map(row => row.map(cell).join(',')).join('\r\n') + '\r\n'
}
