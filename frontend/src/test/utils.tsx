import { QueryClient } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import type { ReactElement } from 'react'
import { createMemoryRouter, RouterProvider, type RouteObject } from 'react-router'
import { Providers } from '@/app/providers'
import { routes } from '@/app/routes'
import { setCurrentUser } from '@/mocks/handlers'
import { seedDb, type SeedOptions } from '@/mocks/seed'
import { db, WS_PURCHASING } from '@/mocks/state'

export function testQueryClient(): QueryClient {
  return new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 }, mutations: { retry: false } } })
}

/** Reset the mock backend and choose who is signed in. */
export function signInAs(username: string, options?: SeedOptions): void {
  seedDb(options)
  db.latencyMs = 0
  setCurrentUser(username)
}

/** Render the real application routes in memory at `path`. */
export function renderApp(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const client = testQueryClient()
  const utils = render(
    <Providers client={client}>
      <RouterProvider router={router} />
    </Providers>,
  )
  return { ...utils, router, client }
}

/** Render one element inside the workspace route tree (so useWorkspace works). */
export function renderInWorkspace(element: ReactElement, path = `/w/${WS_PURCHASING}/test`) {
  const testRoutes: RouteObject[] = [
    {
      path: '/w/:workspaceId',
      element: routes.find((r) => r.path === '/w/:workspaceId')?.element,
      children: [{ path: '*', element }],
    },
  ]
  const router = createMemoryRouter(testRoutes, { initialEntries: [path] })
  const client = testQueryClient()
  const utils = render(
    <Providers client={client}>
      <RouterProvider router={router} />
    </Providers>,
  )
  return { ...utils, router, client }
}

export const WS = WS_PURCHASING
