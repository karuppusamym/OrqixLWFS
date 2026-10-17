import type { ConnectionDetail } from '@/api/types'
import type { FieldType } from './model/fieldRefs'

/** A pinned definition must never silently resolve to another version. */
export function connectionDefinition(detail: ConnectionDetail | undefined, version: number | undefined) {
  if (version !== undefined) return detail?.definitions.find((d) => d.version === version)
  return detail?.definitions.reduce<(NonNullable<ConnectionDetail>['definitions'])[number] | undefined>(
    (latest, definition) => !latest || definition.version > latest.version ? definition : latest, undefined,
  )
}

interface Schema {
  type?: string
  properties?: Record<string, Schema>
}

/** Resolve object-property JSON Pointers, including escaped property names. */
export function responsePointerType(schema: Schema | undefined, pointer: string): FieldType | undefined {
  if (!pointer.startsWith('/') || /~(?:[^01]|$)/.test(pointer)) return undefined
  let field = schema
  for (const segment of pointer.slice(1).split('/')) {
    field = field?.properties?.[segment.replace(/~1/g, '/').replace(/~0/g, '~')]
  }
  const type = field?.type
  return type === 'string' || type === 'number' || type === 'integer' || type === 'boolean' ? type : undefined
}
