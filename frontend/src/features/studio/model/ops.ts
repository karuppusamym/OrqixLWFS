import { toFieldKey } from '@/lib/text'
import { rewriteRefs } from './fieldRefs'
import { nodeById, outgoing, wouldCreateCycle } from './graph'
import { defaultConfig, KINDS } from './kinds'
import { collides, findFreePosition, GAP_X, GAP_Y, nodeSize, placementFor } from './layout'
import type { Condition, Definition, DecisionTable, Literal, NodeKind, Position, RouteKind, Transition, WorkflowNode } from './types'

/**
 * Pure document operations used by the studio store, the Steps tab and the tests.
 * Every function returns a NEW definition (or the same reference when nothing changed), so reference
 * equality is a valid change check for undo/redo history.
 */

export type OpResult = { ok: true; def: Definition } | { ok: false; reason: string }

const ID_PATTERN = /^(?!.*__)[A-Za-z][A-Za-z0-9_-]{0,63}$/

function clone(def: Definition): Definition {
  return structuredClone(def)
}

function canonical(value: unknown): string {
  return JSON.stringify(value, (_key, v) =>
    v && typeof v === 'object' && !Array.isArray(v) ? Object.fromEntries(Object.entries(v as Record<string, unknown>).sort(([a], [b]) => a.localeCompare(b))) : v,
  )
}

/** Structural equality that ignores object key order. */
export function sameJson(a: unknown, b: unknown): boolean {
  return canonical(a) === canonical(b)
}

export function isValidId(id: string): boolean {
  return ID_PATTERN.test(id)
}

/** Node and transition IDs share one namespace (both become BPMN IDs). */
export function idInUse(def: Definition, id: string): boolean {
  return def.nodes.some((n) => n.id === id) || def.transitions.some((t) => t.id === id)
}

export function uniqueId(def: Definition, base: string): string {
  const clean = base.replace(/[^A-Za-z0-9_-]/g, '')
  const root = /^[A-Za-z]/.test(clean) ? clean : `step${clean}`
  if (!idInUse(def, root)) return root
  for (let i = 1; i < 1000; i++) {
    const candidate = `${root}${i}`
    if (!idInUse(def, candidate)) return candidate
  }
  return `${root}${Date.now()}`
}

function nextTransitionId(def: Definition): string {
  for (let i = def.transitions.length + 1; i < 10_000; i++) {
    const id = `t${i}`
    if (!idInUse(def, id)) return id
  }
  return `t${Date.now()}`
}

// Nodes ---------------------------------------------------------------------------------------------------

/** First numeric request field, used to seed a new rule table. */
function firstNumericInput(def: Definition): string | undefined {
  return Object.entries(def.inputSchema.properties).find(([, f]) => f.type === 'number' || f.type === 'integer')?.[0]
}

/**
 * `opts.position` places the step exactly (a drop on the canvas). Otherwise it goes to the right of `opts.anchorId`
 * (the selected step), or below it when that spot is taken, never on top of another step (see layout.ts).
 */
export function addNode(def: Definition, kind: NodeKind, opts: { label?: string; position?: Position; anchorId?: string | null } = {}): { def: Definition; id: string } {
  const next = clone(def)
  const info = KINDS[kind]
  const id = uniqueId(next, kind === 'http' ? 'callSystem' : kind === 'notification' ? 'notify' : kind)
  const node: WorkflowNode = { id, type: kind, label: opts.label ?? info.defaultLabel, config: defaultConfig(kind) }
  if (kind === 'decision') {
    let key = 'rule'
    for (let i = 1; next.decisions.some((d) => d.key === key); i++) key = `rule-${i}`
    const numeric = firstNumericInput(next)
    const table: DecisionTable = {
      key,
      version: 1,
      hitPolicy: 'UNIQUE',
      rows: numeric ? [{ when: { fieldRef: `/input/${numeric}`, operator: 'gte', value: 1000 }, then: { result: 'review' } }] : [],
      default: { result: 'standard' },
    }
    next.decisions.push(table)
    node.config.decisionRef = key
  }
  next.nodes.push(node)
  next.uiLayout ??= { positions: {} }
  next.uiLayout.positions[id] = opts.position ?? placementFor(def, kind, opts.anchorId)
  return { def: next, id }
}

export function updateNodeLabel(def: Definition, id: string, label: string): Definition {
  const node = nodeById(def, id)
  if (!node || node.label === label) return def
  const next = clone(def)
  ;(nodeById(next, id) as WorkflowNode).label = label
  return next
}

export function updateNodeConfig(def: Definition, id: string, patch: Record<string, unknown>): Definition {
  const node = nodeById(def, id)
  if (!node) return def
  const changed = Object.entries(patch).some(([k, v]) => !sameJson(node.config[k], v))
  if (!changed) return def
  const next = clone(def)
  const target = nodeById(next, id) as WorkflowNode
  for (const [k, v] of Object.entries(patch)) {
    if (v === undefined) delete target.config[k]
    else target.config[k] = v
  }
  return next
}

/** Rename a step ID everywhere it is referenced (transitions, layout, field references). */
export function renameNodeId(def: Definition, oldId: string, newId: string): OpResult {
  if (oldId === newId) return { ok: true, def }
  if (!isValidId(newId)) return { ok: false, reason: 'Use letters, digits, dashes and underscores, starting with a letter. Two underscores in a row are reserved.' }
  if (idInUse(def, newId)) return { ok: false, reason: `The ID "${newId}" is already used by another step or connection.` }
  if (!nodeById(def, oldId)) return { ok: false, reason: 'That step does not exist.' }
  const next = clone(def)
  ;(nodeById(next, oldId) as WorkflowNode).id = newId
  for (const t of next.transitions) {
    if (t.from === oldId) t.from = newId
    if (t.to === oldId) t.to = newId
  }
  for (const n of next.nodes) {
    if (n.type === 'parallelSplit' && n.config.joinRef === oldId) n.config.joinRef = newId
    if (n.type === 'http' && n.config.compensates === oldId) n.config.compensates = newId
    if (n.type === 'approval' && Array.isArray(n.config.separateFrom)) {
      n.config.separateFrom = n.config.separateFrom.map((id: unknown) => id === oldId ? newId : id)
    }
  }
  if (next.stages) next.stages = next.stages.map((stage) => ({ ...stage, nodes: stage.nodes.map((id) => id === oldId ? newId : id) }))
  const positions = next.uiLayout?.positions
  const notes = next.metadata.stepNotes
  if (notes && oldId in notes) {
    notes[newId] = notes[oldId] as string
    delete notes[oldId]
  }
  if (positions && oldId in positions) {
    positions[newId] = positions[oldId] as Position
    delete positions[oldId]
  }
  const prefix = `/steps/${oldId}/`
  rewriteRefs(next, (ref) => (ref.startsWith(prefix) ? `/steps/${newId}/${ref.slice(prefix.length)}` : ref))
  return { ok: true, def: next }
}

export function moveNodes(def: Definition, positions: Record<string, Position>): Definition {
  const current = def.uiLayout?.positions ?? {}
  const moved = Object.entries(positions).filter(([id, p]) => nodeById(def, id) && (current[id]?.x !== p.x || current[id]?.y !== p.y))
  if (moved.length === 0) return def
  const next = clone(def)
  next.uiLayout ??= { positions: {} }
  for (const [id, p] of moved) next.uiLayout.positions[id] = { x: Math.round(p.x), y: Math.round(p.y) }
  return next
}

export interface DeletionPlan {
  node: WorkflowNode
  transitions: Transition[]
  /** Rule tables used only by this step, which are removed with it. */
  decisions: string[]
  /** Other places that point at this step's outputs and will be cleaned up. */
  outputs: string[]
}

export function planNodeDeletion(def: Definition, id: string): DeletionPlan | null {
  const node = nodeById(def, id)
  if (!node || node.type === 'start') return null
  const decisions: string[] = []
  if (node.type === 'decision') {
    const ref = node.config.decisionRef as string | undefined
    if (ref && !def.nodes.some((n) => n.id !== id && n.type === 'decision' && n.config.decisionRef === ref)) decisions.push(ref)
  }
  const outputs: string[] = []
  const prefix = `/steps/${id}/`
  for (const [k, m] of Object.entries(def.outputMapping)) if (m.fieldRef.startsWith(prefix)) outputs.push(`the workflow output "${k}"`)
  return { node, transitions: def.transitions.filter((t) => t.from === id || t.to === id), decisions, outputs }
}

/**
 * Deleting a step removes its transitions, its layout position and any rule table used only by it,
 * so the document never trips WF_LAYOUT_UNKNOWN_NODE or WF_DECISION_UNUSED because of the deletion.
 */
export function removeNode(def: Definition, id: string): Definition {
  const plan = planNodeDeletion(def, id)
  if (!plan) return def
  const next = clone(def)
  next.nodes = next.nodes.filter((n) => n.id !== id)
  if (next.stages) next.stages = next.stages.map((stage) => ({ ...stage, nodes: stage.nodes.filter((nodeId) => nodeId !== id) }))
  next.transitions = next.transitions.filter((t) => t.from !== id && t.to !== id)
  if (next.uiLayout) delete next.uiLayout.positions[id]
  if (next.metadata.stepNotes) {
    delete next.metadata.stepNotes[id]
    if (Object.keys(next.metadata.stepNotes).length === 0) delete next.metadata.stepNotes
  }
  next.decisions = next.decisions.filter((d) => !plan.decisions.includes(d.key))
  const prefix = `/steps/${id}/`
  for (const [k, m] of Object.entries(next.outputMapping)) if (m.fieldRef.startsWith(prefix)) delete next.outputMapping[k]
  if (plan.node.type === 'http') {
    const key = plan.node.config.connectionRef as string | undefined
    if (key && !next.nodes.some((n) => n.type === 'http' && n.config.connectionRef === key)) {
      next.dependencies.connections = next.dependencies.connections.filter((c) => c.key !== key)
    }
  }
  return next
}

// Transitions ---------------------------------------------------------------------------------------------

/** Which route kind a React Flow source handle stands for. */
export function routeForHandle(node: WorkflowNode, handle: string | null | undefined): RouteKind {
  if (node.type === 'approval') return handle === 'timedOut' ? 'timedOut' : handle === 'rejected' ? 'rejected' : 'approved'
  if (node.type === 'http') return handle === 'failed' ? 'failed' : 'next'
  if (node.type === 'waitForMessage') return handle === 'timedOut' ? 'timedOut' : 'next'
  if (node.type === 'branch') return 'condition'
  return 'next'
}

/** The source handle used when displaying an existing route on the canvas. */
export function sourceHandleForRoute(on: RouteKind): string {
  return on === 'condition' || on === 'default' ? 'route' : on
}

export interface ConnectRequest {
  from: string
  to: string
  on: RouteKind
  condition?: Condition
  label?: string
  /** When set, an existing transition is re-pointed instead of a new one being added. */
  transitionId?: string
}

/** Convenience rules for the editor (the server validates and is authoritative): see docs/build/S7-frontend.md section 5. */
export function canConnect(def: Definition, req: Pick<ConnectRequest, 'from' | 'to' | 'on' | 'transitionId'>): { ok: true } | { ok: false; reason: string } {
  const from = nodeById(def, req.from)
  const to = nodeById(def, req.to)
  if (!from || !to) return { ok: false, reason: 'That step does not exist.' }
  if (from.id === to.id) return { ok: false, reason: 'A step cannot lead to itself.' }
  if (to.type === 'start') return { ok: false, reason: 'Nothing can lead back to the request form.' }
  if (from.type === 'end') return { ok: false, reason: 'A Finish step ends the workflow, so it cannot lead anywhere.' }
  const others = outgoing(def, from.id).filter((t) => t.id !== req.transitionId)
  switch (from.type) {
    case 'http':
      if (req.on !== 'next' && req.on !== 'failed') return { ok: false, reason: 'Choose Next or Failed for a system call.' }
      if (others.some((t) => t.on === req.on)) return { ok: false, reason: `This step already has a ${req.on === 'failed' ? 'Failed' : 'Next'} route.` }
      break
    case 'approval':
      if (req.on !== 'approved' && req.on !== 'rejected' && !(req.on === 'timedOut' && from.config.timeoutAfter)) return { ok: false, reason: 'Choose Approved, Rejected, or a configured time limit route.' }
      if (others.some((t) => t.on === req.on)) return { ok: false, reason: `This step already has a ${req.on === 'approved' ? 'Approved' : 'Rejected'} route.` }
      break
    case 'waitForMessage':
      if (req.on !== 'next' && !(req.on === 'timedOut' && from.config.timeoutAfter)) return { ok: false, reason: 'Choose Next, or a configured time limit route.' }
      if (others.some((t) => t.on === req.on)) return { ok: false, reason: `This step already has a ${req.on === 'next' ? 'Next' : 'Timed out'} route.` }
      break
    case 'parallelSplit':
      if (req.on !== 'next') return { ok: false, reason: 'Parallel branches use Next routes.' }
      if (others.some((t) => t.to === to.id)) return { ok: false, reason: 'There is already a branch to that step.' }
      break
    case 'branch':
      if (req.on !== 'condition' && req.on !== 'default') return { ok: false, reason: 'Paths of a choice are conditions or Otherwise.' }
      if (req.on === 'default' && others.some((t) => t.on === 'default')) return { ok: false, reason: 'A choice has exactly one Otherwise route.' }
      if (others.some((t) => t.to === to.id)) return { ok: false, reason: 'There is already a path to that step.' }
      break
    default:
      if (req.on !== 'next') return { ok: false, reason: 'This step has one route to the next step.' }
      if (others.length > 0) return { ok: false, reason: 'This step already leads somewhere. Remove that connection first.' }
  }
  if (wouldCreateCycle(def, from.id, to.id)) return { ok: false, reason: 'That would make the workflow loop back on itself. Loops are not supported yet.' }
  return { ok: true }
}

export function connect(def: Definition, req: ConnectRequest): OpResult & { transitionId?: string } {
  const check = canConnect(def, req)
  if (!check.ok) return check
  const next = clone(def)
  if (req.transitionId) {
    const t = next.transitions.find((x) => x.id === req.transitionId)
    if (!t) return { ok: false, reason: 'That connection does not exist.' }
    t.to = req.to
    if (req.label !== undefined) {
      if (req.label) t.label = req.label
      else delete t.label
    }
    if (req.condition) t.condition = req.condition
    return { ok: true, def: next, transitionId: t.id }
  }
  const t: Transition = { id: nextTransitionId(next), from: req.from, to: req.to, on: req.on }
  if (req.label) t.label = req.label
  if (req.on === 'condition') t.condition = req.condition ?? { fieldRef: '', operator: 'eq', value: '' }
  next.transitions.push(t)
  return { ok: true, def: next, transitionId: t.id }
}

export function removeTransition(def: Definition, id: string): Definition {
  if (!def.transitions.some((t) => t.id === id)) return def
  const next = clone(def)
  next.transitions = next.transitions.filter((t) => t.id !== id)
  return next
}

export function updateTransition(def: Definition, id: string, patch: { label?: string | null; condition?: Condition | null; on?: RouteKind }): Definition {
  const t = def.transitions.find((x) => x.id === id)
  if (!t) return def
  const next = clone(def)
  const target = next.transitions.find((x) => x.id === id) as Transition
  if (patch.label !== undefined) {
    if (patch.label) target.label = patch.label
    else delete target.label
  }
  if (patch.on !== undefined) target.on = patch.on
  if (patch.condition !== undefined) {
    if (patch.condition) target.condition = patch.condition
    else delete target.condition
  }
  if (target.on !== 'condition') delete target.condition
  return sameJson(next.transitions, def.transitions) ? def : next
}

/**
 * Steps tab: set the target of one route slot ("When approved -> [step]"). `null` disconnects it.
 * For paths of a choice, `transitionId` identifies the path.
 */
export function setRoute(def: Definition, fromId: string, on: RouteKind, toId: string | null, transitionId?: string): OpResult {
  const existing = def.transitions.find((t) => (transitionId ? t.id === transitionId : nodeById(def, fromId)?.type !== 'parallelSplit' && t.from === fromId && t.on === on))
  if (toId === null) return { ok: true, def: existing ? removeTransition(def, existing.id) : def }
  if (existing) {
    if (existing.to === toId) return { ok: true, def }
    const result = connect(def, { from: fromId, to: toId, on: existing.on, transitionId: existing.id })
    return result.ok ? { ok: true, def: result.def } : result
  }
  const result = connect(def, { from: fromId, to: toId, on })
  return result.ok ? { ok: true, def: result.def } : result
}

/**
 * Add a new step after `afterId` on a route slot. When the slot already leads to a step, the new step is inserted
 * in between and takes over the old target, so the flow keeps working: A -> N -> B.
 */
export function addNodeAfter(def: Definition, afterId: string, kind: NodeKind, slot: { on: RouteKind; transitionId?: string } | null, opts: { label?: string } = {}): OpResult & { id?: string } {
  const source = nodeById(def, afterId)
  if (!source) return { ok: false, reason: 'That step does not exist.' }
  if (source.type === 'end') return { ok: false, reason: 'A Finish step ends the workflow, so nothing can come after it.' }
  const added = addNode(def, kind, { ...(opts.label ? { label: opts.label } : {}), anchorId: afterId })
  let next = added.def
  const origin = def.uiLayout?.positions[afterId]
  if (origin && next.uiLayout) {
    // Right of the source step (a Rejected route goes one row lower), and never on top of another step.
    const size = nodeSize(source.type)
    const desired = { x: origin.x + size.width + GAP_X, y: origin.y + (slot?.on === 'rejected' ? size.height + GAP_Y : 0) }
    next.uiLayout.positions[added.id] = collides(def, desired, kind) ? findFreePosition(def, desired, kind) : desired
  }
  const on = slot?.on ?? KINDS[source.type].routes[0] ?? 'next'
  const existing = next.transitions.find((t) => (slot?.transitionId ? t.id === slot.transitionId : source.type !== 'parallelSplit' && t.from === afterId && t.on === on))
  if (existing) {
    const oldTarget = existing.to
    const re = connect(next, { from: afterId, to: added.id, on: existing.on, transitionId: existing.id })
    if (!re.ok) return re
    next = re.def
    // The new step takes over the old target on its first route, when it has one.
    const route = KINDS[kind].routes[0]
    if (route && kind !== 'end' && oldTarget !== added.id) {
      const forward = connect(next, { from: added.id, to: oldTarget, on: route === 'condition' ? 'default' : route })
      if (forward.ok) next = forward.def
    }
  } else {
    const result = connect(next, { from: afterId, to: added.id, on: on === 'condition' ? 'condition' : on })
    if (!result.ok) return result
    next = result.def
  }
  return { ok: true, def: next, id: added.id }
}

// Decision tables --------------------------------------------------------------------------------------------

export function upsertDecision(def: Definition, table: DecisionTable): Definition {
  const index = def.decisions.findIndex((d) => d.key === table.key)
  if (index >= 0 && sameJson(def.decisions[index], table)) return def
  const next = clone(def)
  if (index >= 0) next.decisions[index] = table
  else next.decisions.push(table)
  return next
}

/** Change a rule table key and point its decision step at the new key. */
export function renameDecisionKey(def: Definition, oldKey: string, newKey: string): OpResult {
  if (oldKey === newKey) return { ok: true, def }
  if (!/^[a-z][a-z0-9-]{0,62}[a-z0-9]$/.test(newKey)) return { ok: false, reason: 'Use lowercase letters, digits and dashes, starting with a letter (2 or more characters).' }
  if (def.decisions.some((d) => d.key === newKey)) return { ok: false, reason: `A rule table "${newKey}" already exists.` }
  const next = clone(def)
  const table = next.decisions.find((d) => d.key === oldKey)
  if (!table) return { ok: false, reason: 'That rule table does not exist.' }
  table.key = newKey
  for (const n of next.nodes) if (n.type === 'decision' && n.config.decisionRef === oldKey) n.config.decisionRef = newKey
  return { ok: true, def: next }
}

export function literalOf(text: string, type: 'string' | 'number' | 'integer' | 'boolean'): Literal | undefined {
  if (type === 'boolean') return text === 'true' ? true : text === 'false' ? false : undefined
  if (type === 'number' || type === 'integer') return /^-?\d+(\.\d+)?$/.test(text.trim()) ? Number(text.trim()) : undefined
  return text
}

// Request form fields -----------------------------------------------------------------------------------------

export type FieldKind = 'text' | 'longText' | 'number' | 'money' | 'integer' | 'date' | 'yesNo' | 'choice' | 'collection' | 'file'

export const FIELD_KIND_LABELS: Record<FieldKind, string> = {
  text: 'Text',
  longText: 'Long text',
  number: 'Number',
  money: 'Money (USD)',
  integer: 'Whole number',
  date: 'Date',
  yesNo: 'Yes/No',
  choice: 'Choice',
  collection: 'Collection',
  file: 'File upload',
}

export interface FormFieldModel {
  incidentEditable?: boolean | undefined
  key: string
  label: string
  kind: FieldKind
  required: boolean
  help: string
  choices: string[]
  minimum?: number | undefined
  maximum?: number | undefined
  minLength?: number | undefined
  maxLength?: number | undefined
  /** Earliest and latest date (YYYY-MM-DD) of a Date question. */
  minDate?: string | undefined
  maxDate?: string | undefined
  itemSchema?: Definition['inputSchema']['properties'][string] | undefined
  minItems?: number | undefined
  maxItems?: number | undefined
  /** File upload: accepted file types (empty = all allowed types) and the largest file in MB (empty = the platform limit). */
  fileTypes?: string[] | undefined
  maxFileMb?: number | undefined
}

export function fieldsFromDefinition(def: Definition): FormFieldModel[] {
  const order = def.inputUi?.order ?? []
  const keys = Object.keys(def.inputSchema.properties)
  const ordered = [...order.filter((k) => keys.includes(k)), ...keys.filter((k) => !order.includes(k))]
  return ordered.map((key) => {
    const f = def.inputSchema.properties[key]!
    const widget = def.inputUi?.widgets?.[key]
    let kind: FieldKind = 'text'
    if (f.type === 'array') kind = 'collection'
    else if (f.type === 'boolean') kind = 'yesNo'
    else if (f.type === 'string' && f.format === 'date') kind = 'date'
    else if (f.type === 'string' && f.format === 'attachment') kind = 'file'
    else if (f.enum && f.enum.length > 0) kind = 'choice'
    else if (f.type === 'integer') kind = 'integer'
    else if (f.type === 'number') kind = widget === 'money' || f.multipleOf === 0.01 ? 'money' : 'number'
    else if (widget === 'textarea') kind = 'longText'
    return {
      key,
      label: f.title,
      kind,
      required: (def.inputSchema.required ?? []).includes(key),
      help: f.description ?? '',
      incidentEditable: f.incidentEditable === true,
      choices: f.enum ?? [],
      minimum: f.minimum,
      maximum: f.maximum,
      minLength: f.minLength,
      maxLength: f.maxLength,
      minDate: f.formatMinimum, maxDate: f.formatMaximum,
      itemSchema: f.items, minItems: f.minItems, maxItems: f.maxItems,
      fileTypes: f.acceptTypes, maxFileMb: f.maxFileBytes === undefined ? undefined : Math.round((f.maxFileBytes / 1048576) * 10) / 10,
    }
  })
}

/** Build inputSchema and inputUi from the editor's field list. Unsupported keywords are never produced. */
export function applyFields(def: Definition, fields: FormFieldModel[]): Definition {
  const properties: Definition['inputSchema']['properties'] = {}
  const required: string[] = []
  const widgets: Record<string, string> = {}
  for (const f of fields) {
    const schema: Definition['inputSchema']['properties'][string] = { type: 'string', title: f.label || f.key }
    if (f.help.trim()) schema.description = f.help.trim()
    if (f.incidentEditable && f.kind !== 'file' && f.kind !== 'collection') schema.incidentEditable = true
    switch (f.kind) {
      case 'collection':
        schema.type = 'array'
        schema.items = f.itemSchema ?? { type: 'string', title: 'Item', minLength: 1, maxLength: 200 }
        schema.minItems = f.minItems ?? 1
        schema.maxItems = f.maxItems ?? 25
        break
      case 'text':
        schema.maxLength = f.maxLength ?? 200
        if (f.minLength !== undefined) schema.minLength = f.minLength
        break
      case 'longText':
        schema.maxLength = f.maxLength ?? 2000
        if (f.minLength !== undefined) schema.minLength = f.minLength
        widgets[f.key] = 'textarea'
        break
      case 'number':
        schema.type = 'number'
        if (f.minimum !== undefined) schema.minimum = f.minimum
        if (f.maximum !== undefined) schema.maximum = f.maximum
        widgets[f.key] = 'number'
        break
      case 'money':
        schema.type = 'number'
        schema.minimum = f.minimum ?? 0.01
        if (f.maximum !== undefined) schema.maximum = f.maximum
        schema.multipleOf = 0.01
        widgets[f.key] = 'money'
        break
      case 'integer':
        schema.type = 'integer'
        if (f.minimum !== undefined) schema.minimum = f.minimum
        if (f.maximum !== undefined) schema.maximum = f.maximum
        widgets[f.key] = 'number'
        break
      case 'date':
        schema.format = 'date'
        if (f.minDate) schema.formatMinimum = f.minDate
        if (f.maxDate) schema.formatMaximum = f.maxDate
        break
      case 'file':
        schema.format = 'attachment'
        if (f.fileTypes && f.fileTypes.length > 0) schema.acceptTypes = [...f.fileTypes]
        if (f.maxFileMb !== undefined) schema.maxFileBytes = Math.max(1, Math.min(10485760, Math.round(f.maxFileMb * 1048576)))
        break
      case 'yesNo':
        schema.type = 'boolean'
        widgets[f.key] = 'checkbox'
        break
      case 'choice':
        schema.enum = f.choices.filter((c) => c.trim() !== '')
        widgets[f.key] = 'select'
        break
    }
    properties[f.key] = schema
    if (f.required) required.push(f.key)
  }
  const next = clone(def)
  next.inputSchema = { type: 'object', additionalProperties: false, required, properties }
  next.inputUi = { ...next.inputUi, order: fields.map((f) => f.key), widgets, currency: 'USD' }
  for (const name of ['visibleWhen', 'requiredWhen'] as const) if (next.inputUi[name]) next.inputUi[name] = Object.fromEntries(Object.entries(next.inputUi[name]!).filter(([key]) => key in properties))
  if (next.inputUi.layout?.sections) next.inputUi.layout.sections = next.inputUi.layout.sections.map(section => ({ ...section, fields: section.fields.filter(key => key in properties) }))
  return sameJson(next.inputSchema, def.inputSchema) && sameJson(next.inputUi, def.inputUi) ? def : next
}

export function newFieldKey(def: Definition, label: string): string {
  const base = toFieldKey(label)
  if (!(base in def.inputSchema.properties)) return base
  for (let i = 2; i < 1000; i++) if (!((`${base}${i}`) in def.inputSchema.properties)) return `${base}${i}`
  return `${base}${Date.now()}`
}

export function addField(def: Definition, label = 'New question', kind: FieldKind = 'text'): { def: Definition; key: string } {
  const key = newFieldKey(def, label)
  const fields = fieldsFromDefinition(def)
  fields.push({ key, label, kind, required: false, help: '', choices: kind === 'choice' ? ['Option 1', 'Option 2'] : [] })
  return { def: applyFields(def, fields), key }
}

export function renameFieldKey(def: Definition, oldKey: string, newKey: string): OpResult {
  if (oldKey === newKey) return { ok: true, def }
  if (!/^[a-z][A-Za-z0-9]{0,63}$/.test(newKey)) return { ok: false, reason: 'Start with a lowercase letter; use letters and digits only.' }
  if (newKey in def.inputSchema.properties) return { ok: false, reason: `A field with the key "${newKey}" already exists.` }
  const fields = fieldsFromDefinition(def).map((f) => (f.key === oldKey ? { ...f, key: newKey } : f))
  const prepared = clone(def)
  for (const name of ['visibleWhen', 'requiredWhen'] as const) if (prepared.inputUi?.[name]?.[oldKey]) {
    prepared.inputUi[name]![newKey] = prepared.inputUi[name]![oldKey]!
    delete prepared.inputUi[name]![oldKey]
  }
  for (const section of prepared.inputUi?.layout?.sections ?? []) section.fields = section.fields.map(key => key === oldKey ? newKey : key)
  const next = clone(applyFields(prepared, fields))
  rewriteRefs(next, (ref) => (ref === `/input/${oldKey}` || ref.startsWith(`/input/${oldKey}/`) ? `/input/${newKey}${ref.slice(`/input/${oldKey}`.length)}` : ref))
  return { ok: true, def: next }
}

// Dependencies ---------------------------------------------------------------------------------------------------

/** Rename an HTTP result and every consumer together, in one undoable edit. */
export function renameHttpResult(def: Definition, nodeId: string, oldName: string, newName: string): OpResult {
  const node = def.nodes.find((n) => n.id === nodeId && n.type === 'http')
  const mapping = node?.config.resultMapping as Record<string, string> | undefined
  if (!mapping || !(oldName in mapping)) return { ok: false, reason: 'Result field not found.' }
  if (oldName === newName) return { ok: true, def }
  if (!/^[a-z][A-Za-z0-9]{0,63}$/.test(newName)) return { ok: false, reason: 'Start with a lowercase letter; use letters and digits only.' }
  if (newName in mapping) return { ok: false, reason: 'That result name is already in use.' }
  const next = clone(def)
  next.nodes.find((n) => n.id === nodeId)!.config.resultMapping = Object.fromEntries(
    Object.entries(mapping).map(([name, pointer]) => [name === oldName ? newName : name, pointer]),
  )
  rewriteRefs(next, (ref) => ref === `/steps/${nodeId}/${oldName}` ? `/steps/${nodeId}/${newName}` : ref)
  return { ok: true, def: next }
}

/** Keep dependencies.connections in step with the connections the steps use. */
export function syncConnectionDependencies(def: Definition, latestVersions: Record<string, number | undefined>): Definition {
  const used = [...new Set(def.nodes.filter((n) => n.type === 'http').map((n) => n.config.connectionRef as string).filter(Boolean))]
  const current = def.dependencies.connections
  const wanted = used.map((key) => ({ key, definitionVersion: current.find((c) => c.key === key)?.definitionVersion ?? latestVersions[key] ?? 1 }))
  if (sameJson(wanted, current)) return def
  const next = clone(def)
  next.dependencies.connections = wanted
  return next
}

// Choice (branch) paths ---------------------------------------------------------------------------------------------

/**
 * Mark one path of a choice as Otherwise. The previous Otherwise path becomes a normal path with an empty
 * condition on the same field as its siblings, so exactly one Otherwise route always remains.
 */
export function setDefaultRoute(def: Definition, branchId: string, transitionId: string, fallbackFieldRef = ''): Definition {
  const routes = outgoing(def, branchId)
  const chosen = routes.find((t) => t.id === transitionId)
  if (!chosen || chosen.on === 'default') return def
  const next = clone(def)
  const siblingField = routes.find((t) => t.on === 'condition' && t.condition?.fieldRef)?.condition?.fieldRef ?? fallbackFieldRef
  for (const t of next.transitions) {
    if (t.from !== branchId) continue
    if (t.id === transitionId) {
      t.on = 'default'
      delete t.condition
    } else if (t.on === 'default') {
      t.on = 'condition'
      t.condition = { fieldRef: siblingField, operator: 'eq', value: '' }
    }
  }
  return next
}

/** Add a conditional path from a choice step to `to`. Returns the new transition id. */
export function addBranchPath(def: Definition, branchId: string, to: string, condition: Condition): OpResult & { transitionId?: string } {
  return connect(def, { from: branchId, to, on: 'condition', condition })
}
