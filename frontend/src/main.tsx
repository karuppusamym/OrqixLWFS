import { StrictMode, Suspense, lazy } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { Providers } from '@/app/providers'
import { routes } from '@/app/routes'
import { applyBrand } from '@/lib/brand'
import './index.css'

// The mock tree is only reachable through this compile-time flag, so normal builds never contain it.
const MockBar = __ORQIX_MOCK__ ? lazy(() => import('@/mocks/MockBar').then((m) => ({ default: m.MockBar }))) : null

async function boot() {
  applyBrand()
  if (__ORQIX_MOCK__) {
    const { startMock } = await import('@/mocks/browser')
    await startMock()
  }
  const router = createBrowserRouter(routes)
  const root = document.getElementById('root')
  if (!root) throw new Error('Missing #root element')
  createRoot(root).render(
    <StrictMode>
      <Providers>
        <RouterProvider router={router} />
        {MockBar ? (
          <Suspense fallback={null}>
            <MockBar />
          </Suspense>
        ) : null}
      </Providers>
    </StrictMode>,
  )
}

void boot()
