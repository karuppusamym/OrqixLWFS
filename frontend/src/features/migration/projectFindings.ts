import type { components } from '@/api/schema'
import { cell } from './reportExport'

export type Project = components['schemas']['MigrationProject']
export type Job = components['schemas']['MigrationJob']
type Issue = components['schemas']['CompatibilityIssue']
export type Finding = Issue & { fileName: string; migrationId: string | null; sourceHash: string | null; sourceSystem: string; adapterVersion: string }

export function projectFindings(project: Project, jobs: Job[]): Finding[] {
  const byId = new Map(jobs.map(job => [job.id, job]))
  return [
    ...project.issues.map(issue => ({ fileName: issue.fileName, migrationId: null, sourceHash: null, sourceSystem: 'project', adapterVersion: '', sourceElementId: issue.fileName, sourceKind: 'projectDependency', severity: 'blocker' as const, reasonCode: issue.code, resolutionStatus: 'open' as const, explanation: issue.detail })),
    ...project.files.flatMap(file => {
      const job = file.migrationId ? byId.get(file.migrationId) : undefined
      return job?.report.issues.map(issue => ({ ...issue, fileName: file.fileName, migrationId: job.id, sourceHash: job.report.sourceHash, sourceSystem: job.report.sourceSystem, adapterVersion: job.report.adapterVersion })) ?? []
    }),
  ]
}

export function groupFindings(findings: Finding[], by: 'reasonCode' | 'sourceKind' | 'fileName') {
  const groups = new Map<string, Finding[]>()
  for (const finding of findings) groups.set(finding[by], [...(groups.get(finding[by]) ?? []), finding])
  return [...groups.entries()].sort(([a], [b]) => a.localeCompare(b))
}

/** Export report evidence only, excluding source bytes, source maps and connection mappings. */
export function projectEvidence(project: Project, jobs: Job[]) {
  const byId = new Map(jobs.map(job => [job.id, job]))
  const files = project.files.map(file => {
    const job = file.migrationId ? byId.get(file.migrationId) : undefined
    if (file.migrationId && !job) throw new Error(`Report missing for ${file.fileName}`)
    return { ...file, report: job?.report ?? null }
  })
  return { format: 'orqix-project-findings-1', sourceValidation: 'adapterAnalysisOnly', project: { ...project, files }, findings: projectFindings(project, jobs) }
}

export function projectFindingsCsv(project: Project, jobs: Job[]): string {
  const evidence = projectEvidence(project, jobs)
  const rows: unknown[][] = [['projectId', 'fileName', 'migrationId', 'sourceSystem', 'sourceHash', 'adapterVersion', 'elementId', 'name', 'kind', 'severity', 'reasonCode', 'resolutionStatus', 'sourcePath', 'targetNodeId', 'explanation', 'suggestedAction']]
  for (const f of evidence.findings) rows.push([project.id, f.fileName, f.migrationId, f.sourceSystem, f.sourceHash, f.adapterVersion, f.sourceElementId, f.sourceName, f.sourceKind, f.severity, f.reasonCode, f.resolutionStatus, f.sourcePath, f.targetNodeId, f.explanation, f.suggestedAction])
  // Preserve provenance for reports without findings and files which could not be analyzed.
  for (const file of evidence.project.files) if (!evidence.findings.some(f => f.fileName === file.fileName && f.sourceSystem !== 'project')) rows.push([project.id, file.fileName, file.migrationId, file.report?.sourceSystem, file.report?.sourceHash, file.report?.adapterVersion, '', '', '', '', 'FILE_PROVENANCE', '', '', '', '', ''])
  return rows.map(row => row.map(cell).join(',')).join('\r\n') + '\r\n'
}
