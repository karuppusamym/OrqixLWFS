import type { RouteObject } from 'react-router'
import { ForbiddenPage, NotFoundPage, RouteErrorPage } from './ErrorPages'
import { RootRedirect } from './RootRedirect'
import { Shell } from './Shell'
import { WorkspaceRoute } from './workspace'
import { HomePage } from '@/features/home/HomePage'
import { MyRequestsPage } from '@/features/requests/MyRequestsPage'
import { RunsPage } from '@/features/runs/RunsPage'
import { StartRequestPage } from '@/features/start/StartRequestPage'
import { TasksPage } from '@/features/tasks/TasksPage'
import { RunDetailPage } from '@/features/runs/RunDetailPage'

/**
 * Route map (docs/build/S7-frontend.md section 2). Heavy screens (workflows, studio, settings) load on demand
 * so the request and task screens stay small.
 */
export const routes: RouteObject[] = [
  { path: '/', element: <RootRedirect />, errorElement: <RouteErrorPage /> },
  {
    path: '/w/:workspaceId',
    element: <WorkspaceRoute />,
    errorElement: <RouteErrorPage />,
    children: [
      {
        element: <Shell />,
        children: [
          { index: true, element: <HomePage /> },
          { path: 'start/:workflowId', element: <StartRequestPage /> },
          { path: 'requests', element: <MyRequestsPage /> },
          { path: 'tasks', element: <TasksPage /> },
          { path: 'tasks/:taskId', element: <TasksPage /> },
          { path: 'runs', element: <RunsPage /> },
          { path: 'runs/:runId', element: <RunDetailPage /> },
          { path: 'settings/connections/:connectionKey/oauth/callback', lazy: async () => ({ Component: (await import('@/features/settings/OAuthCallbackPage')).OAuthCallbackPage }) },
          { path: 'cases', lazy: async () => ({ Component: (await import('@/features/cases/CasesPage')).CasesPage }) },
          { path: 'cases/:instanceId', lazy: async () => ({ Component: (await import('@/features/cases/CaseDetailPage')).CaseDetailPage }) },
          { path: 'views', lazy: async () => ({ Component: (await import('@/features/views/ViewLibraryPage')).ViewLibraryPage }) },
          { path: 'views/:viewId', lazy: async () => ({ Component: (await import('@/features/views/ViewEditorPage')).ViewEditorPage }) },
          { path: 'extensions', lazy: async () => ({ Component: (await import('@/features/extensions/ExtensionsPage')).ExtensionsPage }) },
          { path: 'apps', lazy: async () => ({ Component: (await import('@/features/pages/AppPageRoute')).AppsPage }) },
          { path: 'apps/:slug', lazy: async () => ({ Component: (await import('@/features/pages/AppPageRoute')).AppPageRoute }) },
          { path: 'apps/:slug/:context', lazy: async () => ({ Component: (await import('@/features/pages/AppPageRoute')).AppPageRoute }) },
          { path: 'insights', lazy: async () => ({ Component: (await import('@/features/insights/InsightsPage')).InsightsPage }) },
          { path: 'assistant', lazy: async () => ({ Component: (await import('@/features/conversation/ConversationPage')).ConversationPage }) },
          {
            path: 'migrations',
            lazy: async () => ({ Component: (await import('@/features/migration/MigrationPage')).MigrationPage }),
          },
          {
            path: 'migrations/projects/:projectId',
            lazy: async () => ({ Component: (await import('@/features/migration/ProjectPage')).ProjectPage }),
          },
          {
            path: 'migrations/:migrationId',
            lazy: async () => ({ Component: (await import('@/features/migration/MigrationPage')).MigrationPage }),
          },
          {
            path: 'workflows',
            lazy: async () => ({ Component: (await import('@/features/workflows/WorkflowsPage')).WorkflowsPage }),
          },
          {
            path: 'workflows/:workflowId',
            lazy: async () => ({ Component: (await import('@/features/workflows/WorkflowOverviewPage')).WorkflowOverviewPage }),
          },
          {
            path: 'workflows/:workflowId/edit',
            handle: { fullBleed: true },
            lazy: async () => ({ Component: (await import('@/features/studio/StudioPage')).StudioPage }),
          },
          {
            path: 'workflows/:workflowId/publish',
            lazy: async () => ({ Component: (await import('@/features/workflows/PublishReviewPage')).PublishReviewPage }),
          },
          {
            path: 'workflows/:workflowId/releases/:releaseId/promote',
            lazy: async () => ({ Component: (await import('@/features/promotion/PromotePage')).PromotePage }),
          },
          {
            path: 'agent-writes',
            lazy: async () => ({ Component: (await import('@/features/agentwrites/AgentWriteToolsPage')).AgentWriteToolsPage }),
          },
          {
            path: 'promotions',
            lazy: async () => ({ Component: (await import('@/features/promotion/PromotionRequestsPage')).PromotionRequestsPage }),
          },
          {
            path: 'settings',
            lazy: async () => ({ Component: (await import('@/features/settings/SettingsPage')).SettingsPage }),
          },
          { path: '*', element: <NotFoundPage embedded /> },
        ],
      },
    ],
  },
  { path: '/forbidden', element: <ForbiddenPage />, errorElement: <RouteErrorPage /> },
  { path: '/not-found', element: <NotFoundPage /> },
  { path: '*', element: <NotFoundPage /> },
]
