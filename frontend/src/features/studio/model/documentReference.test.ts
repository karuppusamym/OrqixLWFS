import { describe, expect, it } from 'vitest'
import Ajv2020 from 'ajv/dist/2020'
import workflowSchema from '../../../../../contracts/workflow-v0.schema.json'
import documentExample from '../../../../../examples/document-review.json'
import authoredExample from '../../../../../examples/document-authoring-review.json'
import { normalizeDefinition } from './types'
import { applyDocumentReference } from './documentReference'

const reference = { driveId: 'drive', itemId: 'item', versionId: '1.0' }
const template = () => {
  const definition = normalizeDefinition(documentExample)
  const node = definition.nodes.find((candidate) => candidate.type === 'http')!
  node.config.inputMapping = {}
  return { definition, node }
}

describe('exact document request questions and mappings', () => {
  it('matches the committed compiler fixture produced by the authoring helper', () => {
    const { definition, node } = template()
    definition.workflowKey = 'document-authoring-review'
    definition.name = 'Review an approved historical document reference'
    definition.metadata.description = 'Studio document authoring uses exact constrained request choices, stable version mapping and safe runtime provenance.'
    definition.inputSchema.properties = {}
    definition.inputSchema.required = []
    definition.inputUi = { order: [], widgets: {} }
    definition.dependencies.connections[0]!.definitionVersion = 3
    const result = applyDocumentReference(definition, node.id, reference, ['driveId', 'itemId', 'versionId', 'name', 'eTag', 'contentSha256'])
    expect(result.ok).toBe(true)
    if (result.ok) expect(result.definition).toEqual(authoredExample)
  })
  it('creates schema-valid single-choice required inputs and preserves safe provenance mappings', () => {
    const { definition, node } = template()
    const old = structuredClone(definition)
    const result = applyDocumentReference(definition, node.id, reference, ['name', 'versionId', 'contentSha256', 'driveId', 'itemId'])
    expect(result.ok).toBe(true)
    if (!result.ok) return
    const validate = new Ajv2020({ strict: false, validateFormats: false }).compile(workflowSchema)
    expect(validate(result.definition), JSON.stringify(validate.errors)).toBe(true)
    const mapping = result.definition.nodes.find((candidate) => candidate.id === node.id)!.config.inputMapping as Record<string, string>
    for (const [field, value] of Object.entries(reference)) {
      const key = mapping[field]!.slice(7)
      expect(result.definition.inputSchema.properties[key]?.enum).toEqual([value])
      expect(result.definition.inputSchema.required).toContain(key)
      expect(result.definition.inputUi?.widgets?.[key]).toBe('select')
    }
    expect(result.definition.nodes.find((candidate) => candidate.id === node.id)!.config.resultMapping).toMatchObject(node.config.resultMapping as Record<string, string>)
    expect(definition).toEqual(old)
  })

  it('reuses exact compatible questions without duplicating them and refuses conflicting mappings atomically', () => {
    const { definition, node } = template()
    const first = applyDocumentReference(definition, node.id, reference, [])
    if (!first.ok) throw new Error(first.reason)
    const replay = applyDocumentReference(first.definition, node.id, reference, [])
    expect(replay.ok).toBe(true)
    if (replay.ok) expect(replay.definition).toEqual(first.definition)
    const before = structuredClone(first.definition)
    expect(applyDocumentReference(first.definition, node.id, { ...reference, versionId: '2.0' }, []).ok).toBe(false)
    expect(first.definition).toEqual(before)
    node.config.inputMapping = { driveId: '/input/driveId' }
    expect(applyDocumentReference(definition, node.id, reference, []).ok).toBe(false)
  })

  it('does not overwrite existing questions on name collisions or exceed form limits', () => {
    const { definition, node } = template()
    definition.inputSchema.properties.documentDriveId = { type: 'integer', title: 'Existing unrelated question' }
    const result = applyDocumentReference(definition, node.id, reference, [])
    expect(result.ok).toBe(true)
    if (result.ok) {
      expect(result.definition.inputSchema.properties.documentDriveId?.type).toBe('integer')
      expect((result.definition.nodes.find((candidate) => candidate.id === node.id)!.config.inputMapping as Record<string, string>).driveId).toBe('/input/documentDriveId2')
    }
    while (Object.keys(definition.inputSchema.properties).length < 30) definition.inputSchema.properties[`field${Object.keys(definition.inputSchema.properties).length}`] = { type: 'string', title: 'Other' }
    expect(applyDocumentReference(definition, node.id, reference, []).ok).toBe(false)
  })

  it('accepts one retained snapshot instead of a version and rejects invalid or mixed pin types', () => {
    const { definition, node } = template()
    expect(applyDocumentReference(definition, node.id, { driveId: 'drive', itemId: 'item', snapshotId: '12345678-1234-1234-1234-123456789abc' }, []).ok).toBe(true)
    expect(applyDocumentReference(definition, node.id, { ...reference, snapshotId: '12345678-1234-1234-1234-123456789abc' }, []).ok).toBe(false)
    expect(applyDocumentReference(definition, node.id, { ...reference, itemId: '../other' }, []).ok).toBe(false)
    expect(applyDocumentReference(definition, node.id, { driveId: 'drive', itemId: 'item', snapshotId: 'not-a-snapshot' }, []).ok).toBe(false)
  })
})
