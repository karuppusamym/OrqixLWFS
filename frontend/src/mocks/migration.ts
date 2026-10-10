import type { components } from '@/api/schema'
import type { ProblemCode } from '@/api/types'
import { blankDefinition, clock, db, newId, permissionsOf, userById, type MockMigration, type MockMigrationProject, type MockUser, type MockWorkflow } from './state'

/**
 * Mock-mode migration workbench: a small BPMN analyzer, multi-file project planning and the maker-checker rules of
 * docs/17-migration-workbench.md. It follows the backend's behavior (backend/.../migration, catalog/internal/
 * MigrationGovernance) but understands only a deliberately small BPMN subset; it never claims real compatibility.
 */

type Report = components['schemas']['CompatibilityReport']
type Issue = components['schemas']['CompatibilityIssue']

export const MOCK_ADAPTER = 'bpmn-mock-0.1'
export class MockSourceError extends Error {}

/** Deterministic 64-hex digest; stands in for SHA-256 in mock mode only. */
export function digest(text: string): string {
  let a = 0x811c9dc5
  let b = 0x01000193
  let out = ''
  for (let round = 0; round < 8; round++) {
    for (let i = 0; i < text.length; i++) {
      a = Math.imul(a ^ text.charCodeAt(i), 16777619) >>> 0
      b = (Math.imul(b, 31) + a + round) >>> 0
    }
    out += (a ^ b).toString(16).padStart(8, '0')
  }
  return out
}

// Analysis ------------------------------------------------------------------------------------------------------------------

const UNSUPPORTED = new Set(['scriptTask', 'businessRuleTask', 'subProcess', 'boundaryEvent', 'eventBasedGateway', 'inclusiveGateway', 'manualTask', 'sendTask', 'receiveTask'])

export interface Analysis {
  report: Report
  processId: string
  dependencies: string[]
  /** Call activities whose child process is missing or computed at runtime. */
  dynamicCalls: number
}

function issue(kind: string, id: string, severity: Issue['severity'], reasonCode: string, explanation: string, suggestedAction?: string): Issue {
  return { sourceElementId: id, sourceKind: kind, severity, reasonCode, explanation, ...(suggestedAction ? { suggestedAction } : {}), resolutionStatus: severity === 'supported' ? 'resolved' : 'open' } as Issue
}

export function analyzeBpmn(xml: string, sourceHash: string): Analysis {
  if (!xml.trim() || xml.length > 1_048_576) throw new MockSourceError('Source must be between 1 byte and 1 MiB.')
  if (/<!DOCTYPE/i.test(xml)) throw new MockSourceError('DOCTYPE declarations are not allowed in migration sources.')
  const doc = new DOMParser().parseFromString(xml, 'application/xml')
  if (doc.getElementsByTagName('parsererror').length > 0) throw new MockSourceError('The source is not well-formed XML.')
  const process = doc.getElementsByTagNameNS('*', 'process')[0]
  const processId = process?.getAttribute('id')
  if (!process || !processId) throw new MockSourceError('The source has no BPMN process with an id.')
  const issues: Issue[] = []
  const groups = new Set<string>()
  const services: string[] = []
  const children: string[] = []
  const dependencies = new Set<string>()
  let dynamicCalls = 0
  let supported = 0
  for (const element of Array.from(process.getElementsByTagName('*'))) {
    const kind = element.localName
    const id = element.getAttribute('id') ?? kind
    if (kind === 'callActivity') {
      const called = element.getAttribute('calledElement')
      children.push(id)
      if (called && !/[$#]\{|^=/.test(called)) dependencies.add(called)
      else dynamicCalls++
      issues.push(issue(kind, id, 'action', 'CHILD_WORKFLOW_MAPPING_REQUIRED', `Called process '${called ?? ''}' requires a reviewed workspace workflow and explicit input/result mappings.`, 'Import and publish the child first; map its key and fields.'))
    } else if (kind === 'userTask') {
      const candidate = Array.from(element.attributes).find((a) => a.localName === 'candidateGroups')?.value
      for (const g of (candidate ?? '').split(',').map((v) => v.trim()).filter(Boolean)) groups.add(g)
      if (candidate) issues.push(issue(kind, id, 'action', 'GROUP_MAPPING_REQUIRED', `Choose the workspace group that replaces '${candidate}'.`))
      else supported++
    } else if (kind === 'serviceTask') {
      services.push(id)
      issues.push(issue(kind, id, 'action', 'SERVICE_MAPPING_REQUIRED', 'Map this service task to a registered connection operation.'))
    } else if (UNSUPPORTED.has(kind)) {
      issues.push(issue(kind, id, 'blocker', 'ELEMENT_NOT_SUPPORTED', `A ${kind} has no equivalent in workflow v0.1.`, 'Replace it with an approval step or a connection call.'))
    } else if (['startEvent', 'endEvent', 'sequenceFlow', 'exclusiveGateway', 'parallelGateway'].includes(kind)) supported++
  }
  const action = issues.filter((i) => i.severity === 'action').length
  const blocker = issues.filter((i) => i.severity === 'blocker').length
  const report: Report = {
    sourceSystem: 'bpmn',
    adapterVersion: MOCK_ADAPTER,
    sourceHash,
    processId,
    processName: process.getAttribute('name') ?? processId,
    elementCounts: { total: supported + action + blocker, supported, action, blocker, info: 0 },
    issues,
    assumptions: ['Mock analyzer: only start/end events, group approvals and called processes are understood. Review every mapping before publishing.'],
    requiredMappings: { groups: [...groups], approvalTasks: [], serviceTasks: services, childWorkflows: children },
    publishable: false,
  }
  return { report, processId, dependencies: [...dependencies], dynamicCalls }
}

export function createMigration(wsId: string, fileName: string, source: string): { migration: MockMigration; analysis: Analysis } {
  const sourceHash = digest(source)
  const analysis = analyzeBpmn(source, sourceHash)
  const migration: MockMigration = { id: newId(), wsId, fileName, source, sourceHash, report: analysis.report, status: 'analyzed', convertedWorkflowId: null, convertedById: null, mappingsHash: null, mappings: null, createdAt: clock.iso() }
  db.migrations.set(migration.id, migration)
  return { migration, analysis }
}

export function migrationView(m: MockMigration) {
  return { id: m.id, fileName: m.fileName, status: m.status, report: m.report, convertedWorkflowId: m.convertedWorkflowId, createdAt: m.createdAt, sourceMap: m.status === 'converted' ? {} : null }
}

// Projects ---------------------------------------------------------------------------------------------------------------------

export interface ProjectSource { fileName: string; content: string; sourceFormat?: string }
type ProjectIssue = MockMigrationProject['issues'][number]

/** Mirrors ProjectAnalyzer: unique names, literal child references, no duplicate processes, no cycles, children first. */
export function planProject(wsId: string, name: string, sources: ProjectSource[]): MockMigrationProject {
  const names = new Set<string>()
  const issues: ProjectIssue[] = []
  const entries: Array<{ fileName: string; migrationId: string | null; processId: string | null; dependencies: string[] }> = []
  const processes = new Map<string, number>()
  for (const source of sources) {
    if (!source.fileName.trim() || source.fileName.length > 200 || names.has(source.fileName)) throw new MockSourceError('Project filenames must be unique, nonblank and at most 200 characters.')
    names.add(source.fileName)
  }
  const created: string[] = []
  try {
    for (const source of sources) {
      if (source.sourceFormat && source.sourceFormat !== 'bpmn') {
        issues.push({ fileName: source.fileName, code: 'PROJECT_FORMAT_NOT_SUPPORTED', detail: 'This project preserves the source but converts BPMN only; DMN/forms need separate reviewed reconstruction.' })
        entries.push({ fileName: source.fileName, migrationId: null, processId: null, dependencies: [] })
        continue
      }
      const { migration, analysis } = createMigration(wsId, source.fileName, source.content)
      created.push(migration.id)
      if (analysis.dynamicCalls > 0) issues.push({ fileName: source.fileName, code: 'PROJECT_DYNAMIC_CHILD_REFERENCE', detail: 'A child process reference is missing or dynamic; provide a literal dependency before project conversion.' })
      if (processes.has(analysis.processId)) issues.push({ fileName: source.fileName, code: 'PROJECT_DUPLICATE_PROCESS', detail: `Process ID '${analysis.processId}' appears in more than one file.` })
      else processes.set(analysis.processId, entries.length)
      entries.push({ fileName: source.fileName, migrationId: migration.id, processId: analysis.processId, dependencies: analysis.dependencies })
    }
  } catch (cause) {
    for (const id of created) db.migrations.delete(id)
    throw cause
  }
  for (const entry of entries) for (const dependency of entry.dependencies) if (!processes.has(dependency)) issues.push({ fileName: entry.fileName, code: 'PROJECT_MISSING_DEPENDENCY', detail: `Called process '${dependency}' is not included in this project.` })
  const order: string[] = []
  const state = new Map<string, number>()
  const visit = (process: string) => {
    const current = state.get(process) ?? 0
    if (current === 2) return
    const entry = entries[processes.get(process)!]!
    if (current === 1) { issues.push({ fileName: entry.fileName, code: 'PROJECT_DEPENDENCY_CYCLE', detail: `Cross-file child process dependencies contain a cycle at '${process}'.` }); return }
    state.set(process, 1)
    for (const dependency of entry.dependencies) if (processes.has(dependency)) visit(dependency)
    state.set(process, 2)
    order.push(entry.migrationId!)
  }
  for (const process of processes.keys()) visit(process)
  const project: MockMigrationProject = { id: newId(), wsId, name, files: entries, conversionOrder: issues.length === 0 ? order : [], issues, createdAt: clock.iso() }
  db.migrationProjects.set(project.id, project)
  return project
}

export function projectView(project: MockMigrationProject) {
  let converted = 0
  const files = project.files.map((file) => {
    const migration = file.migrationId ? db.migrations.get(file.migrationId) : undefined
    if (migration?.status === 'converted') converted++
    return { ...file, status: migration ? migration.status : 'blocked', convertedWorkflowId: migration?.convertedWorkflowId ?? null }
  })
  const status = project.issues.length > 0 ? 'blocked' : converted === files.length ? 'converted' : converted > 0 ? 'converting' : 'analyzed'
  return { id: project.id, name: project.name, status, files, conversionOrder: project.conversionOrder, issues: project.issues, createdAt: project.createdAt }
}

/** The file convert-next would convert now, or null when everything is converted. */
export function nextInProject(project: MockMigrationProject): string | null {
  return project.conversionOrder.find((id) => db.migrations.get(id)?.status !== 'converted') ?? null
}

// Conversion -----------------------------------------------------------------------------------------------------------------

export interface ConvertRequest {
  workflowKey?: unknown
  name?: unknown
  groupMappings?: Record<string, string>
  childWorkflowMappings?: Record<string, { workflowRef?: string }>
  serviceTaskMappings?: Record<string, unknown>
  [key: string]: unknown
}

export type ConvertOutcome =
  | { ok: true; workflow: MockWorkflow; migration: MockMigration; report: Report; replay: boolean }
  | { ok: false; status: number; code: ProblemCode; title: string; detail?: string; extra?: Record<string, unknown> }

const KEY = /^[a-z][a-z0-9-]{0,62}[a-z0-9]$/

/** Mirrors MigrationService.convert: validated, atomic, idempotent for identical mappings. */
export function convertMigration(migration: MockMigration, user: MockUser, request: ConvertRequest): ConvertOutcome {
  const key = request.workflowKey
  const name = request.name
  if (typeof key !== 'string' || !KEY.test(key)) return { ok: false, status: 400, code: 'MALFORMED_REQUEST', title: 'Bad request', detail: 'workflowKey must be a valid workflow/group/connection key.' }
  if (typeof name !== 'string' || !name.trim() || name.length > 200) return { ok: false, status: 400, code: 'MALFORMED_REQUEST', title: 'Bad request', detail: 'name must be a nonblank string of at most 200 characters.' }
  if (migration.status === 'converted') {
    if (JSON.stringify(migration.mappings) !== JSON.stringify(request)) return { ok: false, status: 409, code: 'WORKFLOW_VERSION_CONFLICT', title: 'Conflict', detail: 'This source was already converted with different mappings.' }
    return { ok: true, workflow: db.workflows.get(migration.convertedWorkflowId!)!, migration, report: migration.report, replay: true }
  }
  const required = migration.report.requiredMappings ?? {}
  const published = new Set([...db.workflows.values()].filter((w) => w.wsId === migration.wsId && w.activeReleaseId).map((w) => w.key))
  const open: Issue[] = []
  for (const i of migration.report.issues) {
    if (i.severity === 'blocker') { open.push(i); continue }
    if (i.reasonCode === 'GROUP_MAPPING_REQUIRED') {
      const group = (required.groups ?? []).find((g) => i.explanation.includes(`'${g}'`))
      if (!group || !request.groupMappings?.[group]) open.push(i)
    } else if (i.reasonCode === 'CHILD_WORKFLOW_MAPPING_REQUIRED') {
      const ref = request.childWorkflowMappings?.[i.sourceElementId]?.workflowRef
      if (!ref || !published.has(ref)) open.push({ ...i, explanation: ref ? `Child workflow '${ref}' has no published release.` : i.explanation })
    } else if (i.reasonCode === 'SERVICE_MAPPING_REQUIRED' && !request.serviceTaskMappings?.[i.sourceElementId]) open.push(i)
  }
  if (open.length > 0) return { ok: false, status: 422, code: 'MIGRATION_BLOCKED', title: 'Migration blocked', detail: 'Resolve mappings and acknowledge documented losses before converting.', extra: { openItems: open } }
  if ([...db.workflows.values()].some((w) => w.wsId === migration.wsId && w.key === key)) return { ok: false, status: 409, code: 'DUPLICATE_KEY', title: 'A workflow with this key already exists' }
  const definition = blankDefinition(key, name)
  definition.metadata.description = `Imported from ${migration.fileName}; review the compatibility report before publishing.`
  const workflow: MockWorkflow = {
    id: newId(), wsId: migration.wsId, key, name, description: definition.metadata.description ?? null, ownerId: user.id,
    drafts: [{ revision: 1, savedAt: clock.iso(), savedById: user.id, definition }], releases: [], activeReleaseId: null, version: 1,
    createdAt: clock.iso(), updatedAt: clock.iso(), migration: { migrationId: migration.id, mapperId: user.id },
  }
  db.workflows.set(workflow.id, workflow)
  Object.assign(migration, { status: 'converted', convertedWorkflowId: workflow.id, convertedById: user.id, mappings: JSON.parse(JSON.stringify(request)) as unknown, mappingsHash: digest(JSON.stringify(request)) })
  migration.report = { ...migration.report, publishable: true, issues: migration.report.issues.map((i) => ({ ...i, resolutionStatus: 'resolved' }) as Issue) }
  return { ok: true, workflow, migration, report: migration.report, replay: false }
}

// Governance ----------------------------------------------------------------------------------------------------------------------

export function policyView(wsId: string) {
  const policy = db.migrationPolicies.get(wsId)
  return { required: policy?.required ?? false, version: policy?.version ?? 0, reason: policy?.reason ?? null, updatedAt: policy?.updatedAt ?? null }
}

function latestDraft(wf: MockWorkflow) {
  return wf.drafts.at(-1)!
}

function definitionHash(wf: MockWorkflow, revision: number): string {
  const draft = wf.drafts.find((d) => d.revision === revision)!
  const { uiLayout: _layout, ...semantic } = draft.definition
  return digest(JSON.stringify(semantic))
}

function sourceOf(wf: MockWorkflow) {
  const migration = wf.migration ? db.migrations.get(wf.migration.migrationId) : undefined
  if (!wf.migration || !migration) return null
  return { sourceHash: migration.sourceHash, mappingsHash: migration.mappingsHash ?? digest('{}'), mapperId: wf.migration.mapperId }
}

function approvalOf(wf: MockWorkflow, revision: number) {
  return db.migrationReviews.filter((r) => r.workflowId === wf.id && r.draftRevision === revision).sort((a, b) => b.version - a.version)[0] ?? null
}

function eligibleReviewer(wf: MockWorkflow, reviewerId: string): boolean {
  const reviewer = userById(reviewerId)
  return !!reviewer && permissionsOf(reviewer, wf.wsId).includes('workflow.publish')
}

function matches(wf: MockWorkflow, approval: NonNullable<ReturnType<typeof approvalOf>>, source: NonNullable<ReturnType<typeof sourceOf>>): boolean {
  return approval.definitionHash === definitionHash(wf, approval.draftRevision) && approval.sourceHash === source.sourceHash && approval.mappingsHash === source.mappingsHash && approval.mapperId === source.mapperId
}

const ref = (id: string | null | undefined) => {
  const user = id ? userById(id) : undefined
  return user ? { id: user.id, displayName: user.displayName } : null
}

export function reviewView(wf: MockWorkflow, user: MockUser) {
  const draft = latestDraft(wf)
  const source = sourceOf(wf)
  const approval = approvalOf(wf, draft.revision)
  const required = db.migrationPolicies.get(wf.wsId)?.required ?? false
  const approved = !!source && !!approval && matches(wf, approval, source) && eligibleReviewer(wf, approval.reviewerId)
  const canApprove = !!source && source.mapperId !== null && user.id !== source.mapperId && user.id !== draft.savedById && permissionsOf(user, wf.wsId).includes('workflow.publish')
  return {
    migration: !!source,
    required: required && !!source,
    draftRevision: draft.revision,
    definitionHash: definitionHash(wf, draft.revision),
    sourceHash: source?.sourceHash ?? null,
    mappingsHash: source?.mappingsHash ?? null,
    mapper: ref(source?.mapperId),
    reviewedBy: ref(approval?.reviewerId),
    reviewedAt: approval?.reviewedAt ?? null,
    reviewReason: approval?.reason ?? null,
    reviewVersion: approval?.version ?? 0,
    approved,
    canApprove,
    detail: !source ? 'This workflow has no recorded migration conversion.' : approved ? 'This exact draft has an independent review.' : 'Review source mappings and the latest draft before approving.',
  }
}

export interface Denial { status: number; code: ProblemCode; title: string; detail: string }
const blocked = (detail: string): Denial => ({ status: 422, code: 'MIGRATION_BLOCKED', title: 'Migration blocked', detail })
const conflict = (detail: string): Denial => ({ status: 409, code: 'WORKFLOW_VERSION_CONFLICT', title: 'Conflict', detail })

export function approveReview(wf: MockWorkflow, user: MockUser, revision: number, expectedVersion: number, reason: string): Denial | null {
  const draft = latestDraft(wf)
  if (draft.revision !== revision) return conflict('The draft changed. Review its latest revision.')
  const source = sourceOf(wf)
  if (!source || source.mapperId === null) return blocked('A recorded conversion mapper is required. Historical conversions with unknown authors cannot be approved.')
  if (user.id === source.mapperId || user.id === draft.savedById) return { status: 403, code: 'FORBIDDEN', title: 'Forbidden', detail: 'The mapper or draft author cannot approve their own migration.' }
  const previous = approvalOf(wf, revision)
  if ((previous?.version ?? 0) !== expectedVersion) return conflict('The migration review changed. Reload before approving.')
  db.migrationReviews.push({ wsId: wf.wsId, workflowId: wf.id, draftRevision: revision, version: expectedVersion + 1, definitionHash: definitionHash(wf, revision), sourceHash: source.sourceHash, mappingsHash: source.mappingsHash, mapperId: source.mapperId, reviewerId: user.id, reason, reviewedAt: clock.iso() })
  return null
}

/** Mirrors MigrationGovernance.requirePublish; called by the publish handler before the release is created. */
export function migrationPublishDenial(wf: MockWorkflow, user: MockUser, revision: number): Denial | null {
  if (!(db.migrationPolicies.get(wf.wsId)?.required ?? false)) return null
  const source = sourceOf(wf)
  if (!source) return null // The policy governs migrated workflows only.
  if (revision !== latestDraft(wf).revision) return blocked('Only the latest reviewed migration draft may be published.')
  const approval = approvalOf(wf, revision)
  if (source.mapperId === null || !approval || !matches(wf, approval, source)) return blocked('An independent approval of this exact migration draft is required before publishing.')
  if (!eligibleReviewer(wf, approval.reviewerId)) return blocked('The migration reviewer no longer has publishing access. Obtain a new independent review.')
  if (user.id === source.mapperId || user.id === approval.reviewerId || user.id === latestDraft(wf).savedById)
    return { status: 403, code: 'FORBIDDEN', title: 'Forbidden', detail: 'Mapper, reviewer and publisher must be different people; draft authors cannot publish their own governed migration.' }
  return null
}
