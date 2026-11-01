import type { components } from '@/api/schema'

type CaseStatus = components['schemas']['CaseInstance']['status']
type Definition = components['schemas']['CaseDefinition']

/** Business wording and badge tone for a case status; the API value stays the source of truth. */
export const CASE_STATUS: Record<CaseStatus, { label: string; tone: 'info' | 'warning' | 'success' | 'danger' }> = {
  active: { label: 'Active', tone: 'info' },
  suspended: { label: 'On hold', tone: 'warning' },
  completed: { label: 'Completed', tone: 'success' },
  terminated: { label: 'Terminated', tone: 'danger' },
}

/** Short display form of a case id for lists and headings; the full id stays available as a tooltip. */
export const shortCaseId = (id: string) => id.replace(/-/g, '').slice(-6).toUpperCase()

/** Name of the case type a case was started from, when the caller may read case types. */
export function caseTypeName(definitions: Definition[] | undefined, definitionId: string): string | undefined {
  return definitions?.find((definition) => definition.id === definitionId)?.name
}
