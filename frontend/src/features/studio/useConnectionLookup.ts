import { useQueries } from '@tanstack/react-query'
import { useMemo } from 'react'
import * as endpoints from '@/api/endpoints'
import { keys } from '@/api/queries'
import type { ConnectionDetail } from '@/api/types'
import type { ConnectionLookup, FieldType } from './model/fieldRefs'
import { normalizeDefinition } from './model/types'
import { connectionDefinition, responsePointerType } from './connectionSchema'

type Ops = Record<string, { responseSchema?: { properties?: Record<string, { type?: string }> } }>

/**
 * Loads the definitions of the connections a workflow uses, so field chips know the type of each result
 * field (for example that "procurementId" is text). Missing data simply means "string".
 */
export function useConnectionLookup(ws: string, connectionKeys: string[], enabled: boolean, workflowKeys: string[] = []): ConnectionLookup {
  const details = useQueries({
    queries: connectionKeys.map((key) => ({ queryKey: keys.connection(ws, key), queryFn: () => endpoints.getConnection(ws, key), enabled, staleTime: 30_000 })),
    combine: (results) => results.map((r) => r.data as ConnectionDetail | undefined),
  })
  const signature = connectionKeys.join(',')
  const children = useQueries({
    queries: workflowKeys.map((key) => ({
      queryKey: ['ws', ws, 'child-output-schema', key],
      queryFn: async () => {
        let cursor: string | undefined
        do {
          const page = await endpoints.listWorkflows(ws, { q: key, limit: 100, ...(cursor ? { cursor } : {}) })
          const workflow = page.items.find((w) => w.key === key)
          if (workflow?.activeRelease) {
            const release = await endpoints.getRelease(ws, workflow.id, workflow.activeRelease.id)
            return normalizeDefinition(release.definition).outputSchema
          }
          cursor = page.nextCursor ?? undefined
        } while (cursor)
        return null
      },
      staleTime: 30_000,
    })),
    combine: (results) => results.map((r) => r.data),
  })
  const childSignature = workflowKeys.join(',')
  return useMemo<ConnectionLookup>(() => {
    const byKey = new Map<string, ConnectionDetail | undefined>()
    connectionKeys.forEach((k, i) => byKey.set(k, details[i]))
    return {
      responseType(connectionKey, version, operation, pointer) {
        const detail = byKey.get(connectionKey)
        const record = connectionDefinition(detail, version)
        const ops = record?.definition.operations as Ops | undefined
        return responsePointerType(ops?.[operation]?.responseSchema, pointer)
      },
      responseSchema(connectionKey, version, operation) {
        const record = connectionDefinition(byKey.get(connectionKey), version)
        const schema = (record?.definition.operations as Record<string, { responseSchema?: unknown }> | undefined)?.[operation]?.responseSchema
        return schema && typeof schema === 'object' ? (schema as Record<string, unknown>) : undefined
      },
      workflowOutputType(workflowKey, output) {
        return children[workflowKeys.indexOf(workflowKey)]?.properties[output]?.type as FieldType | undefined
      },
    }
    // `details` is structurally shared by TanStack Query, so it only changes when data does.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signature, details, childSignature, children])
}
