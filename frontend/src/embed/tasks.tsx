import { TasksPage } from '@/features/tasks/TasksPage'
import { RunDetailPage } from '@/features/runs/RunDetailPage'
import { mountComponent, type EmbedOptions } from './mount'
export function mount(element: HTMLElement, options: EmbedOptions) {
  return mountComponent(element, options, 'tasks', [
    { path: 'tasks', element: <TasksPage /> },
    { path: 'tasks/:taskId', element: <TasksPage /> },
    { path: 'runs/:runId', element: <RunDetailPage /> },
  ])
}
export type { EmbedOptions } from './mount'
