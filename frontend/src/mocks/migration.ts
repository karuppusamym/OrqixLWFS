import type { components } from '@/api/schema'
import type { ProblemCode } from '@/api/types'
import { MockFormError, analyzeCamunda8, analyzeFormSource, analyzeFormText, isCamunda8, type FormModel } from './camunda8'
import { blankDefinition, clock, db, newId, permissionsOf, userById, type MockMigration, type MockMigrationProject, type MockUser, type MockWorkflow } from './state'

/**
 * Mock-mode migration workbench: small BPMN/DMN inventories, multi-file project planning and the maker-checker rules of
 * docs/17-migration-workbench.md. It follows the backend's behavior (backend/.../migration, catalog/internal/
 * MigrationGovernance) but converts simplified mock drafts; it never claims source equivalence or real compatibility.
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
    out += ((a ^ b) >>> 0).toString(16).padStart(8, '0')
  }
  return out
}

// Analysis ------------------------------------------------------------------------------------------------------------------

const UNSUPPORTED = new Set(['scriptTask', 'subProcess', 'boundaryEvent', 'eventBasedGateway', 'inclusiveGateway', 'manualTask', 'sendTask', 'receiveTask'])
const BPMN_NS = 'http://www.omg.org/spec/BPMN/20100524/MODEL'
const DMN_NAMESPACES = new Set(['http://www.omg.org/spec/DMN/20151101/dmn.xsd', 'https://www.omg.org/spec/DMN/20180521/MODEL/', 'https://www.omg.org/spec/DMN/20191111/MODEL/', 'https://www.omg.org/spec/DMN/20211108/MODEL/'])

function parseSource(xml: string): Document {
  if (!xml.trim() || new TextEncoder().encode(xml).length > 1_048_576) throw new MockSourceError('Source must be between 1 byte and 1 MiB.')
  if (/<!DOCTYPE|<!ENTITY/i.test(xml)) throw new MockSourceError('DOCTYPE and ENTITY declarations are not allowed in migration sources.')
  const doc = new DOMParser().parseFromString(xml, 'application/xml')
  if (doc.getElementsByTagName('parsererror').length > 0) throw new MockSourceError('The source is not well-formed XML.')
  if (doc.getElementsByTagName('*').length > 2000) throw new MockSourceError('Source exceeds the element limit.')
  return doc
}

export interface Analysis {
  report: Report
  processId: string
  dependencies: string[]
  /** Call activities whose child process is missing or computed at runtime. */
  dynamicCalls: number
  sourceFormat: 'bpmn' | 'dmn'
  decisionIds: string[]
  decisionDependencies: Record<string, string>
}

function issue(kind: string, id: string, severity: Issue['severity'], reasonCode: string, explanation: string, suggestedAction?: string): Issue {
  return { sourceElementId: id, sourceKind: kind, severity, reasonCode, explanation, ...(suggestedAction ? { suggestedAction } : {}), resolutionStatus: severity === 'supported' ? 'resolved' : 'open' } as Issue
}

export function analyzeBpmn(xml: string, sourceHash: string): Analysis {
  const doc = parseSource(xml)
  if (doc.documentElement.namespaceURI !== BPMN_NS || doc.documentElement.localName !== 'definitions') throw new MockSourceError('Expected BPMN definitions in the standard namespace.')
  const process = doc.getElementsByTagNameNS(BPMN_NS, 'process')[0]
  const processId = process?.getAttribute('id')
  if (!process || !processId) throw new MockSourceError('The source has no BPMN process with an id.')
  if (isCamunda8(doc)) {
    const { dependencies, dynamicCalls, decisionDependencies, ...report } = analyzeCamunda8(doc, sourceHash)
    return { report: report as Report, processId, dependencies, dynamicCalls, sourceFormat: 'bpmn', decisionIds: [], decisionDependencies }
  }
  const issues: Issue[] = []
  const groups = new Set<string>()
  const services: string[] = []
  const children: string[] = []
  const dependencies = new Set<string>()
  const decisionDependencies: Record<string, string> = {}
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
    } else if (kind === 'businessRuleTask') {
      const decision = Array.from(element.attributes).find(a => a.localName === 'decisionRef')?.value
        ?? Array.from(element.getElementsByTagName('*')).find(e => e.localName === 'calledDecision')?.getAttribute('decisionId')
      if (decision && !/[$#]\{|^=/.test(decision)) decisionDependencies[id] = decision
      issues.push(issue(kind, id, 'action', 'DECISION_MAPPING_REQUIRED', 'Map this business-rule task to a reviewed DMN decision.'))
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
    requiredMappings: { groups: [...groups], approvalTasks: [], serviceTasks: services, childWorkflows: children, decisionTasks: Object.keys(decisionDependencies) },
    publishable: false,
  }
  return { report, processId, dependencies: [...dependencies], dynamicCalls, sourceFormat: 'bpmn', decisionIds: [], decisionDependencies }
}

/** Mock inventory only. The real adapter evaluates and verifies supported unary tests; mock conversion does not. */
export function analyzeDmn(xml: string, sourceHash: string): Analysis {
  const doc = parseSource(xml)
  const root = doc.documentElement
  if (root.localName !== 'definitions' || !DMN_NAMESPACES.has(root.namespaceURI ?? '')) throw new MockSourceError('Expected DMN definitions in a supported namespace.')
  const decisions = Array.from(root.getElementsByTagNameNS(root.namespaceURI, 'decision'))
  const decisionIds = decisions.map(d => d.getAttribute('id') ?? '').filter(Boolean)
  const issues = decisions.flatMap(d => {
    const id = d.getAttribute('id') ?? 'decision'
    const tables = d.getElementsByTagNameNS(root.namespaceURI, 'decisionTable')
    const table = tables[0]
    const convertible = !!d.getAttribute('id') && tables.length === 1 && ['UNIQUE', 'FIRST'].includes(table?.getAttribute('hitPolicy') ?? 'UNIQUE')
      && table?.getElementsByTagNameNS(root.namespaceURI, 'input').length === 1
      && (table?.getElementsByTagNameNS(root.namespaceURI, 'rule').length ?? 0) <= 50
    return convertible ? [] : [issue('decision', id, 'blocker', 'DMN_NOT_SUPPORTED', 'Mock inventory supports single-input UNIQUE/FIRST tables only; use the real adapter for conversion checks.')]
  })
  const processId = root.getAttribute('id') ?? decisionIds[0] ?? 'dmn'
  const report: Report = { sourceSystem: 'dmn', adapterVersion: MOCK_ADAPTER, sourceHash, processId,
    processName: root.getAttribute('name') ?? processId, issues,
    elementCounts: { total: decisions.length, supported: decisions.length - issues.length, action: 0, blocker: issues.length, info: 0 },
    assumptions: ['Mock DMN inventory and simplified draft only; source-to-target equivalence is tested by the real backend.'],
    requiredMappings: {}, publishable: false }
  return { report, processId, dependencies: [], dynamicCalls: 0, sourceFormat: 'dmn', decisionIds, decisionDependencies: {} }
}

export function createMigration(wsId: string, fileName: string, source: string, sourceFormat?: string): { migration: MockMigration; analysis: Analysis } {
  const sourceHash = digest(source)
  if (sourceFormat === 'camundaForm') {
    let report: Report
    try { report = analyzeFormSource(source, sourceHash) } catch (cause) { if (cause instanceof MockFormError) throw new MockSourceError(cause.message); throw cause }
    const migration: MockMigration = { id: newId(), wsId, fileName, source, sourceHash, report, status: 'analyzed', convertedWorkflowId: null, convertedById: null, mappingsHash: null, mappings: null, createdAt: clock.iso() }
    db.migrations.set(migration.id, migration)
    return { migration, analysis: { report, processId: report.processId ?? 'form', dependencies: [], dynamicCalls: 0, sourceFormat: 'bpmn', decisionIds: [], decisionDependencies: {} } }
  }
  const detected = sourceFormat ?? (DMN_NAMESPACES.has(parseSource(source).documentElement.namespaceURI ?? '') ? 'dmn' : 'bpmn')
  const analysis = detected === 'dmn' ? analyzeDmn(source, sourceHash) : analyzeBpmn(source, sourceHash)
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

/** Bounded namespace-aware project inventory. Mock conversions create simplified drafts, not equivalent workflows. */
export function planProject(wsId: string, name: string, sources: ProjectSource[]): MockMigrationProject {
  const names = new Set<string>()
  const issues: ProjectIssue[] = []
  const entries: MockMigrationProject['files'] = []
  const processes = new Map<string, number>()
  const decisions = new Map<string, number>()
  if (sources.length < 1 || sources.length > 10) throw new MockSourceError('Project needs 1 to 10 files.')
  if (sources.reduce((bytes, s) => bytes + new TextEncoder().encode(s.content).length, 0) > 4_194_304) throw new MockSourceError('Project source exceeds 4 MiB total.')
  for (const source of sources) {
    if (!source.fileName.trim() || source.fileName.length > 200 || names.has(source.fileName)) throw new MockSourceError('Project filenames must be unique, nonblank and at most 200 characters.')
    names.add(source.fileName)
  }
  const created: string[] = []
  try {
    for (const source of sources) {
      if (source.sourceFormat && !['bpmn', 'dmn'].includes(source.sourceFormat)) {
        issues.push({ fileName: source.fileName, code: 'PROJECT_FORMAT_NOT_SUPPORTED', detail: 'Projects support BPMN and bounded DMN tables only. Forms need separate reviewed reconstruction.' })
        entries.push({ fileName: source.fileName, migrationId: null, processId: null, dependencies: [], sourceFormat: null, decisionIds: [], decisionDependencies: {} })
        continue
      }
      const { migration, analysis } = createMigration(wsId, source.fileName, source.content, source.sourceFormat)
      created.push(migration.id)
      if (analysis.dynamicCalls > 0) issues.push({ fileName: source.fileName, code: 'PROJECT_DYNAMIC_CHILD_REFERENCE', detail: 'A child process reference is missing or dynamic; provide a literal dependency before project conversion.' })
      if (analysis.sourceFormat === 'dmn') {
        if (analysis.decisionIds.length === 0) issues.push({ fileName: source.fileName, code: 'PROJECT_DMN_SELECTION_REQUIRED', detail: 'The DMN file has no decisions. Include at least one supported decision table.' })
        for (const id of analysis.decisionIds) {
          if (decisions.has(id)) issues.push({ fileName: source.fileName, code: 'PROJECT_DUPLICATE_DECISION', detail: `Decision ID '${id}' appears in more than one DMN file.` })
          else decisions.set(id, entries.length)
        }
        if (analysis.report.issues.some(i => i.severity === 'blocker')) issues.push({ fileName: source.fileName, code: 'PROJECT_DMN_NOT_CONVERTIBLE', detail: 'This DMN file is outside the bounded adapter. Review its report.' })
      } else {
        if (processes.has(analysis.processId)) issues.push({ fileName: source.fileName, code: 'PROJECT_DUPLICATE_PROCESS', detail: `Process ID '${analysis.processId}' appears in more than one file.` })
        else processes.set(analysis.processId, entries.length)
      }
      entries.push({ fileName: source.fileName, migrationId: migration.id, processId: analysis.processId, dependencies: analysis.dependencies,
        sourceFormat: analysis.sourceFormat, decisionIds: analysis.decisionIds, decisionDependencies: analysis.decisionDependencies })
    }
  } catch (cause) {
    for (const id of created) db.migrations.delete(id)
    throw cause
  }
  for (const entry of entries) for (const dependency of entry.dependencies) if (!processes.has(dependency)) issues.push({ fileName: entry.fileName, code: 'PROJECT_MISSING_DEPENDENCY', detail: `Called process '${dependency}' is not included in this project.` })
  for (const entry of entries) for (const decision of new Set(Object.values(entry.decisionDependencies ?? {}))) if (!decisions.has(decision)) issues.push({ fileName: entry.fileName, code: 'PROJECT_MISSING_DECISION', detail: `Called decision '${decision}' is not included in this project.` })
  const order: string[] = []
  const state = new Map<number, number>()
  const visit = (index: number) => {
    const current = state.get(index) ?? 0
    if (current === 2) return
    const entry = entries[index]!
    if (current === 1) { issues.push({ fileName: entry.fileName, code: 'PROJECT_DEPENDENCY_CYCLE', detail: `Cross-file child process dependencies contain a cycle at '${entry.processId}'.` }); return }
    state.set(index, 1)
    for (const dependency of entry.dependencies) if (processes.has(dependency)) visit(processes.get(dependency)!)
    for (const dependency of new Set(Object.values(entry.decisionDependencies ?? {}))) if (decisions.has(dependency)) visit(decisions.get(dependency)!)
    state.set(index, 2)
    order.push(entry.migrationId!)
  }
  entries.forEach((entry, index) => { if (entry.migrationId) visit(index) })
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
  decisionMappings?: Record<string, { migrationId?: string; decisionId?: string }>
  decisionId?: string
  formMappings?: Record<string, { migrationId?: string }>
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
  if (migration.report.sourceSystem === 'dmn') {
    const ids = analyzeDmn(migration.source, migration.sourceHash).decisionIds
    if (request.decisionId ? !ids.includes(request.decisionId) : ids.length !== 1) {
      return { ok: false, status: 422, code: 'SOURCE_INVALID', title: 'Decision selection required', detail: `Choose an existing decisionId from: ${ids.join(', ')}.` }
    }
  }
  const required = migration.report.requiredMappings ?? {}
  const published = new Set([...db.workflows.values()].filter((w) => w.wsId === migration.wsId && w.activeReleaseId).map((w) => w.key))
  const open: Issue[] = []
  const forms: FormModel[] = []
  for (const i of migration.report.issues) {
    if (['CONNECTOR_MAPPING_REQUIRED', 'WORKER_NOT_PORTABLE'].includes(i.reasonCode)) {
      if (!request.serviceTaskMappings?.[i.sourceElementId]) open.push(i)
      continue
    }
    if (i.severity === 'blocker') { open.push(i); continue }
    if (i.reasonCode === 'GROUP_MAPPING_REQUIRED') {
      const group = (required.groups ?? []).find((g) => i.explanation.includes(`'${g}'`))
      if (!group || !request.groupMappings?.[group]) open.push(i)
    } else if (i.reasonCode === 'CHILD_WORKFLOW_MAPPING_REQUIRED') {
      const ref = request.childWorkflowMappings?.[i.sourceElementId]?.workflowRef
      if (!ref || !published.has(ref)) open.push({ ...i, explanation: ref ? `Child workflow '${ref}' has no published release.` : i.explanation })
    } else if (i.reasonCode === 'SERVICE_MAPPING_REQUIRED' && !request.serviceTaskMappings?.[i.sourceElementId]) open.push(i)
    else if (i.reasonCode === 'DECISION_MAPPING_REQUIRED') {
      const mapping = request.decisionMappings?.[i.sourceElementId]
      const target = mapping?.migrationId ? db.migrations.get(mapping.migrationId) : undefined
      if (!target || target.wsId !== migration.wsId || target.report.sourceSystem !== 'dmn' || target.report.issues.some(i => i.severity === 'blocker')) open.push(i)
    }
  }
  for (const [element, mapping] of Object.entries(request.formMappings ?? {})) {
    const hint = migration.report.mappingHints?.forms?.[element]
    const target = mapping.migrationId ? db.migrations.get(mapping.migrationId) : undefined
    if (hint?.state !== 'upload' || !target || target.wsId !== migration.wsId || target.report.sourceSystem !== 'camundaForm')
      return { ok: false, status: 422, code: 'SOURCE_INVALID', title: 'Source rejected', detail: `formMappings/${element} must name an uploaded Camunda form for a step that links one.` }
    if (target.report.processId !== hint.formId)
      return { ok: false, status: 422, code: 'SOURCE_INVALID', title: 'Source rejected', detail: `The step '${element}' uses the form '${hint.formId}', but the uploaded file is the form '${target.report.processId}'.` }
    const model = analyzeFormText(target.source)
    forms.push(model)
    open.push(...model.issues.filter(i => i.severity === 'blocker'))
  }
  if (open.length > 0) return { ok: false, status: 422, code: 'MIGRATION_BLOCKED', title: 'Migration blocked', detail: 'Resolve mappings and acknowledge documented losses before converting.', extra: { openItems: open } }
  if ([...db.workflows.values()].some((w) => w.wsId === migration.wsId && w.key === key)) return { ok: false, status: 409, code: 'DUPLICATE_KEY', title: 'A workflow with this key already exists' }
  const definition = blankDefinition(key, name)
  const startForms = Object.entries(migration.report.mappingHints?.forms ?? {}).filter(([, h]) => h.target === 'start')
  const fields = startForms.flatMap(([element, h]) => h.state === 'upload'
    ? (forms.find(f => f.formId === h.formId)?.fields ?? [])
    : (migration.report.mappingHints?.formPreviews?.[element]?.fields ?? []))
  for (const field of fields) {
    if (!field.key || !field.type) continue
    const schema: Record<string, unknown> = { type: field.type, title: field.label ?? field.key }
    if (field.options?.length) schema.enum = field.options
    ;(definition.inputSchema.properties as Record<string, unknown>)[field.key] = schema
    if (field.required) (definition.inputSchema.required as string[]).push(field.key)
    ;(definition.inputUi!.order as string[]).push(field.key)
    if (field.widget && field.widget !== 'boolean') (definition.inputUi!.widgets as Record<string, string>)[field.key] = field.widget
  }
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
  const job = wf.migration ? db.migrations.get(wf.migration.migrationId) : undefined
  if (job?.report.issues.some(i => i.severity === 'verify' && i.resolutionStatus === 'open'))
    return blocked('The migration has converted-but-changed items that a reviewer must verify and acknowledge before publishing.')
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

/** Mirrors MigrationService.acknowledgeVerifications: after conversion, not by the mapper, only open verify items. */
export function acknowledgeVerifications(migration: MockMigration, user: MockUser, items: Array<{ sourceElementId?: string; reasonCode?: string; sourcePath?: string | null }>, reason: string): Denial | null {
  if (migration.status !== 'converted') return blocked('Convert the source first. Changed behaviour is verified against the converted draft.')
  if (migration.convertedById === user.id) return { status: 403, code: 'FORBIDDEN', title: 'Forbidden', detail: 'You converted this source, so a different reviewer must verify its changed behaviour.' }
  const issues = [...migration.report.issues]
  const verifications = [...(migration.report.verifications ?? [])]
  for (const item of items) {
    let matched = false
    issues.forEach((issue, index) => {
      if (issue.severity === 'verify' && issue.resolutionStatus === 'open' && issue.sourceElementId === item.sourceElementId && issue.reasonCode === item.reasonCode && (item.sourcePath == null || item.sourcePath === issue.sourcePath)) {
        issues[index] = { ...issue, resolutionStatus: 'acknowledged' }; matched = true
        verifications.push({ sourceElementId: issue.sourceElementId, reasonCode: issue.reasonCode, sourcePath: issue.sourcePath ?? null, acknowledgedBy: user.id, acknowledgedByName: user.displayName, acknowledgedAt: clock.iso(), reason: reason.trim() })
      }
    })
    if (!matched) return { status: 400, code: 'MALFORMED_REQUEST', title: 'Bad request', detail: `No open verify item matches ${item.sourceElementId} / ${item.reasonCode}.` }
  }
  migration.report = { ...migration.report, issues, verifications }
  return null
}
