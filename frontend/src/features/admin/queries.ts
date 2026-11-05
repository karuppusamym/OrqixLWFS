import { useInfiniteQuery } from '@tanstack/react-query'
import * as endpoints from '@/api/endpoints'

/** Platform administration lists are not per workspace, so their keys live outside ['ws', id, ...]. */
export const adminKeys = {
  organizations: ['admin', 'organizations'] as const,
  workspaces: ['admin', 'workspaces'] as const,
}

const PAGE = 50

export function useAdminOrganizations(enabled: boolean) {
  return useInfiniteQuery({
    queryKey: adminKeys.organizations,
    queryFn: ({ pageParam }) => endpoints.listOrganizations({ limit: PAGE, ...(pageParam ? { cursor: pageParam } : {}) }),
    initialPageParam: '',
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled,
  })
}

export function useAdminWorkspaces(enabled: boolean) {
  return useInfiniteQuery({
    queryKey: adminKeys.workspaces,
    queryFn: ({ pageParam }) => endpoints.listWorkspacesAdmin({ limit: PAGE, ...(pageParam ? { cursor: pageParam } : {}) }),
    initialPageParam: '',
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled,
  })
}
