import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockPackages, resetPackageMocks, sampleDetail, seedPackageMocks } from '@/mocks/connectorPackages'
import { ConnectorPackagesPanel } from './ConnectorPackagesPanel'

const permission = vi.hoisted(() => ({ granted: new Set(['connector.manage', 'connector.approve', 'connection.read', 'workflow.edit', 'workflow.read']) }))
vi.mock('@/app/workspace', () => ({
  useWorkspace: () => ({
    workspace: { id: 'workspace' },
    can: (...wanted: string[]) => wanted.some((p) => permission.granted.has(p)),
    isOn: () => true,
  }),
}))
vi.mock('@/api/queries', () => ({
  useWorkflows: () => ({ data: { pages: [{ items: [{ id: '00000000-0000-7000-8000-0000000000aa', name: 'Purchase approval' }] }] } }),
}))

function mount() {
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}><ConnectorPackagesPanel /></QueryClientProvider>)
}

describe('Connector packages panel (S133)', () => {
  beforeEach(() => {
    resetPackageMocks()
    permission.granted = new Set(['connector.manage', 'connector.approve', 'connection.read', 'workflow.edit', 'workflow.read'])
  })

  it('reviews what a package would do, approves the exact hash and installs it', async () => {
    const user = userEvent.setup()
    const detail = sampleDetail()
    seedPackageMocks({ packages: [detail] })
    mount()
    const row = await screen.findByTestId('package-row-com.orqix.servicenow-itsm-1.0.0')
    expect(row).toHaveTextContent('Verified')
    expect(row).toHaveTextContent('Waiting for approval')
    await user.click(within(row).getByRole('button', { name: 'Review ServiceNow ITSM 1.0.0' }))
    const review = await screen.findByTestId('package-review')
    expect(review).toHaveTextContent('https://*.service-now.com')
    expect(review).toHaveTextContent('Changes data, checked by lookup')
    expect(review).toHaveTextContent('Changes data, never repeated automatically')
    expect(review).toHaveTextContent('Work notes written by the workflow')
    expect(review).toHaveTextContent('Weaker verification')
    expect(review).toHaveTextContent(`Package hash ${'a'.repeat(64)}`)
    const approve = within(review).getByRole('button', { name: 'Approve this package' })
    expect(approve).toBeDisabled()
    await user.type(within(review).getByLabelText('Decision note'), 'Hosts and effects reviewed')
    await user.click(approve)
    expect(await screen.findByText(/approved\. It can now be installed/)).toBeVisible()
    expect(mockPackages()[0]!.summary.status).toBe('approved')
    await user.click(await screen.findByRole('button', { name: 'Install in this workspace' }))
    expect(await screen.findByText(/Installed 1\.0\.0\. Next: bind servicenow-itsm/)).toBeVisible()
  })

  it('imports a package file and opens its review', async () => {
    const user = userEvent.setup()
    mount()
    expect(await screen.findByText(/No package has been imported/)).toBeVisible()
    expect(screen.queryByLabelText(/Why import a package/)).toBeNull()
    await user.upload(screen.getByLabelText('Package file (.orqpkg)'), new File([new Uint8Array([80, 75, 3, 4])], 'p.orqpkg'))
    await user.click(screen.getByRole('button', { name: 'Import and review' }))
    expect(await screen.findByTestId('package-review')).toBeVisible()
    expect(mockPackages()).toHaveLength(1)
  })

  it('shows nothing to people without connector or connection permissions', async () => {
    permission.granted = new Set()
    const { container } = mount()
    expect(container).toBeEmptyDOMElement()
  })
})
