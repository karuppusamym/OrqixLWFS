import { createRoot } from 'react-dom/client'
import { createMemoryRouter, type RouteObject } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { Providers } from '@/app/providers'
import { WorkspaceRoute } from '@/app/workspace'
import '@/index.css'

export interface EmbedOptions {
  apiVersion: 'v1'
  workspaceId: string
  workflowId?: string
  onNavigate?: (path: string) => void
}
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i
export function mountComponent(element: HTMLElement, options: EmbedOptions, initial: string, children: RouteObject[]) {
  if (options.apiVersion !== 'v1') throw new Error('Unsupported API contract. This component requires v1.')
  if (!uuid.test(options.workspaceId) || options.workflowId && !uuid.test(options.workflowId)) throw new Error('A valid workspace/workflow ID is required.')
  const prefix = `/w/${options.workspaceId}/`
  const router = createMemoryRouter([{ path: '/w/:workspaceId', element: <WorkspaceRoute />, children },
    { path: '*', element: <p role="alert">This destination is unavailable in the embedded component.</p> }], { initialEntries: [prefix + initial] })
  const root = createRoot(element)
  const unsubscribe = router.subscribe(state => {
    const path = state.location.pathname + state.location.search
    options.onNavigate?.(path)
    element.dispatchEvent(new CustomEvent('orqix:navigate', { detail: { path, workspaceId: options.workspaceId }, bubbles: true }))
  })
  root.render(<Providers><RouterProvider router={router} /></Providers>)
  return {
    navigate(path: string) {
      if (!path.startsWith(prefix) || path.includes('..') || path.includes('\\')) throw new Error('Embedded navigation must remain in the selected workspace.')
      return router.navigate(path)
    },
    unmount() { unsubscribe(); root.unmount(); router.dispose() },
  }
}
