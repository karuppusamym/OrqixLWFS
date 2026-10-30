import { nodeById } from '@/features/studio/model/graph'
import { collides, findFreePosition, GAP_X, nodeSize, placementFor } from '@/features/studio/model/layout'
import { idInUse, removeNode, removeTransition, sameJson } from '@/features/studio/model/ops'
import { normalizeDefinition, type Condition, type DecisionTable, type Definition, type NodeKind, type RouteKind, type Transition, type WorkflowNode } from '@/features/studio/model/types'
import { applyFormChanges } from './FormAssistant'
import type { AiDraftEditChange, AiDraftEditOperation, AiFormChange } from './api'

/**
 * Applying reviewed conversational edits (STU02, docs/build/S95) to the studio draft. The server validated every change
 * against the stored revision; here each change is applied only when every element it touches is still exactly as it
 * was then (`before`), new ids are still free and the changes it depends on were applied too. A change that does not
 * fit is skipped whole and named. The caller wraps the result in one undoable edit.
 */
export interface DraftEditOutcome {
  def: Definition
  applied: string[]
  skipped: Array<{ id: string; reason: string }>
}

type Op = AiDraftEditOperation

/** Applies one operation to a working copy, or returns why it cannot be applied any more. */
function applyOperation(def: Definition, op: Op): Definition | string {
  switch (op.op) {
    case 'addStep': {
      if (!op.id || !op.kind || idInUse(def, op.id)) return `the id "${op.id ?? ''}" is already used`
      const next = structuredClone(def)
      const node: WorkflowNode = { id: op.id, type: op.kind as NodeKind, label: op.label ?? op.id, config: structuredClone((op.config ?? {}) as Record<string, unknown>) }
      next.nodes.push(node)
      return next
    }
    case 'updateStep':
    case 'renameStep': {
      const node = op.id ? nodeById(def, op.id) : undefined
      if (!node || !sameJson(node, op.before)) return `the step "${op.id ?? ''}" changed since the proposal`
      const next = structuredClone(def)
      const target = nodeById(next, node.id) as WorkflowNode
      if (op.op === 'renameStep') target.label = op.label ?? target.label
      else for (const [key, value] of Object.entries(op.config ?? {})) {
        if (value === null) delete target.config[key]
        else target.config[key] = structuredClone(value)
      }
      return next
    }
    case 'removeStep': {
      const node = op.id ? nodeById(def, op.id) : undefined
      if (!node || !sameJson(node, op.before)) return `the step "${op.id ?? ''}" changed since the proposal`
      return removeNode(def, node.id)
    }
    case 'connect': {
      if (!op.transitionId || idInUse(def, op.transitionId)) return `the connection id "${op.transitionId ?? ''}" is already used`
      if (!op.from || !op.to || !nodeById(def, op.from) || !nodeById(def, op.to)) return 'a step it connects no longer exists'
      const next = structuredClone(def)
      const t: Transition = { id: op.transitionId, from: op.from, to: op.to, on: op.on as RouteKind }
      if (op.label) t.label = op.label
      if (op.on === 'condition' && op.condition) t.condition = structuredClone(op.condition) as unknown as Condition
      next.transitions.push(t)
      return next
    }
    case 'disconnect': {
      const t = def.transitions.find((x) => x.id === op.transitionId)
      if (!t || !sameJson(t, op.before)) return `the connection "${op.transitionId ?? ''}" changed since the proposal`
      return removeTransition(def, t.id)
    }
    case 'changeFormField': {
      const change = { op: op.action, key: op.key, field: op.field, required: op.required ?? false, reason: '', before: op.before, wasRequired: op.wasRequired } as unknown as AiFormChange
      const outcome = applyFormChanges(def, [change])
      return outcome.applied.length === 1 ? outcome.def : `the question "${op.key ?? ''}" changed since the proposal`
    }
    // S124 operations. Each applies only when what it changes is still as it was when the server checked it.
    case 'setFieldRule': {
      const rule = op.rule as 'visibleWhen' | 'requiredWhen' | undefined
      if (!op.key || !rule || !def.inputSchema.properties[op.key]) return `the question "${op.key ?? ''}" no longer exists`
      if (!sameJson(def.inputUi?.[rule]?.[op.key] ?? null, op.before ?? null)) return `the rule of "${op.key}" changed since the proposal`
      const next = structuredClone(def)
      next.inputUi ??= {}
      const rules = { ...(next.inputUi[rule] ?? {}) }
      if (op.condition) rules[op.key] = structuredClone(op.condition) as unknown as Condition
      else delete rules[op.key]
      if (Object.keys(rules).length) next.inputUi[rule] = rules
      else delete next.inputUi[rule]
      return next
    }
    case 'setStepFieldAccess': {
      const node = op.id ? nodeById(def, op.id) : undefined
      if (!node || !sameJson(node, op.before)) return `the step "${op.id ?? ''}" changed since the proposal`
      const next = structuredClone(def)
      const target = nodeById(next, node.id) as WorkflowNode
      for (const [key, value] of Object.entries(op.config ?? {})) {
        if (value === null) delete target.config[key]
        else target.config[key] = structuredClone(value)
      }
      return next
    }
    case 'setFormGroups': {
      if (!sameJson(def.inputUi?.layout?.sections ?? null, op.before ?? null)) return 'the form groups changed since the proposal'
      const next = structuredClone(def)
      next.inputUi ??= {}
      const groups = (op.groups ?? []) as NonNullable<NonNullable<Definition['inputUi']>['layout']>['sections']
      if (groups && groups.length) next.inputUi.layout = { ...next.inputUi.layout, sections: structuredClone(groups) }
      else if (next.inputUi.layout) {
        delete next.inputUi.layout.sections
        if (Object.keys(next.inputUi.layout).length === 0) delete next.inputUi.layout
      }
      return next
    }
    case 'addDecision': {
      const table = op.table as unknown as DecisionTable | undefined
      if (!op.id || idInUse(def, op.id)) return `the id "${op.id ?? ''}" is already used`
      if (!table || def.decisions.some((d) => d.key === table.key)) return `a rule table "${table?.key ?? ''}" already exists`
      const next = structuredClone(def)
      next.decisions.push(structuredClone(table))
      next.nodes.push({ id: op.id, type: 'decision', label: op.label ?? op.id, config: { decisionRef: table.key } })
      return next
    }
    case 'addCall': {
      if (!op.id || idInUse(def, op.id)) return `the id "${op.id ?? ''}" is already used`
      const config = structuredClone((op.config ?? {}) as Record<string, unknown>)
      const key = config.connectionRef as string | undefined
      if (!key) return 'the call names no connection'
      const next = structuredClone(def)
      next.nodes.push({ id: op.id, type: 'http', label: op.label ?? op.id, config })
      if (!next.dependencies.connections.some((c) => c.key === key)) next.dependencies.connections.push({ key, definitionVersion: op.definitionVersion ?? 1 })
      return next
    }
  }
}

/** Places steps added by a change: right of the step that leads to them, never on top of another step. */
function placeAdded(def: Definition, ids: string[]): Definition {
  if (ids.length === 0) return def
  const next = structuredClone(def)
  next.uiLayout ??= { positions: {} }
  for (const id of ids) {
    const node = nodeById(next, id)
    if (!node) continue
    const incoming = next.transitions.find((t) => t.to === id && next.uiLayout?.positions[t.from])
    const source = incoming ? nodeById(next, incoming.from) : undefined
    const origin = incoming ? next.uiLayout.positions[incoming.from] : undefined
    let position = placementFor(next, node.type, null)
    if (source && origin) {
      const desired = { x: origin.x + nodeSize(source.type).width + GAP_X, y: origin.y }
      position = collides(next, desired, node.type) ? findFreePosition(next, desired, node.type) : desired
    }
    next.uiLayout.positions[id] = position
  }
  return next
}

/** Applies the chosen changes in proposal order. */
export function applyDraftChanges(def: Definition, changes: AiDraftEditChange[], chosen: ReadonlySet<string>): DraftEditOutcome {
  let current = def
  const applied: string[] = []
  const skipped: Array<{ id: string; reason: string }> = []
  for (const change of changes) {
    if (!chosen.has(change.id)) continue
    const missing = change.dependsOn.filter((d) => !applied.includes(d))
    if (missing.length > 0) {
      skipped.push({ id: change.id, reason: `it needs change ${missing.join(', ')}, which was not applied` })
      continue
    }
    let working = current
    let problem: string | null = null
    const added: string[] = []
    for (const op of change.operations) {
      const result = applyOperation(working, op)
      if (typeof result === 'string') { problem = result; break }
      working = result
      if ((op.op === 'addStep' || op.op === 'addDecision' || op.op === 'addCall') && op.id) added.push(op.id)
    }
    if (problem) {
      skipped.push({ id: change.id, reason: problem })
      continue
    }
    current = placeAdded(working, added)
    applied.push(change.id)
  }
  return { def: applied.length > 0 ? current : def, applied, skipped }
}

/**
 * A regeneration replaces the steps, routes, rule tables and layout with the proposed draft. It applies only to the
 * draft it was proposed for (`baseline`, the document when the request was sent); the workflow key and name stay.
 */
export function applyRegeneration(def: Definition, baseline: Definition, proposed: Record<string, unknown>): Definition | null {
  if (!sameJson(def, baseline)) return null
  const next = normalizeDefinition(proposed)
  next.workflowKey = def.workflowKey
  next.name = def.name
  return next
}
