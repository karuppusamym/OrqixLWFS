import type { components } from '@/api/schema'
type Project = components['schemas']['MigrationProject']
type DecisionMappings = NonNullable<components['schemas']['ConvertMigrationRequest']['decisionMappings']>

/** Suggestions require an unambiguous project decision; the designer still reviews and submits them. */
export function projectDecisionMappings(file: Project['files'][number], files: Project['files']): DecisionMappings {
  const mappings: DecisionMappings = {}
  for (const [taskId, decisionId] of Object.entries(file.decisionDependencies ?? {})) {
    const matches = files.filter(candidate => candidate.sourceFormat === 'dmn' && candidate.migrationId && candidate.decisionIds?.includes(decisionId))
    if (matches.length === 1) mappings[taskId] = { migrationId: matches[0]!.migrationId!, decisionId }
  }
  return mappings
}
