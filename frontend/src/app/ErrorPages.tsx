import { FileQuestion, ShieldAlert } from 'lucide-react'
import type { ReactNode } from 'react'
import { isRouteErrorResponse, Link, useRouteError } from 'react-router'
import { isProblemError } from '@/api/problem'
import { EmptyState } from '@/components/common/EmptyState'
import { PageHeader } from '@/components/common/PageHeader'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { readLastWorkspace } from '@/lib/storage'

function homeLink(): string {
  const last = readLastWorkspace()
  return last ? `/w/${last}` : '/'
}

/** Standalone pages are their own main landmark; embedded ones already sit inside the shell's main. */
function Frame({ embedded, children }: { embedded?: boolean | undefined; children: ReactNode }) {
  return embedded ? <div className="mx-auto max-w-xl py-8">{children}</div> : <main id="main" className="mx-auto max-w-xl p-8">{children}</main>
}

export function NotFoundPage({ embedded }: { embedded?: boolean }) {
  return (
    <Frame embedded={embedded}>
      <PageHeader title="Page not found" />
      <EmptyState icon={FileQuestion} title="This page doesn't exist or you don't have access to it." action={<Link to={homeLink()}>Go to home</Link>}>
        Check the address, or ask the owner of the workspace to give you access.
      </EmptyState>
    </Frame>
  )
}

export function ForbiddenPage({ embedded }: { embedded?: boolean }) {
  return (
    <Frame embedded={embedded}>
      <PageHeader title="No access" />
      <EmptyState icon={ShieldAlert} title="You don't have permission to open this page." action={<Link to={homeLink()}>Go to home</Link>}>
        Ask a workspace administrator if you need access.
      </EmptyState>
    </Frame>
  )
}

/** Route-level error boundary: renders instead of a white screen. */
export function RouteErrorPage() {
  const error = useRouteError()
  if (isRouteErrorResponse(error) && error.status === 404) return <NotFoundPage />
  if (isProblemError(error) && error.code === 'NOT_FOUND') return <NotFoundPage />
  if (isProblemError(error) && (error.code === 'FORBIDDEN' || error.code === 'NOT_ELIGIBLE')) return <ForbiddenPage />
  return (
    <Frame>
      <PageHeader title="Something went wrong" />
      <ProblemAlert error={error} onRetry={() => window.location.reload()} />
    </Frame>
  )
}
