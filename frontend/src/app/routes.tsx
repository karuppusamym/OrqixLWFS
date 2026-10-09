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
          {
            path: 'migrations',
            lazy: async () => ({ Component: (await import('@/features/migration/MigrationPage')).MigrationPage }),
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
