import { StudioPage } from '@/features/studio/StudioPage'
import { WorkflowsPage } from '@/features/workflows/WorkflowsPage'
import { WorkflowOverviewPage } from '@/features/workflows/WorkflowOverviewPage'
import { PublishReviewPage } from '@/features/workflows/PublishReviewPage'
import { mountComponent, type EmbedOptions } from './mount'
export function mount(element: HTMLElement, options: EmbedOptions) {
  return mountComponent(element, options, options.workflowId ? `workflows/${options.workflowId}/edit` : 'workflows', [
    { path: 'workflows', element: <WorkflowsPage /> },
    { path: 'workflows/:workflowId/edit', element: <StudioPage /> },
    { path: 'workflows/:workflowId/publish', element: <PublishReviewPage /> },
    { path: 'workflows/:workflowId', element: <WorkflowOverviewPage /> },
  ])
}
export type { EmbedOptions } from './mount'
