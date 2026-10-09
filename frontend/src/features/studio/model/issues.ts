import type { ValidationIssue } from '@/api/types'
import { startNode } from './graph'
import type { Definition } from './types'

export interface IssueLocation {
  /** The step the issue belongs to, or null for whole-workflow issues. */
  nodeId: string | null
  /** Inspector field to focus, matching a `data-field` attribute, e.g. "config.candidateGroupRef". */
  fieldKey: string | null
  transitionId: string | null
}

/** Map a validation issue (JSON Pointer `path`, optional ids) to the step and inspector field to open. */
export function locateIssue(def: Definition, issue: Pick<ValidationIssue, 'path' | 'nodeId' | 'transitionId'>): IssueLocation {
  const parts = issue.path.split('/').filter(Boolean)
  let nodeId = issue.nodeId ?? null
  let fieldKey: string | null = null
  let transitionId = issue.transitionId ?? null

  const [root, a, ...rest] = parts
  switch (root) {
    case 'nodes': {
      const node = def.nodes[Number(a)]
      if (node) {
        nodeId ??= node.id
        const tail = rest.join('.')
        fieldKey = tail || null
      }
      break
    }
    case 'transitions': {
      const t = def.transitions[Number(a)]
      if (t) {
        nodeId ??= t.from
        transitionId ??= t.id
        fieldKey = `route.${t.id}${rest.length ? `.${rest.join('.')}` : ''}`
      }
      break
    }
    case 'decisions': {
      const table = def.decisions[Number(a)]
      if (table) {
        nodeId ??= def.nodes.find((n) => n.type === 'decision' && n.config.decisionRef === table.key)?.id ?? null
        const [section, index, ...tail] = rest
        fieldKey = section === 'rows' ? `rows.${index}${tail.length ? `.${tail.join('.')}` : ''}` : 'table'
      }
      break
    }
    case 'inputSchema':
    case 'inputUi': {
      nodeId ??= startNode(def)?.id ?? null
      fieldKey = a === 'properties' && rest[0] ? `field.${rest[0]}` : null
      break
    }
    default:
      break
  }
  if (!nodeId && issue.transitionId) nodeId = def.transitions.find((t) => t.id === issue.transitionId)?.from ?? null
  return { nodeId, fieldKey, transitionId }
}

/** Issue counts per step id (steps without issues are absent). */
export function countByNode(def: Definition, issues: ValidationIssue[]): Record<string, { blockers: number; warnings: number }> {
  const result: Record<string, { blockers: number; warnings: number }> = {}
  for (const issue of issues) {
    const id = locateIssue(def, issue).nodeId
    if (!id) continue
    const entry = (result[id] ??= { blockers: 0, warnings: 0 })
    if (issue.severity === 'blocker') entry.blockers++
    else entry.warnings++
  }
  return result
}
