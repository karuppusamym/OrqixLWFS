import type { Definition } from './types'

export type DocumentReference = { driveId: string; itemId: string; versionId?: string; snapshotId?: string }
export const validDocumentId = (value: string) => /^[A-Za-z0-9!._~-]{1,200}$/.test(value) && value !== '.' && value !== '..'
export const validDocumentSnapshotId = (value: string) => /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(value)

/** Adds existing scalar enum questions; no new runtime or authoring schema is required. */
export function applyDocumentReference(definition: Definition, nodeId: string, reference: DocumentReference, responseFields: string[]) :
  { ok: true; definition: Definition } | { ok: false; reason: string } {
  const node = definition.nodes.find((candidate) => candidate.id === nodeId)
  if (!node || node.type !== 'http') return { ok: false, reason: 'Select a document system step first.' }
  const values = Object.entries(reference).filter((entry): entry is [string, string] => typeof entry[1] === 'string')
  if (!validDocumentId(reference.driveId) || !validDocumentId(reference.itemId) ||
      Boolean(reference.versionId) === Boolean(reference.snapshotId) || values.some(([, value]) => !validDocumentId(value)) ||
      (reference.snapshotId !== undefined && !validDocumentSnapshotId(reference.snapshotId))) {
    return { ok: false, reason: 'Choose an exact historical version or retained snapshot, with valid drive and document IDs.' }
  }
  const result = structuredClone(definition)
  const target = result.nodes.find((candidate) => candidate.id === nodeId)!
  const mapping = { ...(target.config.inputMapping as Record<string, string> | undefined) }
  for (const [field, value] of values) {
    const current = mapping[field]
    if (current) {
      const key = current.startsWith('/input/') ? current.slice(7) : ''
      const question = result.inputSchema.properties[key]
      if (question?.type !== 'string' || question.enum?.length !== 1 || question.enum[0] !== value || question.format !== undefined ||
          value.length < (question.minLength ?? 0) || value.length > (question.maxLength ?? Infinity)) {
        return { ok: false, reason: `The existing ${field} mapping has a different source. Clear it explicitly before applying this reference.` }
      }
      result.inputSchema.required = [...new Set([...(result.inputSchema.required ?? []), key])]
      continue
    }
    const base = `document${field[0]!.toUpperCase()}${field.slice(1)}`
    let key = base
    for (let suffix = 2; result.inputSchema.properties[key]; suffix++) key = `${base}${suffix}`
    if (Object.keys(result.inputSchema.properties).length >= 30) return { ok: false, reason: 'The request form already has the maximum 30 questions.' }
    result.inputSchema.properties[key] = {
      type: 'string', title: `${node.label}: ${field}`.slice(0, 100), enum: [value],
      description: field === 'versionId' ? 'Approved exact historical version; no latest fallback.' : field === 'snapshotId' ? 'Approved retained snapshot; availability is limited by retention.' : 'Approved document reference.',
    }
    result.inputSchema.required = [...new Set([...(result.inputSchema.required ?? []), key])]
    result.inputUi ??= {}
    result.inputUi.order = [...new Set([...(result.inputUi.order ?? Object.keys(definition.inputSchema.properties)), key])]
    result.inputUi.widgets = { ...result.inputUi.widgets, [key]: 'select' }
    mapping[field] = `/input/${key}`
  }
  target.config.inputMapping = mapping
  const output = { ...(target.config.resultMapping as Record<string, string> | undefined) }
  for (const field of ['driveId', 'itemId', 'versionId', 'snapshotId', 'name', 'eTag', 'contentSha256', 'snapshotExpiresAt', 'pinMode']) {
    const pointer = `/${field}`
    if (!responseFields.includes(field) || Object.values(output).includes(pointer)) continue
    let key = field
    for (let suffix = 2; output[key]; suffix++) key = `${field}${suffix}`
    output[key] = pointer
  }
  target.config.resultMapping = output
  return { ok: true, definition: result }
}
