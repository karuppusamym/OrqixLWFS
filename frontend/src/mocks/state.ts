import purchaseExample from '../../../examples/purchase-approval.json'
import onboardingExample from '../../../examples/new-hire-onboarding.json'
import procurementConnection from '../../../examples/connections/procurement.connection.json'
import vendorAgentConnection from '../../../examples/connections/vendor-risk-assistant.connection.json'
import type { components } from '@/api/schema'
import type { Effect, Permission, TaskDecision } from '@/api/types'
import { normalizeDefinition, type Definition } from '@/features/studio/model/types'
import { uuid } from '@/lib/text'

/**
 * In-memory backend used by mock mode (VITE_MOCK_API=true) and by component tests.
 * It mirrors contracts/openapi.yaml closely enough to exercise every screen without a server.
 * The workflow template and connection definition are imported from /examples, never copied.
 */

export interface MockUser {
  id: string
  username: string
  displayName: string
  email: string
  platformAdmin: boolean
}

export interface MockWorkspace {
  id: string
  key: string
  name: string
  organizationName: string
  isTest: boolean
  allowSelfApprovalWorkflows: boolean
}

export const WS_PURCHASING = '11111111-1111-4111-8111-111111111111'
export const WS_SANDBOX = '22222222-2222-4222-8222-222222222222'
export const WS_FACILITIES = '33333333-3333-4333-8333-333333333333'

function userId(n: number): string {
  return `aaaaaaaa-0000-4000-8000-${String(n).padStart(12, '0')}`
}

export const USERS: MockUser[] = [
  { id: userId(1), username: 'pat.platform', displayName: 'Pat Platform', email: 'pat@example.com', platformAdmin: true },
  { id: userId(2), username: 'wade.admin', displayName: 'Wade Admin', email: 'wade@example.com', platformAdmin: false },
  { id: userId(3), username: 'dana.designer', displayName: 'Dana Designer', email: 'dana@example.com', platformAdmin: false },
  { id: userId(4), username: 'paul.publisher', displayName: 'Paul Publisher', email: 'paul@example.com', platformAdmin: false },
  { id: userId(5), username: 'olga.operator', displayName: 'Olga Operator', email: 'olga@example.com', platformAdmin: false },
  { id: userId(6), username: 'vic.viewer', displayName: 'Vic Viewer', email: 'vic@example.com', platformAdmin: false },
  { id: userId(7), username: 'rhea.requester', displayName: 'Rhea Requester', email: 'rhea@example.com', platformAdmin: false },
  { id: userId(8), username: 'mani.manager', displayName: 'Mani Manager', email: 'mani@example.com', platformAdmin: false },
  { id: userId(9), username: 'max.manager', displayName: 'Max Manager', email: 'max@example.com', platformAdmin: false },
  { id: userId(10), username: 'fin.finance', displayName: 'Fin Finance', email: 'fin@example.com', platformAdmin: false },
  { id: userId(11), username: 'fred.facilities', displayName: 'Fred Facilities', email: 'fred@example.com', platformAdmin: false },
  { id: userId(12), username: 'otto.outsider', displayName: 'Otto Outsider', email: 'otto@example.com', platformAdmin: false },
  // Mock-only: a second publisher, so mapper, reviewer and publisher can be three different people (docs/17).
  { id: userId(13), username: 'pia.publisher', displayName: 'Pia Publisher', email: 'pia@example.com', platformAdmin: false },
]

export const WORKSPACES: MockWorkspace[] = [
  { id: WS_PURCHASING, key: 'purchasing', name: 'Purchasing', organizationName: 'Demo Organization', isTest: false, allowSelfApprovalWorkflows: false },
  { id: WS_SANDBOX, key: 'purchasing-sandbox', name: 'Purchasing sandbox', organizationName: 'Demo Organization', isTest: true, allowSelfApprovalWorkflows: true },
  { id: WS_FACILITIES, key: 'facilities', name: 'Facilities', organizationName: 'Demo Organization', isTest: false, allowSelfApprovalWorkflows: false },
]

/** Default role mapping (docs/build/S6-security.md section 3). */
const ROLE_PERMISSIONS: Record<string, Permission[]> = {
  WORKSPACE_ADMIN: ['workspace.manage-access', 'connection.read'],
  WORKFLOW_DESIGNER: ['workflow.read', 'workflow.edit', 'workflow.export', 'connection.read'],
  PUBLISHER: ['workflow.read', 'workflow.publish', 'workflow.export', 'connection.read'],
  OPERATOR: ['workflow.read', 'run.read', 'run.read-data', 'run.operate', 'connection.read'],
  VIEWER: ['workflow.read', 'run.read'],
  APPROVER: ['task.act'],
  REQUESTER: ['workflow.start'],
  INTEGRATION_ADMIN: ['connection.manage'],
}

/** username -> workspace id -> roles. Mirrors the dev seed in docs/build/S1-engineering-baseline.md section 6.1. */
const MEMBERSHIPS: Record<string, Record<string, string[]>> = {
  'wade.admin': { [WS_PURCHASING]: ['WORKSPACE_ADMIN', 'INTEGRATION_ADMIN'], [WS_SANDBOX]: ['WORKSPACE_ADMIN', 'INTEGRATION_ADMIN'] },
  'dana.designer': { [WS_PURCHASING]: ['WORKFLOW_DESIGNER', 'REQUESTER'], [WS_SANDBOX]: ['WORKFLOW_DESIGNER', 'REQUESTER', 'APPROVER'] },
  'paul.publisher': { [WS_PURCHASING]: ['PUBLISHER'], [WS_SANDBOX]: ['PUBLISHER'] },
  'pia.publisher': { [WS_PURCHASING]: ['PUBLISHER'], [WS_SANDBOX]: ['PUBLISHER'] },
  'olga.operator': { [WS_PURCHASING]: ['OPERATOR'], [WS_SANDBOX]: ['OPERATOR'] },
  'vic.viewer': { [WS_PURCHASING]: ['VIEWER'] },
  'rhea.requester': { [WS_PURCHASING]: ['REQUESTER'], [WS_SANDBOX]: ['REQUESTER'] },
  'mani.manager': { [WS_PURCHASING]: ['APPROVER', 'REQUESTER'], [WS_SANDBOX]: ['APPROVER', 'REQUESTER'] },
  'max.manager': { [WS_PURCHASING]: ['APPROVER', 'REQUESTER'], [WS_SANDBOX]: ['APPROVER', 'REQUESTER'] },
  'fin.finance': { [WS_PURCHASING]: ['APPROVER'], [WS_SANDBOX]: ['APPROVER'] },
  'fred.facilities': { [WS_FACILITIES]: ['REQUESTER'] },
}

/** Task-assignment groups: key -> usernames. */
const GROUP_MEMBERS: Record<string, string[]> = {
  managers: ['mani.manager', 'max.manager'],
  'finance-approvers': ['fin.finance'],
  // Like the dev seed: the IT desk and Facilities groups are backed by existing identity groups.
  'it-desk': ['mani.manager', 'max.manager'],
  'facilities-team': ['fin.finance'],
}

export const GROUPS = [
  { key: 'managers', name: 'Managers', revision: 1 },
  { key: 'finance-approvers', name: 'Finance approvers', revision: 1 },
  { key: 'it-desk', name: 'IT service desk', revision: 1 },
  { key: 'facilities-team', name: 'Facilities team', revision: 1 },
]

export function userByName(username: string): MockUser {
  return USERS.find((u) => u.username === username) ?? (USERS.find((u) => u.username === 'otto.outsider') as MockUser)
}

export function userById(id: string): MockUser | undefined {
  return USERS.find((u) => u.id === id)
}

export function permissionsOf(user: MockUser, wsId: string): Permission[] {
  const roles = MEMBERSHIPS[user.username]?.[wsId] ?? []
  return [...new Set(roles.flatMap((r) => ROLE_PERMISSIONS[r] ?? []))]
}

export function isMember(user: MockUser, wsId: string): boolean {
  return (MEMBERSHIPS[user.username]?.[wsId] ?? []).length > 0
}

export function groupMembers(key: string): MockUser[] {
  return (GROUP_MEMBERS[key] ?? []).map(userByName)
}

export function inGroup(user: MockUser, key: string): boolean {
  return (GROUP_MEMBERS[key] ?? []).includes(user.username)
}

export function rolesOf(user: MockUser, wsId: string): string[] {
  return MEMBERSHIPS[user.username]?.[wsId] ?? []
}

export function allMemberships(): Array<{ username: string; wsId: string; roles: string[] }> {
  return Object.entries(MEMBERSHIPS).flatMap(([username, byWs]) => Object.entries(byWs).map(([wsId, roles]) => ({ username, wsId, roles })))
}

// Clock -------------------------------------------------------------------------------------------

let offsetMs = 0
export const clock = {
  now: () => new Date(Date.now() + offsetMs),
  iso: () => new Date(Date.now() + offsetMs).toISOString(),
  /** Run `fn` as if it were `hoursAgo` hours in the past (used only to age the seed data). */
  asIfAgo<T>(hoursAgo: number, fn: () => T): T {
    const saved = offsetMs
    offsetMs = -hoursAgo * 3_600_000
    try {
      return fn()
    } finally {
      offsetMs = saved
    }
  },
}

// Records -----------------------------------------------------------------------------------------

export interface MockRelease {
  id: string
  workflowId: string
  releaseNumber: number
  draftRevision: number
  publishedAt: string
  publishedById: string
  compilerVersion: string
  releaseNote: string | null
  sourceHash: string
  semanticHash: string
  warnings: Array<{ code: string; severity: 'blocker' | 'warning'; message: string; path: string; nodeId?: string | null; transitionId?: string | null }>
  definition: Definition
}

export interface MockDraftRevision {
  revision: number
  savedAt: string
  savedById: string
  definition: Definition
}

export interface MockWorkflow {
  id: string
  wsId: string
  key: string
  name: string
  description: string | null
  ownerId: string
  drafts: MockDraftRevision[]
  releases: MockRelease[]
  activeReleaseId: string | null
  version: number
  createdAt: string
  updatedAt: string
  /** Set when the draft was created by converting an imported source (maker-checker governance). */
  migration?: { migrationId: string; mapperId: string | null }
}

export interface MockEvent {
  seq: number
  type: string
  nodeId: string | null
  actorId: string | null
  occurredAt: string
  data: Record<string, unknown>
}

export interface MockTask {
  id: string
  runId: string
  nodeId: string
  label: string
  candidateGroup: string
  assigneeId: string | null
  status: 'open' | 'completed' | 'cancelled'
  createdAt: string
  dueAt: string | null
  version: number
  decision: TaskDecision | null
  completedById: string | null
}

export interface MockRun {
  comments?: components['schemas']['RunComment'][]
  id: string
  /** Optional for compatibility with older persisted mock fixtures. */
  reference?: string
  wsId: string
  workflowId: string
  releaseId: string
  businessKey: string | null
  initiatorId: string
  lifecycle: 'running' | 'waiting' | 'needsAttention' | 'completed' | 'cancelled'
  outcome: string | null
  outcomeKind: 'success' | 'rejected' | null
  input: Record<string, unknown>
  output: Record<string, unknown> | null
  steps: Record<string, Record<string, unknown>>
  createdAt: string
  updatedAt: string
  endedAt: string | null
  version: number
  eventSeq: number
  events: MockEvent[]
  tasks: MockTask[]
  effects: Array<Effect & { runId: string }>
  attention: { reason: string; nodeId: string; effectId: string | null; message: string; since: string } | null
  /** Node waiting on an external effect, if any. */
  pendingHttpNode: string | null
  cancelledEffects: string[]
  /** Run that started this one ("Start another workflow") and the step that did. */
  parentRunId?: string
  parentNodeId?: string
  /** Waits in progress: node id -> due time (mock timers are shortened, see startWait in engine.ts). */
  waits?: Record<string, { dueAt: string }>
  /** Parallel joins: node id -> number of branches that arrived. */
  joins?: Record<string, number>
  /** Child runs this run waits for: node id -> child run id. */
  childWaits?: Record<string, string>
}

export interface MockNotification {
  id: string
  wsId: string
  userId: string
  runId: string | null
  kind: 'workflowMessage' | 'runAttention'
  message: string
  createdAt: string
  readAt: string | null
}

export interface MockBinding {
  connectionKey: string
  baseUrl: string
  auth: { type: 'none' | 'bearerToken' | 'apiKeyHeader'; headerName?: string; secretRef?: string }
  enabled: boolean
  revision: number
  updatedAt: string
  updatedById: string
}

export interface MockMigration {
  id: string
  wsId: string
  fileName: string
  source: string
  sourceHash: string
  report: components['schemas']['CompatibilityReport']
  status: 'analyzed' | 'converted'
  convertedWorkflowId: string | null
  convertedById: string | null
  mappingsHash: string | null
  mappings: unknown
  createdAt: string
}

export interface MockMigrationProject {
  id: string
  wsId: string
  name: string
  files: Array<{ fileName: string; migrationId: string | null; processId: string | null; dependencies: string[] }>
  conversionOrder: string[]
  issues: Array<{ fileName: string; code: string; detail: string }>
  createdAt: string
}

export interface MockMigrationPolicy {
  required: boolean
  version: number
  reason: string
  updatedAt: string
}

export interface MockMigrationReview {
  wsId: string
  workflowId: string
  draftRevision: number
  version: number
  definitionHash: string
  sourceHash: string
  mappingsHash: string
  mapperId: string
  reviewerId: string
  reason: string
  reviewedAt: string
}

export type ProcurementFault = 'none' | 'failOnce' | 'unknown' | 'down' | 'rejected'

export interface MockDb {
  migrations: Map<string, MockMigration>
  migrationProjects: Map<string, MockMigrationProject>
  /** Workspace id -> independent-review policy (absent = never set). */
  migrationPolicies: Map<string, MockMigrationPolicy>
  migrationReviews: MockMigrationReview[]
  workflows: Map<string, MockWorkflow>
  runs: Map<string, MockRun>
  notifications: MockNotification[]
  bindings: Map<string, MockBinding>
  idempotency: Map<string, { hash: string; status: number; body: unknown }>
  procurementFault: ProcurementFault
  orderCounter: number
  taskCounter: number
  /** Artificial latency in ms for every API call. */
  latencyMs: number
}

export const PURCHASE_WORKFLOW_ID = 'bbbbbbbb-0000-4000-8000-000000000001'
export const COMPILER_VERSION = '0.1.0-mock'

export const connectionDefinition = procurementConnection
/** The AI agent connection of Demo 2 (docs/build/S13-agents-mcp.md); the mock lists, binds and tests it like the real one. */
export const agentConnectionDefinition = vendorAgentConnection

export function purchaseTemplate(): Definition {
  return normalizeDefinition(purchaseExample)
}

export const TEMPLATES = [
  {
    id: 'purchase-approval',
    name: 'Purchase approval',
    description: 'USD purchase request: manager approval, finance review at USD 5,000 or more, purchase order creation and requester notification.',
    typicalOwner: 'Procurement',
    requiredInputs: ['What are you buying?', 'Business purpose', 'Cost center', 'Amount (USD)'],
    requiredGroups: ['managers', 'finance-approvers'],
    requiredConnections: ['procurement'],
    requiredWorkflows: [],
  },
  {
    id: 'new-hire-onboarding',
    name: 'New hire onboarding',
    description: 'IT and Facilities approve in parallel (IT escalates after 4 hours), the request waits a minute, then the equipment is ordered as a separate purchase request.',
    typicalOwner: 'People operations',
    requiredInputs: ['New colleague', 'Job title', 'Cost center', 'Equipment to order', 'Equipment budget (USD)', 'Why this equipment'],
    requiredGroups: ['it-desk', 'managers', 'facilities-team'],
    requiredConnections: [],
    requiredWorkflows: ['purchase-approval'],
  },
]

export function onboardingTemplate(): Definition {
  return normalizeDefinition(onboardingExample)
}

export function newId(): string {
  return uuid()
}

export function emptyDb(): MockDb {
  return {
    migrations: new Map(),
    migrationProjects: new Map(),
    migrationPolicies: new Map(),
    migrationReviews: [],
    workflows: new Map(),
    runs: new Map(),
    notifications: [],
    bindings: new Map(),
    idempotency: new Map(),
    procurementFault: 'none',
    orderCounter: 1000,
    taskCounter: 100,
    latencyMs: 0,
  }
}

/** The one shared database instance. Tests and mock mode call reset() to start from the seed. */
export let db: MockDb = emptyDb()

export function replaceDb(next: MockDb): void {
  db = next
}

export function blankDefinition(workflowKey: string, name: string): Definition {
  return normalizeDefinition({
    schemaVersion: '0.1',
    workflowKey,
    name,
    metadata: { description: '' },
    inputSchema: { type: 'object', additionalProperties: false, required: [], properties: {} },
    inputUi: { order: [], widgets: {}, currency: 'USD' },
    outputSchema: { type: 'object', additionalProperties: false, properties: {} },
    triggers: ['manual'],
    nodes: [
      { id: 'start', type: 'start', label: 'Request submitted', config: {} },
      { id: 'finished', type: 'end', label: 'Finished', config: { businessOutcome: 'Completed', outcomeKind: 'success' } },
    ],
    transitions: [{ id: 't1', from: 'start', to: 'finished', on: 'next' }],
    decisions: [],
    execution: { maxConcurrentRuns: 500, maxAttempts: 3, retryDelaySeconds: 30 },
    security: { selfApproval: 'deny' },
    dependencies: { connections: [] },
    outputMapping: {},
    uiLayout: { positions: { start: { x: 80, y: 120 }, finished: { x: 380, y: 120 } } },
  })
}

// Persistence (browser mock mode keeps state across reloads and persona switches) -----------------------------------

interface DbSnapshot {
  /** Absent in snapshots written before migrations were mocked. */
  migrations?: Array<[string, MockMigration]>
  migrationProjects?: Array<[string, MockMigrationProject]>
  migrationPolicies?: Array<[string, MockMigrationPolicy]>
  migrationReviews?: MockMigrationReview[]
  workflows: Array<[string, MockWorkflow]>
  runs: Array<[string, MockRun]>
  notifications: MockNotification[]
  bindings: Array<[string, MockBinding]>
  idempotency: Array<[string, { hash: string; status: number; body: unknown }]>
  procurementFault: ProcurementFault
  orderCounter: number
  taskCounter: number
}

export function snapshotDb(): string {
  const snap: DbSnapshot = {
    migrations: [...db.migrations],
    migrationProjects: [...db.migrationProjects],
    migrationPolicies: [...db.migrationPolicies],
    migrationReviews: db.migrationReviews,
    workflows: [...db.workflows],
    runs: [...db.runs],
    notifications: db.notifications,
    bindings: [...db.bindings],
    idempotency: [...db.idempotency],
    procurementFault: db.procurementFault,
    orderCounter: db.orderCounter,
    taskCounter: db.taskCounter,
  }
  return JSON.stringify(snap)
}

export function restoreDb(json: string): boolean {
  try {
    const snap = JSON.parse(json) as DbSnapshot
    const next = emptyDb()
    next.migrations = new Map(snap.migrations ?? [])
    next.migrationProjects = new Map(snap.migrationProjects ?? [])
    next.migrationPolicies = new Map(snap.migrationPolicies ?? [])
    next.migrationReviews = snap.migrationReviews ?? []
    next.workflows = new Map(snap.workflows)
    next.runs = new Map(snap.runs)
    next.notifications = snap.notifications
    next.bindings = new Map(snap.bindings)
    next.idempotency = new Map(snap.idempotency)
    next.procurementFault = snap.procurementFault
    next.orderCounter = snap.orderCounter
    next.taskCounter = snap.taskCounter
    next.latencyMs = db.latencyMs
    replaceDb(next)
    return true
  } catch {
    return false
  }
}
