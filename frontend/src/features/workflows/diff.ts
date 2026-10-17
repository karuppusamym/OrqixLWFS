import { sameJson } from '@/features/studio/model/ops'
import { KINDS } from '@/features/studio/model/kinds'
import { OPERATOR_LABEL } from '@/features/studio/model/summary'
import { isAtomicCondition, type Condition, type DecisionRow, type Definition, type Transition, type WorkflowNode } from '@/features/studio/model/types'
import { humanize } from '@/lib/text'

/**
 * Client-side diff of two canonical workflow documents (docs/build/S7-frontend.md section 5, Publish review).
 * `uiLayout` is ignored: positions never affect behavior. The server remains the authority on what is published.
 */
export type ChangeKind = 'added' | 'removed' | 'changed'
export type ChangeArea = 'step' | 'route' | 'rule' | 'form' | 'settings'

export interface Change {
  kind: ChangeKind
  area: ChangeArea
  title: string
  /** Extra lines, for example "Row 1: ... -> ...". */
  details: string[]
}

function show(value: unknown): string {
  if (value === undefined || value === null || value === '') return '(empty)'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function describeRow(row: DecisionRow): string {
  const outputs = Object.entries(row.then)
    .map(([k, v]) => `${k} = ${String(v)}`)
    .join(', ')
  return `${describeCondition(row.when)} → ${outputs}`
}

function describeCondition(condition: Condition | undefined): string {
  if (!condition) return '(none)'
  if (isAtomicCondition(condition)) return `${condition.fieldRef} ${OPERATOR_LABEL[condition.operator]} ${show(condition.value)}`
  const join = condition.all ? ' AND ' : ' OR '
  return `(${(condition.all ?? condition.any ?? []).map(describeCondition).join(join)})`
}

function stepName(def: Definition, id: string): string {
  return def.nodes.find((n) => n.id === id)?.label ?? id
}

function routeName(def: Definition, t: Transition): string {
  const on = t.on === 'next' ? '' : t.on === 'default' ? ' (otherwise)' : t.on === 'condition' ? ' (condition)' : ` (${t.on})`
  return `${stepName(def, t.from)} → ${stepName(def, t.to)}${on}`
}

function diffConfig(before: WorkflowNode, after: WorkflowNode): string[] {
  const lines: string[] = []
  const keys = new Set([...Object.keys(before.config), ...Object.keys(after.config)])
  for (const k of keys) {
    if (sameJson(before.config[k], after.config[k])) continue
    lines.push(`${humanize(k)}: ${show(before.config[k])} → ${show(after.config[k])}`)
  }
  return lines
}

export function diffDefinitions(before: Definition | null, after: Definition): Change[] {
  const changes: Change[] = []
  if (!before) {
    return [{ kind: 'added', area: 'settings', title: 'First version: every step, route and rule is new', details: [`${after.nodes.length} steps, ${after.transitions.length} routes, ${after.decisions.length} rule tables`] }]
  }

  // Steps
  for (const n of after.nodes) {
    const old = before.nodes.find((x) => x.id === n.id)
    if (!old) {
      changes.push({ kind: 'added', area: 'step', title: `Step added: ${n.label} (${KINDS[n.type].caption})`, details: [] })
      continue
    }
    const details: string[] = []
    if (old.label !== n.label) details.push(`Name: ${old.label} → ${n.label}`)
    if (old.type !== n.type) details.push(`Type: ${old.type} → ${n.type}`)
    details.push(...diffConfig(old, n))
    const oldNote = before.metadata.stepNotes?.[n.id] ?? ''
    const newNote = after.metadata.stepNotes?.[n.id] ?? ''
    if (oldNote !== newNote) details.push(`Step notes: ${show(oldNote)} → ${show(newNote)}`)
    if (details.length > 0) changes.push({ kind: 'changed', area: 'step', title: `Step changed: ${n.label}`, details })
  }
  for (const o of before.nodes) {
    if (!after.nodes.some((n) => n.id === o.id)) changes.push({ kind: 'removed', area: 'step', title: `Step removed: ${o.label} (${KINDS[o.type].caption})`, details: [] })
  }

  // Routes
  for (const t of after.transitions) {
    const old = before.transitions.find((x) => x.id === t.id)
    if (!old) {
      changes.push({ kind: 'added', area: 'route', title: `Route added: ${routeName(after, t)}`, details: [] })
      continue
    }
    const details: string[] = []
    if (old.from !== t.from || old.to !== t.to) details.push(`Leads to ${stepName(before, old.to)} → ${stepName(after, t.to)}`)
    if (old.on !== t.on) details.push(`Kind: ${old.on} → ${t.on}`)
    if (!sameJson(old.condition, t.condition)) details.push(`Condition: ${describeCondition(old.condition)} → ${describeCondition(t.condition)}`)
    if ((old.label ?? '') !== (t.label ?? '')) details.push(`Label: ${show(old.label)} → ${show(t.label)}`)
    if (details.length > 0) changes.push({ kind: 'changed', area: 'route', title: `Route changed: ${routeName(after, t)}`, details })
  }
  for (const o of before.transitions) {
    if (!after.transitions.some((t) => t.id === o.id)) changes.push({ kind: 'removed', area: 'route', title: `Route removed: ${routeName(before, o)}`, details: [] })
  }

  // Rule tables
  for (const d of after.decisions) {
    const old = before.decisions.find((x) => x.key === d.key)
    if (!old) {
      changes.push({ kind: 'added', area: 'rule', title: `Rule table added: ${d.key} (version ${d.version})`, details: d.rows.map((r, i) => `Row ${i + 1}: ${describeRow(r)}`) })
      continue
    }
    const details: string[] = []
    if (old.version !== d.version) details.push(`Version: ${old.version} → ${d.version}`)
    if (old.hitPolicy !== d.hitPolicy) details.push(`Hit policy: ${old.hitPolicy} → ${d.hitPolicy}`)
    const rows = Math.max(old.rows.length, d.rows.length)
    for (let i = 0; i < rows; i++) {
      const a = old.rows[i]
      const b = d.rows[i]
      if (a && b && sameJson(a, b)) continue
      details.push(a && b ? `Row ${i + 1}: ${describeRow(a)} → ${describeRow(b)}` : b ? `Row ${i + 1} added: ${describeRow(b)}` : `Row ${i + 1} removed: ${describeRow(a as DecisionRow)}`)
    }
    if (!sameJson(old.default, d.default)) details.push(`Otherwise: ${show(old.default)} → ${show(d.default)}`)
    if (details.length > 0) changes.push({ kind: 'changed', area: 'rule', title: `Rule table changed: ${d.key}`, details })
  }
  for (const o of before.decisions) {
    if (!after.decisions.some((d) => d.key === o.key)) changes.push({ kind: 'removed', area: 'rule', title: `Rule table removed: ${o.key}`, details: [] })
  }

  // Request form
  const bProps = before.inputSchema.properties
  const aProps = after.inputSchema.properties
  for (const [key, f] of Object.entries(aProps)) {
    const old = bProps[key]
    if (!old) {
      changes.push({ kind: 'added', area: 'form', title: `Question added: ${f.title}`, details: [] })
      continue
    }
    const details: string[] = []
    const keys = new Set([...Object.keys(old), ...Object.keys(f)])
    for (const k of keys) {
      const av = (old as unknown as Record<string, unknown>)[k]
      const bv = (f as unknown as Record<string, unknown>)[k]
      if (!sameJson(av, bv)) details.push(`${humanize(k)}: ${show(av)} → ${show(bv)}`)
    }
    const wasRequired = (before.inputSchema.required ?? []).includes(key)
    const isRequired = (after.inputSchema.required ?? []).includes(key)
    if (wasRequired !== isRequired) details.push(`Required: ${wasRequired ? 'yes' : 'no'} → ${isRequired ? 'yes' : 'no'}`)
    if (!sameJson(before.inputUi?.widgets?.[key], after.inputUi?.widgets?.[key])) details.push(`Appearance: ${show(before.inputUi?.widgets?.[key])} → ${show(after.inputUi?.widgets?.[key])}`)
    if (details.length > 0) changes.push({ kind: 'changed', area: 'form', title: `Question changed: ${f.title}`, details })
  }
  for (const [key, f] of Object.entries(bProps)) if (!(key in aProps)) changes.push({ kind: 'removed', area: 'form', title: `Question removed: ${f.title}`, details: [] })
  if (!sameJson(before.inputUi?.order, after.inputUi?.order) && changes.every((c) => c.area !== 'form' || c.kind === 'changed')) {
    const orderBefore = (before.inputUi?.order ?? []).filter((k) => k in aProps)
    const orderAfter = (after.inputUi?.order ?? []).filter((k) => k in bProps)
    if (!sameJson(orderBefore, orderAfter)) changes.push({ kind: 'changed', area: 'form', title: 'Question order changed', details: [] })
  }

  // Settings
  const settings: Array<[string, unknown, unknown]> = [
    ['Workflow key', before.workflowKey, after.workflowKey],
    ['Triggers', before.triggers, after.triggers],
    ['Name', before.name, after.name],
    ['Description', before.metadata.description, after.metadata.description],
    ['Tags', before.metadata.tags, after.metadata.tags],
    ['Currency display', before.inputUi?.currency, after.inputUi?.currency],
    ['Dependencies', before.dependencies, after.dependencies],
    ['Output schema', before.outputSchema, after.outputSchema],
    ['Open requests at once', before.execution.maxConcurrentRuns, after.execution.maxConcurrentRuns],
    ['Attempts per call to another system', before.execution.maxAttempts, after.execution.maxAttempts],
    ['Seconds between attempts', before.execution.retryDelaySeconds, after.execution.retryDelaySeconds],
    ['Self-approval', before.security.selfApproval, after.security.selfApproval],
    ['Workflow output', before.outputMapping, after.outputMapping],
  ]
  for (const [title, a, b] of settings) if (!sameJson(a, b)) changes.push({ kind: 'changed', area: 'settings', title: `${title} changed`, details: [`${show(a)} → ${show(b)}`] })

  return changes
}
