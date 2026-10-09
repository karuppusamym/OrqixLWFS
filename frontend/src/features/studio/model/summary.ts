import { chipFor, fieldInfo, type ConnectionLookup } from './fieldRefs'
import { nodeById } from './graph'
import { KINDS, ROUTE_LABEL } from './kinds'
import type { Condition, Definition, Operator, Transition, WorkflowNode } from './types'

export const OPERATOR_LABEL: Record<Operator, string> = { eq: 'is', gte: 'at least', lt: 'less than' }

/** "Request → Amount (USD) is at least 5000" */
export function describeCondition(def: Definition, c: Condition, lookup?: ConnectionLookup): string {
  return `${chipFor(def, c.fieldRef, lookup)} ${OPERATOR_LABEL[c.operator]} ${String(c.value)}`
}

/** Short route text used on edges and in the Steps tab. */
export function routeText(def: Definition, t: Transition, lookup?: ConnectionLookup): string {
  switch (t.on) {
    case 'approved':
      return 'Approved'
    case 'rejected':
      return 'Rejected'
    case 'default':
      return t.label ? `Otherwise (${t.label})` : 'Otherwise'
    case 'condition':
      return t.label || (t.condition && t.condition.fieldRef ? `When ${describeCondition(def, t.condition, lookup)}` : 'When (condition not set)')
    case 'next':
      return t.label ?? ''
  }
}

/** Label used in a route select: "When approved", "Otherwise", "Next". */
export function routeSlotLabel(def: Definition, t: Transition | undefined, on: Transition['on'], lookup?: ConnectionLookup): string {
  if (on === 'approved') return 'When approved'
  if (on === 'rejected') return 'When rejected'
  if (on === 'default') return 'Otherwise'
  if (on === 'condition' && t) return t.label ? `When ${t.label}` : t.condition?.fieldRef ? `When ${describeCondition(def, t.condition, lookup)}` : 'When (condition not set)'
  return ROUTE_LABEL[on]
}

/** One-line subtitle for a node card. */
export function nodeSubtitle(def: Definition, node: WorkflowNode, groupNames: Record<string, string> = {}): string {
  const c = node.config
  switch (node.type) {
    case 'start': {
      const count = Object.keys(def.inputSchema.properties).length
      return `${count} ${count === 1 ? 'question' : 'questions'}`
    }
    case 'approval': {
      const group = c.candidateGroupRef as string | undefined
      return group ? `${groupNames[group] ?? group}` : 'No approver group yet'
    }
    case 'decision': {
      const table = def.decisions.find((d) => d.key === c.decisionRef)
      const ref = table?.rows[0]?.when.fieldRef
      return ref ? `Based on ${fieldInfo(def, ref)?.label ?? ref}` : 'Rule table'
    }
    case 'branch':
      return `${def.transitions.filter((t) => t.from === node.id).length} paths`
    case 'http':
      return c.operation ? `${c.connectionRef as string} · ${c.operation as string}` : 'No operation chosen'
    case 'notification':
      return (c.message as string) || 'No message yet'
    case 'end':
      return `${(c.businessOutcome as string) || 'No outcome'}${c.outcomeKind === 'rejected' ? ' (rejected)' : ''}`
  }
}

/** Accessible name of a node, e.g. "Approval: Review request, 2 issues". */
export function nodeAriaLabel(node: WorkflowNode, issueCount: number): string {
  const issues = issueCount === 0 ? '' : `, ${issueCount} ${issueCount === 1 ? 'issue' : 'issues'}`
  return `${KINDS[node.type].caption}: ${node.label}${issues}`
}

export function targetLabel(def: Definition, id: string): string {
  return nodeById(def, id)?.label ?? id
}
