import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { server } from '@/test/server'
import { OpenApiImport } from './OpenApiImport'

const permission = vi.hoisted(() => ({ manage: true }))
vi.mock('@/app/workspace', () => ({ useWorkspace: () => ({ workspace: { id: 'workspace' }, can: () => permission.manage }) }))
const definition = { schemaVersion: '0.1', key: 'reviewed-api', version: 1, name: 'Reviewed API', operations: {
  health: { label: 'Health', method: 'GET', path: '/health', responseSchema: { type: 'object', properties: {} }, successStatuses: [200], effectClass: 'readOnly', maxTimeoutSeconds: 10, testable: true },
} }
const report = { source: {}, servers: [], auth: {}, suggestedKey: 'reviewed-api', suggestedName: 'Reviewed API',
  summary: { operations: 2, supported: 1, simplified: 0, blocked: 1 }, issues: [], operations: [
    { key: 'health', method: 'GET', path: '/health', summary: 'Health', disposition: 'supported', blockers: [], notes: [], suggestedEffectClass: 'readOnly', testable: true, deprecated: false, selectedByDefault: false, inputs: [], outputs: [] },
    { key: 'dynamic', method: 'GET', path: '/items/{id}', summary: 'Dynamic item', disposition: 'blocked', blockers: ['Path parameters cannot be imported.'], notes: [], testable: false, deprecated: false, selectedByDefault: false, inputs: [], outputs: [] },
] }
function mount() { return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}><OpenApiImport /></QueryClientProvider>) }

describe('OpenAPI connection review', () => {
  beforeEach(() => { permission.manage = true })
  it('requires explicit supported selection and retry confirmation before registration, invalidating changed candidates', async () => {
    const user = userEvent.setup(); const reviews: Record<string, unknown>[] = []; const registrations: Record<string, unknown>[] = []
    server.use(http.post('*/api/v1/workspaces/:ws/connections/import-openapi', async ({ request }) => {
      const body = await request.json() as Record<string, unknown>; reviews.push(body)
      return HttpResponse.json({ report, definition: body.confirmEffects ? { ...definition, name: body.name } : null })
    }), http.post('*/api/v1/workspaces/:ws/connections/:key/versions', async ({ request }) => {
      registrations.push(await request.json() as Record<string, unknown>); return HttpResponse.json({}, { status: 201 })
    }))
    mount(); fireEvent.change(screen.getByLabelText('OpenAPI source'), { target: { value: 'openapi: 3.0.3\npaths: {}' } })
    await user.click(screen.getByRole('button', { name: 'Review operations' }))
    expect(await screen.findByText('Path parameters cannot be imported.')).toBeVisible()
    expect(screen.getByRole('checkbox', { name: /GET \/items/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Build reviewed definition' })).toBeDisabled()
    await user.click(screen.getByRole('checkbox', { name: /GET \/health/ }))
    await user.click(screen.getByRole('checkbox', { name: /I confirmed the retry behavior/ }))
    await user.click(screen.getByRole('button', { name: 'Build reviewed definition' }))
    await screen.findByRole('button', { name: 'Register reviewed connection' })
    expect(reviews.at(-1)).toMatchObject({ confirmEffects: true, fileName: '', selections: [{ key: 'health', effectClass: 'readOnly' }] })
    expect(registrations).toHaveLength(0)
    fireEvent.change(screen.getByLabelText('Connection name'), { target: { value: 'Changed API' } })
    expect(screen.queryByRole('button', { name: 'Register reviewed connection' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Build reviewed definition' }))
    await user.click(await screen.findByRole('button', { name: 'Register reviewed connection' }))
    await screen.findByText(/Connection version registered/)
    expect(registrations).toEqual([{ definition: { ...definition, name: 'Changed API' } }])
  })
  it('does not expose import actions without management permission', () => {
    permission.manage = false; mount(); expect(screen.queryByLabelText('OpenAPI source')).not.toBeInTheDocument()
  })
  it('refuses oversized files before reading or sending their contents', async () => {
    const user = userEvent.setup(); const called = vi.fn()
    server.use(http.post('*/api/v1/workspaces/:ws/connections/import-openapi', () => { called(); return HttpResponse.json({ report, definition: null }) }))
    mount(); await user.upload(screen.getByLabelText('OpenAPI file (maximum 2 MiB)'), new File(['x'.repeat(2 * 1024 * 1024 + 1)], 'large.json', { type: 'application/json' }))
    await waitFor(() => expect(screen.getByText('Choose a file no larger than 2 MiB.')).toBeVisible())
    expect(called).not.toHaveBeenCalled()
  })
})
