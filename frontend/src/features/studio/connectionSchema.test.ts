import { describe, expect, it } from 'vitest'
import type { ConnectionDetail } from '@/api/types'
import { connectionDefinition, responsePointerType } from './connectionSchema'

describe('versioned connector schemas', () => {
  const detail = { definitions: [{ version: 3 }, { version: 1 }, { version: 2 }] } as ConnectionDetail
  it('uses the exact pinned version, even when a newer definition exists', () => {
    expect(connectionDefinition(detail, 1)?.version).toBe(1)
    expect(connectionDefinition(detail, 4)).toBeUndefined()
    expect(connectionDefinition(detail, undefined)?.version).toBe(3)
  })
  it('resolves nested and escaped JSON pointers without mistaking the parent for the result', () => {
    const schema = { properties: { order: { type: 'object', properties: { total: { type: 'number' } } },
      'a/b~c': { type: 'boolean' } } }
    expect(responsePointerType(schema, '/order/total')).toBe('number')
    expect(responsePointerType(schema, '/a~1b~0c')).toBe('boolean')
    expect(responsePointerType(schema, '/order')).toBeUndefined()
    expect(responsePointerType(schema, '/order/missing')).toBeUndefined()
    expect(responsePointerType(schema, 'order/total')).toBeUndefined()
    expect(responsePointerType(schema, '/a~2b')).toBeUndefined()
  })
})
