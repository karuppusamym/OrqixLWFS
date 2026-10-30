import { cfg, type Definition, type FieldSchemaDoc, type FormSchemaDoc, type WorkflowNode } from './types'

/**
 * Step forms (docs/build/S123): how each human step shows the request fields, and the fields it fills in itself.
 * `correctable` (S42) keeps working and reads as `editable`.
 */
export type FieldAccess = 'hidden' | 'readOnly' | 'editable' | 'required'

export const ACCESS_LABELS: Record<FieldAccess, string> = {
  hidden: 'Hidden',
  readOnly: 'Read only',
  editable: 'Can change',
  required: 'Must fill in',
}

/** Names the step itself records; a captured field cannot use them. */
export const RESERVED_CAPTURE_KEYS = ['decision', 'reason', 'comment', 'decidedBy', 'decidedAt', 'approvedCount', 'itemDecisions']
export const MAX_CAPTURED = 10

/** Request fields a step may change: single values only, never lists, rows or files (the S42 rule). */
export function changeable(field: FieldSchemaDoc | undefined): boolean {
  return !!field && ['string', 'number', 'integer', 'boolean'].includes(field.type) && field.format !== 'attachment'
}

/** Human steps that can have a step form: one person decides (not several approvers, not one task per list item). */
export function stepFormSteps(definition: Definition): WorkflowNode[] {
  return definition.nodes.filter((n) => n.type === 'approval')
}

export function stepFormBlocked(node: WorkflowNode): string | null {
  if (cfg(node, 'multiInstance')) return 'Several people approve this step, so it cannot have a step form.'
  if (cfg(node, 'perItem')) return 'This step opens one task per list item, so it cannot have a step form.'
  return null
}

export function accessOf(node: WorkflowNode, key: string): FieldAccess {
  const access = cfg<Record<string, FieldAccess>>(node, 'fieldAccess')?.[key]
  if (access) return access
  return (cfg<string[]>(node, 'correctable') ?? []).includes(key) ? 'editable' : 'readOnly'
}

/**
 * The config patch that sets one field's access. The setting moves to `fieldAccess`, and the field leaves the older
 * `correctable` list so there is one place that says what the step does with it.
 */
export function accessPatch(node: WorkflowNode, key: string, access: FieldAccess): Record<string, unknown> {
  const current = { ...(cfg<Record<string, FieldAccess>>(node, 'fieldAccess') ?? {}) }
  if (access === 'readOnly') delete current[key]
  else current[key] = access
  const correctable = (cfg<string[]>(node, 'correctable') ?? []).filter((k) => k !== key)
  return {
    fieldAccess: Object.keys(current).length ? current : undefined,
    correctable: correctable.length ? correctable : undefined,
  }
}

export function captureOf(node: WorkflowNode): FormSchemaDoc | undefined {
  const capture = cfg<FormSchemaDoc>(node, 'capture')
  return capture && Object.keys(capture.properties ?? {}).length ? capture : undefined
}

/** A field key from a label: "PO number" → "poNumber", unique among `taken`. */
export function captureKey(label: string, taken: string[]): string {
  const words = label.normalize('NFKD').replace(/[^A-Za-z0-9 ]/g, ' ').trim().split(/\s+/).filter(Boolean)
  let key = words.map((w, i) => (i === 0 ? w.toLowerCase() : w[0]!.toUpperCase() + w.slice(1).toLowerCase())).join('')
  if (!/^[a-z]/.test(key)) key = `field${key}`
  key = key.slice(0, 60)
  let candidate = key
  for (let n = 2; taken.includes(candidate) || RESERVED_CAPTURE_KEYS.includes(candidate); n++) candidate = `${key}${n}`
  return candidate
}

/** The config patch for a new capture schema; an empty one removes the setting. */
export function capturePatch(properties: Record<string, FieldSchemaDoc>, required: string[]): Record<string, unknown> {
  const keys = Object.keys(properties)
  if (keys.length === 0) return { capture: undefined }
  const req = required.filter((k) => keys.includes(k))
  return { capture: { type: 'object', additionalProperties: false, ...(req.length ? { required: req } : {}), properties } }
}

/** One line for a step's form: "Hides 1 · 2 can change · asks for PO number". Empty when the step has no form. */
export function stepFormSummary(definition: Definition, node: WorkflowNode): string {
  const keys = Object.keys(definition.inputSchema.properties)
  const counts = { hidden: 0, editable: 0, required: 0 }
  for (const key of keys) {
    const access = accessOf(node, key)
    if (access !== 'readOnly') counts[access]++
  }
  const parts: string[] = []
  if (counts.hidden) parts.push(`hides ${counts.hidden}`)
  if (counts.editable) parts.push(`${counts.editable} can change`)
  if (counts.required) parts.push(`${counts.required} must be filled in`)
  const capture = captureOf(node)
  if (capture) parts.push(`asks for ${Object.values(capture.properties).map((f) => f.title).join(', ')}`)
  if (!parts.length) return ''
  const text = parts.join(' · ')
  return text[0]!.toUpperCase() + text.slice(1)
}
