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
          { path: 'case-designs', lazy: async () => ({ Component: (await import('@/features/cases/CaseDesignsPage')).CaseDesignsPage }) },
          { path: 'case-designs/:draftId', handle: { fullBleed: true }, lazy: async () => ({ Component: (await import('@/features/cases/CaseDesignerPage')).CaseDesignerPage }) },
          // S146 part D: the AI skills Studio over the S134 AiSkills API.
          { path: 'ai-skills', lazy: async () => ({ Component: (await import('@/features/aiskills/AiSkillsPage')).AiSkillsPage }) },
          { path: 'ai-skills/:skillKey', lazy: async () => ({ Component: (await import('@/features/aiskills/AiSkillDetailPage')).AiSkillDetailPage }) },
          { path: 'ai-review', lazy: async () => ({ Component: (await import('@/features/aiskills/AiReviewPage')).AiReviewPage }) },
          { path: 'ai-policy', lazy: async () => ({ Component: (await import('@/features/aiskills/AiPolicyPage')).AiPolicyPage }) },
          { path: 'decisions', lazy: async () => ({ Component: (await import('@/features/decisions/DecisionsPage')).DecisionsPage }) },
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
            path: 'agents',
            lazy: async () => ({ Component: (await import('@/features/agenttools/AgentToolsPage')).AgentToolsPage }),
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
  // CG-F05 (docs/build/S140): the guest response page; no sign-in, the link token is in the URL fragment.
  { path: '/guest/:workspaceId', errorElement: <RouteErrorPage />, lazy: async () => ({ Component: (await import('@/features/guest/GuestResponsePage')).GuestResponsePage }) },
  // S141 (CG-T01): the signed action link of an actionable task email; no workspace shell and no sign-in.
  { path: '/act/:token', errorElement: <RouteErrorPage />, lazy: async () => ({ Component: (await import('@/features/tasks/TaskActionLinkPage')).TaskActionLinkPage }) },
  { path: '/forbidden', element: <ForbiddenPage />, errorElement: <RouteErrorPage /> },
  { path: '/not-found', element: <NotFoundPage /> },
  { path: '*', element: <NotFoundPage /> },
]
