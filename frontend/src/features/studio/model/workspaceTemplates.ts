import { KINDS } from './kinds'
import { addNode } from './ops'
import type { Definition, NodeKind, WorkflowNode } from './types'

/**
 * Workspace step templates in Studio (CG-S03, docs/build/S141). A template is a saved step: its type, label and
 * configuration. Adding one creates a new step of that type with a copy of the configuration; references it holds
 * (request fields, groups, connections) are checked by the normal validation of this workflow.
 */
export interface TemplateStep {
  type: string
  label?: string
  description?: string
  config?: Record<string, unknown>
}

/** Step kinds a template can hold: every palette kind except start and end. */
export function templateKind(type: string): NodeKind | null {
  return type in KINDS && type !== 'start' && type !== 'end' ? (type as NodeKind) : null
}

export function addTemplateStep(def: Definition, step: TemplateStep, opts: { anchorId?: string | null } = {}): { def: Definition; id: string } | null {
  const kind = templateKind(step.type)
  if (!kind) return null
  const added = addNode(def, kind, { label: step.label, anchorId: opts.anchorId ?? null })
  const node = added.def.nodes.find((n) => n.id === added.id) as WorkflowNode
  node.config = JSON.parse(JSON.stringify(step.config ?? {})) as Record<string, unknown>
  return added
}

/** What a template keeps of a configured step: no id, position or routes. */
export function templateOf(node: WorkflowNode): TemplateStep {
  return { type: node.type, label: node.label, config: JSON.parse(JSON.stringify(node.config ?? {})) as Record<string, unknown> }
}

/** A template key suggested from a step label: lowercase words joined by dashes, 2-64 characters. */
export function suggestKey(label: string): string {
  const base = label.toLowerCase().normalize('NFKD').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60)
  const key = /^[a-z]/.test(base) ? base : `step-${base}`
  return key.length >= 2 ? key.replace(/-+$/g, '') : 'step-template'
}
