import { Link } from 'react-router'
import { useWorkspace } from '@/app/workspace'
import { PageHeader } from '@/components/common/PageHeader'
import { Alert } from '@/components/ui/alert'
import { SharedAssetsPanel } from '@/features/settings/SharedAssetsPanel'

/**
 * Decisions in the Design area: the shared DMN decision tables workflows reuse, beside Workflows and Case types,
 * instead of only under Settings. A decision used by one workflow is still edited in that workflow's decision step.
 */
export function DecisionsPage() {
  const { workspace, can } = useWorkspace()
  if (!can('workflow.read')) return <Alert tone="warning">You need permission to read workflows to see decisions.</Alert>
  return <>
    <PageHeader title="Decisions" description="Shared decision tables (DMN) that workflows reuse. Import a DMN table here, or share one from a workflow's decision step; each workflow keeps the version it uses until you update it."
      actions={<Link className="text-link underline" to={`/w/${workspace.id}/workflows`}>Go to Workflows</Link>} />
    <SharedAssetsPanel only="decision" />
  </>
}
