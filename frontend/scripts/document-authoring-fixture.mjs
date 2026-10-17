import { readFileSync, writeFileSync } from 'node:fs'
import { applyDocumentReference } from '../src/features/studio/model/documentReference.ts'
import { normalizeDefinition } from '../src/features/studio/model/types.ts'

const definition = normalizeDefinition(JSON.parse(readFileSync(new URL('../../examples/document-review.json', import.meta.url), 'utf8')))
definition.workflowKey = 'document-authoring-review'
definition.name = 'Review an approved historical document reference'
definition.metadata.description = 'Studio document authoring uses exact constrained request choices, stable version mapping and safe runtime provenance.'
definition.inputSchema.properties = {}
definition.inputSchema.required = []
definition.inputUi = { order: [], widgets: {} }
definition.dependencies.connections[0].definitionVersion = 3
const node = definition.nodes.find((candidate) => candidate.type === 'http')
node.config.inputMapping = {}
const result = applyDocumentReference(definition, node.id, { driveId: 'drive', itemId: 'item', versionId: '1.0' }, ['driveId', 'itemId', 'versionId', 'name', 'eTag', 'contentSha256'])
if (!result.ok) throw new Error(result.reason)
writeFileSync(new URL('../../examples/document-authoring-review.json', import.meta.url), `${JSON.stringify(result.definition, null, 2)}\n`)
