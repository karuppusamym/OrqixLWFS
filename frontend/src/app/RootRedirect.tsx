import { Navigate } from 'react-router'
import { useMe } from '@/api/queries'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { EmptyState } from '@/components/common/EmptyState'
import { PageHeader } from '@/components/common/PageHeader'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { UserX } from 'lucide-react'
import { readLastWorkspace } from '@/lib/storage'
import { isProblemError } from '@/api/problem'

/** "/" -> last used workspace (if still a member) or the first workspace from /me. */
export function RootRedirect() {
  const meQuery = useMe()
  if (meQuery.isPending) return <LoadingBlock label="Signing you in" className="mx-auto max-w-3xl p-8" />
  if (meQuery.isError) {
    if (isProblemError(meQuery.error) && meQuery.error.status === 404) {
      return (
        <div className="mx-auto max-w-3xl p-8">
          <PageHeader title="Portal is online" description="Your sign-in succeeded, but the workspace service is not available yet." />
          <Alert tone="warning" title="Workspace API not found">
            <p>The current backend does not provide the identity endpoint the portal needs to load your account and workspaces.</p>
            <Button variant="secondary" size="sm" className="mt-3" onClick={() => void meQuery.refetch()}>
              Try again
            </Button>
          </Alert>
        </div>
      )
    }
    return <ProblemAlert error={meQuery.error} onRetry={() => void meQuery.refetch()} className="mx-auto mt-8 max-w-3xl" />
  }
  const workspaces = meQuery.data.workspaces
  const last = readLastWorkspace()
  const target = workspaces.find((w) => w.id === last) ?? workspaces[0]
  if (!target) {
    return (
      <main id="main" className="mx-auto max-w-xl p-8">
        <PageHeader title="No workspace yet" />
        <EmptyState icon={UserX} title={`You are signed in as ${meQuery.data.subject.displayName}, but you are not a member of any workspace.`}>
          Ask a workspace administrator to add you.
        </EmptyState>
      </main>
    )
  }
  return <Navigate to={`/w/${target.id}`} replace />
}
