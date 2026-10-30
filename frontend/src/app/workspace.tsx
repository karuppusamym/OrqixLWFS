import { createContext, useContext, useEffect, useMemo, type ReactNode } from 'react'
import { Navigate, Outlet, useParams } from 'react-router'
import type { Me, Permission, WorkspaceFeature, WorkspaceMembership } from '@/api/types'
import { useMe } from '@/api/queries'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { writeLastWorkspace } from '@/lib/storage'

export interface WorkspaceContextValue {
  me: Me
  workspace: WorkspaceMembership
  permissions: ReadonlySet<Permission>
  /** True when the caller holds at least one of the permissions. Hiding UI is convenience; the server enforces. */
  can: (...permissions: Permission[]) => boolean
  /** False when a workspace administrator switched the module off (S127). Hiding is convenience; the server enforces. */
  isOn: (feature: WorkspaceFeature) => boolean
}

const WorkspaceContext = createContext<WorkspaceContextValue | null>(null)

export function createWorkspaceValue(me: Me, workspace: WorkspaceMembership): WorkspaceContextValue {
  const permissions = new Set<Permission>(workspace.permissions)
  // Older servers and fixtures without the field: everything is on.
  const off = new Set<WorkspaceFeature>(workspace.disabledFeatures ?? [])
  return {
    me,
    workspace,
    permissions,
    can: (...wanted) => wanted.some((p) => permissions.has(p)),
    isOn: (feature) => !off.has(feature),
  }
}

export function WorkspaceProvider({ value, children }: { value: WorkspaceContextValue; children: ReactNode }) {
  return <WorkspaceContext.Provider value={value}>{children}</WorkspaceContext.Provider>
}

export function useWorkspace(): WorkspaceContextValue {
  const ctx = useContext(WorkspaceContext)
  if (!ctx) throw new Error('useWorkspace must be used inside a workspace route')
  return ctx
}

/** True inside a workspace route (components that are also rendered stand-alone use it to skip workspace features). */
export function useInWorkspace(): boolean {
  return useContext(WorkspaceContext) !== null
}

/** The workspace context when inside a workspace route, otherwise null (for per-person preferences outside one). */
export function useOptionalWorkspace(): WorkspaceContextValue | null {
  return useContext(WorkspaceContext)
}

/** The workspace id of the current route. */
export function useWsId(): string {
  return useWorkspace().workspace.id
}

/** Route element for /w/:workspaceId: resolves the workspace from /me and provides it to children. */
export function WorkspaceRoute() {
  const { workspaceId } = useParams()
  const meQuery = useMe()
  const me = meQuery.data
  const workspace = me?.workspaces.find((w) => w.id === workspaceId)
  const value = useMemo(() => (me && workspace ? createWorkspaceValue(me, workspace) : null), [me, workspace])

  useEffect(() => {
    if (workspace) writeLastWorkspace(workspace.id)
  }, [workspace])

  if (meQuery.isPending) return <main id="main"><LoadingBlock label="Loading your workspace" className="mx-auto max-w-3xl p-8" /></main>
  if (meQuery.isError) return <main id="main"><ProblemAlert error={meQuery.error} onRetry={() => void meQuery.refetch()} className="mx-auto mt-8 max-w-3xl" /></main>
  // Non-members get "not found" for everything under a workspace (docs/build/S3-api.md section 3).
  if (!value) return <Navigate to="/not-found" replace />
  return (
    <WorkspaceProvider value={value}>
      <Outlet />
    </WorkspaceProvider>
  )
}
