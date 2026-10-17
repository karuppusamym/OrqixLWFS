import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { api, call } from '@/api/client'
import type { components } from '@/api/schema'
import { POLL } from '@/api/queries'

type S = components['schemas']

export type RunPath = S['RunPath']
export type PathNode = S['PathNode']
export type PathTransition = S['PathTransition']
export type PathVisit = S['PathVisit']
export type PathCurrent = S['PathCurrent']

/** getRunPath: the pinned release's graph, the steps passed and the current steps (docs/build/S7-frontend.md section 4). */
export const getRunPath = (ws: string, runId: string) =>
  call(api.GET('/workspaces/{workspaceId}/runs/{runId}/path', { params: { path: { workspaceId: ws, runId } } }))

/**
 * The path follows the run: the key carries the run's version and update time, so it is fetched again whenever the run
 * detail changed (the events poll refetches the run detail when something happens). Nothing is fetched until the
 * diagram is opened.
 */
export function useRunPath(ws: string, runId: string, stamp: string, enabled: boolean) {
  return useQuery({
    queryKey: ['ws', ws, 'run', runId, 'path', stamp] as const,
    queryFn: () => getRunPath(ws, runId),
    enabled,
    placeholderData: keepPreviousData,
    staleTime: POLL.waitingEventsMs,
    retry: 1,
  })
}
