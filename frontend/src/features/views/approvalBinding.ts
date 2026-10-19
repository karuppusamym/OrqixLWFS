import type { Definition } from '@/features/studio/model/types'
import { validateView, type ViewDefinition } from './model'
import { compatible } from './requestBinding'

export interface ApprovalViewBinding { id: string; version: number; definition: ViewDefinition }

/** Copy presentation into the release; never change inputs, eligibility, routing or correction rights. */
export function bindApprovalView(workflow: Definition, nodeId: string, id: string, version: number, view: ViewDefinition): Definition {
  const invalid = validateView(view)
  if (invalid.length) throw new Error(invalid[0])
  if (view.kind !== 'approval') throw new Error('Choose an approval view.')
  const actions = view.actions.map(action => action.type)
  if (!actions.includes('approve') || !actions.includes('reject') || new Set(actions).size !== actions.length || actions.some(type => !['approve', 'reject', 'navigate'].includes(type))) throw new Error('Include one Approve and one Reject action, with at most one navigation action. Request correction actions are not supported.')
  const fields = view.sections.flatMap(section => section.fields)
  const keys = fields.map(field => field.binding.slice(8))
  if (keys.length !== Object.keys(workflow.inputSchema.properties).length || new Set(keys).size !== keys.length) throw new Error('The approval view must show every request field exactly once.')
  for (const field of fields) {
    const key = field.binding.slice(8), schema = workflow.inputSchema.properties[key]
    if (!field.binding.startsWith('request.') || !schema || !compatible(field, schema) || (workflow.inputSchema.required ?? []).includes(key) !== (field.required === true)) throw new Error(`The binding, type or required flag for ${field.label} does not match the request. Task comments and corrections use the task page controls.`)
  }
  const next = structuredClone(workflow)
  const node = next.nodes.find(item => item.id === nodeId && item.type === 'approval')
  if (!node) throw new Error('The approval step no longer exists.')
  node.config.viewBinding = { id, version, definition: structuredClone(view) } satisfies ApprovalViewBinding
  return next
}
