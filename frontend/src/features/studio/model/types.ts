/**
 * Canonical workflow v0.1 document as the studio edits it (contracts/workflow-v0.schema.json).
 * Drafts may be incomplete, so every container is normalized on load and every accessor is defensive.
 */
export type NodeKind = 'start' | 'approval' | 'decision' | 'branch' | 'http' | 'notification' | 'parallelSplit' | 'parallelJoin' | 'wait' | 'startWorkflow' | 'end'
export type Operator = 'eq' | 'gte' | 'lt'
export type RouteKind = 'next' | 'approved' | 'rejected' | 'timedOut' | 'condition' | 'default'
export type Literal = string | number | boolean

export interface Condition {
  fieldRef: string
  operator: Operator
  value: Literal
}

export interface WorkflowNode {
  id: string
  type: NodeKind
  label: string
  config: Record<string, unknown>
}

export interface Transition {
  id: string
  from: string
  to: string
  on: RouteKind
  label?: string
  condition?: Condition
}

export interface DecisionRow {
  when: Condition
  then: Record<string, Literal>
}

export interface DecisionTable {
  key: string
  version: number
  hitPolicy: 'UNIQUE'
  rows: DecisionRow[]
  default: Record<string, Literal>
}

export interface FieldSchemaDoc {
  type: 'string' | 'number' | 'integer' | 'boolean'
  title: string
  description?: string
  enum?: string[]
  minLength?: number
  maxLength?: number
  minimum?: number
  maximum?: number
  multipleOf?: number
}

export interface FormSchemaDoc {
  type: 'object'
  additionalProperties: false
  required?: string[]
  properties: Record<string, FieldSchemaDoc>
}

export interface InputUiDoc {
  order?: string[]
  widgets?: Record<string, string>
  currency?: 'USD'
}

export interface Position {
  x: number
  y: number
}

export interface Definition {
  schemaVersion: '0.1'
  workflowKey: string
  name: string
  metadata: { description: string; tags?: string[] }
  inputSchema: FormSchemaDoc
  inputUi?: InputUiDoc
  outputSchema: FormSchemaDoc
  triggers: Array<'manual' | 'api'>
  nodes: WorkflowNode[]
  transitions: Transition[]
  decisions: DecisionTable[]
  execution: { maxConcurrentRuns: number; maxAttempts: number; retryDelaySeconds: number }
  security: { selfApproval: 'deny' | 'allow' }
  dependencies: { connections: Array<{ key: string; definitionVersion: number }> }
  outputMapping: Record<string, { fieldRef: string; required: boolean }>
  uiLayout?: { positions: Record<string, Position>; viewport?: { x: number; y: number; zoom: number } }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/**
 * Fill missing containers so the editor can always operate. Existing content is never rewritten,
 * and the result is only saved after the user edits something.
 */
export function normalizeDefinition(raw: unknown): Definition {
  const src: Record<string, unknown> = isRecord(raw) ? raw : {}
  const def = structuredClone(src) as Record<string, unknown>
  def.schemaVersion = '0.1'
  if (typeof def.workflowKey !== 'string') def.workflowKey = ''
  if (typeof def.name !== 'string') def.name = ''
  if (!isRecord(def.metadata)) def.metadata = { description: '' }
  if (!isRecord(def.inputSchema)) def.inputSchema = { type: 'object', additionalProperties: false, required: [], properties: {} }
  else if (!isRecord((def.inputSchema as Record<string, unknown>).properties)) (def.inputSchema as Record<string, unknown>).properties = {}
  if (!isRecord(def.outputSchema)) def.outputSchema = { type: 'object', additionalProperties: false, properties: {} }
  else if (!isRecord((def.outputSchema as Record<string, unknown>).properties)) (def.outputSchema as Record<string, unknown>).properties = {}
  if (!Array.isArray(def.triggers)) def.triggers = ['manual']
  if (!Array.isArray(def.nodes)) def.nodes = []
  if (!Array.isArray(def.transitions)) def.transitions = []
  if (!Array.isArray(def.decisions)) def.decisions = []
  if (!isRecord(def.execution)) def.execution = { maxConcurrentRuns: 500, maxAttempts: 3, retryDelaySeconds: 30 }
  if (!isRecord(def.security)) def.security = { selfApproval: 'deny' }
  if (!isRecord(def.dependencies)) def.dependencies = { connections: [] }
  else if (!Array.isArray((def.dependencies as Record<string, unknown>).connections)) (def.dependencies as Record<string, unknown>).connections = []
  if (!isRecord(def.outputMapping)) def.outputMapping = {}
  if (!isRecord(def.uiLayout)) def.uiLayout = { positions: {} }
  else if (!isRecord((def.uiLayout as Record<string, unknown>).positions)) (def.uiLayout as Record<string, unknown>).positions = {}
  for (const node of def.nodes as Array<Record<string, unknown>>) {
    if (!isRecord(node.config)) node.config = {}
  }
  return def as unknown as Definition
}

/** Config helpers with defaults, so inspector code does not scatter casts. */
export function cfg<T = unknown>(node: WorkflowNode, key: string): T | undefined {
  return node.config[key] as T | undefined
}
