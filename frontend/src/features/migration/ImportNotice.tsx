import { useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router'
import { getMigration } from '@/api/endpoints'
import { useWorkspace } from '@/app/workspace'
import { Alert } from '@/components/ui/alert'
import { QueryError } from '@/components/common/QueryError'

export function ImportNotice({ workflowId }: { workflowId: string }) {
  const { workspace, can } = useWorkspace()
  const [params] = useSearchParams()
  const id = params.get('migration')
  const query = useQuery({ queryKey: ['ws', workspace.id, 'migration', id],
    queryFn: () => getMigration(workspace.id, id!), enabled: !!id && can('workflow.read'), retry: false })
  if (query.isError && !query.data) return <div className="m-3 space-y-2">
    <QueryError error={query.error} onRetry={() => void query.refetch()} what="the source compatibility report" />
    <Link className="text-sm underline" to={`/w/${workspace.id}/migrations/${id}`}>View compatibility report</Link>
  </div>
  if (query.data?.convertedWorkflowId !== workflowId) return null
  return <Alert tone="warning" live="off" className="m-3">Imported from {query.data.report.sourceSystem}. Review the source mappings and test behavior before publishing. <Link className="underline" to={`/w/${workspace.id}/migrations/${id}`}>View compatibility report</Link></Alert>
}
