import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import { resetAdapterMocks, seedAdapterMocks } from '@/mocks/adapters'
import purchase from '../../../../../examples/purchase-approval.json'
import { StudioProvider, useStudio } from '../StudioContext'
import { normalizeDefinition } from '../model/types'
import { allFields } from '../model/fieldRefs'
import { adapterPlaceholders, recipientProblems, SendAdapterInspector } from './SendAdapterInspector'

const permission = vi.hoisted(() => ({ read: true }))
vi.mock('@/app/workspace', () => ({ useWorkspace: () => ({ workspace: { id: 'workspace' }, can: () => permission.read }) }))

const adapter = (key: string, kind: 'email' | 'kafka' | 'jdbc' | 'teams', config: Record<string, unknown>, enabled = true) =>
  ({ id: key, key, kind, name: `Adapter ${key}`, secretRef: null, config, enabled, version: 1, createdAt: '2026-10-18T00:00:00Z' })

function setup(readOnly = false) {
  const definition = normalizeDefinition(structuredClone(purchase))
  const node = definition.nodes.find((n) => n.id === 'manager')!
  node.type = 'sendAdapter'
  node.label = 'Tell operations'
  node.config = { adapter: '', kind: 'email', to: [], subject: '', body: '', inputMapping: {} }
  const store = createStudioStore()
  store.getState().load({ workflowId: 'workflow', revision: 1, definition })
  function Inspector() {
    const current = useStudio((s) => s.definition.nodes.find((n) => n.id === 'manager')!)
    return <SendAdapterInspector node={current} />
  }
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <StudioProvider value={{ store, saver: {} as DraftSaver, wsId: 'workspace', workflowId: 'workflow', readOnly, connections: { responseType: () => undefined } }}><Inspector /></StudioProvider>
  </QueryClientProvider>)
  return store
}

const config = (store: ReturnType<typeof setup>) => store.getState().definition.nodes.find((n) => n.id === 'manager')!.config

describe('inspector: Send through an adapter', () => {
  beforeEach(() => {
    permission.read = true
    resetAdapterMocks()
    seedAdapterMocks({ adapters: [
      adapter('ops-mail', 'email', { from: 'noreply@example.com', allowedRecipientDomains: ['example.com'], maxRecipients: 2 }),
      adapter('orders', 'kafka', { allowedTopics: ['orders.created'] }),
      adapter('lookup', 'jdbc', { statement: 'SELECT 1 WHERE a = :costCenter', parameters: ['costCenter'] }),
      adapter('chat', 'teams', { baseUrl: 'https://teams.example.com' }, false),
    ] })
  })

  it('finds placeholders and recipients outside the approved domains', () => {
    expect(adapterPlaceholders('Hi {{title}} {{title}} {{amount}} {{ bad }}')).toEqual(['title', 'amount'])
    expect(recipientProblems(['ops@example.com', 'x@evil.example.org', 'nope'], ['example.com'])).toEqual([
      'evil.example.org is not an approved domain of this adapter.', 'nope is not a plain email address.'])
  })

  it('chooses an email adapter, fixes recipients and maps values for placeholders', async () => {
    const store = setup()
    const user = userEvent.setup()
    const choice = await screen.findByLabelText(/^Adapter/)
    await waitFor(() => expect(screen.getByRole('option', { name: /Adapter ops-mail/ })).toBeInTheDocument())
    expect(screen.getByRole('option', { name: /Adapter chat .*disabled/ })).toBeDisabled()
    await user.selectOptions(choice, 'ops-mail')
    expect(config(store)).toMatchObject({ adapter: 'ops-mail', kind: 'email' })
    await user.type(screen.getByLabelText(/^Recipients/), 'ops@example.com{enter}boss@evil.example.org')
    await user.tab()
    expect(config(store).to).toEqual(['ops@example.com', 'boss@evil.example.org'])
    expect(screen.getByText(/evil.example.org is not an approved domain/)).toBeInTheDocument()
    await user.type(screen.getByLabelText(/^Subject/), 'New request {{{{title}}')
    await user.tab()
    expect(config(store).subject).toBe('New request {{title}}')
    expect(screen.getByText(/No value is listed for \{\{title\}\}/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Add a value' }))
    expect(Object.keys(config(store).inputMapping as object)).toEqual(['title'])
    expect(screen.getByText(/never come from request data/)).toBeInTheDocument()
  })

  it('offers only approved Kafka topics and makes database parameters fixed with a typed row count', async () => {
    const store = setup()
    const user = userEvent.setup()
    await waitFor(() => expect(screen.getByRole('option', { name: /Adapter orders/ })).toBeInTheDocument())
    await user.selectOptions(screen.getByLabelText(/^Adapter/), 'orders')
    expect(config(store).to).toBeUndefined()
    await user.selectOptions(screen.getByLabelText(/^Topic/), 'orders.created')
    expect(config(store).topic).toBe('orders.created')
    await user.selectOptions(screen.getByLabelText(/^Adapter/), 'lookup')
    expect(config(store)).toMatchObject({ kind: 'jdbc', inputMapping: { costCenter: '' } })
    expect(config(store).topic).toBeUndefined()
    expect(screen.queryByRole('button', { name: 'Add a value' })).not.toBeInTheDocument()
    expect(allFields(store.getState().definition).map((f) => f.ref)).toEqual(expect.arrayContaining(['/steps/manager/outcome', '/steps/manager/rowCount']))
  })

  it('falls back to a typed key without adapter read access and is read only when published', async () => {
    permission.read = false
    setup(true)
    expect(screen.getByLabelText(/^Adapter key/)).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Add a value' })).not.toBeInTheDocument()
  })
})
